package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.schedule.HalfHourSchedule.OnAir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The half-hour packer: programmes on the :00 and :30, short clips in the gaps, a card for the
 * rest - and every television agreeing on all of it from nothing but the lineup and the clock.
 *
 * Most of these run in UTC, so the arithmetic in the assertions is the arithmetic on the wall.
 * The DST tests exist to prove a zone that jumps does not crash the dial.
 */
class HalfHourScheduleTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 9, 23)

    /** Epoch seconds for [time] on [day] (+[plusDays]) in [zone]. */
    private fun at(time: String, plusDays: Long = 0, zone: ZoneId = utc): Long =
        ZonedDateTime.of(day.plusDays(plusDays), LocalTime.parse(time), zone).toEpochSecond()

    private fun schedule(
        vararg watch: Int,
        parts: List<List<String>> = watch.map { emptyList() },
        channel: Int = 7,
        zone: ZoneId = utc,
    ) = HalfHourSchedule(channel, watch.toList(), parts, zone)

    private fun programme(result: OnAir?): OnAir.Programme {
        assertTrue("expected a programme, got $result", result is OnAir.Programme)
        return result as OnAir.Programme
    }

    // --- alignment, top-up, card ---------------------------------------------------------------

    @Test
    fun `programmes start on the hour and the half hour`() {
        // Three 25-minute programmes and three shorts; no tags, so every part draws from all.
        val s = schedule(1500, 1500, 1500, 240, 180, 90)
        var slot = at("00:00")
        while (slot < at("00:00", plusDays = 1)) {
            val p = programme(s.at(slot))
            assertEquals("a programme joins at its start on a slot boundary", 0.0, p.offsetSeconds, 0.0)
            assertEquals(slot, p.slotStart)
            assertEquals(slot + 1800, p.slotEnd)
            assertTrue("only programmes (5 minutes or more) take a slot", p.index in 0..2)
            slot += 1800
        }
    }

    @Test
    fun `the rest of a slot is topped up largest first, and what will not fit is a card`() {
        val s = schedule(1500, 1500, 1500, 240, 180, 90)
        val slot = at("19:30")
        assertEquals(1499.0, programme(s.at(slot + 1499)).offsetSeconds, 0.0)
        // 300s left: the 240 fits; then 60s, which neither the 180 nor the 90 fits.
        assertEquals(OnAir.TopUp(3, 0.0, slot + 1500, slot + 1740), s.at(slot + 1500))
        assertEquals(OnAir.TopUp(3, 239.0, slot + 1500, slot + 1740), s.at(slot + 1739))
        val card = s.at(slot + 1740) as OnAir.Card
        assertEquals(slot + 1740, card.start)
        assertEquals(slot + 1800, card.until)
        assertEquals(slot + 1800, card.nextAt)
        assertEquals("the card names what comes on at its end",
            programme(s.at(slot + 1800)).index, card.nextIndex)
    }

    @Test
    fun `a slot the programme fills exactly has no gap`() {
        val s = schedule(1800)
        for (minute in 0 until 24 * 60 step 7) {
            val t = at("00:00") + minute * 60L
            val p = programme(s.at(t))
            assertEquals(0, p.index)
            assertEquals((t - p.slotStart).toDouble(), p.offsetSeconds, 0.0)
        }
    }

    // --- spanning ----------------------------------------------------------------------------

    @Test
    fun `a programme longer than half an hour spans whole slots`() {
        // 4000s is three slots. The afternoon's twelve slots hold four of them exactly.
        val s = schedule(4000, 240)
        val start = at("12:00")
        val first = programme(s.at(start))
        assertEquals(OnAir.Programme(0, 0.0, start, start + 5400, start + 4000), first)
        assertEquals("still the same programme a slot later, from the same start",
            OnAir.Programme(0, 1800.0, start, start + 5400, start + 4000), s.at(start + 1800))
        assertEquals(OnAir.TopUp(1, 0.0, start + 4000, start + 4240), s.at(start + 4000))
        val card = s.at(start + 4240) as OnAir.Card
        assertEquals(start + 5400, card.until)
        assertEquals(OnAir.Programme(0, 0.0, start + 5400, start + 10800, start + 9400),
            s.at(start + 5400))
    }

    // --- parts -------------------------------------------------------------------------------

    private val prime = listOf("prime")
    private val late = listOf("late")

    @Test
    fun `a programme that would cross the end of its part is deferred to the part's next day`() {
        // Prime is ten slots. Two four-slot programmes fill eight; the third would run past
        // 23:00, so it waits for tomorrow's prime and the last hour is a card.
        val s = schedule(7200, 7200, 7200, 1700, parts = listOf(prime, prime, prime, late))
        val first = programme(s.at(at("18:00"))).index
        assertEquals((first + 1) % 3, programme(s.at(at("20:00"))).index)
        val card = s.at(at("22:10")) as OnAir.Card
        assertEquals("one card for the whole deferred hour, not one per slot",
            at("22:00"), card.start)
        assertEquals(at("23:00"), card.until)
        assertEquals("up next is what really comes on at 23:00 - the late part's programme",
            3, card.nextIndex)
        assertEquals(at("23:00"), card.nextAt)
        assertEquals(3, programme(s.at(at("23:00"))).index)
        // Tomorrow's prime picks up with the programme that was deferred.
        assertEquals((first + 2) % 3, programme(s.at(at("18:00", plusDays = 1))).index)
    }

    @Test
    fun `each part is its own cycle, continued day to day`() {
        val s = schedule(7200, 7200, 7200, 1700, parts = listOf(prime, prime, prime, late))
        // Two prime programmes a night, in list order, carried across midnight and the other
        // parts in between: prime picks up tomorrow where it left off tonight.
        val shown = (0L until 6L).flatMap { d ->
            listOf(programme(s.at(at("18:00", d))).index, programme(s.at(at("20:00", d))).index)
        }
        shown.zipWithNext().forEach { (a, b) -> assertEquals((a + 1) % 3, b) }
        // Breakfast has no tagged streams, so it draws from all four.
        val breakfast = (0 until 12)
            .mapNotNull { (s.at(at("06:00") + it * 1800L) as? OnAir.Programme)?.index }.toSet()
        assertTrue("breakfast should draw on the whole channel, got $breakfast", 3 in breakfast)
    }

    @Test
    fun `a programme longer than its whole part may cross into the part's next day`() {
        // Six hours in a five-hour prime: it runs to 23:00, then resumes at 18:00 tomorrow.
        val s = schedule(21600, 1700, parts = listOf(prime, prime))
        val d = (0L until 4L).first {
            val p = s.at(at("18:00", it)) as? OnAir.Programme
            p?.index == 0 && p.offsetSeconds == 0.0
        }
        assertEquals(17940.0, programme(s.at(at("22:59", d))).offsetSeconds, 0.0)
        val resumed = programme(s.at(at("18:00", d + 1)))
        assertEquals(0, resumed.index)
        assertEquals(18000.0, resumed.offsetSeconds, 0.0)
        assertEquals(at("19:00", d + 1), resumed.endsAt)
        assertEquals(1, programme(s.at(at("19:00", d + 1))).index)
        // The six-hour programme will not fit the rest of the night, so the rest is a card -
        // one card, from the end of the 1700s programme, since there are no shorts to fill with.
        val card = s.at(at("19:40", d + 1)) as OnAir.Card
        assertEquals(at("19:28:20", d + 1), card.start)
        assertEquals(at("23:00", d + 1), card.until)
    }

    @Test
    fun `a tagged pool whose clips have no watched length falls back to the whole channel`() {
        val s = schedule(0, 1700, parts = listOf(prime, emptyList()))
        assertEquals(1, programme(s.at(at("19:00"))).index)
    }

    // --- determinism -------------------------------------------------------------------------

    @Test
    fun `two televisions compute the same schedule`() {
        val watch = intArrayOf(1500, 2400, 1700, 4000, 7300, 270, 250, 241, 150, 60)
        val parts = listOf(prime, emptyList(), late, emptyList(), prime,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val a = schedule(*watch, parts = parts)
        val b = schedule(*watch, parts = parts)
        var t = at("00:00")
        while (t < at("00:00", plusDays = 3)) {
            assertEquals(a.at(t), b.at(t))
            t += 97
        }
    }

    @Test
    fun `top-up choice is seeded by channel and slot - varied, but the same everywhere`() {
        // Four shorts in the same minute: exactly one fits the 300s gap, and which is the seed's.
        val watch = intArrayOf(1500, 241, 250, 260, 270)
        val chosen = (0 until 48).map { slot ->
            (schedule(*watch).at(at("00:00") + slot * 1800L + 1500) as OnAir.TopUp).index
        }
        assertTrue("the same short in every slot is a loop, not a channel: $chosen",
            chosen.toSet().size > 1)
        assertEquals(chosen, (0 until 48).map { slot ->
            (schedule(*watch).at(at("00:00") + slot * 1800L + 1500) as OnAir.TopUp).index
        })
        val otherChannel = (0 until 48).map { slot ->
            (schedule(*watch, channel = 8).at(at("00:00") + slot * 1800L + 1500) as OnAir.TopUp).index
        }
        assertTrue("another channel draws its own", chosen != otherChannel)
    }

    // --- straight-line replay ----------------------------------------------------------------

    /**
     * Every second of a day, looked up cold, against the same day laid out in one pass by an
     * independent and deliberately naive packer. Shorts are in different minutes, so largest
     * first leaves nothing to the seed and the replay can predict every top-up.
     */
    @Test
    fun `lookups at every second of a day agree with a straight-line replay`() {
        val watch = listOf(1500, 2400, 1700, 4000, 270, 150, 60)
        val programmes = listOf(0, 1, 2, 3)
        val shorts = listOf(4, 5, 6)
        val s = HalfHourSchedule(7, watch, watch.map { emptyList() }, utc)

        data class Expect(val kind: String, val index: Int, val offset: Int)
        val expected = ArrayList<Expect>(86_400)
        fun fill(from: Int, to: Int) {
            var t = from
            for (i in shorts) if (t + watch[i] <= to) {
                repeat(watch[i]) { expected += Expect("topup", i, it) }
                t += watch[i]
            }
            repeat(to - t) { expected += Expect("card", -1, 0) }
        }
        // The calendar day runs 00:00-06:00 late, then breakfast, afternoon, prime, and 23:00
        // late again - each part-day laid out in one pass from the programme it opens with.
        val segments = listOf("00:00" to 12, "06:00" to 12, "12:00" to 12, "18:00" to 10, "23:00" to 2)
        for ((startTime, slots) in segments) {
            // Late began at 23:00 yesterday; replay from there and keep only today's part.
            val opensAt = if (startTime == "00:00") at("23:00", plusDays = -1) else at(startTime)
            var slot = 0
            var pos = programmes.indexOf(programme(s.at(opensAt)).index)
            val partSlots = if (startTime == "00:00" || startTime == "23:00") 14 else slots
            val before = expected.size
            while (slot < partSlots) {
                val w = watch[programmes[pos]]
                val k = (w + 1799) / 1800
                if (slot + k > partSlots) break
                repeat(w) { expected += Expect("programme", programmes[pos], it) }
                fill(slot * 1800 + w, (slot + k) * 1800)
                slot += k
                pos = (pos + 1) % programmes.size
            }
            while (slot < partSlots) {
                fill(slot * 1800, (slot + 1) * 1800)
                slot++
            }
            if (startTime == "00:00") {
                // Drop 23:00-00:00 of yesterday.
                val dropped = expected.subList(before, before + 3600).toList()
                repeat(3600) { expected.removeAt(before) }
                assertEquals(3600, dropped.size)
            }
            if (startTime == "23:00") {
                // Only 23:00-24:00 of this part-day is today.
                while (expected.size > 86_400) expected.removeAt(expected.size - 1)
            }
        }
        assertEquals(86_400, expected.size)
        val midnight = at("00:00")
        for (second in 0 until 86_400) {
            val want = expected[second]
            val got = s.at(midnight + second)
            val actual = when (got) {
                is OnAir.Programme -> Expect("programme", got.index, got.offsetSeconds.toInt())
                is OnAir.TopUp -> Expect("topup", got.index, got.offsetSeconds.toInt())
                is OnAir.Card -> Expect("card", -1, 0)
                null -> null
            }
            assertEquals("at second $second", want, actual)
        }
    }

    // --- degenerate channels -----------------------------------------------------------------

    @Test
    fun `an empty channel has nothing on air`() {
        assertNull(schedule().at(at("12:00")))
        assertNull(schedule(0, 0).at(at("12:00")))
        assertNull(schedule().upNext(at("12:00")))
    }

    @Test
    fun `a channel of nothing but shorts is top-ups and a card, and never crashes`() {
        val s = schedule(200, 100)
        val slot = at("09:00")
        assertEquals(OnAir.TopUp(0, 0.0, slot, slot + 200), s.at(slot))
        assertEquals(OnAir.TopUp(1, 50.0, slot + 200, slot + 300), s.at(slot + 250))
        val card = s.at(slot + 300) as OnAir.Card
        assertEquals(slot + 1800, card.until)
        assertEquals("with no programme anywhere, up next is what the next slot opens with",
            0, card.nextIndex)
    }

    @Test
    fun `up next is the next programme to start, across a part boundary`() {
        val s = schedule(7200, 7200, 7200, 1700, parts = listOf(prime, prime, prime, late))
        val next = assertNotNullAndGet(s.upNext(at("22:15")))
        assertEquals(3 to at("23:00"), next)
        val fromProgramme = assertNotNullAndGet(s.upNext(at("18:05")))
        assertEquals(at("20:00"), fromProgramme.second)
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    // --- time zones --------------------------------------------------------------------------

    @Test
    fun `slots follow the local wall clock through daylight saving, without crashing`() {
        val york = ZoneId.of("America/New_York")
        val s = schedule(1500, 2400, 7300, 240, 180, zone = york)
        for (date in listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1))) {
            val from = ZonedDateTime.of(date, LocalTime.MIDNIGHT, york).toEpochSecond()
            var t = from
            while (t < from + 26 * 3600) {
                val result = s.at(t)
                assertNotNull(result)
                if (result is OnAir.Programme) {
                    val local = ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(t), york)
                    val startsLocal = local.minusSeconds(t - result.slotStart)
                    assertTrue("a programme starts on :00 or :30 local, got $startsLocal",
                        startsLocal.minute % 30 == 0 && startsLocal.second == 0)
                }
                t += 30
            }
        }
    }

    @Test
    fun `in Brisbane the half hours are Brisbane's`() {
        val brisbane = ZoneId.of("Australia/Brisbane")
        val s = schedule(1500, 1500, 240, zone = brisbane)
        val slot = at("19:30", zone = brisbane)
        assertEquals(slot, programme(s.at(slot + 100)).slotStart)
    }
}
