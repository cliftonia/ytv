package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.schedule.HalfHourSchedule.OnAir
import com.cliftonia.fs42tv.schedule.PartPacker.Kind
import com.cliftonia.fs42tv.schedule.PartPacker.Start
import com.cliftonia.fs42tv.schedule.ScheduleProbe.SHORT
import com.cliftonia.fs42tv.schedule.ScheduleProbe.SLOT
import com.cliftonia.fs42tv.schedule.ScheduleProbe.Span
import com.cliftonia.fs42tv.schedule.ScheduleProbe.span
import com.cliftonia.fs42tv.schedule.ScheduleProbe.walk
import com.cliftonia.fs42tv.schedule.ScheduleProbe.within
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The lazy cycle, attacked. The schedule finds a part's cycle with a shortcut - counting only
 * where each part-day's next opening lands, skipping gaps of ten minutes or less and deferred
 * tails - and lays a day out only when asked. Here every part-day from the 1870 anchor is
 * replayed in a straight line with FULL layouts, the next opening read off what was actually
 * laid out, and the schedule's lookups compared with it. Plus the 4096-opening cap, the
 * untagged all-day part, and several threads on one Timetable.
 */
class HalfHourScheduleCycleReplayTest {

    private val firstSlots = mapOf(
        DayPart.LATE to 0, DayPart.BREAKFAST to 14, DayPart.AFTERNOON to 26, DayPart.PRIME to 38, DayPart.ALL_DAY to 0,
    )

    /** The opening after a part-day, read off its full layout rather than the shortcut. */
    private fun nextOpening(items: List<PartPacker.Item>, start: Start, programmes: IntArray, partLength: Int): Start {
        val n = programmes.size
        val positions = programmes.withIndex().associate { (i, v) -> v to i }
        val progs = items.filter { it.kind == Kind.PROGRAMME }
        val last = progs.last()
        val pos = positions.getValue(last.index)
        return when {
            last.cut && start.carry >= 0 && progs.size == 1 && last.watchBase == start.carried ->
                Start(start.next, start.carry, start.carried + partLength)
            last.cut -> Start((pos + 1) % n, pos, partLength)
            else -> Start((pos + 1) % n, -1, 0)
        }
    }

    private fun key(r: OnAir): Triple<Char, Int, Double> = when (r) {
        is OnAir.Programme -> Triple('P', r.index, r.offsetSeconds)
        is OnAir.TopUp -> Triple('T', r.index, r.offsetSeconds)
        is OnAir.Card -> Triple('C', -1, 0.0)
    }

    private fun itemKey(item: PartPacker.Item): Triple<Char, Int, Double> = when (item.kind) {
        Kind.PROGRAMME -> Triple('P', item.index, item.watchBase.toDouble())
        Kind.TOP_UP -> Triple('T', item.index, 0.0)
        Kind.CARD -> Triple('C', -1, 0.0)
    }

    /**
     * Replay [part] of a lineup from the anchor to [toDay], comparing the schedule with the full
     * layout on every [every]th day and on every day in [dense].
     */
    private fun replay(
        label: String,
        durations: List<Int>,
        parts: List<List<String>>,
        ordered: Boolean,
        part: DayPart,
        toDay: Long,
        every: Int,
        dense: LongRange,
    ) {
        val watched = IntArray(durations.size) { durations[it].coerceIn(0, HalfHourSchedule.MAX_DURATION) }
        val playable = watched.indices.filter { watched[it] > 0 }
        val tagged = playable.filter { part.key in parts.getOrElse(it) { emptyList() } }
        val pool = tagged.ifEmpty { playable }
        val programmes = pool.filter { watched[it] >= SHORT }.toIntArray()
        if (programmes.isEmpty()) return
        val channel = 17
        val packer = PartPacker(channel, part, watched, programmes, pool, ordered)
        val s = HalfHourSchedule(channel, durations, parts, ZoneOffset.UTC, ordered)
        val partLength = part.slots * HalfHourSchedule.SLOT
        var opening = Start(0, -1, 0)
        var day = HalfHourSchedule.ANCHOR_DAY
        var n = 0
        while (day <= toDay) {
            val items = packer.layout(opening)
            if (n % every == 0 || day in dense) {
                val partStart = (day * 48 - 2 + firstSlots.getValue(part)) * SLOT
                for (item in items) {
                    val t = partStart + item.start
                    val got = s.at(t)!!
                    assertEquals("$label ${part.key} day $day item at +${item.start}", itemKey(item), key(got))
                }
            }
            opening = nextOpening(items, opening, programmes, partLength)
            day++
            n++
        }
    }

    private fun lineup(rnd: Random): Pair<List<Int>, Boolean> {
        val n = rnd.nextInt(2, 25)
        // Lengths that make gaps of ten minutes or less (1300-1790), deferred tails (2-4 slot
        // programmes against 10-14 slot parts), long carries, and fillers of every size.
        val d = List(n) {
            when (rnd.nextInt(6)) {
                0 -> rnd.nextInt(1300, 1791)
                1 -> rnd.nextInt(3000, 7201)
                2 -> rnd.nextInt(1, 300)
                3 -> rnd.nextInt(300, 1300)
                4 -> rnd.nextInt(18_001, 90_000)
                else -> rnd.nextInt(1791, 3000)
            }
        }
        return d to rnd.nextBoolean()
    }

    @Test
    fun `untagged lineups - every all-day part-day since 1870 matches a straight-line replay`() {
        val rnd = Random(31)
        repeat(12) { c ->
            val (d, ordered) = lineup(rnd)
            replay("untagged #$c $d ordered=$ordered", d, d.map { emptyList() }, ordered, DayPart.ALL_DAY,
                toDay = 23_000, every = 37, dense = 20_700L..20_760L)
        }
    }

    @Test
    fun `tagged lineups - every part-day of every part since 1870 matches a straight-line replay`() {
        val rnd = Random(32)
        val keys = listOf("late", "breakfast", "afternoon", "prime")
        repeat(8) { c ->
            val (d, ordered) = lineup(rnd)
            val parts = d.map { keys.filter { rnd.nextInt(3) == 0 }.ifEmpty { listOf(keys.random(rnd)) } }
            for (part in DayPart.PARTS) {
                replay("tagged #$c ordered=$ordered", d, parts, ordered, part,
                    toDay = 23_000, every = 41, dense = 20_700L..20_730L)
            }
        }
    }

    // --- the 4096-opening cap ------------------------------------------------------------------------

    /** 130 week-long clips in a 5h prime: 34 part-days each, 4420 openings - over the cap. */
    private val capped = List(130) { 7 * 86_400 - it } + listOf(120)
    private val cappedParts = capped.map { listOf("prime") }

    @Test
    fun `a channel over the opening cap builds, answers, and agrees across instances`() {
        val a = within(10, "build a capped channel") { HalfHourSchedule(3, capped, cappedParts, ZoneOffset.UTC) }
        val b = HalfHourSchedule(3, capped.toMutableList(), cappedParts.map { it.toMutableList() }, ZoneOffset.UTC)
        val rnd = Random(3)
        repeat(500) {
            val t = rnd.nextLong(-5_000_000_000L, 5_000_000_000L)
            assertEquals("at $t", a.at(t), b.at(t))
        }
    }

    @Test
    fun `a channel over the opening cap still continues each prime from the one before`() {
        val s = HalfHourSchedule(3, capped, cappedParts, ZoneOffset.UTC)
        var last: Span? = null
        // 2020 to 2034: long enough to cross a 4096-day boundary of a capped cycle.
        for (day in 18_262L..23_376L) {
            val start = (day * 48 - 2 + 38) * SLOT
            for (sp in walk(s, start, start + 10 * SLOT, "capped day $day").filter { it.kind == 'P' }) {
                val a = last
                if (a != null) {
                    if (sp.offsetAtStart > 0) {
                        assertEquals("day $day: continuation of $a", a.index, sp.index)
                        assertEquals("day $day: continues $a", a.offsetAtStart + a.length, sp.offsetAtStart, 0.0)
                    } else {
                        assertEquals("day $day: after $a", (a.index + 1) % 130, sp.index)
                    }
                }
                last = sp
            }
        }
    }

    // --- the untagged all-day part ---------------------------------------------------------------------

    private fun epoch(date: String, time: String, zone: ZoneId): Long =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toEpochSecond()

    @Test
    fun `untagged ordered episodes run strictly in order across 23 00 and midnight, day after day`() {
        orderedAcross(listOf(
            ZoneId.of("America/New_York") to "2026-06-10", ZoneId.of("Australia/Sydney") to "2026-06-10",
            ZoneId.of("Asia/Kathmandu") to "2026-09-22", ZoneOffset.UTC to "2026-12-30",
        ))
    }

    @Test
    fun `untagged ordered episodes run strictly in order across DST nights - none lost, none repeated`() {
        // The wall clock skips an hour in spring and repeats one in autumn; slots follow it, so
        // whatever the schedule put in the missing hour never airs. On an ordered channel that is
        // an episode skipped - the spec's "never out of order" and "slots follow the wall clock"
        // pull against each other here.
        orderedAcross(listOf(
            ZoneId.of("America/New_York") to "2026-03-07", ZoneId.of("America/New_York") to "2026-10-31",
            ZoneId.of("Australia/Sydney") to "2026-04-04", ZoneId.of("Australia/Sydney") to "2026-10-03",
        ))
    }

    private fun orderedAcross(zones: List<Pair<ZoneId, String>>) {
        val rnd = Random(9)
        for ((zone, date) in zones) repeat(8) { c ->
            val d = List(rnd.nextInt(3, 40)) { if (rnd.nextInt(4) == 0) rnd.nextInt(1, 300) else rnd.nextInt(300, 5400) }
            if (d.none { it >= SHORT }) return@repeat
            val s = HalfHourSchedule(c, d, d.map { emptyList() }, zone, ordered = true)
            val from = epoch(date, "12:00", zone)
            val spans = walk(s, from, from + 4 * 86_400, "$zone $date #$c")
            val programmes = d.indices.filter { d[it] >= SHORT }
            val shown = spans.filter { it.kind == 'P' }
            for ((a, b) in shown.zipWithNext()) {
                if (b.offsetAtStart > 0 && a.index == b.index) continue
                assertEquals("$zone $date #$c $d: out of order $a -> $b",
                    programmes[(programmes.indexOf(a.index) + 1) % programmes.size], b.index)
            }
            spans.filter { it.kind == 'T' }.forEach { assertTrue("$zone #$c: episode as filler $it", d[it.index] < SHORT) }
        }
    }

    @Test
    fun `an untagged channel's only seam is 23 00 - spans meet there, and nothing else is a part boundary`() {
        val rnd = Random(10)
        repeat(40) { c ->
            val d = List(rnd.nextInt(2, 30)) { rnd.nextInt(1, 7200) }
            val zone = listOf(ZoneOffset.UTC, ZoneId.of("America/New_York"), ZoneId.of("Asia/Kolkata")).random(rnd)
            val s = HalfHourSchedule(c, d, d.map { emptyList() }, zone)
            for (date in listOf("2026-03-08", "2026-06-15", "2026-11-01")) {
                val t = epoch(date, "23:00", zone)
                val before = span(s.at(t - 1)!!, t - 1)
                val after = span(s.at(t)!!, t)
                assertEquals("$zone $date #$c: the span before 23:00 ends at it", t, before.end)
                assertEquals("$zone $date #$c: the broadcast day opens at 23:00", t, after.start)
            }
        }
    }

    // --- determinism and threads -------------------------------------------------------------------------

    @Test
    fun `filler rotation is the same whatever order days are laid out in`() {
        val rnd = Random(12)
        repeat(20) { c ->
            val d = List(rnd.nextInt(5, 120)) { if (rnd.nextBoolean()) rnd.nextInt(1, 600) else rnd.nextInt(600, 5000) }
            val parts = if (c % 2 == 0) d.map { emptyList<String>() } else d.map { listOf("prime", "late").shuffled(rnd).take(1) }
            val instants = List(400) { 1_790_121_600L + rnd.nextLong(-3000L * 86_400, 3000L * 86_400) }
            val a = HalfHourSchedule(c, d, parts, ZoneId.of("Australia/Sydney"), ordered = c % 3 == 0)
            val expected = instants.map { a.at(it) }
            // Another instance, its small day cache churned: asked backwards, then scattered.
            val b = HalfHourSchedule(c, d, parts, ZoneId.of("Australia/Sydney"), ordered = c % 3 == 0)
            for (i in instants.indices.reversed()) assertEquals("#$c at ${instants[i]}", expected[i], b.at(instants[i]))
            for (i in instants.indices.shuffled(rnd)) assertEquals("#$c at ${instants[i]}", expected[i], b.at(instants[i]))
        }
    }

    @Test
    fun `eight threads asking one Timetable get exactly the single-threaded answers`() {
        val rnd = Random(13)
        val keys = listOf("late", "breakfast", "afternoon", "prime")
        val channels = List(30) { c ->
            val n = rnd.nextInt(1, 80)
            Channel(c, "C$c", "youtube", "clock", ordered = rnd.nextBoolean(), streams = List(n) { i ->
                val dur = if (rnd.nextInt(3) == 0) rnd.nextInt(1, 300) else rnd.nextInt(300, 9000)
                Stream(id = "v$i".padEnd(11, 'x'), url = "u$i", duration = dur,
                    skip = if (rnd.nextInt(4) == 0) listOf(listOf(10.0, 40.0)) else emptyList(),
                    parts = if (c % 2 == 0) emptyList() else keys.filter { rnd.nextBoolean() })
            })
        }
        val zone = ZoneId.of("America/New_York")
        fun timetable() = Timetable(skipsOn = { true }, halfHourOn = { true }, zone = { zone })
        val questions = List(4000) { channels.random(rnd) to 1_772_900_000L + rnd.nextLong(0, 40L * 86_400) }
        val single = timetable()
        val expected = questions.map { (ch, t) -> single.at(ch, t) to single.upNext(ch, t) }
        val shared = timetable()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 8).map { w ->
                pool.submit(Callable {
                    val order = questions.indices.shuffled(Random(w))
                    for (i in order) {
                        val (ch, t) = questions[i]
                        assertEquals("thread $w q$i", expected[i], shared.at(ch, t) to shared.upNext(ch, t))
                    }
                })
            }
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `untagged channels on DST nights - the tuner leaves each clip exactly when the schedule moves on`() {
        // The untagged counterpart of the tagged New York test in TimetableToggleEdgeTest.
        val rnd = Random(14)
        val nights = listOf(
            ZoneId.of("America/New_York") to "2026-03-08", ZoneId.of("America/New_York") to "2026-11-01",
            ZoneId.of("Australia/Sydney") to "2026-10-04", ZoneId.of("Australia/Sydney") to "2026-04-05",
        )
        repeat(24) { c ->
            val (zone, date) = nights[c % nights.size]
            val n = rnd.nextInt(2, 30)
            val ch = Channel(c, "C", "youtube", "clock", ordered = rnd.nextBoolean(), streams = List(n) { i ->
                Stream(id = "v$i".padEnd(11, 'x'), url = "u$i",
                    duration = if (rnd.nextInt(4) == 0) rnd.nextInt(1, 300) else rnd.nextInt(300, 6000))
            })
            val tt = Timetable(skipsOn = { false }, halfHourOn = { true }, zone = { zone })
            var t = epoch(date, "00:00", zone)
            val end = t + 5 * 3600
            while (t < end) {
                val on = tt.at(ch, t)
                if (on is Timetable.OnAir.Clip) {
                    val ends = on.endsAt!!
                    assertTrue("#$c $zone $date: endsAt $ends after $t", ends > t)
                    val last = tt.at(ch, ends - 1) as? Timetable.OnAir.Clip
                    assertEquals("#$c $zone $date at $t: still ${on.index} the second before endsAt",
                        on.index to ends, last?.index to last?.endsAt)
                }
                t += 150
            }
        }
    }
}
