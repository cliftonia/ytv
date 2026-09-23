package com.cliftonia.fs42tv.ui

import android.os.Handler
import android.util.Log
import com.cliftonia.fs42tv.player.ChannelPlayback
import com.cliftonia.fs42tv.player.SkipWatch
import com.cliftonia.fs42tv.schedule.Timetable
import com.cliftonia.fs42tv.tune.Tuned

/**
 * The playback half of SKIP SPONSORS: while a clock clip plays, read the position four times a
 * second and jump each skipped range as the playhead enters it.
 *
 * Engine-agnostic by construction - it only reads [ChannelPlayback.positionSeconds], which the
 * caption overlay already polls on both engines, and calls [ChannelPlayback.seekTo]. The
 * decisions are [SkipWatch]'s; this is the loop and nothing else.
 *
 * Started on a clip's FIRST FRAME and stopped on everything else that happens to the player -
 * a channel change, a paint, an error, the clip ending, the app leaving the screen. Starting any
 * earlier would read the position of a load still opening, which on mpv is the OUTGOING file's.
 * It never touches the join: the clock offset already lands past any range the clock position
 * falls in, and this only acts when playback walks into the next one. Captions need nothing -
 * they are timed against the same file position this seeks.
 *
 * Main thread only: [handler] is on the main looper, like every player call.
 */
class SponsorSkipper(
    private val handler: Handler,
    private val player: () -> ChannelPlayback?,
    private val timetable: Timetable,
    /** A range ran to the end of the clip: the dial's own end-of-clip path. */
    private val ended: () -> Unit,
) {

    private var watch: SkipWatch? = null

    private val tick = object : Runnable {
        override fun run() {
            val current = watch ?: return
            when (val action = current.check(player()?.positionSeconds())) {
                is SkipWatch.Action.SeekTo -> {
                    Log.i("fs42", "skipping sponsor to ${action.seconds.toInt()}s")
                    player()?.seekTo(action.seconds)
                }
                SkipWatch.Action.EndClip -> {
                    Log.i("fs42", "sponsor runs to the end of the clip; ending it")
                    stop()
                    ended()
                    return
                }
                SkipWatch.Action.None -> Unit
            }
            if (watch === current) handler.postDelayed(this, TICK_MILLIS)
        }
    }

    /** [tuned]'s first frame is up: watch it, if it is a clock clip with anything to skip. */
    fun start(tuned: Tuned?) {
        stop()
        val playing = tuned ?: return
        if (playing.channel.rotation != "clock") return
        val ranges = timetable.skipRanges(playing.stream)
        if (ranges.isEmpty()) return
        watch = SkipWatch(ranges, playing.stream.duration.toDouble())
        handler.postDelayed(tick, TICK_MILLIS)
    }

    fun stop() {
        watch = null
        handler.removeCallbacks(tick)
    }

    private companion object {
        /**
         * A quarter of a second: an entered range shows at most that much before the jump, and
         * a position read is one cheap property call on either engine.
         */
        const val TICK_MILLIS = 250L
    }
}
