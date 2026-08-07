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
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.ResolvedCache
import com.cliftonia.fs42tv.resolver.ServerResolver
import com.cliftonia.fs42tv.resolver.Unplayable
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.DialRepository
import com.cliftonia.fs42tv.sync.UrlCache
import com.cliftonia.fs42tv.tune.DialNavigator
import com.cliftonia.fs42tv.tune.Tuned
import com.cliftonia.fs42tv.tune.Tuner
import com.cliftonia.fs42tv.ui.ChannelLabels
import com.cliftonia.fs42tv.ui.ChannelOsd
import com.cliftonia.fs42tv.ui.ChannelPicker
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val SERVER = "http://192.168.4.203:4243"
private const val PREFS_NAME = "fs42"
private const val CHANNEL_KEY = "channel"
private const val NO_REMEMBERED_CHANNEL = -1

class MainActivity : ComponentActivity() {

    private var player: ChannelPlayer? = null
    private var preloader: ChannelPreloader? = null

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
    private val pickerVisible = mutableStateOf(false)
    private val pickerRows = mutableStateOf<List<String>>(emptyList())
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

    // Single-threaded so a rapid burst of channel presses queues in order rather than racing
    // each other over the shared navigator and player.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // Bumped on every keypress. A tune captures the current value when queued and abandons
    // itself if the value has since moved on - that is how a burst of presses on the dial
    // collapses to only the last one actually reaching the player, instead of running every
    // intermediate channel to completion.
    private val generation = AtomicInteger(0)

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
        val budget = DeviceBudget.forDevice(memoryInfo.totalMem)
        Log.i("fs42", "device has ${memoryInfo.totalMem / (1024 * 1024)} MB; preload budget $budget")
        val preloader = ChannelPreloader(this, factory, budget).also { this.preloader = it }

        val player = ChannelPlayer(preloader.exo, factory).also { this.player = it }
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
                val gen = generation.incrementAndGet()
                val requestedAt = SystemClock.elapsedRealtime()
                executor.execute { tuneTo(nav.up(), gen, requestedAt) }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                val gen = generation.incrementAndGet()
                val requestedAt = SystemClock.elapsedRealtime()
                executor.execute { tuneTo(nav.down(), gen, requestedAt) }
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

        pickerRows.value = nav.channels.map { ChannelLabels.listRow(it) }
        pickerStartIndex.value = startIndex
        pickerVisible.value = true

        composeView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        composeView.requestFocus()
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
            val gen = generation.incrementAndGet()
            val requestedAt = SystemClock.elapsedRealtime()
            // jumpTo runs on the executor, not here. DialNavigator documents its index as
            // "mutated on the executor thread today", and up()/down() have always honoured that;
            // calling it inline would make this the one UI-thread writer and quietly retire an
            // invariant the next change in this area would otherwise inherit for free.
            executor.execute {
                nav.jumpTo(channel.number)
                tuneTo(channel, gen, requestedAt)
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

    private fun preloadNeighbours(current: Channel, rebuild: Boolean = false) {
        val nav = navigator ?: return
        val preloader = this.preloader ?: return
        if (destroyed) return

        val channels = nav.channels
        val currentIndex = channels.indexOfFirst { it.number == current.number }
        if (currentIndex < 0) return

        preloader.apply(channels.size, currentIndex, rebuild) { index ->
            val channel = channels.getOrNull(index) ?: return@apply null
            val now = System.currentTimeMillis() / 1000
            val tuned = Tuner.tune(channel, urls, now) ?: return@apply null

            var playable: Playable = tuned.playable
            if (playable is NeedsResolving) {
                val videoId = playable.videoId
                playable = resolvedCache.get(videoId, now)
                    ?: resolver.resolveDetailed(videoId, now)?.also {
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

        val now = System.currentTimeMillis() / 1000
        val tuned = Tuner.tune(channel, urls, now)
        if (tuned == null) {
            Log.w("fs42", "channel ${channel.number} ${channel.name}: nothing on air")
            return
        }

        var playable: Playable = tuned.playable
        if (playable is NeedsResolving) {
            val videoId = playable.videoId
            val remembered = resolvedCache.get(videoId, now)
            if (remembered != null) {
                Log.d("fs42", "resolve hit from cache for $videoId")
                playable = remembered
            } else {
                Log.d("fs42", "resolve miss; asking the server for $videoId")
                val resolved = resolver.resolveDetailed(videoId, now)
                if (resolved != null) {
                    resolvedCache.put(videoId, resolved.playable, resolved.expiresAtSeconds)
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
        executor.shutdownNow()
        // Preloader first: it holds sources feeding the player the release below tears down,
        // and a preload landing against a released player is a crash rather than a wasted
        // fetch. Both are null'd so a tune that outlived the activity finds nothing to touch.
        preloader?.release()
        preloader = null
        player?.release()
        player = null
    }
}
