package com.cliftonia.fs42tv

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.media3.ui.PlayerView
import com.cliftonia.fs42tv.player.ChannelPlayer
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.ServerResolver
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.DialRepository
import com.cliftonia.fs42tv.sync.UrlCache
import com.cliftonia.fs42tv.tune.DialNavigator
import com.cliftonia.fs42tv.tune.Tuner
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val SERVER = "http://192.168.4.203:4243"
private const val PREFS_NAME = "fs42"
private const val CHANNEL_KEY = "channel"
private const val NO_REMEMBERED_CHANNEL = -1

class MainActivity : Activity() {

    private var player: ChannelPlayer? = null

    @Volatile private var navigator: DialNavigator? = null
    private var urls: UrlCache? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var resolver: ServerResolver

    // Single-threaded so a rapid burst of channel presses queues in order rather than racing
    // each other over the shared navigator and player.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        resolver = ServerResolver(fetch = { url -> URL(url).readText() }, baseUrl = SERVER)

        val view = PlayerView(this).apply { useController = false }
        setContentView(view)

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
            tuneTo(nav.current)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val nav = navigator ?: return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                executor.execute { tuneTo(nav.up()) }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                executor.execute { tuneTo(nav.down()) }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * Runs on the background executor: resolves what a channel is showing right now and starts
     * it on the UI thread. A cache miss is resolved from the server before giving up; when even
     * that fails, the current picture is left up rather than blanking the screen.
     */
    private fun tuneTo(channel: Channel) {
        val now = System.currentTimeMillis() / 1000
        val tuned = Tuner.tune(channel, urls, now)
        if (tuned == null) {
            Log.w("fs42", "channel ${channel.number} ${channel.name}: nothing on air")
            return
        }

        var playable: Playable = tuned.playable
        if (playable is NeedsResolving) {
            val resolved = resolver.resolve(playable.videoId)
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

        prefs.edit().putInt(CHANNEL_KEY, channel.number).apply()

        // NeedsResolving here means the server round trip above also failed: play nothing and
        // leave whatever was already on screen rather than blanking it.
        if (playable !is NeedsResolving) {
            runOnUiThread { player?.play(playable, tuned.offsetSeconds) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
        player?.release()
    }
}
