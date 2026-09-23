package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The guide and banner lines for a clock channel on the half-hour schedule - real start times,
 * printed the way the device prints its own clock.
 */
class ScheduleLinesTest {

    /** 2026-09-23 19:30Z, a slot boundary. */
    private val slot = 1_790_191_800L

    private fun table(h24: Boolean = false, zone: ZoneId = ZoneOffset.UTC) =
        Timetable(skipsOn = { false }, halfHourOn = { true }, zone = { zone }, use24Hour = { h24 })

    private val channel = Channel(
        number = 7, name = "Seven", kind = "youtube", rotation = "clock",
        streams = listOf(
            Stream(id = "a", url = "ua", duration = 1500, title = "Grand Designs"),
            Stream(id = "b", url = "ub", duration = 1500, title = "Time Team"),
            Stream(id = "s", url = "us", duration = 240, title = "Short One"),
        ),
    )

    private fun titleAt(t: Long) =
        channel.streams[(table().at(channel, t) as Timetable.OnAir.Clip).index].title

    @Test
    fun `the clock follows the device's 12 or 24 hour setting`() {
        assertEquals("7:30", ScheduleLines.clock(slot, ZoneOffset.UTC, use24Hour = false))
        assertEquals("19:30", ScheduleLines.clock(slot, ZoneOffset.UTC, use24Hour = true))
        assertEquals("12:00", ScheduleLines.clock(slot - 7 * 3600 - 1800, ZoneOffset.UTC, false))
        assertEquals("5:30", ScheduleLines.clock(slot, ZoneId.of("Australia/Brisbane"), false))
    }

    @Test
    fun `a guide row says what is on since when, and what is next at what time`() {
        val now = titleAt(slot)
        val next = titleAt(slot + 1800)
        assertEquals("NOW 7:30 $now · NEXT 8:00 $next",
            ScheduleLines.guideRow(channel, table(), slot + 600))
        assertEquals("NOW 19:30 $now · NEXT 20:00 $next",
            ScheduleLines.guideRow(channel, table(h24 = true), slot + 600))
    }

    @Test
    fun `a top-up is on since it started, and the next programme is still next`() {
        assertEquals("NOW 7:55 Short One · NEXT 8:00 ${titleAt(slot + 1800)}",
            ScheduleLines.guideRow(channel, table(), slot + 1510))
    }

    @Test
    fun `during a card the line is what comes on next`() {
        assertEquals("UP NEXT 8:00 ${titleAt(slot + 1800)}",
            ScheduleLines.guideRow(channel, table(), slot + 1760))
        assertEquals("UP NEXT 8:00 ${titleAt(slot + 1800)}" to "",
            ScheduleLines.banner(channel, table(), slot + 1760, playingIndex = null))
    }

    @Test
    fun `the banner splits NOW and NEXT over two lines`() {
        val (now, next) = ScheduleLines.banner(channel, table(), slot + 60, playingIndex = null)!!
        assertEquals("NOW 7:30 ${titleAt(slot)}", now)
        assertEquals("NEXT 8:00 ${titleAt(slot + 1800)}", next)
    }

    @Test
    fun `a banner for a clip the schedule did not choose names it without a time`() {
        // A dead-clip substitute is on air, not the scheduled programme: the start time printed
        // beside it would be the other programme's.
        val scheduled = (table().at(channel, slot + 60) as Timetable.OnAir.Clip).index
        val other = 1 - scheduled
        val (now, _) = ScheduleLines.banner(channel, table(), slot + 60, playingIndex = other)!!
        assertEquals("NOW ${channel.streams[other].title}", now)
    }

    @Test
    fun `off the half-hour schedule there are no lines, and the old titles stand`() {
        assertNull(ScheduleLines.guideRow(channel, Timetable.PLAIN, slot))
        assertNull(ScheduleLines.banner(channel, Timetable.PLAIN, slot, playingIndex = null))
        val live = channel.copy(kind = "live", rotation = null)
        assertNull(ScheduleLines.guideRow(live, table(), slot))
    }
}
