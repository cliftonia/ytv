package com.cliftonia.fs42tv

import android.app.ActivityManager
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
import com.cliftonia.fs42tv.player.ChannelPlayer
import com.cliftonia.fs42tv.player.MpvChannelPlayer
import com.cliftonia.fs42tv.player.PlayerEngine
import com.cliftonia.fs42tv.schedule.ClockRotation
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.PlaybackDiagnostics
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.ClipResolver
import com.cliftonia.fs42tv.resolver.DeviceResolver
import com.cliftonia.fs42tv.resolver.ResolvedCache
import com.cliftonia.fs42tv.resolver.StreamResolver
import com.cliftonia.fs42tv.resolver.TierLadder
import com.cliftonia.fs42tv.resolver.Unplayable
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.DialRepository
import com.cliftonia.fs42tv.sync.UrlCache
import com.cliftonia.fs42tv.tune.DialNavigator
import com.cliftonia.fs42tv.tune.Tuned
import com.cliftonia.fs42tv.tune.Tuner
import com.cliftonia.fs42tv.ui.ChannelLabels
import com.cliftonia.fs42tv.ui.ChannelOsd
import com.cliftonia.fs42tv.ui.GuideRows
import com.cliftonia.fs42tv.ui.PickerMusic
import com.cliftonia.fs42tv.ui.ChannelPicker
import com.cliftonia.fs42tv.ui.SettingRow
import com.cliftonia.fs42tv.ui.SettingsScreen
import com.cliftonia.fs42tv.ui.StandBy
import com.cliftonia.fs42tv.ui.TuningBlank
import com.cliftonia.fs42tv.ui.UpdatePrompt
import com.cliftonia.fs42tv.update.Updater
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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

/** The repository whose releases carry the apk, for the self-update check. */
private const val RELEASES_REPO = "cliftonia/ytv"

private const val PREFS_NAME = "fs42"
private const val CHANNEL_KEY = "channel"

/**
 * Remembers the chosen video engine so an override survives a relaunch.
 *
 * Persisted rather than decided fresh each start because the point of the flag is to put Media3
 * back in a hurry when mpv misbehaves - and a setting that evaporates on the next launch is no
 * use at all in that moment.
 */
private const val ENGINE_KEY = "engine"

/** Remembered quality ceiling; see qualityLadders. */
private const val QUALITY_KEY = "quality"
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
 * How many dead clips to step over before giving up on a channel.
 *
 * Each attempt costs a full extraction - a couple of seconds - so this is a trade between
 * recovering from the ordinary case and leaving someone watching black while the app grinds.
 * Three covers a run of removed videos; a channel with four consecutive dead clips has a real
 * problem and should say so rather than hide it.
 */
private const val SKIP_DEAD_CLIPS = 3

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

    /**
     * Signed urls published alongside the lineup - always null now, and deliberately still here.
     *
     * The server used to publish a `urls.json` covering about 46% of the dial's clips, which let
     * those tune with no resolve at all. Nothing publishes it any more, so every clip takes the
     * [DeviceResolver] path and the tier machinery in [StreamResolver] reads this as "nothing
     * cached". Kept as the seam rather than deleted: it is what a future pre-resolved cache would
     * fill, and removing it would mean unpicking the tier ladder that the 403 fallback relies on.
     */
    private val urls: UrlCache? = null

    /**
     * What is actually on air right now, as opposed to where the navigator points. A failed
     * tune leaves the previous picture up with the navigator already moved on, so this is set
     * only on a genuine success. Written on the executor thread, read from the UI thread by the
     * phase 2b corner indicator and banner; `@Volatile` is enough because `Tuned` is immutable.
     */
    @Volatile private var onAir: Tuned? = null

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
    private lateinit var resolver: ClipResolver

    // urls.json covers about 46% of the dial's clips, so most tunes fall through to the server.
    // Without this, every later pass over the same channel pays that round trip again. Lives
    // for the session only: these URLs are signed and expire in hours, so persisting them would
    // mean starting up holding URLs that may already be dead.
    private val resolvedCache = ResolvedCache()

    /**
     * Video ids whose cached URL was rejected by the CDN, so the next tune asks the server for a
     * fresh one instead of handing back the same dead link.
     *
     * A signed googlevideo URL can be refused with 403 well inside its stated expiry, so the
     * timestamp alone cannot decide whether it is usable - the box learned the same thing and
     * keeps a `drop()` for exactly this. Without it a re-tune resolves to the identical dead URL
     * and fails identically, which is what put a stand-by card up on every attempt to select a
     * distant channel from the picker.
     */
    /**
     * Tiers the CDN refused this session, as `<id>/<tier>`.
     *
     * Separate from [deadIds], which condemns a whole clip and so forces a `/resolve` - and that
     * runs yt-dlp, measured at 7.7 and 12.2 seconds, well past the 4s after which the viewer is
     * shown a stand-by card. Nearly every clip is published with both an hd and an sd tier in a
     * file the app already holds, so a refused hd falls to sd with no round trip at all.
     */
    private val refusedTiers = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val deadIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Single-threaded so a rapid burst of channel presses queues in order rather than racing
    // each other over the shared navigator and player.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // Bumped on every keypress. A tune captures the current value when queued and abandons
    // itself if the value has since moved on - that is how a burst of presses on the dial
    // collapses to only the last one actually reaching the player, instead of running every
    // intermediate channel to completion.
    private val generation = AtomicInteger(0)

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
     * The quality ceiling, as a remembered preference.
     *
     * This exists because the ladder above was declared, documented at length, and then never
     * assigned from the display - so every device has silently run at `hd` regardless of its
     * panel. Rather than quietly switch a 4K television to 4K and change playback for everyone at
     * once, the ceiling is now something to choose and to observe the effect of.
     *
     * It also settles an argument the code could not: on a 2.34GB 32-bit panel a smooth 720p
     * H.264 beats a 1080p60 VP9 that stalls, and the only way to know which is happening is to be
     * able to switch between them.
     */
    private val qualityLadders = listOf(
        "1080p" to listOf("hd", "sd"),
        "720p" to listOf("sd"),
        "4K" to listOf("uhd", "hd", "sd"),
    )

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
        resolver = DeviceResolver()

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
        // Override with:  adb shell am start -n com.cliftonia.fs42tv/.MainActivity --es engine mpv
        val modeCount = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            display else windowManager.defaultDisplay)?.supportedModes?.size ?: 0
        displayModeCount = modeCount
        ladder = qualityLadders.firstOrNull { it.first == prefs.getString(QUALITY_KEY, null) }
            ?.second ?: qualityLadders.first().second
        val engine = PlayerEngine.parse(intent?.getStringExtra("engine"))
            ?: PlayerEngine.parse(prefs.getString(ENGINE_KEY, null))
            ?: PlayerEngine.default(modeCount)
        prefs.edit().putString(ENGINE_KEY, engine.name.lowercase()).apply()
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
                            onDismiss = ::closePicker,
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
                this, ChannelPlayer.dataSourceFactory(), canSwitchDisplayMode = modeCount > 1)
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
        fun retuneCurrent(reason: String) {
            if (destroyed) return
            val channel = onAir?.channel ?: return
            Log.i("fs42", "re-tuning ${channel.number} ${channel.name} after $reason")
            val gen = generation.incrementAndGet()
            val at = SystemClock.elapsedRealtime()
            executor.execute { tuneTo(channel, gen, at) }
        }
        // Extracted so a rebuilt engine can be given the same callbacks. mpv shuts its
        // core down on a fatal, and a replacement with nothing listening reports no first
        // frame - the stand-by card would then never come down again.
        fun wire(player: ChannelPlayback) {
            player.onClipEnded = { retuneCurrent("clip ended") }
            player.onPlaybackError = { code ->
                if (code == MpvChannelPlayer.ENGINE_DIED) {
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
                    onAir?.stream?.id?.let { id ->
                        // Refuse the TIER, not the clip. Condemning the whole id forces a
                        // /resolve, which runs yt-dlp - measured at 7.7 and 12.2 seconds, well
                        // past the 4s after which the viewer is shown a stand-by card. Nearly
                        // every clip is published with both an hd and an sd tier in a file the
                        // app already holds, so the next rung costs no round trip at all.
                        //
                        // The failing tier is whichever rung the resolver would have taken - the
                        // first fresh one not already refused - so it can be recomputed here
                        // rather than threaded back out of the player.
                        val tier = ladder.firstOrNull {
                            StreamResolver.refusedKey(id, it) !in refusedTiers
                        }
                        if (tier != null) {
                            Log.w("fs42", "tier $tier refused for $id; falling to the next rung")
                            refusedTiers.add(StreamResolver.refusedKey(id, tier))
                            // The cache is keyed by id alone, so it still holds the url of the
                            // rung just refused. Leaving it meant the "fall to the next rung"
                            // re-tune replayed the identical refused url - three or four times
                            // over, each re-arming the stand-by grace, which is why a 403 showed
                            // as several seconds of unexplained black instead of a quick recovery.
                            resolvedCache.forget(id)
                        } else {
                            Log.w("fs42", "all tiers refused for $id; asking the server")
                            deadIds.add(id)
                            resolvedCache.forget(id)
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
                recoveryHandler.postDelayed({ standByReason.value = code }, RECOVERY_GRACE_MILLIS)
                retuneCurrent("playback error $code")
            }
            // The card comes down when a picture actually appears, not when a tune is merely
            // dispatched - a tune that fails again would otherwise clear it and leave black.
            player.onFirstFrame = {
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
        wire(player)

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
            wire(fresh)
            this.player = fresh
            player = fresh
            root.addView(fresh.view, 0, matchParent())
            Log.i("fs42", "engine rebuilt after shutdown")
        }

        checkForUpdate()

        val remembered = prefs.getInt(CHANNEL_KEY, NO_REMEMBERED_CHANNEL)

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
            val synced = runCatching { repo.sync(LINEUP_URL) }.getOrNull()
            val dial = synced?.dial ?: repo.cachedDial()

            val channels = dial?.channels
            if (channels.isNullOrEmpty()) {
                Log.e("fs42", "no dial available; cannot surf")
                return@execute
            }

            val nav = DialNavigator(channels, remembered.takeIf { it > 0 })
            navigator = nav
            tuneTo(nav.current, generation.get(), initialRequestedAt)
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
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                surfTo(nav.up())
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                surfTo(nav.down())
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
     * Move the dial now, show it now, and let the picture catch up.
     *
     * The navigator used to be advanced INSIDE the executor lambda, which meant a second press
     * could not move the dial until the first tune's network round trip had finished - about two
     * seconds during which the remote appeared dead and nothing on screen acknowledged the
     * press. Surfing quickly was impossible even though every press was being registered.
     *
     * Advancing here makes the UI thread the navigator's single writer instead of the executor,
     * which is the same invariant moved rather than broken: `up()`/`down()`/`jumpTo` are now only
     * ever called from key handling, and the executor only reads.
     *
     * The banner goes up immediately with no title, because the title is not known until the
     * clip is resolved. That is how a television behaves - the number changes the instant you
     * press, and the programme name arrives with the picture.
     */
    private fun surfTo(target: Channel) {
        // The title comes from the clock rotation right here, not from the tune that follows.
        // Waiting for the tune meant the banner showed a bare channel name whenever the tune was
        // superseded - which is every press but the last when surfing quickly - and whenever a
        // channel was chosen from the picker, where the title was already known and thrown away.
        //
        // It costs one walk of one channel's clip list, no network, and it is the same
        // arithmetic the guide uses. What is on a channel is knowable without tuning to it.
        // Stop the old channel at the SOURCE rather than covering it. A Compose overlay needs a
        // recomposition and a frame to appear, and the previous channel keeps rendering
        // underneath in the meantime - which showed up as an intermittent flash of the old
        // picture right after choosing a new one. stop() ends that render immediately, and
        // PlayerView's own shutter takes the surface black in the same frame.
        //
        // Safe to do here: surfTo only runs on a deliberate channel change, and the tune that
        // follows calls setMediaSource and prepare regardless of what state the player was left
        // in. The blank overlay stays as well, to cover the gap between the shutter and the
        // first frame of the new channel.
        player?.stop()
        // A deliberate channel change supersedes any error still waiting to be
        // announced: the card would name a channel the viewer has already left.
        recoveryHandler.removeCallbacksAndMessages(null)
        standByReason.value = ""
        tuning.value = true
        updateProgrammeVolume()
        val (line, title) = ChannelLabels.bannerLinesFor(target, nowSeconds())
        bannerChannelLine.value = line
        bannerTitleLine.value = title
        bannerGeneration.value += 1

        val gen = generation.incrementAndGet()
        val requestedAt = SystemClock.elapsedRealtime()
        executor.execute { tuneTo(target, gen, requestedAt) }
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
        generation.incrementAndGet()

        val onAirNumber = onAir?.channel?.number ?: nav.currentNumber
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
            val now = nowSeconds()
            val tuned = Tuner.tune(channel, urls, now, ladder, refusedSnapshot()) ?: return@execute
            var playable: Playable = tuned.playable
            if (playable is NeedsResolving) {
                playable = resolvedCache.get(playable.videoId, now)
                    ?: resolver.resolveDetailed(playable.videoId, now, ladder, refusedSnapshot())?.also {
                        resolvedCache.put(playable.videoId, it.playable, it.expiresAtSeconds)
                    }?.playable ?: return@execute
            }
            // Only the audio track is wanted, so the audio URL is handed over as the source and
            // the video URL is dropped entirely - no second decode, no second video fetch.
            val audioOnly = when (playable) {
                is Progressive -> playable.audioUrl?.let { Progressive(it, null) }
                is Hls -> playable
                else -> null
            } ?: return@execute
            // Always Media3, whatever plays the video. The guide music is an audio-only
            // stream under a translucent list; it has none of the frame-pacing problem that put
            // mpv on the video path, and giving it a second engine would mean a second set of
            // native libraries loaded to play 128kbps of bossa nova.
            val source = ChannelPlayer.sourceFor(
                ChannelPlayer.dataSourceFactory(), audioOnly) ?: return@execute

            runOnUiThread {
                if (destroyed || !pickerVisible.value) return@runOnUiThread
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
            if (onAir?.channel?.number == channel.number) {
                bannerGeneration.value += 1
            } else {
                surfTo(channel)
            }
        }
        closePicker()
    }

    /**
     * Runs on the background executor: resolves what a channel is showing right now and starts
     * it on the UI thread. A cache miss is resolved from the server before giving up; when even
     * that fails, the current picture is left up rather than blanking the screen.
     *
     * [requestGeneration] is checked at the start and again right before the result would reach
     * the player: if a later keypress has since bumped [generation], this tune is superseded and
     * abandons without touching the player, prefs, or [onAir]. That is what lets a burst of
     * presses skip every intermediate channel instead of running each one to completion.
     */
    private fun tuneTo(channel: Channel, requestGeneration: Int, requestedAtMillis: Long) {
        if (requestGeneration != generation.get()) {
            Log.d("fs42", "channel ${channel.number} ${channel.name}: superseded before tuning; abandoning")
            return
        }

        val now = nowSeconds()
        val tuned = Tuner.tune(channel, urls, now, ladder, refusedSnapshot())
        if (tuned == null) {
            Log.w("fs42", "channel ${channel.number} ${channel.name}: nothing on air")
            return
        }

        var playable: Playable = tuned.playable

        // A cached URL that the CDN already refused is worse than no cached URL at all: it will
        // be refused again. Treat it as a miss so the server is asked for a fresh one.
        val tunedId = tuned.stream.id
        if (tunedId != null && tunedId in deadIds && playable is Progressive) {
            playable = NeedsResolving(tunedId)
        }

        if (playable is NeedsResolving) {
            val videoId = playable.videoId
            val remembered = resolvedCache.get(videoId, now)
            if (remembered != null) {
                Log.d("fs42", "resolve hit from cache for $videoId")
                playable = remembered
            } else {
                Log.d("fs42", "resolve miss; asking the server for $videoId")
                val resolved = resolver.resolveDetailed(videoId, now, ladder, refusedSnapshot())
                if (resolved != null) {
                    resolvedCache.put(videoId, resolved.playable, resolved.expiresAtSeconds)
                    deadIds.remove(videoId)
                    playable = resolved.playable
                } else {
                    // Try the NEXT clips in the rotation rather than giving up on the channel.
                    //
                    // "Leaving the current picture up" was never what happened. Arriving here
                    // from a channel change, the previous picture has already been torn down and
                    // the black tuning card raised - and that card is only ever cleared by a
                    // first frame, which is now never coming. So the channel sat black and silent
                    // with no error and no retry until the clock rolled past the clip, which on a
                    // documentary channel is ninety minutes. It read as a dead remote.
                    //
                    // Dead clips are ordinary: the lineup is built nightly and videos are removed,
                    // made private or geo-blocked between then and airtime, and a finished
                    // livestream offers no progressive rendition at all. A television skips to
                    // what it CAN show.
                    Log.w("fs42", "channel ${channel.number} ${channel.name}: could not resolve " +
                        "$videoId; trying the next clips")
                    playable = resolveNextPlayable(channel, tuned.streamIndex, now)
                        ?: playable
                }
            }
        }

        Log.i(
            "fs42",
            "channel ${channel.number} ${channel.name}: clip ${tuned.streamIndex} at " +
                "${tuned.offsetSeconds}s -> ${playable::class.simpleName}",
        )

        // Only a Playable that genuinely reaches the player is a successful tune. A cache miss
        // the server also could not resolve, and anything Unplayable, must leave onAir and the
        // remembered channel as whatever last actually played - otherwise a dead channel becomes
        // what the app reports as on air, and what it resumes on next launch, with no picture
        // and no obvious reason why.
        val playedSuccessfully = when (playable) {
            is Progressive, is Hls -> true
            is NeedsResolving, is Unplayable -> false
        }

        if (requestGeneration != generation.get()) {
            Log.d("fs42", "channel ${channel.number} ${channel.name}: superseded before posting; abandoning")
            return
        }

        // onAir is a field, not a log line, because the navigator's position is not the same
        // thing as what is on screen: SharedPreferences is a consumer of this state, not its
        // owner.
        if (playedSuccessfully && !destroyed && requestGeneration == generation.get()) {
            onAir = tuned
            prefs.edit().putInt(CHANNEL_KEY, channel.number).apply()
        }

        // NeedsResolving here means the server round trip above also failed: play nothing and
        // leave whatever was already on screen rather than blanking it. The destroyed check
        // guards against a tune completing after onDestroy has already released the player -
        // most likely a resolver network call that outlived the activity.
        if (playable !is NeedsResolving && !destroyed) {
            runOnUiThread {
                // The generation is re-checked HERE, not only on the executor. runOnUiThread
                // queues behind whatever the main thread is already doing, so a tune that was
                // current when it posted can run after a newer keypress has already moved the
                // dial - snapping the picture and banner back to a channel the viewer surfed
                // past, and leaving it there if the newer tune then fails to resolve.
                if (requestGeneration != generation.get()) {
                    Log.d("fs42", "channel ${channel.number}: superseded before painting; abandoning")
                    return@runOnUiThread
                }
                if (!destroyed) {
                    player?.play(playable, tuned.offsetSeconds, requestedAtMillis)

                    // Only a genuine success touches the banner, and it reads the current onAir
                    // rather than this tune's outcome directly - a failed tune leaves onAir on
                    // whatever last actually played, exactly as the picture itself does. Bumping
                    // bannerGeneration regardless would replay ChannelOsd's LaunchedEffect and
                    // re-show the banner for a channel that never actually changed.
                    if (playedSuccessfully) {
                        onAir?.let { nowOnAir ->
                            val (channelLine, titleLine) = ChannelLabels.bannerLines(nowOnAir)
                            bannerChannelLine.value = channelLine
                            bannerTitleLine.value = titleLine
                        }
                        bannerGeneration.value += 1
                    }
                }
            }
        }
    }

    /** Guards against a second check while one is already running. */
    private val updateCheckRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Ask the publisher whether there is a newer build, and fetch it if so.
     *
     * On its own thread, never the tuning executor: that executor is what makes a channel change
     * feel instant, and a 66MB download queued in front of a tune would undo the whole point of
     * it. Nothing is shown until the file is on disk, so an unreachable publisher - the normal
     * state of the set in the car - is completely silent.
     */
    /**
     * Open the settings list, freezing the dial underneath the way the picker does.
     *
     * The generation bump is the same guard [openPicker] needs and for the same reason: a channel
     * change pressed a moment earlier still has a tune in flight, and letting it land would change
     * the channel under an open overlay.
     */
    private fun openSettings() {
        generation.incrementAndGet()
        updateStatus.value = ""
        settingsRows.value = buildSettingsRows()
        settingsVisible.value = true
        composeView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        composeView.requestFocus()
    }

    private fun closeSettings() {
        settingsVisible.value = false
        composeView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        composeView.clearFocus()
    }

    /**
     * The settings list, rebuilt each time it opens.
     *
     * Rebuilt rather than held, because every row is a reading of something that changes -
     * which engine is running, how old the lineup is, whether an update is waiting. A list built
     * once at startup would be quietly wrong by the time anyone looked at it, and a settings
     * screen that lies is worse than none.
     */
    private fun buildSettingsRows(): List<SettingRow> {
        val engine = PlayerEngine.parse(prefs.getString(ENGINE_KEY, null))
            ?: PlayerEngine.default(displayModeCount)
        val channels = navigator?.channels.orEmpty()
        val clips = channels.sumOf { it.streams.size }
        val crash = ExitReason.lastAbnormal(this) ?: CrashLog.summary(filesDir)
        return listOfNotNull(
            crash?.let {
                SettingRow(
                    label = "LAST CRASH",
                    value = it,
                    // OK clears it, so the next crash is unambiguously new rather than possibly
                    // the same one being read twice.
                    action = {
                        CrashLog.clear(filesDir)
                        settingsRows.value = buildSettingsRows()
                    },
                )
            },
            SettingRow(
                label = "VIDEO ENGINE",
                value = engine.name,
                // Takes effect on the next launch rather than swapping the player under a running
                // channel. Rebuilding the engine live is possible - the recovery path does it -
                // but doing it from a settings screen would mean re-resolving and re-seeking the
                // current clip, and the one moment this setting is reached for is when playback
                // is already misbehaving.
                action = {
                    val next = if (engine == PlayerEngine.MPV) PlayerEngine.MEDIA3
                               else PlayerEngine.MPV
                    prefs.edit().putString(ENGINE_KEY, next.name.lowercase()).apply()
                    settingsRows.value = buildSettingsRows()
                    Log.i("fs42", "engine set to $next; takes effect on next launch")
                },
            ),
            SettingRow(
                label = "MAX QUALITY",
                value = qualityLadders.firstOrNull { it.second == ladder }?.first ?: "1080p",
                // Applies to the NEXT tune rather than the current one, which is why the row does
                // not restart playback: flipping channel is how you see the effect, and that is
                // the thing you were already doing when you noticed the problem.
                action = {
                    val current = qualityLadders.indexOfFirst { it.second == ladder }
                    val next = qualityLadders[(current + 1).mod(qualityLadders.size)]
                    ladder = next.second
                    prefs.edit().putString(QUALITY_KEY, next.first).apply()
                    resolvedCache.clear()
                    settingsRows.value = buildSettingsRows()
                    Log.i("fs42", "quality ceiling now ${next.first} -> ${next.second}")
                },
            ),
            SettingRow("LAST STREAM", PlaybackDiagnostics.lastStream),
            SettingRow(
                label = "CHECK FOR UPDATE",
                value = updateStatus.value.ifEmpty { "CHECK NOW" },
                action = {
                    checkForUpdate(installWhenReady = true) { status ->
                        updateStatus.value = status
                        settingsRows.value = buildSettingsRows()
                    }
                    settingsRows.value = buildSettingsRows()
                },
            ),
            SettingRow("VERSION", BuildConfig.VERSION_CODE.toString()),
            SettingRow("DISPLAY MODES", displayModeCount.toString()),
            SettingRow("CHANNELS", "${channels.size} / $clips CLIPS"),
            SettingRow("LINEUP", lineupAge()),
        )
    }

    /**
     * How stale the lineup is, in words.
     *
     * The single most useful reading on this screen. When the dial misbehaves the first question
     * is whether the content is old or the extractor has broken, and those have opposite fixes -
     * a lineup fetched today with nothing playing means the extractor; a lineup from three weeks
     * ago means the nightly workflow has been failing and nobody noticed.
     */
    private fun lineupAge(): String {
        val file = java.io.File(cacheDir, "channels.json")
        if (!file.exists()) return "NOT FETCHED"
        val days = (System.currentTimeMillis() - file.lastModified()) / 86_400_000L
        return when {
            days <= 0L -> "FETCHED TODAY"
            days == 1L -> "1 DAY OLD"
            else -> "$days DAYS OLD"
        }
    }

    /**
     * Look for a newer build, and optionally go straight on to installing it.
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
    /**
     * Walk forward through a channel's clips until one resolves.
     *
     * Bounded, and deliberately not the whole list: each attempt is a full extraction of several
     * seconds, so trying a hundred would leave the viewer staring at black for minutes while the
     * app worked - far worse than admitting defeat and putting a card up. A handful covers the
     * ordinary case, which is one or two dead clips in a row, and a channel where even that many
     * consecutive clips are dead has a real problem worth showing.
     *
     * Starts at the clip AFTER the one the clock chose, so the rotation is respected as closely
     * as it can be. The offset is deliberately dropped - a substitute clip starts at its
     * beginning, because there is nothing meaningful to seek to in a programme that was never
     * scheduled to be on now.
     */
    /**
     * A snapshot of the refused tiers, taken under the monitor.
     *
     * `Collections.synchronizedSet` guards each operation, NOT iteration - and copying is an
     * iteration. The set is added to on the main thread while the executor reads it, so an
     * unsynchronised copy can throw ConcurrentModificationException in the middle of a tune.
     */
    private fun refusedSnapshot(): Set<String> = synchronized(refusedTiers) { refusedTiers.toSet() }

    private fun resolveNextPlayable(channel: Channel, failedIndex: Int, now: Long): Playable? {
        for (step in 1..SKIP_DEAD_CLIPS) {
            if (destroyed) return null
            val next = channel.streams.getOrNull((failedIndex + step) % channel.streams.size)
                ?: return null
            val id = next.id ?: continue
            if (id in deadIds) continue
            resolvedCache.get(id, now)?.let {
                Log.i("fs42", "skipped to clip ${failedIndex + step} (cached)")
                return it
            }
            val resolved = resolver.resolveDetailed(id, now, ladder, refusedSnapshot())
            if (resolved != null) {
                resolvedCache.put(id, resolved.playable, resolved.expiresAtSeconds)
                Log.i("fs42", "skipped to clip ${failedIndex + step} after $step dead clip(s)")
                return resolved.playable
            }
            // Remember it so the next tune of this channel does not pay for it again.
            deadIds.add(id)
        }
        Log.w("fs42", "channel ${channel.number}: $SKIP_DEAD_CLIPS consecutive clips unplayable")
        return null
    }

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
        player?.setPaused(true)
        // The guide music is a second player and would otherwise keep playing on its own.
        musicPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        stallHandler.removeCallbacksAndMessages(null)
        recoveryHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        // Both are null'd as well as released, so a tune that outlived the activity finds
        // nothing to touch rather than a released player.
        musicPlayer?.release()
        musicPlayer = null
        player?.release()
        player = null
    }
}
