package com.cliftonia.fs42tv

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.media3.ui.PlayerView
import com.cliftonia.fs42tv.player.ChannelPlayer
import com.cliftonia.fs42tv.resolver.StreamResolver
import com.cliftonia.fs42tv.schedule.ClockRotation
import com.cliftonia.fs42tv.sync.DialRepository
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
            val dial = runCatching { repo.sync(SERVER) }.getOrNull() ?: repo.cachedDial()
            val urls = repo.cachedUrls()
            val channel = dial?.channels?.firstOrNull { it.number == CHANNEL_NUMBER }
            if (channel == null) { Log.e("fs42", "channel $CHANNEL_NUMBER not on the dial"); return@thread }

            val now = System.currentTimeMillis() / 1000
            val point = ClockRotation.playPointFor(channel.streams.map { it.duration }, now)
            if (point == null) { Log.e("fs42", "${channel.name} has nothing on air"); return@thread }

            val stream = channel.streams[point.index]
            val playable = StreamResolver.resolve(stream, urls, preferUhd = false, nowSeconds = now)
            Log.i("fs42", "${channel.name}: clip ${point.index} at ${point.offsetSeconds}s -> $playable")

            runOnUiThread { player.play(playable, point.offsetSeconds) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
