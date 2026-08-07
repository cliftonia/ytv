package com.cliftonia.fs42tv.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The clock rotation is what makes every channel feel live: you join a programme
 * already in progress, at the point it would be if it had been broadcasting all day.
 * Getting it wrong is not a crash - it is a channel that starts every clip from zero,
 * which is exactly the bug that plagued the box this was ported from.
 */
class ClockRotationTest {

    @Test
    fun `lands inside the first clip`() {
        val point = ClockRotation.playPointFor(listOf(100, 200, 300), 50)
        assertEquals(0, point!!.index)
        assertEquals(50.0, point.offsetSeconds, 0.001)
    }

    @Test
    fun `lands inside a later clip with the offset relative to that clip`() {
        val point = ClockRotation.playPointFor(listOf(100, 200, 300), 250)
        assertEquals(1, point!!.index)
        assertEquals("the offset must be relative to the clip on air, not to the whole cycle",
            150.0, point.offsetSeconds, 0.001)
    }

    @Test
    fun `wraps around the cycle`() {
        // cycle is 600; 1250 = two full cycles plus 50
        val point = ClockRotation.playPointFor(listOf(100, 200, 300), 1250)
        assertEquals("a channel must loop seamlessly, not stop at the end of its list",
            0, point!!.index)
        assertEquals(50.0, point.offsetSeconds, 0.001)
    }

    @Test
    fun `a boundary lands at the start of the next clip`() {
        val point = ClockRotation.playPointFor(listOf(100, 200), 100)
        assertEquals("an exact boundary belongs to the next clip, not the end of the previous",
            1, point!!.index)
        assertEquals(0.0, point.offsetSeconds, 0.001)
    }

    @Test
    fun `a single clip channel always plays that clip`() {
        val point = ClockRotation.playPointFor(listOf(300), 1000)
        assertEquals(0, point!!.index)
        assertEquals(100.0, point.offsetSeconds, 0.001)
    }

    @Test
    fun `an empty channel yields nothing to play`() {
        assertNull("returning index 0 for an empty list would index out of bounds downstream",
            ClockRotation.playPointFor(emptyList(), 100))
    }

    @Test
    fun `all-zero durations yield nothing rather than dividing by zero`() {
        assertNull("a modulo by a zero cycle would throw and take the channel down",
            ClockRotation.playPointFor(listOf(0, 0), 100))
    }

    @Test
    fun `a zero duration clip in the middle is skipped, not played for no time`() {
        val point = ClockRotation.playPointFor(listOf(100, 0, 200), 100)
        assertEquals("a zero-length clip can never be on air, so the clock belongs to the next",
            2, point!!.index)
        assertEquals(0.0, point.offsetSeconds, 0.001)
    }
}
