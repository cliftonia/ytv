package com.cliftonia.fs42tv

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.media3.ui.PlayerView
import com.cliftonia.fs42tv.player.ChannelPlayer
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.StreamResolver
import com.cliftonia.fs42tv.schedule.ClockRotation
import com.cliftonia.fs42tv.schedule.PlayPoint
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
            val synced = runCatching { repo.sync(SERVER) }.getOrNull()
            val dial = synced?.dial ?: repo.cachedDial()
            val urls = synced?.urls ?: repo.cachedUrls()
            val channel = dial?.channels?.firstOrNull { it.number == CHANNEL_NUMBER }
            if (channel == null) { Log.e("fs42", "channel $CHANNEL_NUMBER not on the dial"); return@thread }

            val now = System.currentTimeMillis() / 1000
            // `duration` on a live stream is a fixed 600s placeholder, not a real clip length -
            // live channels do not rotate on the clock, so only compute a clock position for
            // channels that actually do; otherwise play the single stream from its start.
            val point = if (channel.rotation == "clock") {
                ClockRotation.playPointFor(channel.streams.map { it.duration }, now)
            } else {
                PlayPoint(0, 0.0)
            }
            if (point == null) { Log.e("fs42", "${channel.name} has nothing on air"); return@thread }

            val stream = channel.streams[point.index]
            // Trust the discriminator the server publishes rather than inferring live-vs-
            // youtube from whether stream.id happens to be null; StreamResolver's own id
            // check remains a correct fallback but must not be the only signal.
            val playable = if (channel.kind == "live") {
                Hls(stream.url)
            } else {
                StreamResolver.resolve(stream, urls, preferUhd = false, nowSeconds = now)
            }
            Log.i("fs42", "${channel.name}: clip ${point.index} at ${point.offsetSeconds}s -> $playable")

            runOnUiThread { player.play(playable, point.offsetSeconds) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
