package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.schedule.Timetable.OnAir
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.tune.Tuner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * The two Settings rows flipped every way on the same channel and instant: OFF must be exactly
 * the continuous rotation, and flipping back must be exactly as if never flipped.
 */
class TimetableToggleEdgeTest {

    /** 2026-09-23 00:00Z. */
    private val day = 1_790_121_600L

    private fun randomChannel(rnd: Random, number: Int, kind: String = "youtube"): Channel {
        val n = rnd.nextInt(1, 60)
        val keys = listOf("breakfast", "afternoon", "prime", "late")
        val streams = List(n) { i ->
            val duration = if (rnd.nextInt(3) == 0) rnd.nextInt(1, 300) else rnd.nextInt(300, 9000)
            val skip = List(rnd.nextInt(0, 4)) {
                val a = rnd.nextDouble(-10.0, duration.toDouble())
                listOf(a, a + rnd.nextDouble(0.0, duration / 4.0 + 1))
            }
            Stream(id = "v$i".padEnd(11, 'x'), url = "u$i", duration = duration, title = "t$i", skip = skip,
                parts = if (rnd.nextBoolean()) emptyList() else keys.filter { rnd.nextBoolean() })
        }
        return Channel(number, "C$number", kind, "clock", streams)
    }

    private class Flags(var skips: Boolean = false, var halfHour: Boolean = false, var zone: ZoneId = ZoneOffset.UTC)

    private fun timetable(f: Flags) = Timetable(skipsOn = { f.skips }, halfHourOn = { f.halfHour }, zone = { f.zone })

    private fun continuous(ch: Channel, t: Long, skips: Boolean): OnAir? {
        val durations = ch.streams.map { if (skips) Skips.watchDuration(it) else it.duration }
        val p = ClockRotation.playPointFor(durations, t) ?: return null
        val file = if (skips) Skips.mediaTime(Skips.ranges(ch.streams[p.index]), p.offsetSeconds) else p.offsetSeconds
        return OnAir.Clip(p.index, file)
    }

    @Test
    fun `both rows off is exactly the continuous rotation, for any channel and instant`() {
        val rnd = Random(1)
        repeat(200) { c ->
            val ch = randomChannel(rnd, c)
            repeat(20) {
                val t = rnd.nextLong(-1_000_000_000L, 5_000_000_000L)
                val plain = ClockRotation.playPointFor(ch.streams.map { it.duration }, t)
                val expected = plain?.let { OnAir.Clip(it.index, it.offsetSeconds) }
                assertEquals(expected, Timetable.PLAIN.at(ch, t))
                assertEquals(expected, timetable(Flags()).at(ch, t))
            }
        }
    }

    @Test
    fun `flipping each row on and back off returns exactly the continuous answer`() {
        val rnd = Random(2)
        repeat(100) { c ->
            val ch = randomChannel(rnd, c)
            val f = Flags(zone = ZoneId.of("America/New_York"))
            val tt = timetable(f)
            val t = day + rnd.nextLong(0, 86_400)
            val answers = HashMap<Pair<Boolean, Boolean>, OnAir?>()
            // Every order of flips, twice round, on one Timetable instance with its caches.
            val order = listOf(false to false, true to false, true to true, false to true, false to false,
                false to true, true to true, true to false, false to false)
            for ((skips, half) in order + order.shuffled(rnd)) {
                f.skips = skips
                f.halfHour = half
                val answer = tt.at(ch, t)
                answers[skips to half]?.let { assertEquals("channel $c skips=$skips half=$half", it, answer) }
                answers[skips to half] = answer
                if (!half) assertEquals("channel $c skips=$skips: off is the rotation", continuous(ch, t, skips), answer)
                // A fresh Timetable, never flipped, agrees.
                assertEquals(answer, timetable(Flags(skips, half, f.zone)).at(ch, t))
            }
        }
    }

    @Test
    fun `on the half-hour schedule with skips, the file offset handed to the player is never in a range`() {
        val rnd = Random(3)
        val tt = timetable(Flags(skips = true, halfHour = true, zone = ZoneId.of("Australia/Sydney")))
        repeat(150) { c ->
            val ch = randomChannel(rnd, c)
            repeat(40) {
                val t = day + rnd.nextLong(-30L * 86_400, 30L * 86_400)
                val a = tt.at(ch, t)
                if (a is OnAir.Clip) {
                    val s = ch.streams[a.index]
                    val ranges = Skips.ranges(s)
                    assertTrue("$a past the end of ${s.duration}", a.offsetSeconds < s.duration)
                    assertTrue("$a inside $ranges", ranges.none { a.offsetSeconds >= it.start && a.offsetSeconds < it.end })
                }
            }
        }
    }

    @Test
    fun `skips change the schedule's lengths - a 1900s clip with 150s skipped fits one slot`() {
        val ch = Channel(1, "One", "youtube", "clock", listOf(
            Stream(id = "a".padEnd(11, 'x'), url = "u", duration = 1900, skip = listOf(listOf(100.0, 250.0))),
        ))
        val slot = 1_790_191_800L
        val on = timetable(Flags(skips = true, halfHour = true)).at(ch, slot) as OnAir.Clip
        assertEquals("1750s watched: one slot, ends inside it", 1750L, on.endsAt!! - on.startsAt!!)
        assertEquals(0L, Math.floorMod(on.startsAt!!, 1800L))
        val off = timetable(Flags(skips = false, halfHour = true)).at(ch, slot) as OnAir.Clip
        assertEquals("1900s raw: two slots, ends 100s into the second", 1900L, off.endsAt!! - off.startsAt!!)
    }

    @Test
    fun `a zone change rebuilds the schedule, and matches a fresh one in the new zone`() {
        val rnd = Random(4)
        val ch = randomChannel(rnd, 7)
        val f = Flags(halfHour = true, zone = ZoneOffset.UTC)
        val tt = timetable(f)
        val t = day + 12_345
        tt.at(ch, t)
        f.zone = ZoneId.of("Asia/Kathmandu")
        assertEquals(timetable(Flags(halfHour = true, zone = f.zone)).at(ch, t), tt.at(ch, t))
    }

    @Test
    fun `two channels sharing a number - each answer is its own lineup's`() {
        val rnd = Random(5)
        val a = randomChannel(rnd, 3)
        val b = randomChannel(rnd, 3)
        val f = Flags(skips = true, halfHour = true)
        val tt = timetable(f)
        repeat(20) {
            val t = day + rnd.nextLong(0, 86_400)
            assertEquals(timetable(f).at(a, t), tt.at(a, t))
            assertEquals(timetable(f).at(b, t), tt.at(b, t))
        }
    }

    @Test
    fun `a negative duration with skips does not crash the tune path in any mode`() {
        val ch = Channel(1, "Bad", "youtube", "clock", listOf(
            Stream(id = "a".padEnd(11, 'x'), url = "u", duration = -5, skip = listOf(listOf(0.0, 1.0))),
            Stream(id = "b".padEnd(11, 'x'), url = "u", duration = 1500),
        ))
        for (skips in listOf(false, true)) for (half in listOf(false, true)) {
            val answer = timetable(Flags(skips, half)).at(ch, day)
            assertNotNull("skips=$skips half=$half", answer)
        }
    }

    @Test
    fun `following a clip on the schedule always moves forward, through a whole day`() {
        val rnd = Random(6)
        repeat(40) { c ->
            val ch = randomChannel(rnd, c)
            val tt = timetable(Flags(skips = rnd.nextBoolean(), halfHour = true, zone = ZoneId.of("America/New_York")))
            var tuned = Tuner.tune(ch, null, day, timetable = tt) ?: return@repeat
            var now = day
            var steps = 0
            while (now < day + 86_400) {
                val card = tuned.card
                val next = if (card != null) {
                    assertTrue("card until ${card.until} after $now", card.until > now)
                    now = card.until
                    Tuner.tune(ch, null, card.until, timetable = tt)
                } else {
                    val ends = tuned.endsAt
                    assertNotNull("a scheduled clip has an end", ends)
                    assertTrue("channel $c: endsAt $ends after $now", ends!! > now)
                    now = ends
                    Tuner.following(tuned, null, timetable = tt)
                }
                assertNotNull(next)
                assertEquals("channel $c: following is what the timetable has at $now",
                    tt.at(ch, now)?.index, next!!.streamIndex)
                tuned = next
                if (++steps > 5000) error("channel $c: following did not advance")
            }
        }
    }

    @Test
    fun `on a DST night the tuner leaves each clip exactly when the schedule does - nothing skipped`() {
        // A 50-minute programme from 01:30 on the New York spring-forward night: the wall clock
        // goes 01:59:59 EST -> 03:00:00 EDT, so the schedule moves on at 03:00 EDT. The tuner
        // retunes at endsAt; it must not sit on the old programme past the schedule's change.
        // Late packs A 23:00, B 00:00, c 01:00, A 01:30-02:30 (content to 02:20 on the wall).
        // Tagged "late" so late is its own cycle and packs as described (untagged, the day has
        // been one all-day cycle since the design fix).
        val ch = Channel(1, "One", "youtube", "clock", listOf(
            Stream(id = "a".padEnd(11, 'x'), url = "u", duration = 3000, parts = listOf("late")),
            Stream(id = "b".padEnd(11, 'x'), url = "u", duration = 3000, parts = listOf("late")),
            Stream(id = "c".padEnd(11, 'x'), url = "u", duration = 1500, parts = listOf("late")),
        ))
        val tt = timetable(Flags(halfHour = true, zone = ZoneId.of("America/New_York")))
        val oneForty = 1_772_952_000L // 2026-03-08 01:40 EST
        val tuned = Tuner.tune(ch, null, oneForty, timetable = tt)!!
        assertEquals("A is on at 01:40", 0, tuned.streamIndex)
        val ends = tuned.endsAt!!
        val lastSecond = tt.at(ch, ends - 1) as OnAir.Clip
        val first = tt.at(ch, oneForty) as OnAir.Clip
        assertEquals("the second before endsAt $ends is still the same programme block",
            first.index to first.endsAt, lastSecond.index to lastSecond.endsAt)
    }

    @Test
    fun `minimal - watch to file time lands a float ulp inside a skip range`() {
        // Found by the random property in SkipsEdgeTest. The join is exactly where the second
        // range begins in watched time, so the file position should be that range's END.
        val ranges = listOf(
            SkipRange(24.862683839497336, 81.17499931053422),
            SkipRange(406.31120901617237, 4024.5030464402194),
        )
        val m = Skips.mediaTime(ranges, 349.9988935451355)
        assertTrue("file time $m is inside ${ranges[1]}", m >= ranges[1].end)
    }
}
