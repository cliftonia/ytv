package com.cliftonia.fs42tv

import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.cliftonia.fs42tv.player.EngineDeck
import com.cliftonia.fs42tv.player.MpvChannelPlayer
import com.cliftonia.fs42tv.player.PlayerEngine
import com.cliftonia.fs42tv.resolver.AcceleratedResolver
import com.cliftonia.fs42tv.resolver.RefusalLedger
import com.cliftonia.fs42tv.sync.DialLoader
import com.cliftonia.fs42tv.sync.LineupSource
import com.cliftonia.fs42tv.tune.DialNavigator
import com.cliftonia.fs42tv.tune.TuneController
import com.cliftonia.fs42tv.ui.AppSurface
import com.cliftonia.fs42tv.ui.GuideMusic
import com.cliftonia.fs42tv.ui.GuidePicker
import com.cliftonia.fs42tv.ui.ScreenDirector
import com.cliftonia.fs42tv.ui.ScreenExtras
import com.cliftonia.fs42tv.ui.SettingRow
import com.cliftonia.fs42tv.ui.SettingsCatalog
import com.cliftonia.fs42tv.ui.featureToggled
import com.cliftonia.fs42tv.ui.wirePlayer
import com.cliftonia.fs42tv.update.UpdateFlow

/** The repository whose releases carry the apk, for the self-update check. */
private const val RELEASES_REPO = "cliftonia/ytv"

private const val PREFS_NAME = "fs42"

private const val NO_REMEMBERED_CHANNEL = -1

/**
 * The Android glue and nothing else: lifecycle, the remote's keys, and the construction that
 * hands every real decision to a named unit.
 *
 * The dial's tuning rules live in [TuneController], what the viewer sees between frames in
 * [ScreenDirector], the guide in [GuidePicker], refusals in [RefusalLedger], settings in
 * [SettingsCatalog], the engine's life and death in [EngineDeck], the lineup in [DialLoader]
 * and the self-update in [UpdateFlow]. Each names its dependencies; this file only supplies
 * them.
 */
class MainActivity : ComponentActivity() {

    @Volatile private var navigator: DialNavigator? = null
    @Volatile private var destroyed: Boolean = false

    /**
     * Main-thread only, so it needs no @Volatile: written in onStart/onStop and read in
     * runOnUiThread blocks.
     */
    private var stopped = false

    /**
     * A crash from the PREVIOUS run, shown on the stand-by card at launch.
     *
     * Separate from the director's live card so that a successful tune cannot wipe it before
     * it has been read - which it otherwise would, within a second or two of starting. Cleared
     * by the first keypress instead, because the viewer pressing a button is the only reliable
     * signal that somebody actually saw it.
     */
    private val crashNotice = mutableStateOf("")

    private val settingsVisible = mutableStateOf(false)
    private val settingsRows = mutableStateOf<List<SettingRow>>(emptyList())

    /** The panel, the quality ladder and the clock, as read at launch - see [LaunchSettings]. */
    private val settings = LaunchSettings()

    /** The tune, prefetch and caption threads - see [AppThreads] for why three. */
    private val threads = AppThreads()

    /** Drives the stand-by card when playback stalls mid-clip. */
    private val stallHandler by lazy { android.os.Handler(mainLooper) }

    /**
     * Delays the stand-by card after a playback error, so a fault the app repairs by itself is
     * never announced. Deliberately NOT the stall handler: both post one delayed reveal and
     * both clear their queue before posting, so sharing one would let a stall cancel a pending
     * error card and leave a genuinely dead channel showing nothing but blank forever.
     */
    private val recoveryHandler by lazy { android.os.Handler(mainLooper) }

    // One object rather than four fields, because a refusal is a four-part update - see the
    // ledger's own comment for the invariant that shipped broken twice as separate fields.
    private val ledger = RefusalLedger(
        nowElapsedSeconds = { SystemClock.elapsedRealtime() / 1000 },
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var source: LineupSource
    private lateinit var resolver: AcceleratedResolver
    private lateinit var tune: TuneController
    private lateinit var director: ScreenDirector
    private lateinit var guide: GuidePicker
    private lateinit var deck: EngineDeck
    private lateinit var updateFlow: UpdateFlow
    private lateinit var settingsCatalog: SettingsCatalog
    private lateinit var composeView: ComposeView
    private lateinit var extras: ScreenExtras
    private lateinit var music: GuideMusic

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First, so that anything failing during the rest of setup is still recorded. A crash
        // on a television with no adb is otherwise unreadable.
        CrashLog.install(filesDir)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        // PLUTO TV became LIVE TV: the saved choice is rewritten once, and the retired dial's
        // cache goes with it. Its remembered channel stays behind unread - LIVE has its own key.
        LineupSource.migrated(prefs.getString(LineupSource.KEY, null))?.let {
            prefs.edit().putString(LineupSource.KEY, it).apply()
            java.io.File(cacheDir, "pluto.json").delete()
        }
        source = LineupSource.parse(prefs.getString(LineupSource.KEY, null))
        // Android's account first, ours second. A native crash leaves nothing in CrashLog -
        // that is precisely the gap ExitReason fills - and when both have something to say,
        // Android's is the one that names what actually happened. Reported once, ever.
        val died = ExitReason.unseenAbnormal(this, prefs) ?: CrashLog.summary(filesDir)
        died?.let { crashNotice.value = "LAST RUN: $it" }
        // Stop the television deciding nobody is there. A remote that has not been touched for
        // half an hour looks exactly like an idle device to Android, and it turns the screen
        // off mid-programme. This applies only while this activity is in front.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        updateFlow = UpdateFlow(
            context = this,
            repo = RELEASES_REPO,
            installedVersion = BuildConfig.VERSION_CODE,
            halted = { destroyed },
            runOnUi = { block -> runOnUiThread(block) },
        )
        resolver = AcceleratedResolver.forDial()

        settings.read(this, prefs)

        extras = ScreenExtras.create(prefs, threads.prefetch, { runOnUiThread(it) }, { destroyed },
            use24Hour = { android.text.format.DateFormat.is24HourFormat(this) },
            mpvLadder = { settings.ladder.takeIf { ::deck.isInitialized && deck.engine == PlayerEngine.MPV } },
            cacheDir = cacheDir, homeServer = { resolver.availableServer() })
        music = GuideMusic(GuideMusic.Deps(
            context = this,
            resolveForAudio = { tune.resolveForAudio(it)?.let(extras::besideTuned) },
            speculativeExecutor = threads.prefetch,
            runOnUi = { block -> runOnUiThread(block) },
            halted = { destroyed },
            stoppedNow = { stopped },
        ))
        director = createScreenDirector()
        tune = createTuneController()
        director.captions.on = prefs.getBoolean(SettingsCatalog.CAPTIONS_KEY, false)
        settingsCatalog = createSettingsCatalog()
        guide = GuidePicker(GuidePicker.Deps(
            tune = tune,
            director = director,
            navigator = { navigator },
            executor = threads.tune,
            runOnUi = { block -> runOnUiThread(block) },
            halted = { destroyed },
            stoppedNow = { stopped },
            nowSeconds = settings::nowSeconds,
            elapsedMillis = { SystemClock.elapsedRealtime() },
            focus = ::grantOverlayFocus,
            extras = extras,
            music = music,
        ))

        composeView = ComposeView(this).apply {
            // The picker needs focus when open; the OSD does not, and must not steal it from
            // the D-pad channel-surfing handled in onKeyDown while the picker is closed.
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setContent {
                AppSurface(
                    director = director,
                    guide = guide,
                    update = updateFlow,
                    crashNotice = crashNotice.value,
                    settingsVisible = settingsVisible.value,
                    settingsRows = settingsRows.value,
                    positionSeconds = { deck.player?.positionSeconds() },
                    onCloseSettings = ::closeSettings,
                )
            }
        }

        deck = EngineDeck(
            context = this,
            engine = settings.chooseEngine(this, prefs) { settingsCatalog.audioRoute() },
            modeCount = settings.displayModeCount,
            overlay = composeView,
            wire = director::wirePlayer,
        )
        setContentView(deck.root)

        updateFlow.check()

        val remembered = prefs.getInt(source.channelKey, NO_REMEMBERED_CHANNEL)
        DialLoader(
            source = source,
            cacheDir = cacheDir,
            executor = threads.tune,
            runOnUi = { block -> runOnUiThread(block) },
            halted = { destroyed },
            loaded = { navigator != null },
            retry = { delay, block -> recoveryHandler.postDelayed(block, delay) },
            onNoDial = { director.standByReason.value = "NO LINEUP - CHECK CONNECTION" },
            onDial = { channels, requestedAt ->
                val nav = DialNavigator(channels, remembered.takeIf { it > 0 })
                navigator = nav
                // Every clock channel's schedule and today's layout, so the first guide open is instant.
                threads.inBackground("prewarm") {
                    if (!destroyed) extras.timetable.prewarm(channels, settings.nowSeconds())
                }
                // Posted BEFORE the tune, which paints through the same queue after it.
                runOnUiThread { director.launchTuneStarted() }
                tune.tuneFirst(nav.current, requestedAt)
            },
            elapsedMillis = { SystemClock.elapsedRealtime() },
        ).load()
    }

    private fun createScreenDirector() = ScreenDirector(ScreenDirector.Deps(
        player = { deck.player },
        tune = { tune },
        pickerOpen = { guide.visible.value },
        fallbackChannel = { navigator?.current },
        nowSeconds = settings::nowSeconds,
        halted = { destroyed },
        stoppedNow = { stopped },
        runOnUi = { block -> runOnUiThread(block) },
        stallHandler = stallHandler,
        recoveryHandler = recoveryHandler,
        overlayOpen = { guide.visible.value || settingsVisible.value },
        condemn = { id -> ledger.condemn(id, settings.ladder) },
        rebuildEngine = { deck.rebuild() },
        recallResolved = ledger::recall,
        persistCaptionsOn = {
            prefs.edit().putBoolean(SettingsCatalog.CAPTIONS_KEY, it).apply()
        },
        captionExecutor = threads.caption,
        extras = extras,
        music = music,
        channels = { navigator?.channels.orEmpty() },
    ))

    private fun createTuneController() = TuneController(TuneController.Deps(
        executor = threads.tune,
        prefetchExecutor = threads.prefetch,
        resolver = resolver,
        ledger = ledger,
        urls = null,
        ladder = { settings.ladder },
        navigator = { navigator },
        nowSeconds = settings::nowSeconds,
        elapsedMillis = { SystemClock.elapsedRealtime() },
        halted = { destroyed },
        runOnUi = { block -> runOnUiThread(block) },
        rememberChannel = { number -> prefs.edit().putInt(source.channelKey, number).apply() },
        screen = director.screen(),
        timetable = extras.timetable,
        livePlayable = extras::livePlayable,
    ))

    private fun createSettingsCatalog() = SettingsCatalog(this, SettingsCatalog.Deps(
        prefs = prefs,
        displayModeCount = { settings.displayModeCount },
        channels = { navigator?.channels.orEmpty() },
        source = source,
        relaunch = ::recreate,
        ladder = { settings.ladder },
        setLadder = { settings.ladder = it },
        clearResolved = ledger::clearResolved,
        captionsOn = { director.captions.on },
        toggleCaptions = director.captions::toggle,
        applyAudioHold = { millis ->
            (deck.player as? MpvChannelPlayer)?.setAudioHoldMillis(millis)
                ?: run { com.cliftonia.fs42tv.player.audioHoldMillis = millis }
        },
        checkForUpdate = { onStatus ->
            updateFlow.check(installWhenReady = true, onStatus = onStatus)
        },
        updateStatus = { updateFlow.status.value },
        refresh = { settingsRows.value = settingsCatalog.rows() },
        features = extras.features,
        featureToggled = director::featureToggled,
    ))

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Any key dismisses last run's crash. Pressing a button is the only reliable evidence
        // somebody was in front of the television to read it; a timer would expire while the
        // room was empty and the notice would be gone by the time anyone looked.
        if (crashNotice.value.isNotEmpty()) {
            crashNotice.value = ""
            CrashLog.clear(filesDir)
        }
        // Belt and braces alongside the focus handoff in the guide: once an overlay is up, the
        // focused row already consumes D-pad up/down/centre, but this guard is what actually
        // guarantees the channel-change keys are inert rather than relying on focus routing
        // alone. KEYCODE_BACK is deliberately not handled here - the overlays own their own
        // dismissal via BackHandler.
        if (guide.visible.value || settingsVisible.value) {
            return super.onKeyDown(keyCode, event)
        }

        // No early return without a dial: Left must still reach settings to switch SOURCE back.
        val nav = navigator
        return when (keyCode) {
            // Left, because it is the only D-pad direction the dial does not already use and
            // cannot be pressed by accident while surfing, which is up and down.
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                openSettings()
                true
            }
            // Right shows what is on, which is what INFO does on a real remote. KEYCODE_INFO
            // is accepted alongside for the remotes that have the button.
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                director.showBanner()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                nav?.let { tune.surfTo(it.up()) }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                nav?.let { tune.surfTo(it.down()) }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_GUIDE -> {
                // OK does double duty, but only while the update prompt is on screen - and the
                // prompt says so. The alternative was a second key, and this remote is a cheap
                // universal one where INFO and MENU may not exist at all.
                if (updateFlow.ready.value) {
                    updateFlow.installNow()
                } else {
                    guide.open()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /** The guide's half of focus: flip the ComposeView's gate and pull focus when granting. */
    private fun grantOverlayFocus(granted: Boolean) {
        composeView.descendantFocusability =
            if (granted) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (granted) composeView.requestFocus() else composeView.clearFocus()
    }

    /**
     * Open the settings list, freezing the dial underneath the way the picker does.
     *
     * The supersede is the same guard the guide needs and for the same reason: a channel
     * change pressed a moment earlier still has a tune in flight, and letting it land would
     * change the channel under an open overlay.
     */
    private fun openSettings() {
        tune.supersede()
        updateFlow.status.value = ""
        settingsRows.value = settingsCatalog.rows()
        settingsVisible.value = true
        grantOverlayFocus(true)
        director.syncCovered()
    }

    private fun closeSettings() {
        settingsVisible.value = false
        grantOverlayFocus(false)
        // openSettings superseded the dial, so the same abandoned-tune check the guide's
        // dismissal makes applies here.
        director.recoverIfAbandoned()
        director.syncCovered()
    }

    /**
     * Check for updates again whenever the viewer comes back to the app.
     *
     * Launch alone was not enough: a television that stays on one channel for days never
     * relaunches, so a change published in the meantime would never be seen.
     */
    override fun onResume() {
        super.onResume()
        // Volume is re-derived rather than assumed: the guide may have been open when they left.
        director.appResumed()
        updateFlow.check()
    }

    override fun onStart() {
        super.onStart()
        stopped = false
    }

    /**
     * Stop playing once the app is no longer what is on screen.
     *
     * onStop rather than onPause: on Android TV a system dialog - the very install prompt this
     * app can raise - pauses the activity without hiding it, and silencing the channel behind
     * a dialog the viewer is about to dismiss would be its own annoyance. onStop means
     * genuinely gone. Paused rather than stopped, so coming back does not re-resolve and seek.
     *
     * The guide music is released outright: holding an idle ExoPlayer keeps a MediaCodec
     * reserved, which showed up as frame drops on every channel.
     */
    override fun onStop() {
        super.onStop()
        stopped = true
        director.appStopped()
        guide.releaseMusic()
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        stallHandler.removeCallbacksAndMessages(null)
        recoveryHandler.removeCallbacksAndMessages(null)
        director.release()
        threads.shutdown()
        resolver.close()
        guide.releaseMusic()
        deck.release()
    }
}
