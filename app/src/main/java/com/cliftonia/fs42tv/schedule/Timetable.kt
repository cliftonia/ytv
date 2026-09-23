package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream

/**
 * The one place that answers "what is on this clock channel at this instant", with the Settings
 * rows that change the answer applied.
 *
 * Every caller that used to run [ClockRotation] itself - the tuner, the neighbour prefetch, the
 * guide, the banner - asks this instead, so a row flipped on the remote changes all of them at
 * once and the guide can never list a programme the tuner would not play.
 *
 * The flags are closures, read at the moment of each question rather than captured: a row
 * switched off takes effect on the next tune at the latest, which is the [Features] promise.
 * [PLAIN] is the answer from before any of the rows existed, and is what tests and callers
 * without settings get.
 */
class Timetable(
    /** SKIP SPONSORS: walk watched time and join past skipped ranges. */
    private val skipsOn: () -> Boolean,
) {

    /** The ranges the watcher should jump for [stream]; empty with SKIP SPONSORS off. */
    fun skipRanges(stream: Stream): List<SkipRange> =
        if (skipsOn()) Skips.ranges(stream) else emptyList()

    /** How long [stream] occupies the clock; its raw length with SKIP SPONSORS off. */
    fun watchDuration(stream: Stream): Int =
        if (skipsOn()) Skips.watchDuration(stream) else stream.duration

    /**
     * The clip on air on [channel] at [nowSeconds] and the FILE position to join it at, or null
     * when nothing can be. Callers check `rotation == "clock"` first, as they always have.
     */
    fun playPoint(channel: Channel, nowSeconds: Long): PlayPoint? {
        // Read once, so a flip mid-question cannot mix watched and raw arithmetic.
        val skips = skipsOn()
        val streams = channel.streams
        val point = ClockRotation.playPointFor(
            streams.map { if (skips) Skips.watchDuration(it) else it.duration }, nowSeconds,
        ) ?: return null
        if (!skips) return point
        val ranges = Skips.ranges(streams[point.index])
        return point.copy(offsetSeconds = Skips.mediaTime(ranges, point.offsetSeconds))
    }

    companion object {
        /** Today's behaviour: raw durations, one continuous rotation. */
        val PLAIN = Timetable(skipsOn = { false })
    }
}
