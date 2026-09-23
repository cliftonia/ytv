package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.schedule.HalfHourSchedule.OnAir
import com.cliftonia.fs42tv.schedule.ScheduleProbe.SHORT
import com.cliftonia.fs42tv.schedule.ScheduleProbe.SLOT
import com.cliftonia.fs42tv.schedule.ScheduleProbe.Span
import com.cliftonia.fs42tv.schedule.ScheduleProbe.local
import com.cliftonia.fs42tv.schedule.ScheduleProbe.partAt
import com.cliftonia.fs42tv.schedule.ScheduleProbe.partSlots
import com.cliftonia.fs42tv.schedule.ScheduleProbe.partStartLocal
import com.cliftonia.fs42tv.schedule.ScheduleProbe.pool
import com.cliftonia.fs42tv.schedule.ScheduleProbe.span
import com.cliftonia.fs42tv.schedule.ScheduleProbe.walk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Property tests: hundreds of seeded pseudo-random channels, each walked span by span across
 * whole days, with every invariant the spec states checked on every span. Seeds are fixed, so a
 * failure names a reproducible channel.
 */
class HalfHourScheduleInvariantTest {

    /** A generated channel: everything [HalfHourSchedule] is built from. */
    data class Gen(
        val seed: Int,
        val number: Int,
        val durations: List<Int>,
        val parts: List<List<String>>,
        val zone: ZoneId,
    ) {
        fun build() = HalfHourSchedule(number, durations, parts, zone)
        override fun toString() = "Gen(seed=$seed, n=${durations.size}, zone=$zone)"
    }

    private val zones: List<ZoneId> = listOf(
        ZoneOffset.UTC, ZoneOffset.ofHoursMinutes(5, 30), ZoneOffset.ofHoursMinutes(5, 45),
        ZoneOffset.ofHoursMinutes(-3, -30), ZoneOffset.ofHours(14), ZoneOffset.ofHours(-12),
        ZoneOffset.ofHours(10),
    )
    private val keys = listOf("breakfast", "afternoon", "prime", "late")

    private fun duration(rnd: Random): Int = when (rnd.nextInt(100)) {
        in 0 until 30 -> rnd.nextInt(1, SHORT)
        in 30 until 75 -> rnd.nextInt(SHORT, 3601)
        in 75 until 92 -> rnd.nextInt(3601, 21_601)
        else -> listOf(1, 299, 300, 301, 1799, 1800, 1801, 3600, 18_000, 21_600).random(rnd)
    }

    private fun gen(seed: Int, maxClips: Int = 400, zone: ZoneId? = null): Gen {
        val rnd = Random(seed)
        val n = rnd.nextInt(0, maxClips + 1)
        val durations = List(n) { duration(rnd) }
        val tagged = rnd.nextInt(3)
        val parts = List(n) {
            when (tagged) {
                0 -> emptyList()
                1 -> keys.filter { rnd.nextInt(3) == 0 }
                else -> (keys + "brunch").filter { rnd.nextInt(5) == 0 }
            }
        }
        return Gen(seed, rnd.nextInt(1, 1000), durations, parts, zone ?: zones.random(rnd))
    }

    private fun dayStart(date: LocalDate, zone: ZoneId): Long =
        ZonedDateTime.of(date, LocalTime.MIDNIGHT, zone).toEpochSecond()

    /** Every per-span invariant of the spec, for spans walked in a fixed-offset zone. */
    private fun checkSpans(g: Gen, s: HalfHourSchedule, spans: List<Span>, rnd: Random, cardNamesAStart: Boolean = false) {
        val d = g.durations
        for ((i, sp) in spans.withIndex()) {
            val ls = local(sp.start, g.zone)
            val le = local(sp.end, g.zone)
            val part = partAt(ls)
            val partEnd = partStartLocal(ls) + partSlots(part) * SLOT
            val pool = pool(d, g.parts, part)
            val ctx = "$g span $sp in $part"
            // Interior: the same thing, offset advancing with the clock.
            if (sp.length > 2) {
                val t = sp.start + 1 + rnd.nextLong(sp.length - 1)
                assertEquals("$ctx changes inside itself at $t", sp, span(s.at(t)!!, t))
            }
            when (sp.kind) {
                'P' -> {
                    val dur = d[sp.index]
                    assertTrue("$ctx: a programme is 5 minutes or more", dur >= SHORT)
                    assertTrue("$ctx: from its part's pool", sp.index in pool)
                    assertEquals("$ctx: starts on :00/:30 local", 0L, Math.floorMod(ls, SLOT))
                    assertTrue("$ctx: offset at start is 0 or a carried whole slot count",
                        sp.offsetAtStart >= 0 && sp.offsetAtStart % SLOT == 0.0)
                    assertTrue("$ctx: never plays past its watch duration $dur",
                        sp.offsetAtStart + sp.length <= dur)
                    val fullyPlayed = sp.offsetAtStart + sp.length == dur.toDouble()
                    assertTrue("$ctx: content only cut short at the part's end", fullyPlayed || le == partEnd)
                    val longerThanPart = dur > partSlots(part) * SLOT
                    assertTrue("$ctx: crosses its part's end but is not longer than the part",
                        le <= partEnd || longerThanPart)
                }
                'T' -> {
                    val dur = d[sp.index]
                    assertTrue("$ctx: a top-up is a short", dur < SHORT)
                    assertTrue("$ctx: from its part's pool", sp.index in pool)
                    assertEquals("$ctx: plays whole, from 0", dur.toLong(), sp.length)
                    assertEquals("$ctx: from 0", 0.0, sp.offsetAtStart, 0.0)
                    assertEquals("$ctx: never overruns its slot",
                        Math.floorDiv(ls, SLOT), Math.floorDiv(le - 1, SLOT))
                }
                'C' -> {
                    assertTrue("$ctx: a card has length", sp.length > 0)
                    assertEquals("$ctx: a card ends on a slot boundary", 0L, Math.floorMod(le, SLOT))
                    val shorts = pool.filter { d[it] < SHORT }
                    if (shorts.isNotEmpty()) {
                        assertEquals("$ctx: with shorts in the pool a card never spans slots",
                            Math.floorDiv(ls, SLOT), Math.floorDiv(le - 1, SLOT))
                        val slot = Math.floorDiv(ls, SLOT)
                        val used = spans.filter { it.kind == 'T' && Math.floorDiv(local(it.start, g.zone), SLOT) == slot }
                            .map { it.index }.toSet()
                        val unused = shorts.filter { it !in used }
                        if (unused.isNotEmpty()) {
                            assertTrue("$ctx: the card is only the remainder - a short of " +
                                "${unused.minOf { d[it] }}s would have fit", sp.length < unused.minOf { d[it] })
                        }
                    }
                    val noProgrammes = pool.none { d[it] >= SHORT }
                    if (sp.index >= 0 && noProgrammes) {
                        // A channel of nothing but shorts: the card names the short that follows.
                        val card = s.at(sp.start) as OnAir.Card
                        val next = s.at(card.nextAt)
                        assertTrue("$ctx: a shorts-only card names what follows: $next",
                            (next is OnAir.TopUp && next.index == sp.index && next.start == card.nextAt) ||
                                (next is OnAir.Programme && next.index == sp.index && next.slotStart == card.nextAt))
                    } else if (sp.index >= 0) {
                        val card = s.at(sp.start) as OnAir.Card
                        val next = s.at(card.nextAt)
                        assertTrue("$ctx: nextAt ${card.nextAt} is at or after the card", card.nextAt >= sp.end)
                        assertTrue("$ctx: the card announces what is on at nextAt: $next",
                            next is OnAir.Programme && next.index == sp.index && next.slotStart == card.nextAt)
                        if (cardNamesAStart) {
                            assertEquals("$ctx: the announced programme STARTS at nextAt",
                                0.0, (next as OnAir.Programme).offsetSeconds, 0.0)
                        }
                    }
                }
            }
            if (i > 0 && sp.kind == 'T') {
                val prev = spans[i - 1]
                assertFalse("$ctx: a top-up follows a card", prev.kind == 'C' &&
                    Math.floorDiv(local(prev.start, g.zone), SLOT) == Math.floorDiv(ls, SLOT))
            }
        }
    }

    /** Programmes in a part, day after day, follow the pool's list order; carries continue. */
    private fun checkEpisodeOrder(g: Gen, spans: List<Span>) {
        for (part in listOf("breakfast", "afternoon", "prime", "late")) {
            val pool = pool(g.durations, g.parts, part)
            val programmes = pool.filter { g.durations[it] >= SHORT }
            if (programmes.isEmpty()) continue
            val inPart = spans.filter { it.kind == 'P' && partAt(local(it.start, g.zone)) == part }
            for ((a, b) in inPart.zipWithNext()) {
                if (b.offsetAtStart > 0) {
                    assertEquals("$g $part: a carried programme continues the one before: $a -> $b", a.index, b.index)
                    assertEquals("$g $part: carried content picks up where it stopped: $a -> $b",
                        a.offsetAtStart + a.length, b.offsetAtStart, 0.0)
                } else {
                    val expected = programmes[(programmes.indexOf(a.index) + 1) % programmes.size]
                    assertEquals("$g $part: episode order broken: $a -> $b", expected, b.index)
                }
            }
        }
    }

    @Test
    fun `500 random channels hold every invariant across two whole days`() {
        for (seed in 1..500) {
            val g = gen(seed)
            val rnd = Random(seed * 31 + 7)
            val s = g.build()
            val date = LocalDate.of(2020, 1, 1).plusDays(rnd.nextLong(0, 3650))
            val from = dayStart(date, g.zone)
            val to = dayStart(date.plusDays(2), g.zone)
            if (g.durations.none { it > 0 }) {
                assertEquals("$g: nothing playable, nothing on air", null, s.at(from))
                continue
            }
            val spans = walk(s, from, to, g.toString())
            checkSpans(g, s, spans, rnd)
            checkEpisodeOrder(g, spans)
        }
    }

    @Test
    fun `an up next card names a programme that starts at nextAt, not one carried over half-way through`() {
        for (seed in 1..500) {
            val g = gen(seed)
            if (g.durations.none { it > 0 }) continue
            val rnd = Random(seed * 31 + 7)
            val s = g.build()
            val date = LocalDate.of(2020, 1, 1).plusDays(rnd.nextLong(0, 3650))
            val spans = walk(s, dayStart(date, g.zone), dayStart(date.plusDays(2), g.zone), g.toString())
            checkSpans(g, s, spans, rnd, cardNamesAStart = true)
        }
    }

    @Test
    fun `two instances built independently and asked in different orders agree`() {
        for (seed in 1000..1060) {
            val g = gen(seed)
            if (g.durations.none { it > 0 }) continue
            val rnd = Random(seed)
            val base = dayStart(LocalDate.of(2026, 9, 23), g.zone)
            val instants = List(400) { base + rnd.nextLong(-40L * 86_400, 40L * 86_400) }
            val a = g.build()
            val forward = instants.map { a.at(it) }
            // A second instance: parts lists copied into fresh collections, questions shuffled,
            // with a warm-up that fills its caches with unrelated days first.
            val b = HalfHourSchedule(g.number, ArrayList(g.durations), g.parts.map { ArrayList(it) }, g.zone)
            repeat(50) { b.at(base + rnd.nextLong(-400L * 86_400, 400L * 86_400)) }
            val order = instants.indices.shuffled(rnd)
            val shuffled = arrayOfNulls<OnAir>(instants.size)
            for (i in order) shuffled[i] = b.at(instants[i])
            for (i in instants.indices) {
                assertEquals("$g at ${instants[i]}", forward[i], shuffled[i])
                assertEquals("$g upNext at ${instants[i]}", a.upNext(instants[i]), b.upNext(instants[i]))
            }
        }
    }

    @Test
    fun `the same instant asked twice, far apart in a busy session, gets the same answer`() {
        val g = gen(4242, zone = ZoneOffset.UTC).copy(durations = List(300) { 200 + it * 37 % 4000 })
        val s = HalfHourSchedule(g.number, g.durations, List(300) { emptyList() }, ZoneOffset.UTC)
        val probe = 1_790_191_234L
        val first = s.at(probe)
        val rnd = Random(9)
        repeat(2000) { s.at(probe + rnd.nextLong(-10_000_000, 10_000_000)) }
        assertEquals(first, s.at(probe))
    }

    // --- a straight-line replay from the epoch --------------------------------------------------

    private data class St(val next: Int, val carry: Int, val carried: Int)
    private data class Blk(val pos: Int, val startSlot: Int, val slots: Int, val baseSlots: Int)

    /** The spec's packing, replayed day by day from broadcast day 0 with no shortcuts. */
    private fun place(k: IntArray, partSlots: Int, st: St): Pair<List<Blk>, St> {
        val blocks = ArrayList<Blk>()
        var slot = 0
        if (st.carry >= 0) {
            val remaining = k[st.carry] - st.carried
            if (remaining > partSlots) {
                return listOf(Blk(st.carry, 0, partSlots, st.carried)) to St(st.next, st.carry, st.carried + partSlots)
            }
            blocks += Blk(st.carry, 0, remaining, st.carried)
            slot = remaining
        }
        var next = st.next
        while (slot < partSlots) {
            if (slot + k[next] <= partSlots) {
                blocks += Blk(next, slot, k[next], 0)
                slot += k[next]
                next = (next + 1) % k.size
            } else if (k[next] > partSlots && slot == 0) {
                blocks += Blk(next, 0, partSlots, 0)
                return blocks to St((next + 1) % k.size, next, partSlots)
            } else {
                break
            }
        }
        return blocks to St(next, -1, 0)
    }

    @Test
    fun `lookups agree with a straight-line replay of every part-day since the epoch`() {
        val firstSlots = mapOf("late" to 0, "breakfast" to 14, "afternoon" to 26, "prime" to 38)
        for (seed in 2000..2040) {
            val g = gen(seed, maxClips = 120, zone = ZoneOffset.UTC)
            val s = g.build()
            val rnd = Random(seed)
            val checkDays = setOf(0L, 1L, 2L, 3L, 20_000L + rnd.nextInt(800), 47_000L + rnd.nextInt(100))
            for ((part, firstSlot) in firstSlots) {
                val programmes = pool(g.durations, g.parts, part).filter { g.durations[it] >= SHORT }
                if (programmes.isEmpty()) continue
                val k = IntArray(programmes.size) { (g.durations[programmes[it]] + 1799) / 1800 }
                var st = St(0, -1, 0)
                for (day in 0L..checkDays.max()) {
                    val (blocks, after) = place(k, partSlots(part), st)
                    if (day in checkDays) {
                        val partStart = (day * 48 - 2 + firstSlot) * SLOT
                        val covered = HashSet<Int>()
                        for (b in blocks) {
                            val at = partStart + b.startSlot * SLOT
                            val r = s.at(at)
                            val ctx = "$g $part day $day block $b"
                            assertTrue("$ctx: expected a programme, got $r", r is OnAir.Programme)
                            r as OnAir.Programme
                            assertEquals(ctx, programmes[b.pos], r.index)
                            assertEquals(ctx, (b.baseSlots * SLOT).toDouble(), r.offsetSeconds, 0.0)
                            assertEquals(ctx, at, r.slotStart)
                            assertEquals(ctx, at + b.slots * SLOT, r.slotEnd)
                            for (x in b.startSlot until b.startSlot + b.slots) covered += x
                        }
                        for (x in 0 until partSlots(part)) {
                            if (x in covered) continue
                            val r = s.at(partStart + x * SLOT)
                            assertFalse("$g $part day $day slot $x: replay has no programme here, got $r",
                                r is OnAir.Programme)
                        }
                    }
                    st = after
                }
            }
        }
    }
}
