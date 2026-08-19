package com.cliftonia.fs42tv.player

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.Unplayable
import com.cliftonia.fs42tv.resolver.unplayableReason

/**
 * Building a Media3 [MediaSource] from a [Playable], and the data source factory it reads through.
 *
 * Neither of these needs a [ChannelPlayer], and MainActivity's guide-music player already calls
 * both with no player instance at all - so an object is the honest description of what they are.
 */
@UnstableApi
object Media3Sources {

    /**
     * The MediaSource for a playable, or null when there is nothing to play.
     *
     * Split out of [ChannelPlayer.play] because the guide-music player needs sources built exactly
     * the way the video player builds them - a source that differs from the played one buffers
     * bytes that are then thrown away.
     */
    fun sourceFor(factory: DataSource.Factory, playable: Playable): MediaSource? = when (playable) {
        is Hls -> HlsMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(playable.url))

        is Progressive -> {
            val video = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(playable.videoUrl))
            // YouTube serves video and audio separately above 360p, so they are merged
            // rather than played one after the other.
            //
            // Both flags are ON deliberately. The plain constructor leaves adjustPeriodTimeOffsets
            // false, which merges two independently-timed files without aligning their period
            // start times - and these ARE two independent files, muxed by nobody, each with its
            // own offsets. Media3's own guidance is that in almost all cases both should be true
            // so every source starts and ends together.
            //
            // Left false, video is rendered against an audio clock that sits at a different
            // origin, so frame release times are continuously nudged to chase it. Measured, that
            // showed up as a frame-hold sequence of 32222233332323... - the picture running fast
            // then slow several times a second - against a clean 232323... when it went right,
            // with the SAME file, the same frame rate, no dropped frames and identical hold
            // counts. The offsets depend on where in each file the clock-derived seek lands,
            // which is why the same clip was smooth on one tune and juddery on the next.
            val programme = if (playable.audioUrl == null) video else MergingMediaSource(
                /* adjustPeriodTimeOffsets = */ true,
                /* clipDurations = */ true,
                video,
                ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(MediaItem.fromUri(playable.audioUrl)),
            )
            val caption = playable.captionUrl ?: return@sourceFor programme

            // The subtitle joins with BOTH flags false, unlike the audio above.
            //
            // A subtitle track carries its own absolute timestamps and no media clock of its own,
            // so aligning period offsets against it would shift the cues rather than the picture,
            // and clipping durations to the shortest source would end the programme when the last
            // caption did. The audio needed both flags for exactly the opposite reason: it is a
            // second independently-timed MEDIA file, and leaving them false was the frame-pacing
            // fault described above.
            MergingMediaSource(
                /* adjustPeriodTimeOffsets = */ false,
                /* clipDurations = */ false,
                programme,
                SingleSampleMediaSource.Factory(factory).createMediaSource(
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(caption))
                        // WebVTT because Captions.asWebVtt asked YouTube for it. This said VTT
                        // while the url still served TTML, so Media3 parsed nothing and reported
                        // nothing - a caption track that is silently ignored looks exactly like a
                        // clip that never had one.
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage("en")
                        // SELECTION_FLAG_DEFAULT, so the track is shown without anyone choosing
                        // it. A viewer who turned captions on in settings has already chosen;
                        // making them find a track menu this dial has no remote button for would
                        // be a feature that appears not to work.
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                    /* durationUs = */ C.TIME_UNSET,
                ),
            )
        }

        // Nothing to hand the player. The reason is worded once, in unplayableReason, because
        // both engines land here with the same two branches - and the point of logging it at all
        // is that a miss must not be a silent black screen behind a healthy-looking log line.
        is NeedsResolving, is Unplayable -> {
            Log.w("fs42", unplayableReason(playable).orEmpty())
            null
        }
    }

    /**
     * Cross-protocol redirects would let an https media URL be silently downgraded to plain
     * http mid-stream; on untrusted Wi-Fi that is an open door for URL injection, so this
     * stays false even though it means a stream that genuinely needs such a redirect fails
     * loudly instead.
     *
     * Shared with MainActivity's guide-music player rather than built twice: a source fetched
     * through a different data source than the played one is bytes buffered and thrown away.
     */
    fun dataSourceFactory(): DataSource.Factory =
        // Wrapped so every read is a BOUNDED byte range. googlevideo throttles an
        // open-ended request to roughly the video's own bitrate - 2.57 Mbps measured - and
        // serves a bounded one at 398.85 Mbps. It is the boundedness that matters, not the
        // header: `Range: bytes=0-` is throttled exactly like no range at all, and that is
        // what DefaultHttpDataSource sends on its own.
        //
        // Measured effect: the same 4K clip went from 16.3s to 5.1s to first frame, and
        // once a connection is warm a further window opens in 37-58ms. This is what makes
        // 4K affordable at all.
        ChunkedDataSource.factory(
            DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false)
        )
}
