package com.cliftonia.fs42tv

import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.cliftonia.fs42tv.player.EngineDeck
import com.cliftonia.fs42tv.player.FrameCadence
import com.cliftonia.fs42tv.player.MpvChannelPlayer
import com.cliftonia.fs42tv.player.PlayerEngine
import com.cliftonia.fs42tv.resolver.AcceleratedResolver
import com.cliftonia.fs42tv.resolver.ClipResolver
import com.cliftonia.fs42tv.resolver.DeviceResolver
import com.cliftonia.fs42tv.resolver.RefusalLedger
import com.cliftonia.fs42tv.resolver.ServerResolver
import com.cliftonia.fs42tv.sync.DialLoader
import com.cliftonia.fs42tv.tune.DialNavigator
import com.cliftonia.fs42tv.tune.TuneController
import com.cliftonia.fs42tv.ui.AppSurface
import com.cliftonia.fs42tv.ui.GuidePicker
import com.cliftonia.fs42tv.ui.ScreenDirector
import com.cliftonia.fs42tv.ui.SettingRow
import com.cliftonia.fs42tv.ui.SettingsCatalog
import com.cliftonia.fs42tv.update.UpdateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The resolve accelerator's two addresses, in preference order - one machine, two networks.
 *
 * The LAN address first: at home it answers in single-digit milliseconds, and the LAN is where
 * both televisions actually live. The tailnet address is the same box for anything that can
 * reach the tailnet. Optional by construction - the television in the car reaches neither and
 * must not care; see [AcceleratedResolver]. Hard-wired rather than configurable because there
 * is exactly one of these and a setting would only be another thing to get wrong.
 */
private val RESOLVE_SERVERS = listOf(
    "http://192.168.4.58:4243",
    "http://100.74.3.68:4243",
)

/** The repository whose releases carry the apk, for the self-update check. */
private const val RELEASES_REPO = "cliftonia/ytv"

private const val PREFS_NAME = "fs42"
private const val CHANNEL_KEY = "channel"

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

    /**
     * How many modes the panel reports, kept because the settings screen shows it and the
     * engine default is derived from it. One mode means a television that cannot change its
     * refresh rate, which is the whole reason two engines exist.
     */
    private var displayModeCount: Int = 0

    /**
     * Which quality tiers to ask for. `@Volatile`: written by the settings row on the UI
     * thread, read by every resolve on the executors.
     */
    @Volatile private var ladder: List<String> = listOf("hd", "sd")

    /**
     * Wall-clock seconds, or a frozen instant when one was supplied at launch.
     *
     * Every channel derives its clip and offset from the current time, so two measurement runs
     * minutes apart are watching entirely different content - a larger source of variance than
     * any setting worth tuning, and the cause of three separate false results. Freezing the
     * clock pins clip selection and offset; a launch without the extra behaves exactly as the
     * remote does. tools/measure-switch.sh passes it as `--el fs42.now`.
     */
    @Volatile private var fixedNowSeconds: Long = -1L

    private fun nowSeconds(): Long =
        if (fixedNowSeconds > 0) fixedNowSeconds else System.currentTimeMillis() / 1000

    // Single-threaded so a rapid burst of channel presses queues in order rather than racing
    // each other over the shared navigator and player.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * A second thread, for resolving channels nobody has asked for yet. Separate from
     * [executor] on purpose: that one serves the channel the viewer is actually waiting for,
     * and a speculative resolve queued ahead of a real keypress would make surfing slower.
     */
    private val prefetchExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * A third thread, for downloading the subtitle file of the clip that just started. Its own
     * thread for the same reason [prefetchExecutor] has one, in both directions: on [executor]
     * it would delay the next channel change, and on [prefetchExecutor] the captions for the
     * programme being watched would queue behind neighbours nobody asked for.
     */
    private val captionExecutor: ExecutorService = Executors.newSingleThreadExecutor()

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
    private lateinit var resolver: ClipResolver
    private lateinit var tune: TuneController
    private lateinit var director: ScreenDirector
    private lateinit var guide: GuidePicker
    private lateinit var deck: EngineDeck
    private lateinit var updateFlow: UpdateFlow
    private lateinit var settingsCatalog: SettingsCatalog
    private lateinit var composeView: ComposeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First, so that anything failing during the rest of setup is still recorded. A crash
        // on a television with no adb is otherwise unreadable.
        CrashLog.install(filesDir)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
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
        resolver = AcceleratedResolver(
            servers = RESOLVE_SERVERS.map(ServerResolver::overHttp),
            device = DeviceResolver(),
        )

        readSettings()

        director = createScreenDirector()
        tune = createTuneController()
        director.captionsOn = prefs.getBoolean(SettingsCatalog.CAPTIONS_KEY, false)
        settingsCatalog = createSettingsCatalog()
        guide = GuidePicker(GuidePicker.Deps(
            context = this,
            tune = tune,
            director = director,
            navigator = { navigator },
            executor = executor,
            runOnUi = { block -> runOnUiThread(block) },
            halted = { destroyed },
            stoppedNow = { stopped },
            nowSeconds = ::nowSeconds,
            elapsedMillis = { SystemClock.elapsedRealtime() },
            focus = ::grantOverlayFocus,
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
            engine = chooseEngine(),
            modeCount = displayModeCount,
            overlay = composeView,
            wire = director::wirePlayer,
        )
        setContentView(deck.root)

        updateFlow.check()

        val remembered = prefs.getInt(CHANNEL_KEY, NO_REMEMBERED_CHANNEL)
        DialLoader(
            cacheDir = cacheDir,
            executor = executor,
            runOnUi = { block -> runOnUiThread(block) },
            halted = { destroyed },
            loaded = { navigator != null },
            retry = { delay, block -> recoveryHandler.postDelayed(block, delay) },
            onNoDial = { director.standByReason.value = "NO LINEUP - CHECK CONNECTION" },
            onDial = { channels, requestedAt ->
                val nav = DialNavigator(channels, remembered.takeIf { it > 0 })
                navigator = nav
                tune.tuneFirst(nav.current, requestedAt)
            },
            elapsedMillis = { SystemClock.elapsedRealtime() },
        ).load()
    }

    /** Every remembered preference, read before the engine is built - mpv applies some during init. */
    private fun readSettings() {
        displayModeCount =
            (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                display else windowManager.defaultDisplay)?.supportedModes?.size ?: 0
        com.cliftonia.fs42tv.player.videoSyncMode =
            prefs.getString(SettingsCatalog.VIDEO_SYNC_KEY, null)
                ?: FrameCadence.SYNC_MODES.first()
        // Re-read on every engine build rather than only the first: mpv is rebuilt whenever
        // its core shuts down, and a trim that reset itself on that path would look exactly
        // like the audio fault coming back.
        com.cliftonia.fs42tv.player.audioHoldMillis =
            prefs.getInt(SettingsCatalog.AUDIO_HOLD_KEY, 0)
        ladder = SettingsCatalog.QUALITY_LADDERS
            .firstOrNull { it.first == prefs.getString(SettingsCatalog.QUALITY_KEY, null) }
            ?.second ?: SettingsCatalog.QUALITY_LADDERS.first().second
        // The measurement seam - see [fixedNowSeconds]. Disconnected once during a refactor,
        // after which a sweep silently measured rotating content.
        fixedNowSeconds = intent?.getLongExtra("fs42.now", -1L) ?: -1L
        if (fixedNowSeconds > 0) Log.i("fs42", "clock pinned to $fixedNowSeconds")
    }

    /**
     * Which engine plays the dial, and why it is not simply "the newer one".
     *
     * Media3 judders on this television - roughly two tunes in five come back with the picture
     * running fast then slow - and mpv does not, measured on the same clips at the same
     * offsets. androidx/media issue 2941 documents the same fault on BUILT-IN Android TVs and
     * explicitly NOT on Chromecast or Fire TV, which matches: a stick can change its HDMI
     * output mode, a panel with one mode cannot. So the choice is made from the number of
     * display modes rather than from a device name, and Media3 stays the default wherever it
     * works - it is a fifth of the install size and starts faster.
     *
     * Override with:  adb shell am start -S -n com.cliftonia.fs42tv/.MainActivity --es engine mpv
     * (-S because launchMode is singleTask: without it a launch while the app is running
     * re-delivers the intent to the EXISTING activity and onCreate never runs.)
     */
    private fun chooseEngine(): PlayerEngine {
        val engine = PlayerEngine.parse(intent?.getStringExtra("engine"))
            ?: PlayerEngine.parse(prefs.getString(SettingsCatalog.ENGINE_KEY, null))
            ?: PlayerEngine.default(displayModeCount)
        prefs.edit().putString(SettingsCatalog.ENGINE_KEY, engine.name.lowercase()).apply()
        Log.i("fs42", "player engine $engine ($displayModeCount display mode(s)), " +
            "audio out ${settingsCatalog.audioRoute()}, " +
            "hold ${com.cliftonia.fs42tv.player.audioHoldMillis}ms")
        return engine
    }

    private fun createScreenDirector() = ScreenDirector(ScreenDirector.Deps(
        player = { deck.player },
        tune = { tune },
        pickerOpen = { guide.visible.value },
        fallbackChannel = { navigator?.current },
        nowSeconds = ::nowSeconds,
        halted = { destroyed },
        stoppedNow = { stopped },
        runOnUi = { block -> runOnUiThread(block) },
        stallHandler = stallHandler,
        recoveryHandler = recoveryHandler,
        condemn = { id -> ledger.condemn(id, ladder) },
        rebuildEngine = { deck.rebuild() },
        recallResolved = ledger::recall,
        persistCaptionsOn = {
            prefs.edit().putBoolean(SettingsCatalog.CAPTIONS_KEY, it).apply()
        },
        captionExecutor = captionExecutor,
    ))

    private fun createTuneController() = TuneController(TuneController.Deps(
        executor = executor,
        prefetchExecutor = prefetchExecutor,
        resolver = resolver,
        ledger = ledger,
        urls = null,
        ladder = { ladder },
        navigator = { navigator },
        nowSeconds = ::nowSeconds,
        elapsedMillis = { SystemClock.elapsedRealtime() },
        halted = { destroyed },
        runOnUi = { block -> runOnUiThread(block) },
        rememberChannel = { number -> prefs.edit().putInt(CHANNEL_KEY, number).apply() },
        screen = director.screen(),
    ))

    private fun createSettingsCatalog() = SettingsCatalog(this, SettingsCatalog.Deps(
        prefs = prefs,
        displayModeCount = { displayModeCount },
        channels = { navigator?.channels.orEmpty() },
        ladder = { ladder },
        setLadder = { ladder = it },
        clearResolved = ledger::clearResolved,
        captionsOn = { director.captionsOn },
        toggleCaptions = director::toggleCaptions,
        applyAudioHold = { millis ->
            (deck.player as? MpvChannelPlayer)?.setAudioHoldMillis(millis)
                ?: run { com.cliftonia.fs42tv.player.audioHoldMillis = millis }
        },
        checkForUpdate = { onStatus ->
            updateFlow.check(installWhenReady = true, onStatus = onStatus)
        },
        updateStatus = { updateFlow.status.value },
        refresh = { settingsRows.value = settingsCatalog.rows() },
    ))

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Any key dismisses last run's crash. Pressing a button is the only reliable evidence
        // somebody was in front of the television to read it; a timer would expire while the
        // room was empty and the notice would be gone by the time anyone looked.
        if (crashNotice.value.isNotEmpty()) {
            crashNotice.value = ""
            CrashLog.clear(filesDir)
        }
        val nav = navigator ?: return super.onKeyDown(keyCode, event)

        // Belt and braces alongside the focus handoff in the guide: once an overlay is up, the
        // focused row already consumes D-pad up/down/centre, but this guard is what actually
        // guarantees the channel-change keys are inert rather than relying on focus routing
        // alone. KEYCODE_BACK is deliberately not handled here - the overlays own their own
        // dismissal via BackHandler.
        if (guide.visible.value || settingsVisible.value) {
            return super.onKeyDown(keyCode, event)
        }

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
                tune.surfTo(nav.up())
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                tune.surfTo(nav.down())
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
    }

    private fun closeSettings() {
        settingsVisible.value = false
        grantOverlayFocus(false)
        // openSettings superseded the dial, so the same abandoned-tune check the guide's
        // dismissal makes applies here.
        director.recoverIfAbandoned()
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
        deck.player?.setPaused(false)
        director.updateProgrammeVolume()
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
        deck.player?.setPaused(true)
        guide.releaseMusic()
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        stallHandler.removeCallbacksAndMessages(null)
        recoveryHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        prefetchExecutor.shutdownNow()
        captionExecutor.shutdownNow()
        guide.releaseMusic()
        deck.release()
    }
}
