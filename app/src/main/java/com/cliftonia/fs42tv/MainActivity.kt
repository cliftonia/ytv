package com.cliftonia.fs42tv

import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.cliftonia.fs42tv.player.ChannelPlayback
import com.cliftonia.fs42tv.player.FrameCadence
import com.cliftonia.fs42tv.player.ChannelPlayer
import com.cliftonia.fs42tv.player.Media3Sources
import com.cliftonia.fs42tv.player.MpvChannelPlayer
import com.cliftonia.fs42tv.player.PlayerEngine
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.PlaybackDiagnostics
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.ClipResolver
import com.cliftonia.fs42tv.resolver.AcceleratedResolver
import com.cliftonia.fs42tv.resolver.DeviceResolver
import com.cliftonia.fs42tv.resolver.ServerResolver
import com.cliftonia.fs42tv.resolver.RefusalLedger
import com.cliftonia.fs42tv.resolver.VttCues
import com.cliftonia.fs42tv.sync.DialRepository
import com.cliftonia.fs42tv.tune.DialNavigator
import com.cliftonia.fs42tv.tune.TuneController
import com.cliftonia.fs42tv.ui.CaptionLine
import com.cliftonia.fs42tv.ui.ChannelLabels
import com.cliftonia.fs42tv.ui.ChannelOsd
import com.cliftonia.fs42tv.ui.GuideRows
import com.cliftonia.fs42tv.ui.PickerMusic
import com.cliftonia.fs42tv.ui.ChannelPicker
import com.cliftonia.fs42tv.ui.SettingRow
import com.cliftonia.fs42tv.ui.CaptionLoader
import com.cliftonia.fs42tv.ui.SettingsCatalog
import com.cliftonia.fs42tv.ui.SettingsScreen
import com.cliftonia.fs42tv.ui.StandBy
import com.cliftonia.fs42tv.ui.TuningBlank
import com.cliftonia.fs42tv.ui.UpdatePrompt
import com.cliftonia.fs42tv.update.Updater
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Where the lineup lives.
 *
 * A file in a public git repository, not an endpoint on a machine at home. The dial is rebuilt
 * nightly by a workflow and committed, so the television picks up new content by fetching one
 * file over the open internet - which is the whole point, because one of these televisions lives
 * in a car and is rarely on the house network.
 *
 * `raw.githubusercontent.com` rather than the api: no rate limit worth worrying about, no token,
 * and it serves the file at whatever the branch currently points to.
 */
private const val LINEUP_URL =
    "https://raw.githubusercontent.com/cliftonia/ytv/main/channels.json"

/**
 * A resolve accelerator on the home network, if this set can reach one.
 *
 * Optional by construction. The television in the car will not reach it and must not care; see
 * [AcceleratedResolver]. Hard-wired rather than configurable because there is exactly one of
 * these and a setting would only be another thing to get wrong.
 */
private const val RESOLVE_SERVER = "http://100.74.3.68:4243"

/** The repository whose releases carry the apk, for the self-update check. */
private const val RELEASES_REPO = "cliftonia/ytv"

private const val PREFS_NAME = "fs42"
private const val CHANNEL_KEY = "channel"

private const val NO_REMEMBERED_CHANNEL = -1

/** Long enough not to flash on the brief stalls that clear themselves. */
private const val STALL_CARD_MILLIS = 2_500L

/**
 * How long a playback error is given to fix itself before the stand-by card appears.
 *
 * A 403 on a signed URL recovers by dropping the dead id, asking the server for a fresh one and
 * tuning again. Measured end to end that is about 1.5s - a clean tune reaches first frame in
 * 910-2870ms, and the extra server resolve is the slow part. 4s covers the slow end with room,
 * while still being short enough that a channel which is genuinely dead says so rather than
 * sitting blank.
 */
private const val RECOVERY_GRACE_MILLIS = 4_000L

/**
 * How long a launch with no lineup waits before asking again. Long enough not to hammer a
 * hotspot that is still coming up, short enough that the dial appears within a minute of the
 * network doing so.
 */
private const val DIAL_RETRY_MILLIS = 30_000L


class MainActivity : ComponentActivity() {

    // @Volatile: assigned on the UI thread in onCreate, read from the executor when tuning.
    @Volatile private var player: ChannelPlayback? = null

    /**
     * Audio-only player for the music under the channel picker.
     *
     * A separate ExoPlayer rather than the main one, because the channel being watched must keep
     * playing underneath the list - the picker is translucent over it. Audio only, so the cost is
     * one stream of about 128kbps rather than a second video decode; video preloading was
     * abandoned over exactly that bandwidth budget, and this stays well inside it.
     *
     * Created on first use and released with the activity. Everything about it is best-effort:
     * guide music is atmosphere, and atmosphere is never worth an error.
     */
    private var musicPlayer: androidx.media3.exoplayer.ExoPlayer? = null

    @Volatile private var navigator: DialNavigator? = null
    @Volatile private var destroyed: Boolean = false

    // Compose state backing the tune banner. Written only from the runOnUiThread block below,
    // and only on a genuine success: a failed re-tune must not touch these, since bumping
    // bannerGeneration would replay the LaunchedEffect in ChannelOsd and pop a banner back up
    // for a channel that never changed.
    private val bannerChannelLine = mutableStateOf("")
    private val bannerTitleLine = mutableStateOf("")

    // Separate from `generation` below on purpose: that counter is bumped once per keypress, to
    // coalesce a burst of presses, and can advance even when a tune ultimately fails - it does
    // not increment exactly once per successful tune. Using it as the banner's LaunchedEffect
    // key would replay the auto-hide timer (and thus re-show the banner) on a failed re-tune
    // even though nothing on screen changed. This counter only advances alongside onAir itself.
    private val bannerGeneration = mutableStateOf(0)

    // Backs the channel picker. pickerRows/pickerStartIndex are captured once, at the moment
    // the picker opens, rather than derived live from navigator/onAir - the whole point of the
    // picker is that surfing is frozen while it's up, so nothing should move these under it.
    // Backs the stand-by card. A black screen is indistinguishable from a dead app or a
    // dead TV; the card says the app knows and is retrying.
    /**
     * A crash from the PREVIOUS run, shown on the stand-by card at launch.
     *
     * Separate from [standByReason] so that a successful tune cannot wipe it before it has been
     * read - which it otherwise would, within a second or two of starting. Cleared by the first
     * keypress instead, because the viewer pressing a button is the only reliable signal that
     * somebody actually saw it.
     */
    private val crashNotice = mutableStateOf("")

    /** What the update row says right now, so a slow download does not look like a dead button. */
    private val updateStatus = mutableStateOf("")

    private val settingsVisible = mutableStateOf(false)
    private val settingsRows = mutableStateOf<List<SettingRow>>(emptyList())

    /**
     * How many modes the panel reports, kept because the settings screen shows it and the engine
     * default is derived from it. One mode means a television that cannot change its refresh rate,
     * which is the whole reason two engines exist.
     */
    private var displayModeCount: Int = 0

    /**
     * Whether the viewer wants English subtitles.
     *
     * Off by default. Most of the dial is in English and captions on a channel nobody needed them
     * for is a worse default than absence; the channels that genuinely need them - dubbed martial
     * arts, subtitled anime - are the reason it exists at all.
     *
     * `@Volatile` because the toggle is flipped on the UI thread and read on the tuning executor
     * and the prefetch executor when a clip is resolved.
     */
    @Volatile private var captionsOn: Boolean = false

    /**
     * The cues of the clip currently playing, as the overlay draws them.
     *
     * The app parses and draws subtitles itself rather than handing the track to the player -
     * see [com.cliftonia.fs42tv.ui.CaptionLine] for why. Written only on the UI thread: cleared
     * where the clip starts, filled by [loadCaptions] once the file has come down.
     */
    private val captionCues = mutableStateOf<List<VttCues.Cue>>(emptyList())

    private val standByReason = mutableStateOf("")

    // True from choosing a channel until its first frame arrives, so the previous channel is not
    // left playing under a banner announcing a different one.
    private val tuning = mutableStateOf(false)

    /** True once a newer build has been downloaded and is sitting ready to install. */
    private val updateReady = mutableStateOf(false)

    /**
     * Set the channel's volume from the two things that can silence it, rather than from
     * whichever happened last.
     *
     * Both the guide music and a channel change want the programme audio down, and they overlap:
     * selecting from the picker closes it - restoring volume - immediately AFTER the tune has
     * muted, so a last-writer-wins approach let the previous channel's audio out for exactly the
     * split second the new one took to arrive. Deriving the value from both conditions makes the
     * order they fire in irrelevant.
     */
    private fun updateProgrammeVolume() {
        player?.setVolume(if (tuning.value || pickerVisible.value) 0f else 1f)
    }

    private val pickerVisible = mutableStateOf(false)
    private val pickerRows = mutableStateOf<List<Pair<String, String>>>(emptyList())
    private val pickerStartIndex = mutableStateOf(0)

    // Local var rather than only a local val in onCreate: opening the picker needs to flip this
    // view's descendantFocusability and pull focus onto it, which onKeyDown must be able to
    // reach after onCreate has returned.
    private lateinit var composeView: ComposeView

    private lateinit var prefs: SharedPreferences
    private lateinit var tune: TuneController
    private lateinit var settingsCatalog: SettingsCatalog
    private lateinit var resolver: ClipResolver

    // One object rather than four fields, because a refusal is a four-part update - see the
    // ledger's own comment for the invariant that shipped broken twice as separate fields.
    private val ledger = RefusalLedger(
        nowElapsedSeconds = { SystemClock.elapsedRealtime() / 1000 },
    )

    // Single-threaded so a rapid burst of channel presses queues in order rather than racing
    // each other over the shared navigator and player.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * A second thread, for resolving channels nobody has asked for yet.
     *
     * Separate from [executor] on purpose: that one serves the channel the viewer is actually
     * waiting for, and a speculative resolve queued ahead of a real keypress would make surfing
     * slower rather than faster - the exact opposite of why this exists.
     */
    private val prefetchExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * A third thread, for downloading the subtitle file of the clip that just started.
     *
     * Its own thread for the same reason [prefetchExecutor] has one, in both directions. Putting
     * this on [executor] would add a round trip to googlevideo in front of the next channel
     * change, and the resolve already costs 2.4s on device; putting it on [prefetchExecutor]
     * would queue it behind two speculative resolves, so the captions for the programme actually
     * being watched would arrive after the neighbours nobody has asked for.
     */
    private val captionExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val captions by lazy {
        CaptionLoader(
            executor = captionExecutor,
            runOnUi = { block -> runOnUiThread(block) },
            generationNow = { tune.generationNow() },
            halted = { destroyed },
            show = { captionCues.value = it },
        )
    }

    /** Drives the stand-by card when playback stalls mid-clip. */
    private val stallHandler by lazy { android.os.Handler(mainLooper) }

    /**
     * Delays the stand-by card after a playback error, so a fault the app repairs by itself is
     * never announced.
     *
     * Deliberately NOT the stall handler. Both post one delayed reveal and both clear their queue
     * before posting, so sharing one would let a stall cancel a pending error card and leave a
     * genuinely dead channel showing nothing but a blank screen forever.
     */
    private val recoveryHandler by lazy { android.os.Handler(mainLooper) }

    /**
     * Builds a fresh engine and swaps it into the layout, releasing the old one.
     *
     * Only mpv needs this, and only for one reason: a dead URL makes an EDL yield no streams at
     * all, which mpv treats as FATAL and shuts its core down - `idle=yes` does not cover a fatal.
     * Without a rebuild, one 403 blacks out the dial until the app is restarted by hand.
     */
    @Volatile private var rebuildEngine: (() -> Unit)? = null

    /**
     * Which quality tiers to ask for, from what this panel can actually show.
     *
     * Read from `Display.getMode()`, which reports the PHYSICAL mode - 3840x2160 on the TCL -
     * unlike `DisplayMetrics`, which on Android TV reports the UI layer and is 1920x1080 on the
     * same set. Reading the wrong one caps every 4K television at hd, which is precisely what
     * the old hard-wired `preferUhd = false` did.
     *
     * The UI layer being 1080p does not cap video: the app's UI and the video surface are
     * composited separately, and a SurfaceView renders at panel resolution regardless.
     */
    @Volatile private var ladder: List<String> = listOf("hd", "sd")

    /**
     * Wall-clock seconds, or a frozen instant when one was supplied at launch.
     *
     * Every channel on this dial derives its clip and offset from the current time, so two runs
     * a few minutes apart are watching entirely different content - different bitrates, different
     * file sizes, different CDN hosts. That is a far larger source of variance than any setting
     * worth tuning, and it produced three separate false results before it was identified: the
     * same configuration measured 3892ms then 9971ms on the emulator, and 1779ms then 4483ms on
     * the television.
     *
     * Freezing the clock pins clip selection and offset, so a sweep compares configurations
     * against identical content instead of against whatever happened to be on air. Only ever set
     * for measurement; a launch without the extra behaves exactly as the remote does.
     */
    @Volatile private var fixedNowSeconds: Long = -1L

    private fun nowSeconds(): Long =
        if (fixedNowSeconds > 0) fixedNowSeconds else System.currentTimeMillis() / 1000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First, so that anything failing during the rest of setup is still recorded. A crash on
        // a television with no adb is otherwise unreadable.
        CrashLog.install(filesDir)
        // Surfaced immediately, before a channel has even been tuned. A crash on a television
        // with no adb is otherwise invisible, and the card is already the place the app uses to
        // say something has gone wrong.
        // Android's account first, ours second. A native crash or a low-memory kill leaves
        // nothing in CrashLog - that is precisely the gap this fills - and when both have
        // something to say, Android's is the one that names what actually happened.
        val died = ExitReason.lastAbnormal(this) ?: CrashLog.summary(filesDir)
        died?.let { crashNotice.value = "LAST RUN: $it" }
        // Stop the television deciding nobody is there.
        //
        // A remote that has not been touched for half an hour looks exactly like an idle device
        // to Android, and it turns the screen off mid-programme. This is the flag video apps use
        // to say otherwise, and it applies only while this activity is in front - it cannot keep
        // the set awake once someone leaves.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        settingsCatalog = SettingsCatalog(this, SettingsCatalog.Deps(
            prefs = prefs,
            displayModeCount = { displayModeCount },
            channels = { navigator?.channels.orEmpty() },
            ladder = { ladder },
            setLadder = { ladder = it },
            clearResolved = ledger::clearResolved,
            captionsOn = { captionsOn },
            toggleCaptions = ::toggleCaptions,
            applyAudioHold = { millis ->
                (player as? MpvChannelPlayer)?.setAudioHoldMillis(millis)
                    ?: run { com.cliftonia.fs42tv.player.audioHoldMillis = millis }
            },
            checkForUpdate = { onStatus ->
                checkForUpdate(installWhenReady = true) { status ->
                    updateStatus.value = status
                    onStatus(status)
                }
            },
            updateStatus = { updateStatus.value },
            refresh = ::refreshSettingsRows,
        ))
        resolver = AcceleratedResolver(
            server = ServerResolver(RESOLVE_SERVER) { url, timeout ->
                (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
                    connectTimeout = timeout
                    readTimeout = timeout
                    try {
                        inputStream.bufferedReader().use { it.readText() }
                    } finally {
                        disconnect()
                    }
                }
            },
            device = DeviceResolver(),
        )

        tune = createTuneController()

        // Which engine plays the dial, and why it is not simply "the newer one".
        //
        // Media3 judders on this television - roughly two tunes in five come back with the
        // picture running fast then slow - and mpv does not, measured on the same clips at the
        // same offsets. androidx/media issue 2941 documents the same fault on BUILT-IN Android
        // TVs and explicitly NOT on Chromecast or Fire TV, which matches: a stick can change its
        // HDMI output mode, a panel with one mode cannot. So the choice is made from the number
        // of display modes rather than from a device name, and Media3 stays the default wherever
        // it works - it is a fifth of the install size and starts faster.
        //
        // Override with:  adb shell am start -S -n com.cliftonia.fs42tv/.MainActivity --es engine mpv
        // (-S because launchMode is singleTask: without it a launch while the app is running
        // re-delivers the intent to the EXISTING activity and onCreate - where this is read -
        // never runs.)
        val modeCount = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            display else windowManager.defaultDisplay)?.supportedModes?.size ?: 0
        displayModeCount = modeCount
        captionsOn = prefs.getBoolean(SettingsCatalog.CAPTIONS_KEY, false)
        // Read before the engine is built, because mpv applies it during initialisation.
        com.cliftonia.fs42tv.player.videoSyncMode =
            prefs.getString(SettingsCatalog.VIDEO_SYNC_KEY, null) ?: FrameCadence.SYNC_MODES.first()
        // Same reason, and it must be re-read on every engine build rather than only on the first:
        // mpv is rebuilt whenever its core shuts down, and a trim that reset itself on that path
        // would look exactly like the audio fault coming back.
        com.cliftonia.fs42tv.player.audioHoldMillis = prefs.getInt(SettingsCatalog.AUDIO_HOLD_KEY, 0)
        Log.i("fs42", "audio out ${settingsCatalog.audioRoute()}, hold " +
            "${com.cliftonia.fs42tv.player.audioHoldMillis}ms")
        ladder = SettingsCatalog.QUALITY_LADDERS
            .firstOrNull { it.first == prefs.getString(SettingsCatalog.QUALITY_KEY, null) }
            ?.second ?: SettingsCatalog.QUALITY_LADDERS.first().second
        // The measurement seam: tools/measure-switch.sh passes `--el fs42.now` to pin the
        // clock, so a latency sweep tunes the same clips at the same offsets on every run
        // instead of measuring whatever happens to be on air. Disconnected once during a
        // refactor, after which a sweep silently measured rotating content - the numbers
        // looked plausible and meant nothing.
        fixedNowSeconds = intent?.getLongExtra("fs42.now", -1L) ?: -1L
        if (fixedNowSeconds > 0) Log.i("fs42", "clock pinned to $fixedNowSeconds")
        val engine = PlayerEngine.parse(intent?.getStringExtra("engine"))
            ?: PlayerEngine.parse(prefs.getString(SettingsCatalog.ENGINE_KEY, null))
            ?: PlayerEngine.default(modeCount)
        prefs.edit().putString(SettingsCatalog.ENGINE_KEY, engine.name.lowercase()).apply()
        Log.i("fs42", "player engine $engine ($modeCount display mode(s))")
        composeView = ComposeView(this).apply {
            // The picker needs focus when open; the OSD does not, and must not steal it from
            // the D-pad channel-surfing handled in onKeyDown while the picker is closed.
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Beneath the OSD, so the banner stays readable through the change.
                    TuningBlank(tuning.value)
                    // Above the blank so it is not drawn over black between channels, and below
                    // everything else so a banner, a card or the guide always wins the bottom of
                    // the screen. Hidden whenever something is up in front of the programme:
                    // subtitles for a channel nobody is currently looking at are noise.
                    CaptionLine(
                        cues = captionCues.value,
                        positionSeconds = { player?.positionSeconds() },
                        visible = !tuning.value && !pickerVisible.value && !settingsVisible.value,
                    )
                    UpdatePrompt(updateReady.value)
                    ChannelOsd(
                        channelLine = bannerChannelLine.value,
                        titleLine = bannerTitleLine.value,
                        generation = bannerGeneration.value,
                    )
                    // One card, two sources: a live playback failure, or last run's crash.
                    // The crash wins while it is showing, since a channel that is currently
                    // failing will say so again in four seconds anyway.
                    val standByText = crashNotice.value.ifEmpty { standByReason.value }
                    StandBy(standByText.isNotEmpty(), standByText)
                    if (settingsVisible.value) {
                        SettingsScreen(rows = settingsRows.value, onDismiss = ::closeSettings)
                    }
                    if (pickerVisible.value) {
                        ChannelPicker(
                            rows = pickerRows.value,
                            startIndex = pickerStartIndex.value,
                            onPick = ::onPickChannel,
                            onDismiss = ::dismissPicker,
                        )
                    }
                }
            }
        }
        fun matchParent() = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        fun newEngine(): ChannelPlayback = when (engine) {
            PlayerEngine.MPV -> MpvChannelPlayer(this)
            PlayerEngine.MEDIA3 -> ChannelPlayer(
                this, Media3Sources.dataSourceFactory(), canSwitchDisplayMode = modeCount > 1)
        }

        var player: ChannelPlayback = newEngine()
        this.player = player

        val root = FrameLayout(this).apply {
            addView(player.view, matchParent())
            addView(composeView, matchParent())
        }
        setContentView(root)

        // Both of these leave a black screen if nothing handles them, and neither reports itself:
        // a finished clip simply stops, and a rejected URL stops too. Re-tuning the channel is
        // the right answer to both - the clock rotation will pick whatever should be on now,
        // which after a finished clip is the next one along.
        //
        // Guarded by a fresh generation so a recovery cannot fight a channel change the viewer
        // has already made, and skipped entirely once the activity is gone.
        wirePlayer(player)

        // Below the callback wiring so a rebuilt engine gets the same callbacks the first one
        // had - a fresh player with nothing listening reports no first frame, so the stand-by
        // card would never come down again.
        rebuildEngine = {
            // THE OLD ENGINE IS TORN DOWN COMPLETELY BEFORE THE NEW ONE IS BUILT. Order is not a
            // style choice here, it is the whole correctness of this block.
            //
            // libmpv's Java binding is a process-global singleton holding ONE mpv handle. Its
            // native `create` is `if (handle != NULL) die("mpv is already initialized")`, and
            // `die` is a log line followed by exit(1) - a clean process exit, so there is no
            // signal, no tombstone and no Java exception. It looks exactly like the app quietly
            // vanishing, which is what was being chased.
            //
            // Building the replacement first therefore killed the process on the FIRST rebuild
            // the app ever attempted; and had it survived, releasing the old engine afterwards
            // would have destroyed the global handle the new one was already using, so the next
            // `setVolume` or `loadfile` would exit(1) on `die("mpv is not created")` instead.
            //
            // Removing the dead view before releasing it was the third face of the same bug: it
            // is `removeView` that dispatches surfaceDestroyed, and that handler sets `vo=null`
            // and detaches the surface on the GLOBAL handle - which would by then have belonged
            // to the new engine. Release first, and the surface teardown lands on its own core.
            val dead = this.player
            dead?.release()
            root.removeView(dead?.view)

            val fresh = newEngine()
            wirePlayer(fresh)
            this.player = fresh
            player = fresh
            root.addView(fresh.view, 0, matchParent())
            Log.i("fs42", "engine rebuilt after shutdown")
        }

        checkForUpdate()

        val remembered = prefs.getInt(CHANNEL_KEY, NO_REMEMBERED_CHANNEL)

        loadDial(remembered)
    }

    /**
     * The dial's engine room, with the screen's half of every tune spelled out as callbacks.
     * See [TuneController] for why the generation logic lives outside the activity.
     */
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
        rememberChannel = { number ->
            prefs.edit().putInt(CHANNEL_KEY, number).apply()
        },
        screen = TuneController.Screen(
            startBlank = { target ->
                // Stop the old channel at the SOURCE rather than covering it. A Compose
                // overlay needs a recomposition and a frame to appear, and the previous
                // channel keeps rendering underneath in the meantime - which showed up as an
                // intermittent flash of the old picture right after choosing a new one.
                // stop() ends that render immediately, and the blank covers the gap between
                // the shutter and the first frame of the new channel.
                player?.stop()
                // A deliberate channel change supersedes any error still waiting to be
                // announced: the card would name a channel the viewer has already left.
                recoveryHandler.removeCallbacksAndMessages(null)
                standByReason.value = ""
                tuning.value = true
                updateProgrammeVolume()
                // The title comes from the clock rotation right here, not from the tune that
                // follows. Waiting for the tune meant the banner showed a bare channel name
                // whenever the tune was superseded - which is every press but the last when
                // surfing quickly. What is on a channel is knowable without tuning to it.
                val (line, title) = ChannelLabels.bannerLinesFor(target, nowSeconds())
                bannerChannelLine.value = line
                bannerTitleLine.value = title
                bannerGeneration.value += 1
            },
            paint = { painted, playable, requestedAt, played, gen ->
                player?.play(playable, painted.offsetSeconds, requestedAt)
                // A tune that lands while the app is in the background must not leave the
                // player running: onStop already paused whatever was playing, and this tune
                // would otherwise stream and decode to a screen nobody is watching.
                if (stopped) player?.setPaused(true)
                // Cleared on the same thread that starts the clip, so the outgoing
                // programme's dialogue cannot be left sitting over the incoming one.
                captionCues.value = emptyList()
                if (captionsOn) captions.load(playable, gen)
                // Only a genuine success touches the banner, and it reads the current onAir
                // rather than this tune's outcome directly - a failed tune leaves onAir on
                // whatever last actually played, exactly as the picture itself does.
                if (played) {
                    tune.onAir?.let { nowOnAir ->
                        val (channelLine, titleLine) = ChannelLabels.bannerLines(nowOnAir)
                        bannerChannelLine.value = channelLine
                        bannerTitleLine.value = titleLine
                    }
                    bannerGeneration.value += 1
                }
            },
            channelUnavailable = { channel ->
                standByReason.value = "CHANNEL ${channel.number} UNAVAILABLE"
            },
        ),
    ))

    /**
     * Give [player] the four callbacks that keep the dial honest.
     *
     * A method rather than wiring inline in onCreate so a REBUILT engine gets the same
     * callbacks the first one had: mpv shuts its core down on a fatal, and a replacement with
     * nothing listening reports no first frame - the stand-by card would then never come down
     * again.
     */
    private fun wirePlayer(player: ChannelPlayback) {
        player.onClipEnded = { tune.clipEnded() }
        player.onPlaybackError = { code ->
            if (code.startsWith(MpvChannelPlayer.ENGINE_DIED) && !destroyed) {
                // The engine, not the clip. Rebuild first, then let the normal recovery below
                // re-tune into the new instance.
                rebuildEngine?.invoke()
            }
            // A rejected URL is the one error worth reacting to specifically: re-tuning without
            // forgetting it would resolve to the same dead link and fail the same way.
            // Engine-agnostic on purpose. Media3 names the fault precisely; mpv reports only
            // that the file ended in error, and its commonest cause by far is exactly this - a
            // signed URL the CDN refused. Matching only Media3's spellings meant an mpv 403
            // re-tuned to the very same dead URL, forever. Being wrong in the other direction
            // costs one server resolve.
            if (code.contains("BAD_HTTP_STATUS") || code.contains("FILE_NOT_FOUND") ||
                code.startsWith("MPV_")) {
                tune.onAir?.stream?.id?.let { id ->
                    // Refuse the TIER, not the clip - condemning the whole id forces a
                    // /resolve, which runs yt-dlp at seven to twelve measured seconds, and
                    // nearly every clip carries a lower rung in a file the app already
                    // holds. Which rung, and what to forget, is the ledger's decision.
                    val tier = ledger.condemn(id, ladder)
                    if (tier != null) {
                        Log.w("fs42", "tier $tier refused for $id; falling to the next rung")
                    } else {
                        Log.w("fs42", "all tiers refused for $id; asking the server")
                    }
                }
            }

            // Do NOT put the stand-by card up yet. A signed googlevideo URL can be refused with
            // 403 while still inside its stated expiry, and the recovery below - drop the dead
            // id, ask the server for a fresh one, tune again - puts a picture back in about a
            // second. Announcing that as a fault showed the viewer an error code for something
            // the app had already fixed, which reads as far more broken than the brief blank a
            // channel change produces anyway.
            //
            // The card is only delayed, never skipped: if the retune has not produced a picture
            // by the time the grace period is up, this is a real fault and says so. Blanking
            // meanwhile is what a channel change already does, so the transition looks the same
            // as any other.
            tuning.value = true
            updateProgrammeVolume()
            recoveryHandler.removeCallbacksAndMessages(null)
            recoveryHandler.postDelayed(
                { if (!destroyed) standByReason.value = code }, RECOVERY_GRACE_MILLIS)
            tune.retuneCurrent("playback error $code")
        }
        // The card comes down when a picture actually appears, not when a tune is merely
        // dispatched - a tune that fails again would otherwise clear it and leave black.
        player.onFirstFrame = {
            tune.noteFirstFrame()
            recoveryHandler.removeCallbacksAndMessages(null)
            standByReason.value = ""
            tuning.value = false
            updateProgrammeVolume()
        }

        // A stall is the third way this player goes quiet, and the only silent one - no error,
        // no end of media, just a stopped picture. The box calls the same condition a fault
        // after two seconds and puts a stand-by card up (field_player.py:575).
        //
        // The card is ALL that happens. Re-tuning on a stall was tried and made things far
        // worse: it discards whatever has buffered and restarts the deep seek, so on a
        // connection that cannot sustain the bitrate it produced a permanent cycle of six
        // seconds of picture and twelve of nothing. ExoPlayer keeps filling during a stall and
        // resumes by itself; interrupting that is the one thing that stops it recovering.
        player.onBuffering = { buffering ->
            stallHandler.removeCallbacksAndMessages(null)
            if (buffering) {
                stallHandler.postDelayed({
                    if (!destroyed) standByReason.value = "BUFFERING"
                }, STALL_CARD_MILLIS)
                // NO automatic re-tune on a stall. It was tried and it made things materially
                // worse: a re-tune discards whatever has buffered and restarts the deep seek, so
                // on a connection that cannot sustain the bitrate it produced a permanent cycle -
                // six seconds of playback, twelve seconds of nothing, repeat. ExoPlayer keeps
                // filling the buffer during a stall and resumes on its own; interrupting that is
                // the one thing guaranteed to stop it recovering.
            } else {
                standByReason.value = ""
            }
        }
    }

    /**
     * Fetch the lineup and start the first tune, retrying for as long as there is no dial.
     *
     * The failure branch exists because its absence was a bricked television: first launch on a
     * dead hotspot (or after the cache was cleared) logged one line and returned, leaving a
     * permanently black screen with a dead remote - navigator stays null, so onKeyDown ignores
     * every key - and the sync exception itself was discarded, so even adb could not say whether
     * it was DNS, TLS or a captive portal. The card says the app is alive and what it needs, and
     * the retry means a hotspot that comes up a minute later revives the dial without a relaunch.
     */
    private fun loadDial(remembered: Int) {
        val initialRequestedAt = SystemClock.elapsedRealtime()
        executor.execute {
            val repo = DialRepository(
                // Timeouts, because the default is none at all. This runs on the SAME
                // single-threaded executor as every tune, so one hung connection to a CDN edge
                // meant no channel ever tuned again and every keypress queued silently behind it -
                // a television that looks bricked with nothing on screen to say why.
                fetch = { url ->
                    (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
                        connectTimeout = 10_000
                        readTimeout = 20_000
                        try {
                            inputStream.bufferedReader().use { it.readText() }
                        } finally {
                            disconnect()
                        }
                    }
                },
                cacheDir = cacheDir,
            )
            val synced = runCatching { repo.sync(LINEUP_URL) }
                .onFailure { Log.w("fs42", "lineup sync failed", it) }
                .getOrNull()
            val dial = synced?.dial ?: repo.cachedDial()

            val channels = dial?.channels
            if (channels.isNullOrEmpty()) {
                Log.e("fs42", "no dial available; retrying in ${DIAL_RETRY_MILLIS / 1000}s")
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    standByReason.value = "NO LINEUP - CHECK CONNECTION"
                    // recoveryHandler is drained in onDestroy, and the navigator check makes a
                    // retry that raced a successful load a no-op. The destroyed check must run
                    // before execute: onDestroy shuts the executor down, and posting to a dead
                    // one throws rather than being quietly dropped.
                    recoveryHandler.postDelayed({
                        if (!destroyed && navigator == null) loadDial(remembered)
                    }, DIAL_RETRY_MILLIS)
                }
                return@execute
            }

            val nav = DialNavigator(channels, remembered.takeIf { it > 0 })
            navigator = nav
            tune.tuneFirst(nav.current, initialRequestedAt)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Any key dismisses last run's crash. Pressing a button is the only reliable evidence
        // somebody was in front of the television to read it; a timer would expire while the
        // room was empty and the notice would be gone by the time anyone looked.
        if (crashNotice.value.isNotEmpty()) {
            crashNotice.value = ""
            CrashLog.clear(filesDir)
        }
        val nav = navigator ?: return super.onKeyDown(keyCode, event)

        // Belt and braces alongside the focus handoff in openPicker(): once the picker is up,
        // the focused row already consumes D-pad up/down/centre before the activity would ever
        // see them, but this guard is what actually guarantees the channel-change keys are
        // inert here rather than relying on focus routing alone. KEYCODE_BACK is deliberately
        // not handled here at all - the picker owns its own dismissal via BackHandler.
        if (pickerVisible.value || settingsVisible.value) {
            return super.onKeyDown(keyCode, event)
        }

        return when (keyCode) {
            // Left, because it is the only D-pad direction the dial does not already use and it
            // cannot be pressed by accident while surfing, which is up and down. It exists mainly
            // for the engine switch: that is the escape hatch for the judder, and until this it
            // needed `adb shell am start --es engine mpv` from a laptop.
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                openSettings()
                true
            }
            // Right shows what is on, which is what INFO does on a real remote - and right is the
            // one d-pad direction the dial had left. KEYCODE_INFO is accepted alongside it for
            // the remotes that have the button; the Google TV and TCL remotes do not.
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                showBanner()
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
                // prompt says so, so it is not a surprise. The alternative was a second key, and
                // this remote is a cheap universal one where INFO and MENU may not exist at all;
                // a long press would have meant taking over key tracking from the guide.
                //
                // Cleared before installing either way: whether the viewer accepts Android's
                // dialog or dismisses it, the prompt has done its job and must not sit there
                // hijacking the guide button afterwards.
                if (updateReady.value) {
                    updateReady.value = false
                    Updater(this, RELEASES_REPO).install()
                } else {
                    openPicker(nav)
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }


    /**
     * Opens the picker seeded on the channel actually on air - [onAir], not [DialNavigator.currentIndex]:
     * a tune that failed leaves the navigator pointed somewhere the picture never actually
     * reached, and the picker must open on what the viewer is looking at, not where the dial
     * silently moved to.
     */
    private fun openPicker(nav: DialNavigator) {
        // Opening the picker is a supersede point. onKeyDown queues `tuneTo(nav.up(), gen)` and
        // evaluates nav.up() when the EXECUTOR reaches it, not at keypress time - so a press
        // landing a moment before this one still has a tune in flight. Without this bump that
        // tune passes its own generation check, moves the navigator out from under the rows
        // captured below, and starts playing with its banner drawn behind the open list. That
        // was reproduced on device before this line existed, not theorised.
        tune.supersede()

        val onAirNumber = tune.onAir?.channel?.number ?: nav.currentNumber
        val startIndex = nav.channels.indexOfFirst { it.number == onAirNumber }
            .let { if (it >= 0) it else nav.currentIndex }

        // The list goes up with channel names ONLY, immediately. Working out what is on each
        // of 111 channels means walking every channel's clip list, and doing that before the
        // first frame of the picker is drawn puts a visible pause between pressing the button
        // and seeing anything - the one moment where a guide has to feel instant.
        //
        // The titles arrive a beat later and the rows fill in underneath, which is what a
        // skeleton is for: structure now, detail when it exists.
        pickerRows.value = nav.channels.map { ChannelLabels.listRow(it) }
        pickerStartIndex.value = startIndex
        pickerVisible.value = true

        composeView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        composeView.requestFocus()
        fillPickerTitles(nav)
        startPickerMusic(nav)
    }

    /**
     * Work out what is on each channel and fill the rows in behind the already-visible list.
     *
     * Off the UI thread, and discarded if the picker has closed by the time it finishes - a
     * viewer who opened and dismissed the guide in under a second should not have rows quietly
     * rewritten underneath the channel they went back to watching.
     */
    private fun fillPickerTitles(nav: DialNavigator) {
        val channels = nav.channels
        executor.execute {
            val started = SystemClock.elapsedRealtime()
            // One instant for the whole dial - see GuideRows, which owns this now so it can be
            // tested. Walking 100 channels while reading the clock per channel would let the list
            // straddle a programme boundary and show two different moments at once.
            val rows = GuideRows.forChannels(channels, nowSeconds())
            val took = SystemClock.elapsedRealtime() - started
            runOnUiThread {
                if (destroyed || !pickerVisible.value) return@runOnUiThread
                Log.d("fs42", "guide titles for ${rows.size} channels in ${took}ms")
                pickerRows.value = rows
            }
        }
    }

    /**
     * Play the guide music and duck the channel underneath.
     *
     * Ducked rather than left alone: two audio sources at once is noise, and a guide channel
     * always replaced the programme audio rather than competing with it. The picture keeps
     * playing, so closing the picker restores sound to a channel that never stopped.
     */
    private fun startPickerMusic(nav: DialNavigator) {
        val channel = PickerMusic.choose(nav.channels) ?: return
        updateProgrammeVolume()
        executor.execute {
            if (destroyed) return@execute
            val tuned = tune.resolveForAudio(channel) ?: return@execute
            // Only the audio track is wanted, so the audio URL is handed over as the source and
            // the video URL is dropped entirely - no second decode, no second video fetch.
            val audioOnly = when (val playable = tuned.playable) {
                is Progressive -> playable.audioUrl?.let { Progressive(it, null) }
                is Hls -> playable
                else -> null
            } ?: return@execute
            // Always Media3, whatever plays the video. The guide music is an audio-only
            // stream under a translucent list; it has none of the frame-pacing problem that put
            // mpv on the video path, and giving it a second engine would mean a second set of
            // native libraries loaded to play 128kbps of bossa nova.
            val source = Media3Sources.sourceFor(
                Media3Sources.dataSourceFactory(), audioOnly) ?: return@execute

            runOnUiThread {
                // The stopped check is what keeps bossa nova off the launcher: onStop releases
                // musicPlayer, but a resolve already in flight lands here afterwards and would
                // otherwise build a fresh ExoPlayer and play guide music behind the home screen
                // (pickerVisible stays true across HOME - the picker deliberately survives it).
                if (destroyed || stopped || !pickerVisible.value) return@runOnUiThread
                val music = musicPlayer ?: androidx.media3.exoplayer.ExoPlayer.Builder(this)
                    .build().also { musicPlayer = it }
                Log.i("fs42", "guide music: ${channel.name}")
                music.setMediaSource(source, (tuned.offsetSeconds * 1000).toLong())
                music.prepare()
                music.playWhenReady = true
            }
        }
    }

    private fun stopPickerMusic() {
        // RELEASE, not stop(). stop() halts playback but keeps the instance, and with it a
        // hardware MediaCodec - a limited resource on this television, held idle alongside the
        // video decoder for as long as the app runs. Frame drops appeared across every channel
        // as soon as this player was introduced, which is what an extra codec instance looks
        // like from the outside. Recreating it on the next open costs a few hundred
        // milliseconds of music, against a picture that stays smooth.
        musicPlayer?.release()
        musicPlayer = null
        updateProgrammeVolume()
    }

    /**
     * Deliberately does NOT bump [generation], unlike [openPicker]. Closing happens either from
     * BACK - when nothing is queued, because onKeyDown refuses every channel key while the picker
     * is up - or from [onPickChannel], which runs immediately AFTER queueing the tune the viewer
     * just asked for. A bump here would supersede that tune and selecting a channel would quietly
     * do nothing.
     */
    private fun closePicker() {
        pickerVisible.value = false
        stopPickerMusic()
        composeView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    /**
     * BACK on the picker. Distinct from [closePicker] because dismissal is the one close that
     * must also check for an abandoned tune: [openPicker] bumps the generation, which kills any
     * error-recovery retune in flight, and if that recovery was what stood between the viewer
     * and a black screen, the channel behind the list is still black. [onPickChannel] keeps
     * calling [closePicker] directly - it just queued a tune of its own, and a recovery bump
     * here would supersede it.
     */
    private fun dismissPicker() {
        closePicker()
        recoverIfAbandoned()
    }

    /**
     * Re-tune if an overlay closed over a tune that never finished.
     *
     * tuning.value is set by surfTo and only a first frame clears it, so it still being up when
     * an overlay closes means the picture never arrived - either the tune failed or the overlay's
     * generation bump abandoned it. Watching normally it is false and this does nothing.
     */
    private fun recoverIfAbandoned() {
        if (!tuning.value || destroyed) return
        val channel = tune.onAir?.channel ?: navigator?.current ?: return
        Log.i("fs42", "re-tuning ${channel.number} ${channel.name}: overlay closed over an unfinished tune")
        tune.tune(channel)
    }

    /**
     * OK on a row: resolves the row index back to a channel, moves the navigator to it exactly
     * like surfing does, tunes it on the executor, and closes. This is the one path that
     * actually changes the channel from the picker - reusing [tuneTo] rather than a second one,
     * per the same generation bookkeeping surfing uses.
     */
    private fun onPickChannel(index: Int) {
        val nav = navigator
        val channel = nav?.channels?.getOrNull(index)
        if (nav != null && channel != null) {
            // jumpTo on this thread, like up() and down(): key handling is the navigator's single
            // writer, and the executor only reads it.
            nav.jumpTo(channel.number)

            // Selecting the channel already on air must NOT re-tune. A re-tune tears the player
            // down and restarts the same clip at a freshly computed offset a few seconds later,
            // so the picture visibly jumps for no reason - the viewer asked for the channel they
            // are already watching, and the correct answer is "you have it". The banner is still
            // re-shown, because pressing OK on a channel is a request to be told what it is.
            if (tune.onAir?.channel?.number == channel.number) {
                bannerGeneration.value += 1
            } else {
                tune.surfTo(channel)
            }
        }
        closePicker()
    }


    /**
     * Find and draw a caption track for whatever is playing right now.
     *
     * Only for the toggle. A tune already loads captions as part of resolving, and this exists
     * because the clip on screen was resolved before the viewer asked for them - re-resolving is
     * a couple of seconds on the caption thread and costs the picture nothing, since it is only
     * the subtitle url that is wanted.
     */
    private fun loadCaptionsForCurrentClip() {
        val id = tune.onAir?.stream?.id ?: run {
            Log.i("fs42", "captions: nothing on air to caption")
            return
        }
        val playable = ledger.recall(id, nowSeconds()) ?: run {
            // Only reachable if the clip's urls expired while it was still playing, which the
            // tune path handles by re-resolving anyway.
            Log.i("fs42", "captions: $id is not in the resolved cache")
            PlaybackDiagnostics.recordCaptions("NOT CACHED")
            return
        }
        captions.load(playable, tune.generationNow())
    }

    /** Guards against a second check while one is already running. */
    private val updateCheckRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Put the channel banner back up, recomputed rather than replayed.
     *
     * The stored lines were written when the channel was tuned, and a clip that has rolled over
     * since would name the programme before this one - which is worse than no banner, because it
     * is confidently wrong. [ChannelLabels.bannerLinesFor] is pure clock arithmetic over a list
     * already in memory, so recomputing costs nothing and is always right.
     *
     * Falls back to the stored lines only when nothing is on air, which is a channel between
     * clips rather than a mistake.
     */
    private fun showBanner() {
        val channel = tune.onAir?.channel ?: navigator?.current
        if (channel != null) {
            val (line, title) = ChannelLabels.bannerLinesFor(channel, nowSeconds())
            bannerChannelLine.value = line
            if (title.isNotEmpty()) bannerTitleLine.value = title
        }
        // The generation is what replays the auto-hide timer in ChannelOsd, so bumping it is what
        // actually shows the banner. Incrementing it with no change to the lines is exactly what
        // pressing OK on the channel already playing does.
        bannerGeneration.value += 1
    }

    /**
     * Open the settings list, freezing the dial underneath the way the picker does.
     *
     * The generation bump is the same guard [openPicker] needs and for the same reason: a channel
     * change pressed a moment earlier still has a tune in flight, and letting it land would change
     * the channel under an open overlay.
     */
    private fun openSettings() {
        tune.supersede()
        updateStatus.value = ""
        refreshSettingsRows()
        settingsVisible.value = true
        composeView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        composeView.requestFocus()
    }

    private fun closeSettings() {
        settingsVisible.value = false
        composeView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        composeView.clearFocus()
        // openSettings bumped the generation, so the same abandoned-tune check the picker's
        // dismissal makes applies here - see recoverIfAbandoned.
        recoverIfAbandoned()
    }

    /**
     * The settings screen's rows live in [SettingsCatalog]; the activity only provides the
     * capabilities its [SettingsCatalog.Deps] names and republishes the list when asked.
     */
    private fun refreshSettingsRows() {
        settingsRows.value = settingsCatalog.rows()
    }

    /**
     * The captions flag, applied to the clip already playing.
     *
     * Every resolve carries its caption url whether or not captions are on, so turning them on
     * is a fetch of the current clip's track out of the cache - no re-resolve. Off clears the
     * overlay immediately.
     */
    private fun toggleCaptions() {
        captionsOn = !captionsOn
        prefs.edit().putBoolean(SettingsCatalog.CAPTIONS_KEY, captionsOn).apply()
        if (captionsOn) loadCaptionsForCurrentClip() else captionCues.value = emptyList()
        Log.i("fs42", "captions ${if (captionsOn) "on" else "off"}")
    }





    /**
     * Ask the publisher whether there is a newer build, and fetch it if so.
     *
     * On its own thread, never the tuning executor: that executor is what makes a channel change
     * feel instant, and a 66MB download queued in front of a tune would undo the whole point of
     * it. Nothing is shown until the file is on disk, so an unreachable publisher - the normal
     * state of the set in the car - is completely silent.
     *
     * [installWhenReady] is what separates the two callers. On launch the check is a background
     * courtesy - it finds a build, says so on the dial, and waits for OK, because interrupting
     * someone who just turned the television on with an installer is rude. Asked for explicitly
     * from settings it should simply do the thing: a button called CHECK FOR UPDATE that finds an
     * update and then requires you to leave the screen and press a different button is a button
     * that has not finished its job.
     *
     * [onStatus] reports progress in words for the settings row, so a thirty-second download on a
     * slow connection looks like progress rather than a dead button.
     */
    private fun checkForUpdate(
        installWhenReady: Boolean = false,
        onStatus: (String) -> Unit = {},
    ) {
        if (!updateCheckRunning.compareAndSet(false, true)) return
        onStatus("CHECKING...")
        Thread {
            var status = "UP TO DATE"
            try {
                val updater = Updater(this, RELEASES_REPO)
                if (updater.downloadIfNewer(BuildConfig.VERSION_CODE)) {
                    runOnUiThread { if (!destroyed) updateReady.value = true }
                    if (installWhenReady) {
                        status = "INSTALLING..."
                        runOnUiThread {
                            if (destroyed) return@runOnUiThread
                            // Cleared before handing over, exactly as the dial's OK path does:
                            // whether the viewer accepts Android's dialog or dismisses it, the
                            // prompt has done its job and must not sit there afterwards.
                            updateReady.value = false
                            updater.install()
                        }
                    } else {
                        status = "READY - PRESS OK"
                    }
                }
            } catch (e: Exception) {
                // The publisher being unreachable is the normal state of a television in a car.
                Log.w("fs42", "update check failed: $e")
                status = "COULD NOT CHECK"
            } finally {
                updateCheckRunning.set(false)
                runOnUiThread { if (!destroyed) onStatus(status) }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Check again whenever the viewer comes back to the app.
     *
     * Launch alone was not enough: a television that stays on one channel for days never
     * relaunches, so a change published in the meantime would never be seen. Coming back from
     * the home screen is the natural moment to notice - and it costs one small request, since
     * the manifest is two fields and the apk is only fetched when it is genuinely newer.
     */
    override fun onResume() {
        super.onResume()
        // Pick up where the viewer left it. Volume is re-derived rather than assumed, since the
        // guide may have been open when they left.
        player?.setPaused(false)
        updateProgrammeVolume()
        checkForUpdate()
    }

    /**
     * Main-thread only, so it needs no @Volatile: it is written in onStart/onStop and read in
     * runOnUiThread blocks.
     */
    private var stopped = false

    override fun onStart() {
        super.onStart()
        stopped = false
    }

    /**
     * Stop playing once the app is no longer what is on screen.
     *
     * onStop rather than onPause: on Android TV a system dialog - the very install prompt this
     * app can raise - pauses the activity without hiding it, and silencing the channel behind a
     * dialog the viewer is about to dismiss would be its own annoyance. onStop means genuinely
     * gone: the home screen, another app, the television switched off.
     *
     * Paused rather than stopped, so coming back does not re-resolve a URL and seek again.
     */
    override fun onStop() {
        super.onStop()
        stopped = true
        player?.setPaused(true)
        // The guide music is a second player and would otherwise keep playing on its own.
        // Released rather than paused. Holding an idle ExoPlayer keeps a MediaCodec instance
        // reserved, and stopPickerMusic explains at length why that caused frame drops across
        // every channel - the same reasoning applies while the app is in the background, where it
        // is doing nothing whatsoever with it.
        musicPlayer?.release()
        musicPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        stallHandler.removeCallbacksAndMessages(null)
        recoveryHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        prefetchExecutor.shutdownNow()
        captionExecutor.shutdownNow()
        // Both are null'd as well as released, so a tune that outlived the activity finds
        // nothing to touch rather than a released player.
        musicPlayer?.release()
        musicPlayer = null
        player?.release()
        player = null
    }
}
