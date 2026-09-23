package com.cliftonia.fs42tv.schedule

/**
 * Lays out one part-day of one [DayPart]: which programmes air, where, and what fills the gaps.
 *
 * A pure function of where the part-day opens ([Start]) - never of the date - so a part's days
 * form a repeating cycle and a lookup years away is an index into it, not a replay. That is also
 * why the gap fillers are seeded by what the gap follows and where it sits, not by the calendar:
 * a seed that changed with the date would change how much of a gap was filled, and with it the
 * ten-minute cap, where every later programme starts, and the cycle itself.
 *
 * The rules (see docs/superpowers/specs/2026-09-23-schedule-and-skips-design.md, as amended after
 * measuring the real lineup: the first packer left 14.6% of all airtime as cards):
 * - A programme starts on a :00/:30 boundary, or straight after the item before it when the gap
 *   it would otherwise leave is too long to be a card ([CARD_CAP]).
 * - After a programme, the gap to the next boundary is filled largest first (to the minute, the
 *   order within a minute seeded): from ANY clip of the part's pool - except the programme itself
 *   and the one due next - on an unordered channel; on an [ordered] one, first with the next
 *   programmes in list order while they fit (they have aired, and the order continues after
 *   them), then with shorts. Episodes of an ordered channel never play out of order.
 * - What remains is the card, if it is [CARD_CAP] or less; otherwise the next programme starts now.
 * - A programme that would cross the end of the part waits for the part's next day, and the rest
 *   of the part is filled slot by slot. One longer than the whole part starts only at the part's
 *   start, is cut at its end, and carries on in the part's next day.
 */
internal class PartPacker(
    private val channelNumber: Int,
    private val part: DayPart,
    /** Watched seconds per stream, already clamped to something sane. */
    private val durations: IntArray,
    /** Stream indices of the programmes, in list order. */
    val programmes: IntArray,
    /** Every playable stream index of the part's pool. */
    pool: List<Int>,
    private val ordered: Boolean,
) {

    enum class Kind { PROGRAMME, TOP_UP, CARD }

    /**
     * One entry of a part-day; positions are seconds from the part-day's start. A programme's
     * [watchBase] is how much of it earlier part-days showed; [cut] means the part ended before
     * its content did.
     */
    class Item(
        val kind: Kind,
        val index: Int,
        val start: Int,
        val end: Int,
        val watchBase: Int = 0,
        val cut: Boolean = false,
    )

    /**
     * Where a part-day opens: the next programme (a position in [programmes]) and, when one
     * longer than the part is running on, which one and how many seconds of it have aired.
     */
    data class Start(val next: Int, val carry: Int, val carried: Int)

    private val partLength = part.slots * SLOT
    private val shorts: List<Int> = pool.filter { durations[it] < SHORT }

    /** [pool] grouped by whole minutes, longest group first - largest first, to the minute. */
    private val allByMinute: List<IntArray> = byMinute(pool)
    private val shortsByMinute: List<IntArray> = byMinute(shorts)

    /** The shortest filler there is: a gap shorter than this fills with nothing. */
    private val shortest: Int = (if (ordered) shorts else pool).minOfOrNull { durations[it] } ?: Int.MAX_VALUE

    /** The part-day that opens with [start], laid out. */
    fun layout(start: Start): List<Item> {
        val items = ArrayList<Item>()
        pack(start, items)
        return mergeCards(items)
    }

    /**
     * Where the part-day after one opening with [start] opens - the cycle detection's step, and
     * cheap: with [items] null nothing is built, only positions are counted.
     */
    fun next(start: Start): Start = pack(start, null)

    private fun pack(start: Start, items: MutableList<Item>?): Start {
        val n = programmes.size
        if (n == 0) {
            region(items, 0, -1, -1)
            return start
        }
        var pos = 0
        var next = start.next
        // The last programme placed, so the deferred tail never fills with what just aired.
        var last = -1
        if (start.carry >= 0) {
            val index = programmes[start.carry]
            val remaining = durations[index] - start.carried
            if (remaining > partLength) {
                items?.add(Item(Kind.PROGRAMME, index, 0, partLength, start.carried, cut = true))
                return Start(next, start.carry, start.carried + partLength)
            }
            items?.add(Item(Kind.PROGRAMME, index, 0, remaining, start.carried))
            pos = remaining
            last = index
            val after = gap(items, pos, index, next)
            pos = after.first
            next = after.second
        }
        while (pos < partLength) {
            val index = programmes[next]
            val length = durations[index]
            when {
                pos + length <= partLength -> {
                    items?.add(Item(Kind.PROGRAMME, index, pos, pos + length))
                    pos += length
                    last = index
                    next = (next + 1) % n
                    val after = gap(items, pos, index, next)
                    pos = after.first
                    next = after.second
                }
                length > partLength && pos == 0 -> {
                    items?.add(Item(Kind.PROGRAMME, index, 0, partLength, 0, cut = true))
                    return Start((next + 1) % n, next, partLength)
                }
                // Would cross the end of the part: deferred to the part's next day.
                else -> break
            }
        }
        region(items, pos, last, programmes[next])
        return Start(next, -1, 0)
    }

    /**
     * The gap after [previous], which ended at [from]: chain (ordered), fill, then card or not.
     * Returns where the next programme may start and the next programme's position.
     */
    private fun gap(items: MutableList<Item>?, from: Int, previous: Int, nextPos: Int): Pair<Int, Int> {
        val boundary = minOf(ceilToSlot(from), partLength)
        if (boundary == from) return from to nextPos
        var pos = from
        var next = nextPos
        if (ordered) {
            // The next episodes, in order, while they fit: they have aired, so the order goes on.
            while (true) {
                val index = programmes[next]
                if (pos + durations[index] > boundary) break
                items?.add(Item(Kind.PROGRAMME, index, pos, pos + durations[index]))
                pos += durations[index]
                next = (next + 1) % programmes.size
            }
        }
        val exclude = if (ordered) -1 else programmes[next]
        pos = fill(items, pos, boundary, previous, exclude)
        val remaining = boundary - pos
        return when {
            remaining == 0 -> boundary to next
            remaining <= CARD_CAP -> {
                items?.add(Item(Kind.CARD, -1, pos, boundary))
                boundary to next
            }
            // Too long for a card: the next programme starts now, off the boundary.
            else -> pos to next
        }
    }

    /**
     * The rest of the part from [from], with no programme to come: the deferred tail. Filled
     * first as one stretch - so a long clip can fill the hour a long programme left, where half
     * hours of shorts could not (0.9% of all airtime was these cards on the real lineup) - then
     * slot by slot, each slot's remainder a card. A channel with no programmes at all is only
     * ever slot by slot: every half hour of it is the same shape.
     */
    private fun region(items: MutableList<Item>?, from: Int, previous: Int, exclude: Int) {
        var pos = from
        if (programmes.isNotEmpty()) pos = fill(items, pos, partLength, previous, exclude)
        while (pos < partLength) {
            val boundary = minOf(ceilToSlot(pos + 1), partLength)
            val filled = fill(items, pos, boundary, previous, exclude)
            if (filled < boundary) items?.add(Item(Kind.CARD, -1, filled, boundary))
            pos = boundary
        }
    }

    /**
     * One greedy pass, largest first to the minute, never [a] or [b]; returns where it stopped.
     *
     * Within a minute the clips are taken in a ROTATION starting at a seeded point, not a seeded
     * sort: the same variety - which clip of a minute leads differs gap to gap - for no
     * allocation, and a pass stops the moment nothing left in the group could fit. The cycle
     * detection runs this for every gap of every part-day of a cycle; a sort per gap made one
     * channel of 100 eight-minute clips cost 10ms to build.
     */
    private fun fill(items: MutableList<Item>?, from: Int, to: Int, a: Int, b: Int): Int {
        var pos = from
        val groups = if (ordered) shortsByMinute else allByMinute
        if (groups.isEmpty()) return pos
        val seed = HalfHourSchedule.mix(
            HalfHourSchedule.mix(channelNumber.toLong() * 31 + part.ordinal) xor
                (a.toLong() shl 32) xor from.toLong())
        for (group in groups) {
            if (to - pos < shortest) break
            val size = group.size
            // Every clip in a group is within a minute of the others; skip a group that cannot fit.
            val groupShortest = durations[group[size - 1]]
            if (groupShortest > to - pos) continue
            val first = Math.floorMod(seed, size.toLong()).toInt()
            for (j in 0 until size) {
                if (to - pos < groupShortest) break
                val index = group[(first + j) % size]
                if (index == a || index == b) continue
                val length = durations[index]
                if (pos + length <= to) {
                    items?.add(Item(Kind.TOP_UP, index, pos, pos + length))
                    pos += length
                }
            }
        }
        return pos
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

    private fun byMinute(indices: List<Int>): List<IntArray> =
        indices.groupBy { durations[it] / 60 }.toSortedMap(compareByDescending { it })
            .values.map { group -> group.sortedByDescending { durations[it] }.toIntArray() }

    private fun ceilToSlot(pos: Int): Int = ((pos + SLOT - 1) / SLOT) * SLOT

    companion object {
        const val SLOT = HalfHourSchedule.SLOT
        const val SHORT = HalfHourSchedule.SHORT

        /** Ten minutes: a remainder longer than this is not a card - the next programme starts. */
        const val CARD_CAP = 600
    }
}
