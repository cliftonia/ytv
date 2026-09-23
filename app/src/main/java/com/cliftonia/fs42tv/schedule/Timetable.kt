package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place that answers "what is on this clock channel at this instant", with the Settings
 * rows that change the answer applied.
 *
 * Every caller that used to run [ClockRotation] itself - the tuner, the neighbour prefetch, the
 * guide, the banner - asks this instead, so a row flipped on the remote changes all of them at
 * once and the guide can never list a programme the tuner would not play.
 *
 * The flags are closures, read at the moment of each question rather than captured: a row
 * switched off takes effect on the next tune at the latest, which is the Features promise.
 * [PLAIN] is the answer from before any of the rows existed, and is what tests and callers
 * without settings get.
 *
 * Thread-safe: asked from the tune executor, the prefetch thread and the UI thread at once. The
 * only state is the schedule cache, which is a concurrent map of immutable schedules.
 */
class Timetable(
    /** SKIP SPONSORS: walk watched time and join past skipped ranges. */
    private val skipsOn: () -> Boolean,
    /** SCHEDULE: HALF-HOUR rather than CONTINUOUS. */
    private val halfHourOn: () -> Boolean = { false },
    /** The device's zone, read per question: the half hours are its half hours. */
    val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** The device's 12/24-hour setting, for the times the guide and banner print. */
    val use24Hour: () -> Boolean = { false },
) {

    /** What is on. [index] is the stream on air - or, for a card, the one it announces. */
    sealed class OnAir {
        abstract val index: Int

        /**
         * A clip, joined [offsetSeconds] into the FILE. [startsAt] and [endsAt] are known only on
         * the half-hour schedule, where a clip has a real place in the day.
         */
        data class Clip(
            override val index: Int,
            val offsetSeconds: Double,
            val startsAt: Long? = null,
            val endsAt: Long? = null,
            /**
             * Set when the schedule stops this clip before its content ends - a programme longer
             * than its part, cut at the part's end. Nothing else would stop it: the player plays
             * to the end of the file, so the dial must retune at this instant itself.
             */
            val cutAt: Long? = null,
        ) : OnAir()

        /** The "up next" card, [start] to [until]; [index] comes on at [nextAt]. */
        data class Card(
            override val index: Int,
            val nextAt: Long,
            val start: Long,
            val until: Long,
        ) : OnAir()
    }

    /** Whether [channel] is on the half-hour schedule right now. */
    fun halfHour(channel: Channel): Boolean = channel.rotation == "clock" && halfHourOn()

    /** The ranges the watcher should jump for [stream]; empty with SKIP SPONSORS off. */
    fun skipRanges(stream: Stream): List<SkipRange> =
        if (skipsOn()) Skips.ranges(stream) else emptyList()

    /** How long [stream] occupies the clock; its raw length with SKIP SPONSORS off. */
    fun watchDuration(stream: Stream): Int =
        if (skipsOn()) Skips.watchDuration(stream) else stream.duration

    /**
     * What is on [channel] at [nowSeconds], or null when nothing can be. Callers check
     * `rotation == "clock"` first, as they always have.
     */
    fun at(channel: Channel, nowSeconds: Long): OnAir? {
        // Each flag read once, so a flip mid-question cannot mix two answers.
        val skips = skipsOn()
        val streams = channel.streams
        fun file(index: Int, watched: Double) =
            if (skips) Skips.mediaTime(Skips.ranges(streams[index]), watched) else watched
        if (!halfHour(channel)) {
            val point = ClockRotation.playPointFor(
                streams.map { if (skips) Skips.watchDuration(it) else it.duration }, nowSeconds,
            ) ?: return null
            return OnAir.Clip(point.index, file(point.index, point.offsetSeconds))
        }
        return when (val slot = scheduleFor(channel, skips).at(nowSeconds)) {
            null -> null
            is HalfHourSchedule.OnAir.Programme -> OnAir.Clip(
                slot.index, file(slot.index, slot.offsetSeconds), slot.slotStart, slot.endsAt,
                cutAt = slot.endsAt.takeIf { slot.cut },
            )
            is HalfHourSchedule.OnAir.TopUp ->
                OnAir.Clip(slot.index, file(slot.index, slot.offsetSeconds), slot.start, slot.end)
            // A card always names something: a pool that can put a card up can put a clip up.
            is HalfHourSchedule.OnAir.Card ->
                OnAir.Card(slot.nextIndex.coerceAtLeast(0), slot.nextAt, slot.start, slot.until)
        }
    }

    /**
     * The next programme to start on [channel] after what is on at [nowSeconds], and when - for
     * the NEXT lines. Null off the half-hour schedule, where nothing has a start time.
     */
    fun upNext(channel: Channel, nowSeconds: Long): Pair<Int, Long>? =
        if (halfHour(channel)) scheduleFor(channel, skipsOn()).upNext(nowSeconds) else null

    /** Where [stream] is joined from its beginning: past a sponsor read at 0:00, when skipping. */
    fun startOffset(stream: Stream): Double = Skips.mediaTime(skipRanges(stream), 0.0)

    /**
     * Clips to try, in order, in place of [dead] - scheduled on [channel] at [nowSeconds] until
     * [endsAt] - when it will not resolve.
     *
     * Continuous: the next clips in list order, as it always was. On the half-hour schedule the
     * substitute must not wreck what comes after it: it is drawn from the same part's pool and
     * must end by [endsAt], starting after [dead] in list order; [avoid] is the substitute that
     * just finished, so a long dead programme is not covered by one short clip on repeat. An
     * ordered channel offers only its shorts: any episode but the next would air out of order,
     * and the next is what the schedule has at [endsAt] anyway. With nothing that fits, whatever
     * the schedule has at [endsAt], early.
     */
    fun substitutes(channel: Channel, nowSeconds: Long, dead: Int, endsAt: Long?, avoid: Int?): List<Int> {
        val count = channel.streams.size
        if (count == 0) return emptyList()
        if (!halfHour(channel) || endsAt == null) return (1 until count).map { (dead + it) % count }
        val room = endsAt - nowSeconds
        val pool = scheduleFor(channel, skipsOn()).poolAt(nowSeconds)
        val rotated = pool.filter { it > dead } + pool.filter { it < dead }
        val fits = rotated.filter { i ->
            val w = watchDuration(channel.streams[i])
            i != avoid && w in 1..room && (!channel.ordered || w < HalfHourSchedule.SHORT)
        }
        return fits.ifEmpty { listOfNotNull(at(channel, endsAt)?.index?.takeIf { it != dead }) }
    }

    /**
     * When a substitute for a dead clip, started at [nowSeconds], stops being on air. When it is
     * the schedule's own next item started early, that item's scheduled end - so its natural end
     * is recognised as that showing finishing, not replayed; otherwise when its content runs out.
     */
    fun substituteEndsAt(channel: Channel, nowSeconds: Long, substitute: Int, deadEndsAt: Long?): Long? {
        deadEndsAt ?: return null
        val next = at(channel, deadEndsAt) as? OnAir.Clip
        if (next?.index == substitute) return next.endsAt
        return nowSeconds + watchDuration(channel.streams[substitute])
    }

    /**
     * Build every clock channel's schedule now, off the UI thread, so the first guide open does
     * not pay for a hundred of them at once. A no-op off the half-hour schedule.
     *
     * Asks each one what is on at [nowSeconds] rather than only constructing it: the part cycles
     * are lazy and a day is packed when first asked for, so constructing alone left the real
     * work - cycle detection and today's layout - to the guide's first pass, on the UI thread.
     */
    fun prewarm(channels: List<Channel>, nowSeconds: Long) {
        if (!halfHourOn()) return
        val skips = skipsOn()
        channels.filter { halfHour(it) }.forEach { scheduleFor(it, skips).at(nowSeconds) }
    }

    private class Cached(
        val streams: List<Stream>,
        val skips: Boolean,
        val ordered: Boolean,
        val zone: ZoneId,
        val schedule: HalfHourSchedule,
    )

    /** Built once per channel and lineup; the guide asks for a hundred channels at a time. */
    private val schedules = ConcurrentHashMap<Int, Cached>()

    private fun scheduleFor(channel: Channel, skips: Boolean): HalfHourSchedule {
        val zone = zone()
        fun fits(cached: Cached?): Boolean {
            cached ?: return false
            // Identity first - the same parsed lineup, the common case - then equality, so a
            // reloaded but identical lineup keeps its cycle and a changed one rebuilds it.
            val sameLineup = cached.streams === channel.streams || cached.streams == channel.streams
            return sameLineup && cached.skips == skips && cached.ordered == channel.ordered && cached.zone == zone
        }
        schedules[channel.number]?.let { if (fits(it)) return it.schedule }
        // compute, not get-then-put: the prewarm, the tuner and the guide can all ask for the same
        // channel at once, and one build is enough.
        return schedules.compute(channel.number) { _, cached ->
            if (fits(cached)) cached else Cached(
                channel.streams, skips, channel.ordered, zone,
                HalfHourSchedule(
                    channelNumber = channel.number,
                    durations = channel.streams.map { if (skips) Skips.watchDuration(it) else it.duration },
                    parts = channel.streams.map { it.parts },
                    zone = zone,
                    ordered = channel.ordered,
                ),
            )
        }!!.schedule
    }

    companion object {
        /** Today's behaviour: raw durations, one continuous rotation. */
        val PLAIN = Timetable(skipsOn = { false })
    }
}
