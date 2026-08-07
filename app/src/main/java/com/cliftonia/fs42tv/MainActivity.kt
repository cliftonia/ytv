package com.cliftonia.fs42tv

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.media3.ui.PlayerView
import com.cliftonia.fs42tv.player.ChannelPlayer
import com.cliftonia.fs42tv.sync.DialRepository
import com.cliftonia.fs42tv.tune.Tuner
import java.net.URL
import kotlin.concurrent.thread

private const val SERVER = "http://192.168.4.203:4243"
private const val CHANNEL_NUMBER = 2

class MainActivity : Activity() {

    private var player: ChannelPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = PlayerView(this).apply { useController = false }
        setContentView(view)

        val player = ChannelPlayer(this).also { this.player = it }
        view.player = player.exo

        thread {
            val repo = DialRepository(
                fetch = { url -> URL(url).readText() },
                cacheDir = cacheDir,
            )
            val synced = runCatching { repo.sync(SERVER) }.getOrNull()
            val dial = synced?.dial ?: repo.cachedDial()
            val urls = synced?.urls ?: repo.cachedUrls()
            val channel = dial?.channels?.firstOrNull { it.number == CHANNEL_NUMBER }
            if (channel == null) { Log.e("fs42", "channel $CHANNEL_NUMBER not on the dial"); return@thread }

            val now = System.currentTimeMillis() / 1000
            val tuned = Tuner.tune(channel, urls, now)
            if (tuned == null) { Log.e("fs42", "${channel.name} has nothing on air"); return@thread }

            Log.i("fs42", "${tuned.channel.name}: clip ${tuned.streamIndex} at " +
                "${tuned.offsetSeconds}s -> ${tuned.playable}")

            runOnUiThread { player.play(tuned.playable, tuned.offsetSeconds) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
