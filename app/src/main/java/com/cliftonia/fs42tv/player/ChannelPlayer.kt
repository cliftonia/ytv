package com.cliftonia.fs42tv.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Progressive

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

    private val factory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
    val exo: ExoPlayer = ExoPlayer.Builder(context).build()

    fun play(playable: Playable, startAtSeconds: Double) {
        val source = when (playable) {
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

            else -> return
        }
        exo.setMediaSource(source as MediaSource, (startAtSeconds * 1000).toLong())
        exo.prepare()
        exo.playWhenReady = true
    }

    fun release() = exo.release()
}
