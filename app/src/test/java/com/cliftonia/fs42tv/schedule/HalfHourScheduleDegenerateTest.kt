package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.schedule.HalfHourSchedule.OnAir
import com.cliftonia.fs42tv.schedule.ScheduleProbe.SLOT
import com.cliftonia.fs42tv.schedule.ScheduleProbe.span
import com.cliftonia.fs42tv.schedule.ScheduleProbe.walk
import com.cliftonia.fs42tv.schedule.ScheduleProbe.within
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * Channels nobody would curate on purpose, but a sideloaded lineup can still carry: empty, one
 * clip, all shorts, all long, lengths exactly on the thresholds, zero and negative durations,
 * odd tags - and lengths so large they are garbage. Plus the performance guard.
 */
class HalfHourScheduleDegenerateTest {

    private val utc: ZoneId = ZoneOffset.UTC

    /** 2026-09-23 00:00Z. */
    private val day = 1_790_121_600L

    private fun schedule(vararg d: Int, parts: List<List<String>> = d.map { emptyList() }, zone: ZoneId = utc) =
        HalfHourSchedule(9, d.toList(), parts, zone)

    private fun spansOfDay(s: HalfHourSchedule) = walk(s, day, day + 86_400)

    @Test
    fun `no clips - nothing on air, no next, no crash`() {
        val s = schedule()
        assertNull(s.at(day))
        assertNull(s.upNext(day))
    }

    @Test
    fun `only zero and negative durations - nothing on air`() {
        val s = schedule(0, -1, -1800, Int.MIN_VALUE)
        assertNull(s.at(day))
        assertNull(s.upNext(day))
    }

    @Test
    fun `zero and negative durations are never on air among playable clips`() {
        val s = schedule(0, 1500, -60, 120, 0)
        spansOfDay(s).forEach { assertTrue("$it", it.kind == 'C' || it.index == 1 || it.index == 3) }
    }

    @Test
    fun `one programme - back to back, with a card only for a short gap or a part's end`() {
        // Amended gap rule: 1600s left after it is too long for a card, and nothing else fills
        // it, so it starts again at once. A card is ten minutes or less - or the tail of a part,
        // which it may not cross.
        val spans = spansOfDay(schedule(2000))
        assertTrue(spans.all { it.kind == 'P' || it.kind == 'C' })
        spans.filter { it.kind == 'P' }.forEach { assertEquals(2000L, it.length) }
        spans.filter { it.kind == 'C' }.forEach {
            val partEnd = Math.floorMod(it.end, 86_400L) in listOf(6L * 3600, 12L * 3600, 18L * 3600, 23L * 3600)
            assertTrue("$it", it.length <= 600 || partEnd)
        }
    }

    @Test
    fun `one short - every slot is that short then a card`() {
        val spans = spansOfDay(schedule(100))
        assertEquals(48 * 2, spans.size)
        for ((a, b) in spans.chunked(2).map { it[0] to it[1] }) {
            assertEquals('T', a.kind)
            assertEquals(0L, Math.floorMod(a.start, SLOT))
            assertEquals('C', b.kind)
            assertEquals(1700L, b.length)
        }
    }

    @Test
    fun `all shorts - no programmes, each slot is filled largest-first then a card`() {
        val d = intArrayOf(290, 10, 100, 250, 5, 299, 120, 60)
        val spans = spansOfDay(schedule(*d))
        assertTrue(spans.none { it.kind == 'P' })
        // Every short fits in a slot: 1134s of shorts, then 666s of card, every slot.
        val bySlot = spans.groupBy { Math.floorDiv(it.start, SLOT) }
        assertEquals(48, bySlot.size)
        for ((slot, items) in bySlot) {
            assertEquals("slot $slot", d.size, items.count { it.kind == 'T' })
            assertEquals("slot $slot", 666L, items.single { it.kind == 'C' }.length)
            val minutes = items.filter { it.kind == 'T' }.map { d[it.index] / 60 }
            assertEquals("slot $slot is largest first to the minute", minutes.sortedDescending(), minutes)
        }
    }

    @Test
    fun `all programmes longer than a slot - every one ends in a card, no top-ups`() {
        val spans = spansOfDay(schedule(1801, 2500, 3599, 3601, 5000))
        // Nothing fits a gap after a programme; only a part's deferred tail - no programme to
        // come before the part ends - is filled, as one stretch, with another of them.
        for (t in spans.filter { it.kind == 'T' }) {
            val partEnd = ScheduleProbe.partStartLocal(t.start) +
                ScheduleProbe.partSlots(ScheduleProbe.partAt(t.start)) * SLOT
            assertTrue("$t is not in a deferred tail",
                spans.none { it.kind == 'P' && it.offsetAtStart == 0.0 && it.start in t.end until partEnd })
        }
        // Nothing fits a gap under 1801s, and every gap here is over ten minutes: the next
        // programme follows at once (the amended rule) - on a boundary or straight after another.
        for ((a, b) in spans.zipWithNext()) {
            if (b.kind == 'P') assertTrue("$b", Math.floorMod(b.start, SLOT) == 0L || a.kind == 'P')
        }
    }

    @Test
    fun `every clip exactly 1800s - wall to wall programmes, never a card`() {
        val spans = spansOfDay(schedule(1800, 1800, 1800))
        assertEquals(48, spans.size)
        assertTrue(spans.all { it.kind == 'P' && it.length == 1800L })
    }

    @Test
    fun `exactly 300s is a programme, 299s is a short`() {
        val spans = spansOfDay(schedule(300, 299))
        // Each slot: 300 + 299 leaves 1201s - too long for a card, so the programme again at once
        // (the amended rule): 300, 299, 300, 299, 300, 299, then a 3s card.
        assertTrue(spans.filter { it.kind == 'P' }.all { it.index == 0 && it.length == 300L })
        assertTrue(spans.filter { it.kind == 'T' }.all { it.index == 1 })
        assertTrue(spans.filter { it.kind == 'C' }.all { it.length == 3L })
        assertEquals(48 * 7, spans.size)
    }

    @Test
    fun `a part with no tagged clips draws from all of them`() {
        val s = schedule(1500, 1500, 200, parts = listOf(listOf("prime"), emptyList(), emptyList()))
        val breakfast = walk(s, day + 6 * 3600, day + 12 * 3600).filter { it.kind == 'P' }.map { it.index }.toSet()
        assertEquals(setOf(0, 1), breakfast)
        val prime = walk(s, day + 18 * 3600, day + 23 * 3600).filter { it.kind != 'C' }.map { it.index }.toSet()
        assertEquals(setOf(0), prime)
    }

    @Test
    fun `unknown part names are no tags at all`() {
        val s = schedule(1500, 1500, parts = listOf(listOf("Prime", "brunch"), listOf("PRIME")))
        val prime = walk(s, day + 18 * 3600, day + 23 * 3600).filter { it.kind == 'P' }.map { it.index }.toSet()
        assertEquals(setOf(0, 1), prime)
    }

    @Test
    fun `all clips tagged to one part - the others still get the whole channel`() {
        val tags = List(6) { listOf("late") }
        val s = schedule(1500, 1600, 1700, 120, 90, 60, parts = tags)
        for (h in listOf(0, 7, 13, 19, 23)) {
            val spans = walk(s, day + h * 3600, day + h * 3600 + 3600)
            assertTrue(spans.isNotEmpty())
        }
    }

    @Test
    fun `a card before a part that opens mid-way through a carried programme names a programme that starts`() {
        // Minimal repro. One 7h programme and a 100s short, untagged, UTC. Afternoon (12 slots)
        // and prime (10) both carry the 7h programme across days; a card at the end of an
        // afternoon slot names "next: <programme> at 18:00" - but at 18:00 prime opens 5h into it.
        val s = schedule(25_200, 100)
        for (d in 0L until 4) {
            val cardAt = day + d * 86_400 + 18 * 3600 - 1
            val card = s.at(cardAt)
            if (card !is OnAir.Card) continue
            val next = s.at(card.nextAt) as OnAir.Programme
            assertEquals("day $d: the card at 17:59:59 announces $next at ${card.nextAt} as a programme starting",
                0.0, next.offsetSeconds, 0.0)
        }
    }

    @Test
    fun `Int MAX duration does not hang, crash, or return garbage`() {
        val s = within(10, "building with an Int.MAX_VALUE clip") { schedule(Int.MAX_VALUE, 1500, 120) }
        within(10, "looking up with an Int.MAX_VALUE clip") {
            val r = s.at(day)
            if (r != null) {
                val sp = span(r, day)
                assertTrue("$r must contain the instant", sp.start <= day && day < sp.end)
                if (r is OnAir.Programme) assertTrue("$r within its length", r.offsetSeconds >= 0)
            }
            walk(s, day, day + 86_400, "Int.MAX")
        }
    }

    @Test
    fun `garbage-long clips - a lineup of 400 month-long clips builds in bounded time`() {
        val d = IntArray(400) { 30 * 86_400 + it }
        within(5, "building 400 month-long clips") {
            val s = schedule(*d)
            walk(s, day, day + 86_400, "month-long")
        }
    }

    // --- performance -----------------------------------------------------------------------------

    private fun busyChannel(seed: Int): Pair<List<Int>, List<List<String>>> {
        val rnd = Random(seed)
        val keys = listOf("breakfast", "afternoon", "prime", "late")
        val d = List(400) { if (rnd.nextInt(4) == 0) rnd.nextInt(10, 300) else rnd.nextInt(300, 7200) }
        val p = List(400) { keys.filter { rnd.nextBoolean() } }
        return d to p
    }

    @Test
    fun `a 400 clip channel with parts builds in under 200ms`() {
        // Warm the JIT on a different channel first; the bound is for a build, not class loading.
        repeat(3) { val (d, p) = busyChannel(100 + it); HalfHourSchedule(1, d, p, utc).at(day) }
        val (d, p) = busyChannel(1)
        val start = System.nanoTime()
        HalfHourSchedule(1, d, p, ZoneId.of("America/New_York"))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("build took ${ms}ms", ms < 200)
    }

    @Test
    fun `10k lookups of any instant take under 200ms`() {
        val (d, p) = busyChannel(2)
        val s = HalfHourSchedule(1, d, p, ZoneId.of("America/New_York"))
        val rnd = Random(3)
        repeat(2000) { s.at(day + rnd.nextLong(-3650L * 86_400, 3650L * 86_400)) }
        val instants = LongArray(10_000) { day + rnd.nextLong(-3650L * 86_400, 3650L * 86_400) }
        val start = System.nanoTime()
        for (t in instants) s.at(t)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("10k random lookups took ${ms}ms", ms < 200)
    }

    @Test
    fun `10k lookups within one evening take under 200ms`() {
        val (d, p) = busyChannel(2)
        val s = HalfHourSchedule(1, d, p, ZoneId.of("America/New_York"))
        val rnd = Random(4)
        repeat(2000) { s.at(day + rnd.nextLong(86_400)) }
        val start = System.nanoTime()
        repeat(10_000) { s.at(day + 18 * 3600 + rnd.nextLong(5 * 3600)) }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("10k lookups in one evening took ${ms}ms", ms < 200)
    }

    @Test
    fun `lookups in 2100 are no slower than lookups today`() {
        val (d, p) = busyChannel(5)
        val s = HalfHourSchedule(1, d, p, utc)
        val y2100 = 4_115_059_200L // 2100-05-26
        val rnd = Random(6)
        fun time(base: Long): Long {
            val start = System.nanoTime()
            repeat(5000) { s.at(base + rnd.nextLong(0, 7 * 86_400)) }
            return (System.nanoTime() - start) / 1_000_000
        }
        time(day); time(y2100) // warm
        val today = time(day)
        val future = time(y2100)
        assertTrue("2100 took ${future}ms against ${today}ms today", future <= today * 3 + 50)
    }
}
