package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.schedule.PartPacker.Kind
import java.time.Instant
import java.time.ZoneId

/**
 * The four parts of a broadcast day, in local time, as slot ranges of a day that starts at 23:00
 * - so late night is one unbroken part rather than two halves either side of midnight.
 */
enum class DayPart(val key: String, val firstSlot: Int, val slots: Int) {
    /** 23:00-06:00. */
    LATE("late", 0, 14),

    /** 06:00-12:00. */
    BREAKFAST("breakfast", 14, 12),

    /** 12:00-18:00. */
    AFTERNOON("afternoon", 26, 12),

    /** 18:00-23:00. */
    PRIME("prime", 38, 10);

    companion object {
        private val ALL = values()
        fun of(broadcastSlot: Int): DayPart = ALL.last { broadcastSlot >= it.firstSlot }
    }
}

/**
 * One clock channel's day on the half hour: programmes from the :00 and :30, the gaps filled with
 * other clips, and an "up next" card for a short remainder. How a part-day is laid out is
 * [PartPacker]'s; this is the day around it - the parts, the cycle, and the clock.
 *
 * A pure function of (channel, lineup, instant). Every television must reach the same answer from
 * the same cached lineup, offline, with no coordination - which is what the continuous rotation
 * already gives the dial, and what this has to keep giving it. So nothing here reads a clock, a
 * random source, or anything the device knows that the lineup does not: the instant and the zone
 * come in as arguments, and every choice that looks random is a fixed hash.
 *
 * - Each [DayPart] draws from the streams tagged with it, or from every stream when none are.
 * - Its pool is laid out as a CYCLE of part-days, counted only in that part's days: prime picks up
 *   tomorrow exactly where it stopped tonight, instead of joining whatever afternoon left.
 * - The cycle is anchored at [ANCHOR_DAY], far enough back that every date a television will see
 *   - the epoch included - is inside the repeating part, so episode order runs straight across.
 *
 * [durations] are WATCHED seconds, in stream order - sponsor skips already taken out.
 */
class HalfHourSchedule(
    private val channelNumber: Int,
    durations: List<Int>,
    private val parts: List<List<String>>,
    private val zone: ZoneId,
    /** Episodes must air in list order: gaps are filled with the next episodes, never others. */
    private val ordered: Boolean = false,
) {

    /** What is on at an instant. All times are epoch seconds. */
    sealed class OnAir {
        /**
         * A programme, [offsetSeconds] into its WATCHED time, joined at [slotStart]. [endsAt] is
         * when the schedule moves on; [slotEnd] the boundary its gap runs to. [cut] means the part
         * ends before its content does: at [endsAt] it stops, and carries on another day.
         */
        data class Programme(
            val index: Int,
            val offsetSeconds: Double,
            val slotStart: Long,
            val slotEnd: Long,
            val endsAt: Long,
            val cut: Boolean = false,
        ) : OnAir()

        /** A clip filling the gap after a programme, played whole. */
        data class TopUp(val index: Int, val offsetSeconds: Double, val start: Long, val end: Long) : OnAir()

        /** The card, from [start] [until] a slot boundary; [nextIndex] comes on at [nextAt]. */
        data class Card(val nextIndex: Int, val nextAt: Long, val start: Long, val until: Long) : OnAir()
    }

    /**
     * Clamped: a lineup can carry any Int, and a clip longer than a week is garbage - but an
     * Int.MAX_VALUE one once asked for a slot table the size of the heap.
     */
    private val watched = IntArray(durations.size) { durations[it].coerceIn(0, MAX_DURATION) }

    private val cycles: Map<DayPart, Cycle?> = DayPart.values().associateWith { cycleFor(it) }

    /** What is on at [epochSeconds], or null when the channel has nothing that can be. */
    fun at(epochSeconds: Long): OnAir? {
        val here = locate(epochSeconds) ?: return null
        val item = here.item
        return when (item.kind) {
            Kind.PROGRAMME -> OnAir.Programme(
                index = item.index,
                offsetSeconds = (item.watchBase + here.pos - item.start).toDouble(),
                slotStart = here.utc(item.start),
                slotEnd = here.utc(minOf(ceilToSlot(item.end), here.partLength)),
                endsAt = here.utc(item.end),
                cut = item.cut,
            )
            Kind.TOP_UP -> OnAir.TopUp(
                item.index, (here.pos - item.start).toDouble(), here.utc(item.start), here.utc(item.end),
            )
            Kind.CARD -> {
                val until = here.utc(item.end)
                // The next PROGRAMME to start, which is what a card is for. With none anywhere
                // near - a channel of nothing but shorts - whatever opens the next slot.
                val next = upNext(epochSeconds)
                    ?: locate(until)?.item?.takeIf { it.kind != Kind.CARD }?.let { it.index to until }
                OnAir.Card(next?.first ?: -1, next?.second ?: until, here.utc(item.start), until)
            }
        }
    }

    /**
     * The next programme to START after whatever is on at [epochSeconds], and when - or null
     * within [UP_NEXT_PROBES] items. Walks forward item by item through the real day, so across a
     * part boundary it names what really comes on. A carried continuation is not a start.
     */
    fun upNext(epochSeconds: Long): Pair<Int, Long>? {
        val first = locate(epochSeconds) ?: return null
        var probe = first.utc(first.item.end)
        repeat(UP_NEXT_PROBES) {
            val here = locate(probe) ?: return null
            val item = here.item
            if (item.kind == Kind.PROGRAMME && here.pos == item.start && item.watchBase == 0) {
                return item.index to probe
            }
            probe = maxOf(here.utc(item.end), probe + 1)
        }
        return null
    }

    /** The pool the part on air at [epochSeconds] draws from, in list order - for substitutes. */
    fun poolAt(epochSeconds: Long): List<Int> {
        val t = epochSeconds.coerceIn(MIN_INSTANT, MAX_INSTANT)
        val local = t + zone.rules.getOffset(Instant.ofEpochSecond(t)).totalSeconds
        val shifted = Math.floorDiv(local, SLOT.toLong()) + DAY_SHIFT
        return cycles[DayPart.of(Math.floorMod(shifted, SLOTS_PER_DAY.toLong()).toInt())]?.pool.orEmpty()
    }

    /**
     * An instant placed in its part-day: the item on air, and how to turn part-day positions back
     * into real time.
     *
     * Positions are wall-clock seconds, so the conversion uses the offset in force at the instant
     * asked about - clamped to the stretch between the zone's transitions around it. Across a
     * DST change the wall clock jumps or repeats; clamping is what keeps the answers contiguous in
     * real time, with a span that meets the jump ending exactly at it.
     */
    private class Located(
        val item: PartPacker.Item,
        val pos: Int,
        val partLength: Int,
        private val partStartMinusOffset: Long,
        private val segmentStart: Long,
        private val segmentEnd: Long,
    ) {
        fun utc(p: Int): Long = (partStartMinusOffset + p).coerceIn(segmentStart, segmentEnd)
    }

    private fun locate(epochSeconds: Long): Located? {
        val t = epochSeconds.coerceIn(MIN_INSTANT, MAX_INSTANT)
        val instant = Instant.ofEpochSecond(t)
        val rules = zone.rules
        val offset = rules.getOffset(instant).totalSeconds
        val local = t + offset
        val shifted = Math.floorDiv(local, SLOT.toLong()) + DAY_SHIFT
        val day = Math.floorDiv(shifted, SLOTS_PER_DAY.toLong())
        val part = DayPart.of(Math.floorMod(shifted, SLOTS_PER_DAY.toLong()).toInt())
        val cycle = cycles[part] ?: return null
        val partStart = (day * SLOTS_PER_DAY - DAY_SHIFT + part.firstSlot) * SLOT
        val pos = (local - partStart).toInt()
        val items = cycle.itemsOn(day)
        val item = items[items.binarySearchBy(pos)]
        val segmentStart = if (rules.isFixedOffset) Long.MIN_VALUE
        else rules.previousTransition(instant.plusSeconds(1))?.instant?.epochSecond ?: Long.MIN_VALUE
        val segmentEnd = if (rules.isFixedOffset) Long.MAX_VALUE
        else rules.nextTransition(instant)?.instant?.epochSecond ?: Long.MAX_VALUE
        return Located(item, pos, part.slots * SLOT, partStart - offset, segmentStart, segmentEnd)
    }

    /** The item covering [pos]: items are contiguous from 0, so the last one starting at or before. */
    private fun List<PartPacker.Item>.binarySearchBy(pos: Int): Int {
        var low = 0
        var high = size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (this[mid].start <= pos) low = mid else high = mid - 1
        }
        return low
    }

    private fun cycleFor(part: DayPart): Cycle? {
        val playable = watched.indices.filter { watched[it] > 0 }
        val tagged = playable.filter { part.key in parts.getOrElse(it) { emptyList() } }
        val pool = tagged.ifEmpty { playable }
        if (pool.isEmpty()) return null
        val programmes = pool.filter { watched[it] >= SHORT }.toIntArray()
        return Cycle(PartPacker(channelNumber, part, watched, programmes, pool, ordered), pool)
    }

    /**
     * One part's part-days, from [ANCHOR_DAY] until an opening repeats. Each part-day is a pure
     * function of its opening, so from the first repeat on the sequence is periodic, and every
     * part-day of it is packed once, here, and kept: a lookup is an index and a binary search.
     */
    private class Cycle(packer: PartPacker, val pool: List<Int>) {
        private val days = ArrayList<List<PartPacker.Item>>()
        private val loopStart: Int

        init {
            val seen = HashMap<PartPacker.Start, Int>()
            var start = PartPacker.Start(0, -1, 0)
            while (start !in seen) {
                seen[start] = days.size
                val packed = packer.pack(start)
                days += packed.items
                start = packed.after
            }
            loopStart = seen.getValue(start)
        }

        fun itemsOn(day: Long): List<PartPacker.Item> {
            val sinceAnchor = day - ANCHOR_DAY
            if (sinceAnchor >= 0 && sinceAnchor < loopStart) return days[sinceAnchor.toInt()]
            val period = (days.size - loopStart).toLong()
            return days[loopStart + Math.floorMod(sinceAnchor - loopStart, period).toInt()]
        }
    }

    private fun ceilToSlot(pos: Int): Int = ((pos + SLOT - 1) / SLOT) * SLOT

    companion object {
        const val SLOT = 1800
        const val SLOTS_PER_DAY = 48

        /** Five minutes of watched time: a programme at or above, a top-up-only short below. */
        const val SHORT = 300

        /** A week: longer is garbage, and is treated as a week. */
        const val MAX_DURATION = 7 * 86_400

        /** 23:00 is slot 46 of a calendar day; shifting by two starts the broadcast day there. */
        private const val DAY_SHIFT = 2

        /**
         * Broadcast day 1 January 1870. Cycles count from here rather than from the epoch, so the
         * epoch, and every date either side a television will see, runs on one straight sequence.
         */
        const val ANCHOR_DAY = -36_525L

        /** Instants are clamped to about +-10,000 years: a garbage clock is a date, not a crash. */
        private const val MAX_INSTANT = 315_537_897_599L
        private const val MIN_INSTANT = -377_705_116_800L

        /** Items walked looking for the next programme: two full days of the busiest channel. */
        private const val UP_NEXT_PROBES = 200

        /**
         * SplitMix64's finaliser: a fixed, documented bit mix, so the seeded order is the same on
         * every television and every build - not a library generator whose sequence is only
         * promised within one runtime version.
         */
        fun mix(value: Long): Long {
            var z = value + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}
