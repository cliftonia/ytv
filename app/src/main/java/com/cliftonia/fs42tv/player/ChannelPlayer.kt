package com.cliftonia.fs42tv.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
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
class ChannelPlayer(context: Context) {

    // Cross-protocol redirects would let an https media URL be silently downgraded to plain
    // http mid-stream; on untrusted Wi-Fi that is an open door for URL injection, so this
    // stays false even though it means a stream that genuinely needs such a redirect fails
    // loudly instead.
    private val factory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false)
    val exo: ExoPlayer = ExoPlayer.Builder(context).build()

    fun play(playable: Playable, startAtSeconds: Double) {
        val source: MediaSource = when (playable) {
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
                return
            }

            is Unplayable -> {
                // No server round trip would help this one - make that legible too, rather
                // than a silent black screen behind a healthy-looking log line.
                Log.w("fs42", "cannot play: ${playable.reason}")
                return
            }
        }
        exo.setMediaSource(source, (startAtSeconds * 1000).toLong())
        exo.prepare()
        exo.playWhenReady = true
    }

    fun release() = exo.release()
}
