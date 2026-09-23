package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rotation with SKIP SPONSORS on: it walks watched time, so both televisions agree on what
 * is on, and the offset handed to the player is a position in the FILE.
 */
class TimetableTest {

    private fun channel(vararg streams: Stream) =
        Channel(number = 4, name = "Four", kind = "youtube", rotation = "clock", streams = streams.toList())

    private fun clip(id: String, duration: Int, vararg skip: List<Double>) =
        Stream(id = id, url = "u$id", duration = duration, title = id, skip = skip.toList())

    private val skipsOn = Timetable(skipsOn = { true })

    @Test
    fun `off is exactly the rotation as it was`() {
        val ch = channel(clip("a", 100, listOf(0.0, 50.0)), clip("b", 100))
        val point = Timetable.PLAIN.playPoint(ch, 120)!!
        assertEquals(PlayPoint(1, 20.0), point)
        assertEquals(ClockRotation.playPointFor(listOf(100, 100), 120), point)
    }

    @Test
    fun `on, the cycle is watched time, so the next clip comes round sooner`() {
        // Clip a is 100s with 50s skipped: 50s watched. At 60s the clock is 10s into clip b.
        val ch = channel(clip("a", 100, listOf(0.0, 50.0)), clip("b", 100))
        assertEquals(PlayPoint(1, 10.0), skipsOn.playPoint(ch, 60))
    }

    @Test
    fun `on, the offset handed to the player is a file position past the skipped range`() {
        val ch = channel(clip("a", 100, listOf(20.0, 30.0)), clip("b", 100))
        // 25s of watched time: 20 before the range, then 5 more after it = file second 35.
        assertEquals(PlayPoint(0, 35.0), skipsOn.playPoint(ch, 25))
        assertEquals(90, skipsOn.watchDuration(ch.streams[0]))
    }

    @Test
    fun `off, skip ranges are ignored entirely`() {
        val ch = channel(clip("a", 100, listOf(20.0, 30.0)))
        assertEquals(emptyList<SkipRange>(), Timetable.PLAIN.skipRanges(ch.streams[0]))
        assertEquals(100, Timetable.PLAIN.watchDuration(ch.streams[0]))
    }

    @Test
    fun `the flag is read when asked, so a flip applies to the next question`() {
        var on = false
        val table = Timetable(skipsOn = { on })
        val ch = channel(clip("a", 100, listOf(0.0, 50.0)), clip("b", 100))
        assertEquals(PlayPoint(0, 60.0), table.playPoint(ch, 60))
        on = true
        assertEquals(PlayPoint(1, 10.0), table.playPoint(ch, 60))
    }
}
