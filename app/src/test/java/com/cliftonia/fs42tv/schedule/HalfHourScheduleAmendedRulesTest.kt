package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.schedule.HalfHourSchedule.OnAir
import com.cliftonia.fs42tv.schedule.PartPacker.Companion.CARD_CAP
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
import com.cliftonia.fs42tv.schedule.ScheduleProbe.within
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.tune.Tuner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * The amended gap rules, attacked: fillers from any clip (ordered channels: next episodes, then
 * shorts), no card over ten minutes outside a part's deferred tail, programmes starting off the
 * half hour, part-end cuts, the cycle anchored in 1870 - checked over many seeded channels.
 */
class HalfHourScheduleAmendedRulesTest {

    data class Gen(
        val seed: Int,
        val number: Int,
        val durations: List<Int>,
        val parts: List<List<String>>,
        val zone: ZoneId,
        val ordered: Boolean,
    ) {
        fun build(zone: ZoneId = this.zone) = HalfHourSchedule(number, durations, parts, zone, ordered)
        override fun toString() = "Gen(seed=$seed, n=${durations.size}, zone=$zone, ordered=$ordered)"
    }

    private val zones: List<ZoneId> = listOf(
        ZoneOffset.UTC, ZoneOffset.ofHoursMinutes(5, 30), ZoneOffset.ofHoursMinutes(5, 45),
        ZoneOffset.ofHoursMinutes(-3, -30), ZoneOffset.ofHours(14), ZoneOffset.ofHours(-12),
    )
    private val keys = listOf("breakfast", "afternoon", "prime", "late")

    private fun duration(rnd: Random): Int = when (rnd.nextInt(100)) {
        in 0 until 30 -> rnd.nextInt(1, SHORT)
        in 30 until 75 -> rnd.nextInt(SHORT, 3601)
        in 75 until 92 -> rnd.nextInt(3601, 21_601)
        else -> listOf(1, 299, 300, 301, 599, 600, 601, 1199, 1200, 1201, 1799, 1800, 1801, 18_000, 21_600).random(rnd)
    }

    private fun gen(seed: Int, maxClips: Int = 400, ordered: Boolean? = null, zone: ZoneId? = null): Gen {
        val rnd = Random(seed * 7919)
        val n = rnd.nextInt(0, maxClips + 1)
        val durations = List(n) { duration(rnd) }
        val tagged = rnd.nextInt(3)
        val parts = List(n) {
            when (tagged) {
                0 -> emptyList()
                1 -> keys.filter { rnd.nextInt(3) == 0 }
                else -> keys.filter { rnd.nextInt(5) == 0 }
            }
        }
        return Gen(seed, rnd.nextInt(1, 1000), durations, parts, zone ?: zones.random(rnd), ordered ?: rnd.nextBoolean())
    }

    private fun dayStart(date: LocalDate, zone: ZoneId): Long =
        ZonedDateTime.of(date, LocalTime.MIDNIGHT, zone).toEpochSecond()

    /** Spans of one channel over two days, from a seeded date. */
    private fun twoDays(g: Gen, s: HalfHourSchedule = g.build()): List<Span> {
        val date = LocalDate.of(2021, 1, 1).plusDays(Random(g.seed).nextLong(0, 3000))
        return walk(s, dayStart(date, g.zone), dayStart(date.plusDays(2), g.zone), g.toString())
    }

    private fun partKey(g: Gen, sp: Span): Pair<String, Long> {
        val ls = local(sp.start, g.zone)
        return partAt(ls) to partStartLocal(ls)
    }

    private fun partEndLocal(g: Gen, sp: Span): Long {
        val (part, start) = partKey(g, sp)
        return start + partSlots(part) * SLOT
    }

    // --- the ten-minute cap and the remainder -----------------------------------------------------

    @Test
    fun `no card over ten minutes unless nothing but fillers and cards follow it to the part's end`() {
        for (seed in 1..400) {
            val g = gen(seed)
            if (g.durations.none { it > 0 }) continue
            val spans = twoDays(g)
            for ((i, sp) in spans.withIndex()) {
                if (sp.kind != 'C' || sp.length <= CARD_CAP) continue
                val key = partKey(g, sp)
                val rest = spans.drop(i + 1).takeWhile { partKey(g, it) == key }
                assertTrue("$g: card $sp is over ten minutes, and a programme follows it in its part: " +
                    "${rest.firstOrNull { it.kind == 'P' }}", rest.none { it.kind == 'P' })
            }
        }
    }

    @Test
    fun `a card between programmes is only the remainder - no unused clip of the pool would have fit`() {
        for (seed in 1..400) {
            val g = gen(seed)
            if (g.durations.none { it > 0 }) continue
            val d = g.durations
            val spans = twoDays(g)
            for ((i, sp) in spans.withIndex()) {
                if (sp.kind != 'C' || i == 0 || i + 1 >= spans.size) continue
                val next = spans[i + 1]
                if (next.kind != 'P' || next.offsetAtStart != 0.0 || partKey(g, next) != partKey(g, sp)) continue
                val prevAt = (i - 1 downTo 0).firstOrNull { spans[it].kind == 'P' } ?: continue
                val previous = spans[prevAt]
                if (partKey(g, previous) != partKey(g, sp)) continue
                val gap = spans.subList(prevAt + 1, i)
                val used = gap.map { it.index }.toSet()
                val pool = pool(d, g.parts, partAt(local(sp.start, g.zone)))
                val candidates = pool.filter { it !in used && it != previous.index && it != next.index }
                    .filter { !g.ordered || d[it] < SHORT }
                val fits = candidates.filter { d[it] <= sp.length }
                assertTrue("$g: card $sp after $previous with fillers $gap, but ${fits.map { it to d[it] }} would fit",
                    fits.isEmpty())
                if (g.ordered) {
                    assertTrue("$g: the next episode ${next.index} (${d[next.index]}s) would have fit the ${sp.length}s card",
                        d[next.index] > sp.length)
                }
            }
        }
    }

    @Test
    fun `a programme off the half hour starts exactly where a clip ended, and only when the gap was over ten minutes`() {
        for (seed in 1..400) {
            val g = gen(seed)
            if (g.durations.none { it > 0 }) continue
            val spans = twoDays(g)
            for ((i, sp) in spans.withIndex()) {
                if (i == 0 || sp.kind != 'P') continue
                val ls = local(sp.start, g.zone)
                if (Math.floorMod(ls, SLOT) == 0L) continue
                val prev = spans[i - 1]
                assertTrue("$g: $sp off the half hour after $prev", prev.kind != 'C' && prev.end == sp.start)
                val boundary = (Math.floorDiv(ls, SLOT) + 1) * SLOT
                val chained = g.ordered && local(sp.end, g.zone) <= boundary && sp.length == g.durations[sp.index].toLong()
                assertTrue("$g: $sp started early with only ${boundary - ls}s to the half hour",
                    chained || boundary - ls > CARD_CAP)
                assertEquals("$g: $sp contiguous with what precedes it", span(g.build().at(sp.start - 1)!!, sp.start - 1).end,
                    sp.start)
            }
        }
    }

    // --- fillers -----------------------------------------------------------------------------------

    @Test
    fun `a filler is never the programme just shown nor the one due next`() {
        for (seed in 1..400) {
            val g = gen(seed)
            if (g.durations.none { it > 0 }) continue
            val spans = twoDays(g)
            for ((i, sp) in spans.withIndex()) {
                if (sp.kind != 'T') continue
                val key = partKey(g, sp)
                val previous = (i - 1 downTo 0).map { spans[it] }.takeWhile { partKey(g, it) == key }
                    .firstOrNull { it.kind == 'P' }
                val next = spans.drop(i + 1).takeWhile { partKey(g, it) == key }.firstOrNull { it.kind == 'P' }
                if (previous != null) {
                    assertTrue("$g: filler $sp repeats the programme just shown $previous", sp.index != previous.index)
                }
                if (next != null && next.offsetAtStart == 0.0) {
                    assertTrue("$g: filler $sp is the programme due next $next", sp.index != next.index)
                }
            }
        }
    }

    @Test
    fun `an ordered channel fills only with shorts and never airs an episode out of list order`() {
        for (seed in 1..300) {
            val g = gen(seed, ordered = true)
            if (g.durations.none { it > 0 }) continue
            val d = g.durations
            val s = g.build()
            val date = LocalDate.of(2021, 1, 1).plusDays(Random(seed).nextLong(0, 3000))
            val spans = walk(s, dayStart(date, g.zone), dayStart(date.plusDays(4), g.zone), g.toString())
            spans.filter { it.kind == 'T' }.forEach { assertTrue("$g: $it is an episode used as a filler", d[it.index] < SHORT) }
            for (part in keys) {
                val programmes = pool(d, g.parts, part).filter { d[it] >= SHORT }
                if (programmes.isEmpty()) continue
                val inPart = spans.filter { it.kind == 'P' && partAt(local(it.start, g.zone)) == part }
                for ((a, b) in inPart.zipWithNext()) {
                    if (b.offsetAtStart > 0) {
                        assertEquals("$g $part: continuation $a -> $b", a.index, b.index)
                        assertEquals("$g $part: continuation picks up $a -> $b", a.offsetAtStart + a.length, b.offsetAtStart, 0.0)
                    } else {
                        val expected = programmes[(programmes.indexOf(a.index) + 1) % programmes.size]
                        assertEquals("$g $part: out of order $a -> $b", expected, b.index)
                    }
                }
            }
        }
    }

    // --- part-end cut ------------------------------------------------------------------------------

    @Test
    fun `a programme cut at its part's end reports it - endsAt is the part end and cut is set`() {
        for (seed in 1..300) {
            val g = gen(seed)
            if (g.durations.none { it > 0 }) continue
            val s = g.build()
            for (sp in twoDays(g, s).filter { it.kind == 'P' }) {
                val p = s.at(sp.start) as OnAir.Programme
                val contentLeft = g.durations[sp.index] - sp.offsetAtStart
                val cut = sp.length < contentLeft
                assertEquals("$g: $sp cut flag", cut, p.cut)
                if (cut) {
                    assertEquals("$g: $sp is cut, so it ends at its part's end", partEndLocal(g, sp), local(p.endsAt, g.zone))
                } else {
                    assertEquals("$g: $sp plays its content out", contentLeft, sp.length.toDouble(), 0.0)
                }
            }
        }
    }

    @Test
    fun `the tuner is told when to cut a programme longer than its part`() {
        // 7h programme in a 5h prime: cut at 23:00.
        val ch = Channel(1, "Long", "youtube", "clock", listOf(
            Stream(id = "a".padEnd(11, 'x'), url = "u", duration = 25_200, parts = listOf("prime")),
            Stream(id = "b".padEnd(11, 'x'), url = "u", duration = 1500, parts = listOf("prime")),
        ))
        val tt = Timetable(skipsOn = { false }, halfHourOn = { true }, zone = { ZoneOffset.UTC })
        val base = 1_790_121_600L + 18 * 3600 // 2026-09-23 18:00Z
        for (d in 0L until 4) {
            val t = base + d * 86_400 + 60
            val tuned = Tuner.tune(ch, null, t, timetable = tt)!!
            val on = tt.at(ch, t) as Timetable.OnAir.Clip
            if (on.index == 0 && on.endsAt == base + d * 86_400 + 5 * 3600) {
                assertEquals("day $d: cut at 23:00", on.endsAt, tuned.cutAt)
            }
            if (tuned.cutAt != null) assertEquals(base + d * 86_400 + 5 * 3600, tuned.cutAt)
        }
    }

    // --- determinism ------------------------------------------------------------------------------

    @Test
    fun `the same lineup gives the same wall-clock day in every fixed-offset zone`() {
        for (seed in 500..560) {
            val g = gen(seed, maxClips = 150, zone = ZoneOffset.UTC)
            if (g.durations.none { it > 0 }) continue
            val utc = g.build(ZoneOffset.UTC)
            val rnd = Random(seed)
            for (zone in zones.drop(1)) {
                val other = g.build(zone)
                val offset = (zone as ZoneOffset).totalSeconds
                repeat(60) {
                    val t = 1_790_121_600L + rnd.nextLong(-400L * 86_400, 400L * 86_400)
                    val a = utc.at(t)!!
                    val b = other.at(t - offset)!!
                    assertEquals("$g: the same wall time in $zone", key(a), key(b))
                }
            }
        }
    }

    private fun key(r: OnAir): Triple<Char, Int, Double> = when (r) {
        is OnAir.Programme -> Triple('P', r.index, r.offsetSeconds)
        is OnAir.TopUp -> Triple('T', r.index, r.offsetSeconds)
        is OnAir.Card -> Triple('C', r.nextIndex, 0.0)
    }

    @Test
    fun `fresh instances agree, asked in any order, ordered or not`() {
        for (seed in 600..640) {
            val g = gen(seed)
            if (g.durations.none { it > 0 }) continue
            val rnd = Random(seed)
            val instants = List(300) { 1_790_121_600L + rnd.nextLong(-3000L * 86_400, 3000L * 86_400) }
            val a = g.build()
            val b = HalfHourSchedule(g.number, g.durations.toMutableList(), g.parts.map { it.toMutableList() }, g.zone, g.ordered)
            val answers = instants.map { a.at(it) }
            for (i in instants.indices.shuffled(rnd)) assertEquals("$g at ${instants[i]}", answers[i], b.at(instants[i]))
        }
    }

    // --- the anchored cycle -------------------------------------------------------------------------

    @Test
    fun `every prime from the 1870 anchor to 2030 continues the one before it`() {
        val lineups = listOf(
            listOf(3600, 16_200, 14_400),
            listOf(25_200, 1500, 100, 200),
            listOf(1500, 2000, 700, 4000, 250, 90, 5400, 30_000),
        )
        for ((n, d) in lineups.withIndex()) {
            for (ordered in listOf(false, true)) {
                val s = HalfHourSchedule(n + 1, d, d.map { emptyList() }, ZoneOffset.UTC, ordered)
                val programmes = d.indices.filter { d[it] >= SHORT }
                var last: Span? = null
                val endDay = 22_000L // 2030
                var day = HalfHourSchedule.ANCHOR_DAY
                while (day < endDay) {
                    val start = (day * 48 - 2 + 38) * SLOT
                    val spans = walkFast(s, start, start + 10 * SLOT)
                    for (sp in spans) {
                        val a = last
                        if (a != null) {
                            if (sp.offsetAtStart > 0) {
                                assertEquals("lineup $n ordered=$ordered day $day: continuation", a.index, sp.index)
                            } else {
                                assertEquals("lineup $n ordered=$ordered day $day: $a -> $sp",
                                    programmes[(programmes.indexOf(a.index) + 1) % programmes.size], sp.index)
                            }
                        }
                        last = sp
                    }
                    day++
                }
            }
        }
    }

    /** Programme spans only, stepping by endsAt - cheaper than a full walk, for 57,000 days. */
    private fun walkFast(s: HalfHourSchedule, from: Long, to: Long): List<Span> {
        val out = ArrayList<Span>()
        var t = from
        while (t < to) {
            val r = s.at(t)!!
            val sp = span(r, t)
            if (sp.kind == 'P') out += sp
            t = sp.end
        }
        return out
    }

    @Test
    fun `2100 lookups are as fast as today, ordered or not`() {
        for (ordered in listOf(false, true)) {
            val g = gen(4242, ordered = ordered).copy(durations = List(400) { Random(it).nextInt(1, 7200) })
            val s = g.build()
            val rnd = Random(1)
            fun time(base: Long): Long {
                val start = System.nanoTime()
                repeat(10_000) { s.at(base + rnd.nextLong(0, 30L * 86_400)) }
                return (System.nanoTime() - start) / 1_000_000
            }
            time(1_790_121_600L); time(4_115_059_200L)
            val today = time(1_790_121_600L)
            val future = time(4_115_059_200L)
            assertTrue("ordered=$ordered: 2100 took ${future}ms against ${today}ms", future <= today * 3 + 30)
            assertTrue("ordered=$ordered: 10k lookups took ${today}ms", today < 200)
        }
    }

    @Test
    fun `builds stay bounded - 400 clips of every shape, and 400 week-long clips`() {
        repeat(3) { gen(9000 + it).build() } // warm
        for (seed in 1..20) {
            val g = gen(seed * 13, maxClips = 400)
            val start = System.nanoTime()
            g.build()
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue("$g built in ${ms}ms", ms < 200)
        }
        within(10, "400 week-long clips") {
            val d = List(400) { 7 * 86_400 - it }
            val s = HalfHourSchedule(1, d, d.map { emptyList() }, ZoneOffset.UTC, ordered = true)
            walk(s, 1_790_121_600L, 1_790_121_600L + 2 * 86_400, "week-long")
        }
    }

    // --- DST and garbage clocks under the amended rules --------------------------------------------

    private val dstDays = listOf(
        ZoneId.of("America/New_York") to "2026-03-08", ZoneId.of("America/New_York") to "2026-11-01",
        ZoneId.of("Australia/Sydney") to "2026-04-05", ZoneId.of("Australia/Sydney") to "2026-10-04",
        ZoneId.of("Australia/Brisbane") to "2026-10-04", ZoneId.of("Asia/Kathmandu") to "2026-10-04",
    )

    @Test
    fun `DST nights walk with no gap or overlap, ordered and not`() {
        for (seed in 1..120) {
            val (zone, date) = dstDays[seed % dstDays.size]
            val g = gen(seed, maxClips = 120, zone = zone)
            if (g.durations.none { it > 0 }) continue
            val s = g.build()
            val from = ZonedDateTime.of(LocalDate.parse(date), LocalTime.MIDNIGHT, zone).toEpochSecond() - 3600
            val spans = walk(s, from, from + 28 * 3600, "$g $date")
            for (sp in spans.filter { it.kind == 'P' }) {
                val p = s.at(sp.start) as OnAir.Programme
                assertTrue("$g $date: $sp offset within content", p.offsetSeconds >= 0 && p.offsetSeconds < g.durations[sp.index])
                assertTrue("$g $date: $sp never plays past its content",
                    sp.offsetAtStart + sp.length <= g.durations[sp.index])
            }
        }
    }

    @Test
    fun `a card on a DST night announces the programme that is really on at nextAt`() {
        for (seed in 1..120) {
            val (zone, date) = dstDays[seed % dstDays.size]
            val g = gen(seed, maxClips = 120, zone = zone)
            if (g.durations.none { it >= SHORT }) continue
            val s = g.build()
            val from = ZonedDateTime.of(LocalDate.parse(date), LocalTime.MIDNIGHT, zone).toEpochSecond() - 3600
            for (sp in walk(s, from, from + 28 * 3600, "$g $date").filter { it.kind == 'C' }) {
                val card = s.at(sp.start) as OnAir.Card
                if (card.nextIndex < 0) continue
                val next = s.at(card.nextAt)
                assertTrue("$g $date: $card announces ${card.nextIndex} at ${card.nextAt}, but $next is on",
                    next is OnAir.Programme && next.index == card.nextIndex)
            }
        }
    }

    @Test
    fun `garbage clocks do not throw or hang, ordered or not`() {
        for (ordered in listOf(false, true)) {
            val d = listOf(1500, 5400, 240, 2400, 90, 7200)
            val s = HalfHourSchedule(1, d, d.map { emptyList() }, ZoneId.of("America/New_York"), ordered)
            within(10, "garbage clock") {
                for (t in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, Int.MIN_VALUE.toLong(), Long.MAX_VALUE / 3)) {
                    assertNotNull("at($t)", s.at(t))
                    s.upNext(t)
                    s.poolAt(t)
                }
            }
        }
    }

    // --- substitutes for a dead clip ------------------------------------------------------------------

    @Test
    fun `an ordered channel never substitutes a later episode for a dead one`() {
        // Episodes 0..5 of 22 minutes, and a pair of shorts. Episode 2 will not resolve.
        val durations = listOf(1320, 1320, 1320, 1320, 1320, 1320, 120, 90)
        val ch = Channel(1, "Series", "youtube", "clock", ordered = true, streams = durations.mapIndexed { i, d ->
            Stream(id = "e$i".padEnd(11, 'x'), url = "u$i", duration = d)
        })
        val tt = Timetable(skipsOn = { false }, halfHourOn = { true }, zone = { ZoneOffset.UTC })
        var t = 1_790_121_600L
        var checked = 0
        while (t < 1_790_121_600L + 86_400) {
            val on = tt.at(ch, t)
            if (on is Timetable.OnAir.Clip && durations[on.index] >= SHORT && on.startsAt == t) {
                val subs = tt.substitutes(ch, t, on.index, on.endsAt, null)
                val episodes = subs.filter { durations[it] >= SHORT }
                val nextEpisode = (on.index + 1) % 6
                assertTrue("dead episode ${on.index} at $t: substitutes $subs would air episodes out of order",
                    episodes.all { it == nextEpisode })
                checked++
            }
            t += 60
        }
        assertTrue(checked > 0)
    }

    @Test
    fun `a substitute always ends by the dead clip's scheduled end`() {
        val rnd = Random(11)
        repeat(60) { c ->
            val g = gen(700 + c, maxClips = 80)
            if (g.durations.none { it > 0 }) return@repeat
            val ch = Channel(c, "C", "youtube", "clock", ordered = g.ordered, streams = g.durations.mapIndexed { i, d ->
                Stream(id = "s$i".padEnd(11, 'x'), url = "u$i", duration = d, parts = g.parts[i])
            })
            val tt = Timetable(skipsOn = { false }, halfHourOn = { true }, zone = { g.zone })
            repeat(30) {
                val t = 1_790_121_600L + rnd.nextLong(0, 86_400)
                val on = tt.at(ch, t) as? Timetable.OnAir.Clip ?: return@repeat
                val ends = on.endsAt ?: return@repeat
                val subs = tt.substitutes(ch, t, on.index, ends, null)
                assertFalse("the dead clip is not its own substitute: $subs", on.index in subs)
                val fallback = tt.at(ch, ends)?.index
                for (i in subs) {
                    assertTrue("$g: substitute $i (${g.durations[i]}s) overruns ${ends - t}s left",
                        g.durations[i] <= ends - t || i == fallback)
                }
            }
        }
    }
}
