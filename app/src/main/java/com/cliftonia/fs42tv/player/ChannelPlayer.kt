package com.cliftonia.fs42tv.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.Unplayable

/**
 * Turns a Playable into something ExoPlayer can start, at a given offset.
 *
 * The start position is passed to setMediaSource rather than applied as a seek afterwards.
 * That is deliberate: on the box this was ported from, seeking straight after starting
 * playback silently did nothing, because playback had not begun and the seek was dropped -
 * every channel then started its clip from 00:00. Media3 makes the start position part of
 * the load, so that class of bug cannot happen here.
 */
@UnstableApi
class ChannelPlayer(val exo: ExoPlayer, private val factory: DataSource.Factory) {

    companion object {
        /**
         * Cross-protocol redirects would let an https media URL be silently downgraded to plain
         * http mid-stream; on untrusted Wi-Fi that is an open door for URL injection, so this
         * stays false even though it means a stream that genuinely needs such a redirect fails
         * loudly instead.
         *
         * Shared with the preloader rather than built twice: a preloaded source fetched through
         * a different data source than the played one is bytes buffered and thrown away.
         */
        fun dataSourceFactory(): DataSource.Factory =
            DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false)
    }

    /** Set when a tune starts, cleared when its first frame lands. Main thread only. */
    private var requestedAtMillis = 0L

    init {
        exo.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val requested = requestedAtMillis
                if (requested > 0L) {
                    // Measured from the KEYPRESS, not from setMediaSource. The resolve step
                    // ahead of this is only ~70ms on a cache hit, but it is part of what the
                    // viewer waits through, and a ruler that starts after the expensive part
                    // would flatter every change made in this phase.
                    Log.i("fs42", "first frame ${SystemClock.elapsedRealtime() - requested} ms")
                    requestedAtMillis = 0L
                }
            }
        })
    }

    /**
     * The MediaSource for a playable, or null when there is nothing to play.
     *
     * Split out of [play] because the preload manager needs sources built exactly the way the
     * player builds them - a preloaded source that differs from the played one buffers bytes
     * that are then thrown away.
     */
    fun sourceFor(playable: Playable): MediaSource? = when (playable) {
        is Hls -> HlsMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(playable.url))

        is Progressive -> {
            val video = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(playable.videoUrl))
            // YouTube serves video and audio separately above 360p, so they are merged
            // rather than played one after the other.
            if (playable.audioUrl == null) video else MergingMediaSource(
                video,
                ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(MediaItem.fromUri(playable.audioUrl)),
            )
        }

        is NeedsResolving -> {
            // Nothing usable is cached for this id. Asking the server to resolve it is
            // later-phase work; for now, make the miss legible instead of a silent
            // black screen behind a healthy-looking log line.
            Log.w("fs42", "no cached stream for video id ${playable.videoId}; needs server resolve")
            null
        }

        is Unplayable -> {
            // No server round trip would help this one - make that legible too, rather
            // than a silent black screen behind a healthy-looking log line.
            Log.w("fs42", "cannot play: ${playable.reason}")
            null
        }
    }

    fun play(playable: Playable, startAtSeconds: Double, requestedAtMillis: Long) {
        val source = sourceFor(playable) ?: return
        this.requestedAtMillis = requestedAtMillis
        exo.setMediaSource(source, (startAtSeconds * 1000).toLong())
        exo.prepare()
        exo.playWhenReady = true
    }

    fun release() = exo.release()
}
