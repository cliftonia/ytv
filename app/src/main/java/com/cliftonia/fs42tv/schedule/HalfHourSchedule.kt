package com.cliftonia.fs42tv.schedule

import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

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
        fun of(broadcastSlot: Int): DayPart = values().last { broadcastSlot >= it.firstSlot }
    }
}

/**
 * One clock channel's day on the half hour: programmes starting at :00 and :30, the gap at the
 * end of each filled with short clips, and an "up next" card for whatever will not fit.
 *
 * A pure function of (channel, lineup, instant). Every television must reach the same answer from
 * the same cached lineup, offline, with no coordination - which is what the continuous rotation
 * already gives the dial, and what this has to keep giving it. So nothing here reads a clock, a
 * random source, or anything the device knows that the lineup does not: the instant and the zone
 * come in as arguments, and every choice that looks random is a hash of (channel, slot).
 *
 * How a day is built (see docs/superpowers/specs/2026-09-23-schedule-and-skips-design.md):
 * - Each [DayPart] draws from the streams tagged with it, or from every stream when none are.
 * - Its pool is laid out as a CYCLE of part-days, counted only in that part's slots: prime picks
 *   up tomorrow exactly where it stopped tonight, instead of joining whatever afternoon left.
 * - Programmes (five minutes of watched time or more) take whole slots in list order - so the
 *   curation's episode order survives. One that would cross the end of its part waits for the
 *   part's next day; one longer than the whole part runs on into it.
 * - A gap is filled with shorts (under five minutes), largest first to the minute, the order
 *   within a minute seeded by (channel, slot). What remains is a card.
 *
 * [durations] are WATCHED seconds, in stream order - sponsor skips already taken out.
 */
class HalfHourSchedule(
    private val channelNumber: Int,
    private val durations: List<Int>,
    private val parts: List<List<String>>,
    private val zone: ZoneId,
) {

    /** What is on at an instant. All times are epoch seconds. */
    sealed class OnAir {
        /**
         * A programme, [offsetSeconds] into its WATCHED time. [slotStart] to [slotEnd] is the
         * block of slots it occupies in this part; [endsAt] is when its content runs out and the
         * top-ups begin.
         */
        data class Programme(
            val index: Int,
            val offsetSeconds: Double,
            val slotStart: Long,
            val slotEnd: Long,
            val endsAt: Long,
        ) : OnAir()

        /** A short clip in the gap after a programme. */
        data class TopUp(val index: Int, val offsetSeconds: Double, val start: Long, val end: Long) : OnAir()

        /** The card, from [start] [until] a slot boundary; [nextIndex] comes on at [nextAt]. */
        data class Card(val nextIndex: Int, val nextAt: Long, val start: Long, val until: Long) : OnAir()
    }

    private enum class Kind { PROGRAMME, TOP_UP, CARD }

    /** One entry of a laid-out part-day; positions are seconds from the part-day's start. */
    private class Item(
        val kind: Kind,
        val index: Int,
        val start: Int,
        val end: Int,
        val watchBase: Int = 0,
        val blockEnd: Int = end,
    )

    /**
     * Where a part-day opens: the next programme (a position in the cycle's programmes) and,
     * when a programme longer than the part is running on, which one and how many slots it has
     * already had.
     */
    private data class Start(val next: Int, val carry: Int, val carried: Int)

    private class Block(val pos: Int, val startSlot: Int, val slots: Int, val baseSlots: Int)

    private class Placement(val blocks: List<Block>, val after: Start)

    private val cycles: Map<DayPart, PartCycle?> = DayPart.values().associateWith { cycleFor(it) }

    /** The last part-day laid out per part: lookups cluster, and a guide asks once per channel. */
    private val laidOut = ConcurrentHashMap<DayPart, Pair<Long, List<Item>>>()

    /** What is on at [epochSeconds], or null when the channel has nothing that can be. */
    fun at(epochSeconds: Long): OnAir? {
        val here = locate(epochSeconds) ?: return null
        val item = here.item
        return when (item.kind) {
            Kind.PROGRAMME -> OnAir.Programme(
                index = item.index,
                offsetSeconds = (item.watchBase + here.pos - item.start).toDouble(),
                slotStart = here.utc(item.start),
                slotEnd = here.utc(item.blockEnd),
                endsAt = here.utc(item.end),
            )
            Kind.TOP_UP -> OnAir.TopUp(
                item.index, (here.pos - item.start).toDouble(), here.utc(item.start), here.utc(item.end),
            )
            Kind.CARD -> {
                val until = here.utc(item.end)
                // The next PROGRAMME, which is what a card is for. With none anywhere near - a
                // channel of nothing but shorts - whatever opens the next slot.
                val next = upNext(epochSeconds)
                    ?: locate(until)?.item?.takeIf { it.kind != Kind.CARD }?.let { it.index to until }
                OnAir.Card(next?.first ?: -1, next?.second ?: until, here.utc(item.start), until)
            }
        }
    }

    /**
     * The next programme to START after whatever is on at [epochSeconds], and when - or null
     * within [UP_NEXT_PROBES] items. Walks forward item by item through the real day, so across a
     * part boundary it names what really comes on, not the same part's next programme.
     */
    fun upNext(epochSeconds: Long): Pair<Int, Long>? {
        val first = locate(epochSeconds) ?: return null
        var probe = first.utc(first.item.end)
        repeat(UP_NEXT_PROBES) {
            val here = locate(probe) ?: return null
            val item = here.item
            if (item.kind == Kind.PROGRAMME && here.pos == item.start) return item.index to probe
            probe = maxOf(here.utc(item.end), probe + 1)
        }
        return null
    }

    /** An instant placed in its part-day: the item on air and how to turn positions into time. */
    private class Located(val item: Item, val pos: Int, val t: Long, val local: Long, val partStart: Long) {
        /** Epoch seconds for part-day position [p], relative to the instant asked about. */
        fun utc(p: Int): Long = t + (partStart + p - local)
    }

    private fun locate(t: Long): Located? {
        // Local wall-clock seconds: the slots are the device's half hours, DST and all.
        val local = t + zone.rules.getOffset(Instant.ofEpochSecond(t)).totalSeconds
        val shifted = Math.floorDiv(local, SLOT.toLong()) + DAY_SHIFT
        val day = Math.floorDiv(shifted, SLOTS_PER_DAY.toLong())
        val part = DayPart.of(Math.floorMod(shifted, SLOTS_PER_DAY.toLong()).toInt())
        val cycle = cycles[part] ?: return null
        val firstSlot = day * SLOTS_PER_DAY - DAY_SHIFT + part.firstSlot
        val partStart = firstSlot * SLOT
        val pos = (local - partStart).toInt()
        val items = layout(part, cycle, day, firstSlot)
        val item = items.firstOrNull { pos < it.end } ?: return null
        return Located(item, pos, t, local, partStart)
    }

    private fun layout(part: DayPart, cycle: PartCycle, day: Long, firstSlot: Long): List<Item> {
        laidOut[part]?.let { (cachedDay, items) -> if (cachedDay == day) return items }
        val items = ArrayList<Item>()
        val placement = cycle.place(cycle.stateOn(day))
        var slot = 0
        for (block in placement.blocks) {
            val index = cycle.programmes[block.pos]
            val start = block.startSlot * SLOT
            val blockEnd = (block.startSlot + block.slots) * SLOT
            val base = block.baseSlots * SLOT
            val contentEnd = minOf(blockEnd, start + durations[index] - base)
            items += Item(Kind.PROGRAMME, index, start, contentEnd, base, blockEnd)
            if (contentEnd < blockEnd) {
                fill(items, cycle, contentEnd, blockEnd, firstSlot + block.startSlot + block.slots - 1)
            }
            slot = block.startSlot + block.slots
        }
        // Deferred: the rest of the part is top-ups and cards, slot by slot.
        while (slot < part.slots) {
            fill(items, cycle, slot * SLOT, (slot + 1) * SLOT, firstSlot + slot)
            slot++
        }
        val merged = mergeCards(items)
        laidOut[part] = day to merged
        return merged
    }

    /** Shorts that fit [from]..[to], largest first, then a card for what is left. */
    private fun fill(items: MutableList<Item>, cycle: PartCycle, from: Int, to: Int, slotIndex: Long) {
        var at = from
        for (index in cycle.shortsFor(slotIndex)) {
            val length = durations[index]
            if (at + length <= to) {
                items += Item(Kind.TOP_UP, index, at, at + length)
                at += length
            }
        }
        if (at < to) items += Item(Kind.CARD, -1, at, to)
    }

    /**
     * Adjacent cards become one. A deferred hour with nothing to top it up is one card to the end
     * of the part, not a card that ends and re-tunes into an identical card every half hour.
     */
    private fun mergeCards(items: List<Item>): List<Item> {
        val out = ArrayList<Item>(items.size)
        for (item in items) {
            val last = out.lastOrNull()
            if (item.kind == Kind.CARD && last?.kind == Kind.CARD) {
                out[out.size - 1] = Item(Kind.CARD, -1, last.start, item.end)
            } else {
                out += item
            }
        }
        return out
    }

    private fun cycleFor(part: DayPart): PartCycle? {
        val playable = durations.indices.filter { durations[it] > 0 }
        val tagged = playable.filter { part.key in parts.getOrElse(it) { emptyList() } }
        val pool = tagged.ifEmpty { playable }
        if (pool.isEmpty()) return null
        return PartCycle(
            part = part,
            programmes = pool.filter { durations[it] >= SHORT },
            shorts = pool.filter { durations[it] < SHORT },
        )
    }

    /** One part's pool laid out as a repeating sequence of part-days. */
    private inner class PartCycle(
        val part: DayPart,
        val programmes: List<Int>,
        val shorts: List<Int>,
    ) {
        private val slotsOf = IntArray(programmes.size) { (durations[programmes[it]] + SLOT - 1) / SLOT }

        // Part-day openings from the epoch until one repeats. The sequence is a pure function of
        // its opening, so from the first repeat on it is periodic - and a lookup years from the
        // epoch is an index into that period, not a replay of every day in between.
        private val starts = ArrayList<Start>()
        private val loopStart: Int

        init {
            val seen = HashMap<Start, Int>()
            var start = Start(0, -1, 0)
            while (start !in seen) {
                seen[start] = starts.size
                starts += start
                if (programmes.isEmpty()) break
                start = place(start).after
            }
            loopStart = seen.getValue(start)
        }

        fun stateOn(day: Long): Start {
            if (day >= 0 && day < loopStart) return starts[day.toInt()]
            val period = (starts.size - loopStart).toLong()
            return starts[loopStart + Math.floorMod(day - loopStart, period).toInt()]
        }

        fun place(start: Start): Placement {
            val blocks = ArrayList<Block>()
            if (programmes.isEmpty()) return Placement(blocks, start)
            var slot = 0
            if (start.carry >= 0) {
                val remaining = slotsOf[start.carry] - start.carried
                if (remaining > part.slots) {
                    blocks += Block(start.carry, 0, part.slots, start.carried)
                    return Placement(blocks, Start(start.next, start.carry, start.carried + part.slots))
                }
                blocks += Block(start.carry, 0, remaining, start.carried)
                slot = remaining
            }
            var next = start.next
            while (slot < part.slots) {
                val k = slotsOf[next]
                when {
                    slot + k <= part.slots -> {
                        blocks += Block(next, slot, k, 0)
                        slot += k
                        next = (next + 1) % programmes.size
                    }
                    // Longer than the whole part: it may cross, but only from the part's start,
                    // where it gets as much of the part as it can.
                    k > part.slots && slot == 0 -> {
                        blocks += Block(next, 0, part.slots, 0)
                        return Placement(blocks, Start((next + 1) % programmes.size, next, part.slots))
                    }
                    // Would cross the end of the part: deferred to the part's next day.
                    else -> break
                }
            }
            return Placement(blocks, Start(next, -1, 0))
        }

        /** Shorts for the gap in [slotIndex]: largest first to the minute, then by seed. */
        fun shortsFor(slotIndex: Long): List<Int> {
            if (shorts.size < 2) return shorts
            val seed = mix(mix(channelNumber.toLong()) xor slotIndex)
            return shorts.sortedWith(
                compareByDescending<Int> { durations[it] / 60 }.thenBy { mix(seed + it) },
            )
        }
    }

    companion object {
        const val SLOT = 1800
        const val SLOTS_PER_DAY = 48

        /** Five minutes of watched time: a programme at or above, a top-up below. */
        const val SHORT = 300

        /** 23:00 is slot 46 of a calendar day; shifting by two starts the broadcast day there. */
        private const val DAY_SHIFT = 2

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
