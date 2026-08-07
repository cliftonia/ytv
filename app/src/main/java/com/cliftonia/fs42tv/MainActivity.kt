package com.cliftonia.fs42tv

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.media3.ui.PlayerView
import com.cliftonia.fs42tv.player.ChannelPlayer
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.ServerResolver
import com.cliftonia.fs42tv.resolver.Unplayable
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.DialRepository
import com.cliftonia.fs42tv.sync.UrlCache
import com.cliftonia.fs42tv.tune.DialNavigator
import com.cliftonia.fs42tv.tune.Tuned
import com.cliftonia.fs42tv.tune.Tuner
import com.cliftonia.fs42tv.ui.ChannelIndicator
import com.cliftonia.fs42tv.ui.ChannelLabels
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

    // Compose state backing the corner indicator. Read only from the UI thread by the
    // ChannelIndicator composable; written only from the runOnUiThread block below, alongside
    // onAir, so it never appears out of step with the picture actually on screen.
    private val indicatorText = mutableStateOf("")

    private lateinit var prefs: SharedPreferences
    private lateinit var resolver: ServerResolver

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
        val composeView = ComposeView(this).apply {
            // The picker in task 4 needs focus; the indicator does not, and must not steal it
            // from the D-pad channel-surfing handled in onKeyDown.
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setContent { ChannelIndicator(indicatorText.value) }
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

        val player = ChannelPlayer(this).also { this.player = it }
        view.player = player.exo

        val remembered = prefs.getInt(CHANNEL_KEY, NO_REMEMBERED_CHANNEL)

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
            tuneTo(nav.current, generation.get())
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val nav = navigator ?: return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                val gen = generation.incrementAndGet()
                executor.execute { tuneTo(nav.up(), gen) }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                val gen = generation.incrementAndGet()
                executor.execute { tuneTo(nav.down(), gen) }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
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
    private fun tuneTo(channel: Channel, requestGeneration: Int) {
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
            val resolved = resolver.resolve(playable.videoId, now)
            if (resolved != null) {
                playable = resolved
            } else {
                Log.w(
                    "fs42",
                    "channel ${channel.number} ${channel.name}: server could not resolve " +
                        "${playable.videoId}; leaving current picture up",
                )
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
        }

        // NeedsResolving here means the server round trip above also failed: play nothing and
        // leave whatever was already on screen rather than blanking it. The destroyed check
        // guards against a tune completing after onDestroy has already released the player -
        // most likely a resolver network call that outlived the activity.
        if (playable !is NeedsResolving && !destroyed) {
            runOnUiThread {
                if (!destroyed) {
                    player?.play(playable, tuned.offsetSeconds)
                    // Reads the current onAir, not this tune's outcome directly: a failed tune
                    // leaves onAir - and therefore the indicator - on whatever last actually
                    // played, exactly as the picture itself does.
                    indicatorText.value = onAir?.let { ChannelLabels.indicator(it.channel.number) } ?: ""
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        executor.shutdownNow()
        player?.release()
        player = null
    }
}
