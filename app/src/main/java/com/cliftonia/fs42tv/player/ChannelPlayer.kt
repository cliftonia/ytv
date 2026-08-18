package com.cliftonia.fs42tv.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.cliftonia.fs42tv.resolver.Playable

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
class ChannelPlayer(
    context: android.content.Context,
    private val factory: DataSource.Factory,
    /**
     * Whether this display can switch to a mode matching the content's frame rate.
     *
     * Measured on both real devices and the difference is total: the TCL panel offers ONE mode -
     * 3840x2160 at 60.000004Hz, no alternatives - while the Chromecast offers twenty, including
     * native 23.976, 24, 25, 29.97, 30 and 50. Where the output can follow the content, 23.976fps
     * film plays at 23.976Hz: one frame per refresh and no pulldown at all.
     */
    private val canSwitchDisplayMode: Boolean = false,
) : ChannelPlayback {

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
     * These values live HERE, with the player they configure. They previously lived in a
     * ChannelPreloader that also built the player - so a class named "Preloader" owned playback
     * itself, and switching preloading off left it silently load-bearing. That class is gone;
     * the reason for keeping the numbers beside the player they configure is not.
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
        // Ask the platform to follow the content's frame rate ONLY where it can act on the
        // answer - see canSwitchDisplayMode.
        //
        // On a single-mode panel it cannot, and the call actively harms: the platform honours it
        // as a frame-rate OVERRIDE. Read straight off the video layer with
        // `dumpsys SurfaceFlinger --latency` while the picture was visibly juddering:
        //
        //   desired present:  50.0  33.3  50.0  33.3   <- ExoPlayer asking for correct 3:2
        //   actual  present:  41.7  41.7  41.7  41.7   <- compositor forcing a flat 24Hz
        //
        // 41.7ms is 2.5 vsyncs, which a 60Hz panel cannot present, so every frame lands between
        // vsyncs. Beware measuring this: uniform 41.7ms is PERFECTLY constant, so any metric
        // scoring deviation-from-constant-rate calls it flawless - one here passed 12 of 12 runs
        // the viewer could plainly see juddering.
        .setVideoChangeFrameRateStrategy(
            if (canSwitchDisplayMode) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        )
        .build()


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
    override var onClipEnded: (() -> Unit)? = null

    /**
     * Called when playback fails outright, so the channel can be re-tuned rather than left dark.
     *
     * A signed googlevideo URL can be rejected with 403 well inside its stated expiry, and any
     * network blip during the load surfaces here too. ExoPlayer's response is to stop and stay
     * stopped: the screen goes black and nothing recovers it, because the app never asks for
     * anything again. Surfing quickly makes this far more likely, since every abandoned tune is
     * another chance to have picked up a URL that had gone bad.
     */
    override var onPlaybackError: ((String) -> Unit)? = null

    /** Fired when a picture actually appears, so a stand-by card can be taken down. */
    override var onFirstFrame: (() -> Unit)? = null

    /**
     * Fired with true when playback stalls to buffer and false when it recovers.
     *
     * Every clip on this dial is entered at a wall-clock offset - often tens of minutes into a
     * large progressive MP4 - so playback is streaming from deep inside a file rather than from
     * its start. A stall there is common and completely silent.
     */
    override var onBuffering: ((Boolean) -> Unit)? = null

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
             * Without this line, "the picture is janky sometimes" is unattributable. With it,
             * the answer is in the log next to the channel that caused it. [FrameCadence] holds
             * what each rate does to a panel with one 60Hz mode and no software fix for either.
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
                // matters useless. What the number means for this panel is in [FrameCadence].
                val fps = exo.videoFormat?.frameRate ?: -1f
                Log.i("fs42", "playing ${exo.videoFormat?.width}x${exo.videoFormat?.height} " +
                    "@ ${fps}fps - ${FrameCadence.describe(fps)}")
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
     * The view showing the picture.
     *
     * Built here rather than in the activity so the engine owns its own surface: the mpv
     * implementation needs a completely different one, and the switch between them has to be a
     * single line rather than a second set of layout code.
     */
    override val view: android.view.View = androidx.media3.ui.PlayerView(context).apply {
        useController = false
        // The shutter is PlayerView's own black cover over the video surface, and it exists for
        // exactly this: hiding the last frame of whatever was playing when the player is reset.
        // Explicit rather than relying on the default, because a channel change depends on it
        // and defaults are the first thing a library changes.
        setKeepContentOnPlayerReset(false)
        setShutterBackgroundColor(android.graphics.Color.BLACK)
        player = exo
    }

    override fun stop() = exo.stop()

    override fun setPaused(paused: Boolean) { exo.playWhenReady = !paused }

    override fun setVolume(volume: Float) { exo.volume = volume }

    override fun play(playable: Playable, startAtSeconds: Double, requestedAtMillis: Long) {
        val source = Media3Sources.sourceFor(factory, playable) ?: return
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
        // clock, resolving its URL (cache hit or a fresh extraction), and then connecting and
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

    override fun release() = exo.release()
}
