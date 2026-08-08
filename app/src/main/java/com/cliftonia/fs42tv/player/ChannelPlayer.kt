package com.cliftonia.fs42tv.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
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
class ChannelPlayer(context: android.content.Context, private val factory: DataSource.Factory) {

    /**
     * Buffer targets modelled on the box's mpv configuration, which plays this exact content over
     * this exact network without stalling.
     *
     * mpv reads lazily - `cache-secs=3`, `demuxer-readahead-secs=0` - pulling at roughly playback
     * rate. Media3 defaults to racing 50 seconds ahead as fast as the connection allows, which
     * against a throttled connection is a consumer permanently demanding more than it can be
     * given. `bufferForPlayback` stays at the Media3 default: shaving it was tried and produced
     * worse results.
     *
     * These values live HERE, with the player they configure. They previously lived in
     * ChannelPreloader, which also built the player - so a class named "Preloader" owned playback
     * itself, and switching preloading off left it silently load-bearing.
     */
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMsForStreaming(
            /* minBufferMs = */ 20_000,
            /* maxBufferMs = */ 20_000,
            /* bufferForPlaybackMs = */ DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
            /* bufferForPlaybackAfterRebufferMs = */
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .build()

    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        // OFF, and this is load-bearing rather than an optimisation.
        //
        // Left on, ExoPlayer calls Surface.setFrameRate() with the content rate, and the platform
        // honours it as a frame-rate OVERRIDE. Read straight off the video layer with
        // `dumpsys SurfaceFlinger --latency` while the picture was visibly juddering:
        //
        //   desired present:  50.0  33.3  50.0  33.3   <- ExoPlayer asking for correct 3:2
        //   actual  present:  41.7  41.7  41.7  41.7   <- compositor forcing a flat 24Hz
        //
        // 41.7ms is 2.5 vsyncs. This panel reports exactly one mode - 3840x2160 at 60.000004Hz
        // with no alternates - so 60/24 = 2.5 cannot divide evenly and there is no mode to
        // switch to. Every frame then lands between vsyncs and is held for two refreshes or
        // three in a drifting pattern, which is the judder. With this off, ExoPlayer's own 3:2
        // request reaches the panel on its vsync grid instead.
        //
        // Beware measuring this: uniform 41.7ms spacing is PERFECTLY constant, so any metric
        // scoring deviation-from-constant-rate reports it as flawless. One here did exactly
        // that and passed 12 of 12 runs the viewer could see juddering.
        .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        .build()


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

    /** Set when a tune starts, cleared when its first frame lands. Main thread only. */
    private var requestedAtMillis = 0L

    /**
     * Whether this tune has ever put a picture up.
     *
     * Every tune BEGINS in STATE_BUFFERING - that is just loading, and reporting it as a fault
     * puts a stand-by card over the normal two seconds between pressing a button and seeing a
     * channel. Only a stall AFTER a picture has appeared is a stall; before that it is progress.
     * The box draws the same line, treating the condition as a fault only once it is stuck.
     */
    private var hasPicture = false

    /**
     * Called when the current clip runs out, so the channel can move to whatever the clock says
     * is on next.
     *
     * Without this a channel plays exactly one clip and then shows nothing at all - ExoPlayer
     * reaches STATE_ENDED and simply stops, with no error and nothing in the log to explain the
     * black screen. It looks like a broken channel rather than a finished clip, and the only way
     * out is to change channel and come back.
     */
    var onClipEnded: (() -> Unit)? = null

    /**
     * Called when playback fails outright, so the channel can be re-tuned rather than left dark.
     *
     * A signed googlevideo URL can be rejected with 403 well inside its stated expiry, and any
     * network blip during the load surfaces here too. ExoPlayer's response is to stop and stay
     * stopped: the screen goes black and nothing recovers it, because the app never asks for
     * anything again. Surfing quickly makes this far more likely, since every abandoned tune is
     * another chance to have picked up a URL that had gone bad.
     */
    var onPlaybackError: ((String) -> Unit)? = null

    /** Fired when a picture actually appears, so a stand-by card can be taken down. */
    var onFirstFrame: (() -> Unit)? = null

    /**
     * Fired with true when playback stalls to buffer and false when it recovers.
     *
     * Every clip on this dial is entered at a wall-clock offset - often tens of minutes into a
     * large progressive MP4 - so playback is streaming from deep inside a file rather than from
     * its start. A stall there is common and completely silent.
     */
    var onBuffering: ((Boolean) -> Unit)? = null

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_ENDED -> {
                        Log.i("fs42", "clip ended; asking for the next one")
                        onClipEnded?.invoke()
                    }
                    // Buffering is the third way this player goes quiet, and the only one that
                    // reports nothing at all: no error, no end of media, just a stopped picture.
                    // The box treats the same condition as a fault after two seconds and puts a
                    // stand-by card up (field_player.py:575); without logging it here, a stall is
                    // indistinguishable from a crash from the outside.
                    Player.STATE_BUFFERING -> {
                        // Logged either way - a slow initial load is worth seeing in the log even
                        // when it is not worth putting on the screen.
                        Log.i("fs42", "buffering at ${exo.currentPosition / 1000}s" +
                            if (hasPicture) " (mid-playback stall)" else " (initial load)")
                        if (hasPicture) onBuffering?.invoke(true)
                    }
                    Player.STATE_READY -> onBuffering?.invoke(false)
                    else -> Unit
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Logged at warning with the error name, because "black screen" on its own is
                // indistinguishable from a dozen other faults - this is the line that says which.
                Log.w("fs42", "playback failed: ${error.errorCodeName}", error)
                onPlaybackError?.invoke(error.errorCodeName)
            }

            /**
             * Log the source frame rate, because judder on this panel depends entirely on it.
             *
             * The television reports exactly one display mode, 60Hz, so there is nothing to
             * switch the panel to. 30fps and 60fps material maps cleanly onto that; 25fps PAL
             * content - which this dial carries a lot of, being full of British and Australian
             * programmes - needs an uneven 2:2:2:2:3 cadence, and 23.976fps film needs 3:2.
             * Both read as periodic stutter, and no software setting can fix either.
             *
             * Without this line, "the picture is janky sometimes" is unattributable. With it,
             * the answer is in the log next to the channel that caused it.
             */
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
                hasPicture = true
                // Frame rate read HERE, not at onVideoSizeChanged: the format is not populated
                // that early and reported -1.0fps every time, which made the one diagnostic that
                // matters useless.
                //
                // This panel reports a single display mode, 60Hz, so there is nothing to switch
                // it to. 30 and 60fps map onto that cleanly. 25fps PAL - which a dial full of
                // British and Australian programmes carries a lot of - needs an uneven
                // 2:2:2:2:3 cadence, and 23.976fps film needs 3:2. Both look like stutter and
                // NEITHER drops a frame, which is exactly what the dropped-frame counter showed:
                // zero, while the picture visibly juddered.
                val fps = exo.videoFormat?.frameRate ?: -1f
                val cadence = when {
                    fps <= 0f -> "unknown"
                    kotlin.math.abs(fps - 60f) < 1f -> "60fps - clean on a 60Hz panel"
                    kotlin.math.abs(fps - 30f) < 1f -> "30fps - clean 2:2 on a 60Hz panel"
                    kotlin.math.abs(fps - 50f) < 1f -> "50fps PAL - UNEVEN on a 60Hz panel"
                    kotlin.math.abs(fps - 25f) < 1f -> "25fps PAL - UNEVEN on a 60Hz panel"
                    kotlin.math.abs(fps - 24f) < 1.5f -> "24fps film - 3:2 pulldown on a 60Hz panel"
                    else -> "non-standard"
                }
                Log.i("fs42", "playing ${exo.videoFormat?.width}x${exo.videoFormat?.height} " +
                    "@ ${fps}fps - $cadence")
                onFirstFrame?.invoke()
            }
        })

        // Dropped frames live on AnalyticsListener, not Player.Listener.
        //
        // "The picture feels janky" has been guessed at repeatedly in this project - blamed on
        // preloading, on a second codec, on 4K decode, on the panel's single 60Hz mode - and
        // never once measured. ExoPlayer counts them; nothing here had asked.
        //
        // The RATE is what matters, not the total: a handful over a minute is normal, dozens in
        // a second is not.
        exo.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                val perSecond = if (elapsedMs > 0) droppedFrames * 1000.0 / elapsedMs else 0.0
                Log.w("fs42", "dropped $droppedFrames frames in ${elapsedMs}ms " +
                    "(${"%.1f".format(perSecond)}/s) at ${exo.currentPosition / 1000}s")
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
            if (playable.audioUrl == null) video else MergingMediaSource(
                /* adjustPeriodTimeOffsets = */ true,
                /* clipDurations = */ true,
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
        hasPicture = false
        this.requestedAtMillis = requestedAtMillis
        // Reset the renderer's frame timing before loading anything else.
        //
        // VideoFrameReleaseHelper keeps a cadence calibrated for the frame rate it was last
        // fed, and setMediaSource alone does not clear it - only stop() does (androidx/media
        // issue 2941, which describes this exact fault on BUILT-IN Android TVs and not on
        // external sticks). This dial mixes 24, 25, 30, 50 and 60fps, so nearly every channel
        // change is a frame-rate change, and a stale cadence is a picture that runs fast then
        // slow for the whole clip until you tune away and back.
        //
        // The documented cost is a black screen through the transition, which costs this app
        // nothing: it already blanks and mutes across every channel change by design.
        //
        // surfTo() also stops, but only on a deliberate channel change. Clip roll-over and
        // error recovery reach here without it, and those cross frame rates just as often.
        // The split between deciding what to play and being able to show it. "first frame" alone
        // is one number covering three very different costs - working out the clip from the
        // clock, resolving its URL (cache hit or a server round trip), and then connecting and
        // filling the buffer - and the same number came out as 844ms and 8939ms on consecutive
        // tunes. Logging the hand-off makes the first two separable from the third; the third
        // then falls out of the fs42chunk open timings, which already carry timestamps.
        Log.i("fs42", "handing to player after ${SystemClock.elapsedRealtime() - requestedAtMillis}" +
            "ms: ${playable.javaClass.simpleName} at ${startAtSeconds.toInt()}s")
        exo.stop()
        exo.setMediaSource(source, (startAtSeconds * 1000).toLong())
        exo.prepare()
        exo.playWhenReady = true
    }

    fun release() = exo.release()
}
