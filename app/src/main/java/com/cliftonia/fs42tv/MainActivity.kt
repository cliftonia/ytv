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
import androidx.media3.ui.PlayerView
import com.cliftonia.fs42tv.player.ChannelPlayer
import com.cliftonia.fs42tv.player.ChannelPreloader
import com.cliftonia.fs42tv.player.DeviceBudget
import com.cliftonia.fs42tv.schedule.ClockRotation
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.ResolvedCache
import com.cliftonia.fs42tv.resolver.ServerResolver
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
import com.cliftonia.fs42tv.ui.PickerMusic
import com.cliftonia.fs42tv.ui.ChannelPicker
import com.cliftonia.fs42tv.ui.StandBy
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val SERVER = "http://192.168.4.203:4243"
private const val PREFS_NAME = "fs42"
private const val CHANNEL_KEY = "channel"
private const val NO_REMEMBERED_CHANNEL = -1

/** Long enough not to flash on the brief stalls that clear themselves. */
private const val STALL_CARD_MILLIS = 2_500L

class MainActivity : ComponentActivity() {

    private var player: ChannelPlayer? = null
    private var preloader: ChannelPreloader? = null

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
    private var urls: UrlCache? = null

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
    private val standByReason = mutableStateOf("")

    private val pickerVisible = mutableStateOf(false)
    private val pickerRows = mutableStateOf<List<Pair<String, String>>>(emptyList())
    private val pickerStartIndex = mutableStateOf(0)

    // Local var rather than only a local val in onCreate: opening the picker needs to flip this
    // view's descendantFocusability and pull focus onto it, which onKeyDown must be able to
    // reach after onCreate has returned.
    private lateinit var composeView: ComposeView

    private lateinit var prefs: SharedPreferences
    private lateinit var resolver: ServerResolver

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
    private val deadIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Single-threaded so a rapid burst of channel presses queues in order rather than racing
    // each other over the shared navigator and player.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // Bumped on every keypress. A tune captures the current value when queued and abandons
    // itself if the value has since moved on - that is how a burst of presses on the dial
    // collapses to only the last one actually reaching the player, instead of running every
    // intermediate channel to completion.
    private val generation = AtomicInteger(0)

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

    @Volatile private var fixedNowSeconds: Long = -1L

    private fun nowSeconds(): Long =
        if (fixedNowSeconds > 0) fixedNowSeconds else System.currentTimeMillis() / 1000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        resolver = ServerResolver(fetch = { url -> URL(url).readText() }, baseUrl = SERVER)

        val view = PlayerView(this).apply { useController = false }
        composeView = ComposeView(this).apply {
            // The picker needs focus when open; the OSD does not, and must not steal it from
            // the D-pad channel-surfing handled in onKeyDown while the picker is closed.
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    ChannelOsd(
                        channelLine = bannerChannelLine.value,
                        titleLine = bannerTitleLine.value,
                        generation = bannerGeneration.value,
                    )
                    StandBy(standByReason.value.isNotEmpty(), standByReason.value)
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
        val root = FrameLayout(this).apply {
            addView(view, matchParent())
            addView(composeView, matchParent())
        }
        setContentView(root)

        // The preloader is built first because it owns the ExoPlayer: a preloaded source is only
        // reusable by a player that shares the manager's track selector, load control and
        // bandwidth meter, so the player has to come out of the manager's own builder rather
        // than be constructed alongside it.
        val factory = ChannelPlayer.dataSourceFactory()
        val memoryInfo = ActivityManager.MemoryInfo().also {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        // Both tunables can be overridden at launch, so a sweep across configurations does not
        // need a rebuild and reinstall per data point:
        //
        //   adb shell am start -n com.cliftonia.fs42tv/.MainActivity --ei fs42.budget 4 \
        //       --el fs42.preload_ms 5000
        //
        // Each measurement run costs several minutes of wall clock, and the settings being swept
        // were originally chosen on an emulator whose bandwidth made every one of them wrong.
        // A negative value means "use the real one", so a launch with no extras behaves exactly
        // as a launch from the remote does.
        // Display.getMode() reports the PHYSICAL mode - 2160 on the TCL - where DisplayMetrics
        // reports the 1080p UI layer. Reading the wrong one caps every 4K television at hd,
        // which is what the old hard-wired preferUhd = false effectively did.
        //
        // The 1080p UI layer does not cap video: the UI and the video surface are composited
        // separately, and a SurfaceView renders at panel resolution regardless.
        //
        // 4K is ON. It costs more to start than 1080p - a 4K clip is roughly four times the
        // bytes - but ChunkedDataSource took the same clip from 16.3s to 5.1s by asking for
        // bounded byte ranges, which is what made it affordable. `--ei fs42.uhd 0` drops back
        // to hd without a rebuild if a particular evening wants speed over pixels.
        val panelHeight = display?.mode?.physicalHeight ?: 0
        val wantUhd = intent.getIntExtra("fs42.uhd", 1) == 1
        ladder = TierLadder.forDisplay(panelHeight)
            .let { if (wantUhd) it else it.filterNot { tier -> tier == "uhd" } }
            .ifEmpty { listOf("hd", "sd") }
        Log.i("fs42", "panel is ${panelHeight}p; asking for tiers $ladder (uhd=$wantUhd)")

        val budgetOverride = intent.getIntExtra("fs42.budget", -1)
        val budget = if (budgetOverride >= 0) budgetOverride else DeviceBudget.forDevice(memoryInfo.totalMem)
        fixedNowSeconds = intent.getLongExtra("fs42.now", -1L)
        if (fixedNowSeconds > 0) {
            Log.i("fs42", "clock pinned to $fixedNowSeconds for measurement")
        }
        val windowOverride = intent.getLongExtra("fs42.preload_ms", -1L)
        val preloadWindow =
            if (windowOverride >= 0) windowOverride else ChannelPreloader.DEFAULT_PRELOAD_WINDOW_MILLIS
        Log.i("fs42", "device has ${memoryInfo.totalMem / (1024 * 1024)} MB; preload budget $budget window ${preloadWindow}ms")
        val preloader = ChannelPreloader(this, factory, budget, preloadWindow)
            .also { this.preloader = it }

        val player = ChannelPlayer(preloader.exo, factory).also { this.player = it }

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
        player.onClipEnded = { retuneCurrent("clip ended") }
        player.onPlaybackError = { code ->
            standByReason.value = code
            // A rejected URL is the one error worth reacting to specifically: re-tuning without
            // forgetting it would resolve to the same dead link and fail the same way.
            if (code.contains("BAD_HTTP_STATUS") || code.contains("FILE_NOT_FOUND")) {
                onAir?.stream?.id?.let {
                    Log.w("fs42", "dropping dead url for $it")
                    deadIds.add(it)
                    resolvedCache.forget(it)
                }
            }
            retuneCurrent("playback error $code")
        }
        // The card comes down when a picture actually appears, not when a tune is merely
        // dispatched - a tune that fails again would otherwise clear it and leave black.
        player.onFirstFrame = { standByReason.value = "" }

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
        view.player = player.exo

        val remembered = prefs.getInt(CHANNEL_KEY, NO_REMEMBERED_CHANNEL)

        refreshHandler.postDelayed(refreshRunnable, preloadRefreshMillis)

        val initialRequestedAt = SystemClock.elapsedRealtime()
        executor.execute {
            val repo = DialRepository(
                fetch = { url -> URL(url).readText() },
                cacheDir = cacheDir,
            )
            val synced = runCatching { repo.sync(SERVER) }.getOrNull()
            val dial = synced?.dial ?: repo.cachedDial()
            urls = synced?.urls ?: repo.cachedUrls()

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
        val nav = navigator ?: return super.onKeyDown(keyCode, event)

        // Belt and braces alongside the focus handoff in openPicker(): once the picker is up,
        // the focused row already consumes D-pad up/down/centre before the activity would ever
        // see them, but this guard is what actually guarantees the channel-change keys are
        // inert here rather than relying on focus routing alone. KEYCODE_BACK is deliberately
        // not handled here at all - the picker owns its own dismissal via BackHandler.
        if (pickerVisible.value) {
            return super.onKeyDown(keyCode, event)
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                surfTo(nav.up())
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                surfTo(nav.down())
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_GUIDE -> {
                openPicker(nav)
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
        bannerChannelLine.value = "%02d %s".format(target.number, target.name.uppercase())
        bannerTitleLine.value = ""
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

        // What is on each channel right now, from the clock rotation alone - no network, no
        // resolving. The titles are already in channels.json; only the arithmetic saying WHICH
        // one is current is needed, which is the same walk the tuner does.
        val now = nowSeconds()
        pickerRows.value = nav.channels.map { channel ->
            val title = ClockRotation
                .playPointFor(channel.streams.map { it.duration }, now)
                ?.let { channel.streams.getOrNull(it.index)?.title }
            ChannelLabels.listRow(channel, title)
        }
        pickerStartIndex.value = startIndex
        pickerVisible.value = true

        composeView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        composeView.requestFocus()
        startPickerMusic(nav)
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
        player?.exo?.volume = 0f
        executor.execute {
            if (destroyed) return@execute
            val now = nowSeconds()
            val tuned = Tuner.tune(channel, urls, now, ladder) ?: return@execute
            var playable: Playable = tuned.playable
            if (playable is NeedsResolving) {
                playable = resolvedCache.get(playable.videoId, now)
                    ?: resolver.resolveDetailed(playable.videoId, now, ladder)?.also {
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
            val source = player?.sourceFor(audioOnly) ?: return@execute

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
        player?.exo?.volume = 1f
    }

    /**
     * Deliberately does NOT bump [generation], unlike [openPicker]. Closing happens either from
     * BACK - when nothing is queued, because onKeyDown refuses every channel key while the picker
     * is up - or from [onPickChannel], which runs immediately AFTER queueing the tune the viewer
     * just asked for. A bump here would supersede that tune and selecting a channel would quietly
     * do nothing.
     */
    private fun closePicker() {
        stopPickerMusic()
        pickerVisible.value = false
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
    /**
     * Hand the preloader the neighbours of whatever just came on air.
     *
     * Runs on the executor, inside the tune that triggered it, so the navigator is not read
     * from a second thread. Each neighbour is resolved exactly as a real tune would resolve it,
     * through [resolvedCache] first, so the fan-out does not multiply server round trips.
     *
     * Every step is best-effort. A neighbour that cannot be resolved is simply not preloaded;
     * nothing here may disturb the channel actually playing.
     */
    /**
     * Re-apply the preload plan periodically, recomputing each neighbour's clock offset.
     *
     * A preloaded buffer only helps at the position it is actually started from, and every
     * channel here runs on a wall clock: buffer channel 64 at 1200 s, tune three minutes later,
     * and the clock wants 1380 s. The bytes are wrong, but DNS, TLS and the connection stay
     * warm - so unrefreshed preloading does not fail visibly, it quietly decays into connection
     * warming. That is exactly how a team measuring once at the start concludes preloading
     * "doesn't seem to help".
     *
     * A minute is comfortably shorter than the preload window is wrong by: at 60 s of drift
     * against a 2 s buffer the bytes have long since stopped matching, so refreshing sooner
     * would only spend bandwidth the previous task proved is the scarce resource here.
     */
    private val preloadRefreshMillis = 60_000L

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            onAir?.channel?.let { channel ->
                executor.execute { if (!destroyed) preloadNeighbours(channel, rebuild = true) }
            }
            refreshHandler.postDelayed(this, preloadRefreshMillis)
        }
    }

    private val refreshHandler by lazy { android.os.Handler(mainLooper) }

    /** Separate from the refresh handler so a stall timer cannot cancel a refresh. */
    private val stallHandler by lazy { android.os.Handler(mainLooper) }

    private fun preloadNeighbours(current: Channel, rebuild: Boolean = false) {
        val nav = navigator ?: return
        val preloader = this.preloader ?: return
        if (destroyed) return

        val channels = nav.channels
        val currentIndex = channels.indexOfFirst { it.number == current.number }
        if (currentIndex < 0) return

        preloader.apply(
            channels.size,
            currentIndex,
            rebuild,
            wantedStartMillis = { index ->
                channels.getOrNull(index)
                    ?.let { Tuner.tune(it, urls, nowSeconds(), ladder) }
                    ?.let { (it.offsetSeconds * 1000).toLong() }
            },
        ) { index ->
            val channel = channels.getOrNull(index) ?: return@apply null
            val now = nowSeconds()
            val tuned = Tuner.tune(channel, urls, now, ladder) ?: return@apply null

            var playable: Playable = tuned.playable
            if (playable is NeedsResolving) {
                val videoId = playable.videoId
                playable = resolvedCache.get(videoId, now)
                    ?: resolver.resolveDetailed(videoId, now, ladder)?.also {
                        resolvedCache.put(videoId, it.playable, it.expiresAtSeconds)
                    }?.playable
                    ?: return@apply null
            }

            val source = player?.sourceFor(playable) ?: return@apply null
            source to (tuned.offsetSeconds * 1000).toLong()
        }
    }

    private fun tuneTo(channel: Channel, requestGeneration: Int, requestedAtMillis: Long) {
        if (requestGeneration != generation.get()) {
            Log.d("fs42", "channel ${channel.number} ${channel.name}: superseded before tuning; abandoning")
            return
        }

        val now = nowSeconds()
        val tuned = Tuner.tune(channel, urls, now, ladder)
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
                val resolved = resolver.resolveDetailed(videoId, now, ladder)
                if (resolved != null) {
                    resolvedCache.put(videoId, resolved.playable, resolved.expiresAtSeconds)
                    deadIds.remove(videoId)
                    playable = resolved.playable
                } else {
                    Log.w(
                        "fs42",
                        "channel ${channel.number} ${channel.name}: server could not resolve " +
                            "$videoId; leaving current picture up",
                    )
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
        if (playedSuccessfully && !destroyed) {
            onAir = tuned
            prefs.edit().putInt(CHANNEL_KEY, channel.number).apply()
            preloadNeighbours(channel)
        }

        // NeedsResolving here means the server round trip above also failed: play nothing and
        // leave whatever was already on screen rather than blanking it. The destroyed check
        // guards against a tune completing after onDestroy has already released the player -
        // most likely a resolver network call that outlived the activity.
        if (playable !is NeedsResolving && !destroyed) {
            runOnUiThread {
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

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        refreshHandler.removeCallbacks(refreshRunnable)
        stallHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        // Preloader first: it holds sources feeding the player the release below tears down,
        // and a preload landing against a released player is a crash rather than a wasted
        // fetch. Both are null'd so a tune that outlived the activity finds nothing to touch.
        musicPlayer?.release()
        musicPlayer = null
        preloader?.release()
        preloader = null
        player?.release()
        player = null
    }
}
