package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.schedule.HalfHourSchedule.OnAir
import com.cliftonia.fs42tv.schedule.ScheduleProbe.SLOT
import com.cliftonia.fs42tv.schedule.ScheduleProbe.Span
import com.cliftonia.fs42tv.schedule.ScheduleProbe.local
import com.cliftonia.fs42tv.schedule.ScheduleProbe.span
import com.cliftonia.fs42tv.schedule.ScheduleProbe.walk
import com.cliftonia.fs42tv.schedule.ScheduleProbe.within
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * The clock at its awkward moments: midnight, the part boundaries, DST in both hemispheres,
 * half-hour and 45-minute zones, year ends, the epoch, 2100, and a clock that is plain garbage.
 */
class HalfHourScheduleTimeEdgeTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val sydney: ZoneId = ZoneId.of("Australia/Sydney")
    private val brisbane: ZoneId = ZoneId.of("Australia/Brisbane")
    private val kolkata: ZoneId = ZoneId.of("Asia/Kolkata")
    private val kathmandu: ZoneId = ZoneId.of("Asia/Kathmandu")

    private fun epoch(date: String, time: String, zone: ZoneId): Long =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toEpochSecond()

    /** A mixed channel: programmes of assorted lengths and a handful of shorts. */
    private fun mixed(zone: ZoneId, number: Int = 12): HalfHourSchedule {
        val durations = listOf(1500, 5400, 2400, 240, 3000, 180, 1700, 90, 7200, 1234, 45, 4000)
        return HalfHourSchedule(number, durations, durations.map { emptyList() }, zone)
    }

    // --- part boundaries --------------------------------------------------------------------------

    /** Four disjoint pools: late 0-1, breakfast 2-3, afternoon 4-5, prime 6-7, and one short each. */
    private fun partsChannel(zone: ZoneId): HalfHourSchedule {
        val durations = listOf(1500, 1500, 1500, 1500, 1500, 1500, 1500, 1500, 200, 200, 200, 200)
        val keys = listOf("late", "late", "breakfast", "breakfast", "afternoon", "afternoon", "prime", "prime",
            "late", "breakfast", "afternoon", "prime")
        return HalfHourSchedule(5, durations, keys.map { listOf(it) }, zone)
    }

    private fun poolOf(r: OnAir?): String {
        val index = when (r) {
            is OnAir.Programme -> r.index
            is OnAir.TopUp -> r.index
            else -> error("expected a clip, got $r")
        }
        return listOf("late", "breakfast", "afternoon", "prime")[if (index >= 8) index - 8 else index / 2]
    }

    @Test
    fun `each part boundary switches pool on the exact second, in local time`() {
        for (zone in listOf(utc, newYork, kathmandu)) {
            val s = partsChannel(zone)
            for ((time, before, after) in listOf(
                Triple("06:00", "late", "breakfast"), Triple("12:00", "breakfast", "afternoon"),
                Triple("18:00", "afternoon", "prime"), Triple("23:00", "prime", "late"),
            )) {
                val t = epoch("2026-09-23", time, zone)
                assertEquals("$zone $time - 1s", before, poolOf(s.at(t - SLOT)))
                assertEquals("$zone $time", after, poolOf(s.at(t)))
                val p = s.at(t) as OnAir.Programme
                assertEquals("$zone $time: a part opens with a programme from its start", 0.0, p.offsetSeconds, 0.0)
                assertEquals(t, p.slotStart)
            }
            // Midnight is inside late: the same part either side.
            val midnight = epoch("2026-09-24", "00:00", zone)
            assertEquals("late", poolOf(s.at(midnight - SLOT)))
            assertEquals("late", poolOf(s.at(midnight)))
        }
    }

    @Test
    fun `23 59 59 to 00 00 00 is seamless, and so is every part boundary, in every zone`() {
        for (zone in listOf(utc, newYork, sydney, brisbane, kolkata, kathmandu)) {
            val s = mixed(zone)
            for (time in listOf("00:00", "06:00", "12:00", "18:00", "23:00")) {
                val t = epoch("2026-06-15", time, zone)
                val before = span(s.at(t - 1)!!, t - 1)
                val after = span(s.at(t)!!, t)
                assertEquals("$zone $time: the span before ends where the next begins", before.end, after.start)
                walk(s, t - 6 * 3600, t + 6 * 3600, "$zone around $time")
            }
        }
    }

    @Test
    fun `a programme that would cross the end of its part is deferred to the part's next day`() {
        // Prime is 10 slots. 4 + 4 fit; the third 4-slot programme would run past 23:00.
        val durations = listOf(7200, 7200, 7200, 120)
        val s = HalfHourSchedule(3, durations, listOf(listOf("prime"), listOf("prime"), listOf("prime"), listOf("prime")), utc)
        val programmes = ArrayList<Pair<Int, Long>>()
        var t = epoch("2026-09-23", "18:00", utc)
        val end = epoch("2026-09-27", "18:00", utc)
        while (t < end) {
            val r = s.at(t)
            val hour = Math.floorMod(t, 86_400L) / 3600
            if (hour in 18..22 && r is OnAir.Programme && r.slotStart == t) programmes += r.index to t
            t += SLOT
        }
        for ((_, start) in programmes) {
            val hour = Math.floorMod(start, 86_400L) / 3600
            assertTrue("a 2h programme at ${hour}h would run past 23:00", hour in 18..20)
        }
        // Order survives deferral: two a night, the third waits for tomorrow's 18:00.
        for ((a, b) in programmes.zipWithNext()) assertEquals("order $programmes", (a.first + 1) % 3, b.first)
        assertEquals("two a night", 8, programmes.size)
        // The deferred hour is top-ups and cards, nothing crosses 23:00.
        walk(s, epoch("2026-09-23", "21:00", utc), epoch("2026-09-24", "00:00", utc)).forEach {
            assertTrue("$it crosses 23:00", it.start >= epoch("2026-09-23", "23:00", utc) ||
                it.end <= epoch("2026-09-23", "23:00", utc))
        }
    }

    @Test
    fun `a programme longer than its whole part plays all of its content across part-days, in order`() {
        // A 7h programme in a 5h prime: 5h tonight, the last 2h tomorrow at 18:00.
        val durations = listOf(25_200, 1500, 100)
        val s = HalfHourSchedule(3, durations, listOf(listOf("prime"), listOf("prime"), listOf("prime")), utc)
        val seen = ArrayList<Span>()
        for (day in 0L..3) {
            val from = epoch("2026-09-23", "18:00", utc) + day * 86_400
            seen += walk(s, from, from + 5 * 3600, "prime day $day").filter { it.kind == 'P' && it.index == 0 }
        }
        for ((a, b) in seen.zipWithNext()) {
            if (b.offsetAtStart > 0) assertEquals("$a -> $b", a.offsetAtStart + a.length, b.offsetAtStart, 0.0)
        }
        val watched = seen.sumOf { it.length }
        assertTrue("the whole 7h is shown over the days, not truncated: $seen", watched >= 25_200)
    }

    // --- DST ----------------------------------------------------------------------------------------

    private val dstDays = listOf(
        newYork to "2026-03-08", newYork to "2026-11-01",
        sydney to "2026-04-05", sydney to "2026-10-04",
        brisbane to "2026-10-04",
    )

    @Test
    fun `DST days, walked span by span, have no gap and no overlap in real time`() {
        for ((zone, date) in dstDays) {
            val s = mixed(zone)
            val from = epoch(date, "00:00", zone) - 3600
            walk(s, from, from + 28 * 3600, "$zone $date")
        }
    }

    @Test
    fun `DST days hold for random channels too`() {
        for (seed in 1..60) {
            val rnd = Random(seed)
            val durations = List(rnd.nextInt(1, 80)) { if (rnd.nextBoolean()) rnd.nextInt(1, 300) else rnd.nextInt(300, 9000) }
            val (zone, date) = dstDays[seed % dstDays.size]
            val s = HalfHourSchedule(seed, durations, durations.map { emptyList() }, zone)
            val from = epoch(date, "00:00", zone) - 3600
            walk(s, from, from + 28 * 3600, "seed $seed $zone $date")
        }
    }

    @Test
    fun `minimal - New York spring forward, a 90 minute programme from 02 00 is reported with a start and end that exist`() {
        // Late (23:00) packs 90-minute programmes 23:00, 00:30, 02:00. On 2026-03-08 02:00 EST
        // never happens: the clock goes 01:59:59 EST -> 03:00:00 EDT.
        val s = HalfHourSchedule(1, listOf(5400), listOf(emptyList()), newYork)
        val t = epoch("2026-03-08", "03:10", newYork)
        val p = s.at(t) as OnAir.Programme
        // Asked at 03:10 EDT, 70 minutes into the 02:00 block by the wall clock.
        assertEquals(4200.0, p.offsetSeconds, 0.0)
        // Its reported start must not be before the previous span's end - which was 03:00 EDT.
        val before = span(s.at(t - 600 - 1)!!, t - 600 - 1)
        assertEquals("previous span ends where this one starts", before.end, p.slotStart)
    }

    @Test
    fun `minimal - New York spring forward, endsAt is when the schedule really moves on`() {
        // Late packs 90-minute programmes 23:00, 00:30, 02:00; so 00:30-02:00 ends exactly at
        // the gap, and the 02:00-03:30 one has lost its first hour. Asked at 01:45 EST, a 3-slot
        // programme from 00:30: its end must be the first instant something else is on.
        val s = HalfHourSchedule(1, listOf(5400), listOf(emptyList()), newYork)
        for (probe in listOf("00:45", "01:45", "01:59")) {
            val t = epoch("2026-03-08", probe, newYork)
            val p = s.at(t) as OnAir.Programme
            val lastSecond = s.at(p.endsAt - 1)
            assertTrue("at $probe: still the same programme one second before endsAt ($lastSecond)",
                lastSecond is OnAir.Programme && lastSecond.slotStart == p.slotStart)
        }
    }

    @Test
    fun `fall back repeats the wall clock's hour, and does so contiguously`() {
        val s = mixed(newYork)
        val firstOneAm = epoch("2026-11-01", "01:00", newYork) // EDT, the first 01:00
        val secondOneAm = firstOneAm + 3600 // EST, the second 01:00
        val first = s.at(firstOneAm)
        val second = s.at(secondOneAm)
        assertEquals("slots follow the wall clock: the same wall time, the same thing",
            span(first!!, firstOneAm).copy(start = 0, end = 0), span(second!!, secondOneAm).copy(start = 0, end = 0))
        walk(s, firstOneAm - 3600, secondOneAm + 7200, "NY fall back")
    }

    // --- half-hour and 45-minute zones ----------------------------------------------------------

    @Test
    fun `Kolkata and Kathmandu slots are their own wall clock's half hours`() {
        for ((zone, utcRemainder) in listOf(kolkata to 0L, kathmandu to 900L)) {
            val s = mixed(zone)
            val from = epoch("2026-09-23", "00:00", zone)
            val spans = walk(s, from, from + 2 * 86_400, zone.id)
            for (sp in spans.filter { it.kind == 'P' && it.offsetAtStart == 0.0 }) {
                assertEquals("$zone $sp local :00/:30", 0L, Math.floorMod(local(sp.start, zone), SLOT))
                assertEquals("$zone $sp in UTC", utcRemainder, Math.floorMod(sp.start, SLOT))
            }
        }
    }

    // --- calendar edges ---------------------------------------------------------------------------

    @Test
    fun `year boundaries and leap days walk cleanly`() {
        for (zone in listOf(utc, newYork, sydney, kathmandu)) {
            val s = mixed(zone)
            for (date in listOf("2026-12-31", "2027-12-31", "2028-02-28", "2028-02-29", "2099-12-31")) {
                val from = epoch(date, "12:00", zone)
                walk(s, from, from + 86_400, "$zone $date")
            }
        }
    }

    @Test
    fun `the epoch and the years either side walk cleanly`() {
        for (zone in listOf(utc, newYork, sydney, kathmandu)) {
            val s = mixed(zone)
            walk(s, -3 * 86_400, 3 * 86_400, "$zone around the epoch")
            walk(s, -40L * 365 * 86_400, -40L * 365 * 86_400 + 86_400, "$zone 1930")
        }
    }

    @Test
    fun `episode order carries straight across the epoch - day minus one leads into day zero`() {
        // Prime is 10 slots. Programmes of 2, 9 and 8 slots: from day 0 prime opens with 0, then
        // 1, 2, 1, 2... so the part-day that opens with programme 0 never comes round again.
        val durations = listOf(3600, 16_200, 14_400)
        val s = HalfHourSchedule(1, durations, durations.map { emptyList() }, utc)
        val starts = ArrayList<Int>()
        for (day in -3L..3L) {
            val primeStart = day * 86_400 + 18 * 3600
            walk(s, primeStart, primeStart + 5 * 3600).filter { it.kind == 'P' && it.offsetAtStart == 0.0 }
                .forEach { starts += it.index }
        }
        for ((a, b) in starts.zipWithNext()) {
            assertEquals("prime order $starts broke at $a -> $b", (a + 1) % 3, b)
        }
    }

    @Test
    fun `2100 and beyond answer the same way as today`() {
        val s = mixed(utc)
        walk(s, epoch("2100-06-01", "00:00", utc), epoch("2100-06-03", "00:00", utc), "2100")
        walk(s, epoch("2999-06-01", "00:00", utc), epoch("2999-06-02", "00:00", utc), "2999")
    }

    @Test
    fun `a garbage clock does not crash or hang`() {
        val s = mixed(newYork)
        within(10, "garbage clock lookups") {
            for (t in listOf(0L, -1L, 1L, 59L, -86_400L, 1_000_000L, Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong(),
                -62_135_596_800L, 253_402_300_799L)) {
                assertNotNull("at($t)", s.at(t))
                s.upNext(t)
            }
        }
    }

    @Test
    fun `an absurd clock - Long MIN or MAX - does not throw`() {
        // The continuous rotation takes any Long; the schedule should not be the thing that
        // crashes the tune path when the RTC hands the app nonsense.
        val s = mixed(newYork)
        within(10, "extreme clock lookups") {
            for (t in listOf(Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE / 2, Long.MIN_VALUE / 2)) {
                s.at(t)
                s.upNext(t)
            }
        }
    }

    @Test
    fun `LocalDateTime check - the wall clock reading at a programme start is on the half hour, DST or not`() {
        for ((zone, date) in dstDays) {
            val s = mixed(zone)
            val from = epoch(date, "00:00", zone) - 3600
            var t = from
            while (t < from + 28 * 3600) {
                val r = s.at(t)
                if (r is OnAir.Programme && r.offsetSeconds == 0.0) {
                    val wall = LocalDateTime.ofEpochSecond(t, 0, zone.rules.getOffset(java.time.Instant.ofEpochSecond(t)))
                    assertTrue("$zone $date: programme joined from 0 at $wall - not on a half hour",
                        wall.second == 0 && wall.minute % 30 == 0)
                }
                t += 60
            }
        }
    }
}
