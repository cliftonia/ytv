package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.schedule.Timetable.OnAir
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * The one answer every clock-channel caller shares, with the Settings rows applied.
 *
 * SKIP SPONSORS: the rotation walks watched time, so both televisions agree on what is on, and
 * the offset handed to the player is a position in the FILE. SCHEDULE: HALF-HOUR swaps the
 * continuous rotation for [HalfHourSchedule]; CONTINUOUS is the rotation exactly as it was.
 */
class TimetableTest {

    private fun channel(vararg streams: Stream, number: Int = 4) =
        Channel(number = number, name = "Four", kind = "youtube", rotation = "clock", streams = streams.toList())

    private fun clip(id: String, duration: Int, vararg skip: List<Double>) =
        Stream(id = id, url = "u$id", duration = duration, title = id, skip = skip.toList())

    private val skipsOn = Timetable(skipsOn = { true })

    /** Half-hour, in UTC, so slot arithmetic in the assertions is plain. */
    private fun halfHour(skips: Boolean = false) =
        Timetable(skipsOn = { skips }, halfHourOn = { true }, zone = { ZoneOffset.UTC })

    /** A UTC slot boundary: 2026-09-23 19:30Z. */
    private val slot = 1_790_191_800L

    // --- sponsor skips on the continuous rotation --------------------------------------------

    @Test
    fun `off is exactly the rotation as it was`() {
        val ch = channel(clip("a", 100, listOf(0.0, 50.0)), clip("b", 100))
        val onAir = Timetable.PLAIN.at(ch, 120)
        assertEquals(OnAir.Clip(1, 20.0), onAir)
        assertEquals(ClockRotation.playPointFor(listOf(100, 100), 120)!!.offsetSeconds,
            (onAir as OnAir.Clip).offsetSeconds, 0.0)
    }

    @Test
    fun `skipping, the cycle is watched time, so the next clip comes round sooner`() {
        // Clip a is 100s with 50s skipped: 50s watched. At 60s the clock is 10s into clip b.
        val ch = channel(clip("a", 100, listOf(0.0, 50.0)), clip("b", 100))
        assertEquals(OnAir.Clip(1, 10.0), skipsOn.at(ch, 60))
    }

    @Test
    fun `skipping, the offset handed to the player is a file position past the skipped range`() {
        val ch = channel(clip("a", 100, listOf(20.0, 30.0)), clip("b", 100))
        // 25s of watched time: 20 before the range, then 5 more after it = file second 35.
        assertEquals(OnAir.Clip(0, 35.0), skipsOn.at(ch, 25))
        assertEquals(90, skipsOn.watchDuration(ch.streams[0]))
    }

    @Test
    fun `not skipping, skip ranges are ignored entirely`() {
        val ch = channel(clip("a", 100, listOf(20.0, 30.0)))
        assertEquals(emptyList<SkipRange>(), Timetable.PLAIN.skipRanges(ch.streams[0]))
        assertEquals(100, Timetable.PLAIN.watchDuration(ch.streams[0]))
    }

    @Test
    fun `the flags are read when asked, so a flip applies to the next question`() {
        var skips = false
        var half = false
        val table = Timetable(skipsOn = { skips }, halfHourOn = { half }, zone = { ZoneOffset.UTC })
        val ch = channel(clip("a", 100, listOf(0.0, 50.0)), clip("b", 100))
        assertEquals(OnAir.Clip(0, 60.0), table.at(ch, 60))
        skips = true
        assertEquals(OnAir.Clip(1, 10.0), table.at(ch, 60))
        half = true
        assertTrue(table.at(ch, slot) is OnAir.Clip)
        assertTrue("a programme joined on the schedule carries its times",
            (table.at(ch, slot) as OnAir.Clip).startsAt != null)
    }

    // --- the half-hour schedule --------------------------------------------------------------

    @Test
    fun `on the half-hour schedule a programme carries its real start and end`() {
        val ch = channel(clip("a", 1500), clip("b", 1500), clip("s", 240))
        val onAir = halfHour().at(ch, slot + 60) as OnAir.Clip
        assertEquals(60.0, onAir.offsetSeconds, 0.0)
        assertEquals(slot, onAir.startsAt)
        assertEquals(slot + 1500, onAir.endsAt)
        assertEquals(OnAir.Clip(2, 10.0, slot + 1500, slot + 1740), halfHour().at(ch, slot + 1510))
    }

    @Test
    fun `the schedule's card names the next programme and when it comes on`() {
        val ch = channel(clip("a", 1500), clip("b", 1500), clip("s", 240))
        val card = halfHour().at(ch, slot + 1750) as OnAir.Card
        assertEquals(slot + 1740, card.start)
        assertEquals(slot + 1800, card.until)
        assertEquals(slot + 1800, card.nextAt)
        assertEquals((halfHour().at(ch, slot + 1800) as OnAir.Clip).index, card.index)
    }

    @Test
    fun `the schedule packs watched time and joins at the file position`() {
        // 2000s raw with a 400s read at 100-500 is 1600s watched: one slot, 200s to top up.
        val ch = channel(clip("a", 2000, listOf(100.0, 500.0)), clip("s", 150))
        val table = halfHour(skips = true)
        val onAir = table.at(ch, slot + 300) as OnAir.Clip
        assertEquals(0, onAir.index)
        assertEquals("300s watched is file second 700: 100 before the read, 200 after",
            700.0, onAir.offsetSeconds, 0.0)
        assertEquals(slot + 1600, onAir.endsAt)
        assertEquals(1, (table.at(ch, slot + 1600) as OnAir.Clip).index)
    }

    @Test
    fun `a new lineup rebuilds the channel's schedule`() {
        val table = halfHour()
        val before = channel(clip("a", 1500), clip("s", 240))
        assertEquals(slot + 1500, (table.at(before, slot) as OnAir.Clip).endsAt)
        val after = channel(clip("a", 1200), clip("s", 240))
        assertEquals("the cached cycle must not outlive the lineup it was built from",
            slot + 1200, (table.at(after, slot) as OnAir.Clip).endsAt)
    }

    @Test
    fun `a channel with nothing playable has nothing on, either way`() {
        val empty = channel(clip("a", 0))
        assertNull(Timetable.PLAIN.at(empty, slot))
        assertNull(halfHour().at(empty, slot))
    }

    @Test
    fun `up next is only known on the half-hour schedule`() {
        val ch = channel(clip("a", 1500), clip("b", 1500), clip("s", 240))
        assertNull(Timetable.PLAIN.upNext(ch, slot))
        val next = halfHour().upNext(ch, slot + 10)!!
        assertEquals(slot + 1800, next.second)
    }

    // --- amended rules: the cut, joins from the beginning, substitutes -----------------------

    @Test
    fun `a clip the schedule cuts at its part's end says when`() {
        val ch = channel(
            clip("f", 21600).copy(parts = listOf("prime")), clip("g", 1700).copy(parts = listOf("prime")))
        val table = halfHour()
        val day = 1_790_121_600L
        val d = (0L until 4L).first {
            (table.at(ch, day + it * 86_400 + 18 * 3600) as? OnAir.Clip)?.let { c ->
                c.index == 0 && c.offsetSeconds == 0.0 } == true
        }
        val playing = table.at(ch, day + d * 86_400 + 22 * 3600) as OnAir.Clip
        assertEquals(day + d * 86_400 + 23 * 3600, playing.cutAt)
        assertNull("off the schedule nothing is cut", Timetable.PLAIN.at(ch, 5_000) .let { (it as OnAir.Clip).cutAt })
    }

    @Test
    fun `a clip joined from its beginning starts past a sponsor read at zero`() {
        val s = clip("a", 600, listOf(0.0, 30.0))
        assertEquals(30.0, skipsOn.startOffset(s), 0.0)
        assertEquals(0.0, Timetable.PLAIN.startOffset(s), 0.0)
    }

    @Test
    fun `substitutes on the schedule come from the part's pool and end before the dead clip's slot`() {
        val ch = channel(clip("a", 1500), clip("b", 1500), clip("c", 250), clip("d", 900))
        val table = halfHour()
        val dead = table.at(ch, slot + 10) as OnAir.Clip
        val subs = table.substitutes(ch, slot + 10, dead.index, dead.endsAt, avoid = 2)
        assertTrue("never the dead clip or the one to avoid: $subs", subs.none { it == dead.index || it == 2 })
        assertTrue("each ends before $dead does: $subs",
            subs.all { table.watchDuration(ch.streams[it]) <= dead.endsAt!! - (slot + 10) })
        assertEquals("continuous keeps list order", listOf(2, 3, 0), Timetable.PLAIN.substitutes(ch, 0, 1, null, null))
    }
}
