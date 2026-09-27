package com.cliftonia.fs42tv.pluto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a break spent on commercials tunes back: the first moment a fresh load lands on the
 * programme, not the bumper. Instants are on the stream's clock; a break from 100s to 160s.
 */
class BreakReturnTest {

    private val start = 100_000L
    private val end = 160_000L

    /** A window of 5s segments ending at [edge], read at wall [readAt]; offset = three segments. */
    private fun view(
        edge: Long?,
        readAt: Long = 0L,
        end: Long? = this.end,
        start: Long? = this.start,
        blind: Boolean = false,
        offset: Long = 15_000L,
    ) = BreakView(
        timed = true, start = start, end = end, edgeEnd = edge, targetDurationMillis = 5_000L,
        firstWindowStarts = emptyList(), firstReadAt = 0L, readAt = readAt, liveOffsetMillis = offset, blind = blind,
    )

    @Test
    fun `mpv joins three segments back - their own lengths, and Media3 is bounded by four targets`() {
        val v = view(edge = 170_000L, offset = 15_015L)
        assertEquals(15_015L, BreakReturn.joinOffsetMillis(v, joinsThirdFromLast = true))
        assertEquals(20_000L, BreakReturn.joinOffsetMillis(v, joinsThirdFromLast = false))
    }

    @Test
    fun `a read whose window already joins at the return tunes now`() {
        // Edge 175s: a load now joins at 160s, the programme's first frame.
        val verdict = BreakReturn.decide(view(edge = 175_000L), onScreen = 159_000L, wallNow = 0L, joinOffsetMillis = 15_000L)
        assertTrue(verdict is BreakReturn.Verdict.Now)
    }

    @Test
    fun `one segment short, it waits - a tune now would land on the logo`() {
        // Edge 170s: a load now joins at 155s, a segment of bumper. By the clock, the published
        // edge is surely past 175s once the estimate is a whole target further: 180s, 10s on.
        val verdict = BreakReturn.decide(view(edge = 170_000L, readAt = 0L), 155_000L, wallNow = 0L, joinOffsetMillis = 15_000L)
        assertEquals(BreakReturn.Verdict.After(10_000L), verdict)
        // Four seconds after the read, six to go.
        assertEquals(BreakReturn.Verdict.After(6_000L),
            BreakReturn.decide(view(edge = 170_000L), 159_000L, wallNow = 4_000L, joinOffsetMillis = 15_000L))
        // The bound reached with no newer read: now.
        assertTrue(BreakReturn.decide(view(edge = 170_000L), 165_000L, wallNow = 10_000L, joinOffsetMillis = 15_000L)
            is BreakReturn.Verdict.Now)
    }

    @Test
    fun `Media3's larger join waits for a further segment`() {
        assertEquals(BreakReturn.Verdict.After(10_000L),
            BreakReturn.decide(view(edge = 175_000L), 160_000L, wallNow = 0L, joinOffsetMillis = 20_000L))
        assertTrue(BreakReturn.decide(view(edge = 180_000L), 160_000L, wallNow = 0L, joinOffsetMillis = 20_000L)
            is BreakReturn.Verdict.Now)
    }

    @Test
    fun `an end not yet seen waits - up to the ceiling`() {
        assertEquals(BreakReturn.Verdict.After(5 * 60_000L - 30_000L),
            BreakReturn.decide(view(edge = 150_000L, end = null), onScreen = 130_000L, wallNow = 0L, joinOffsetMillis = 15_000L))
    }

    @Test
    fun `the wait never runs past the ceiling`() {
        val onScreen = start + BreakView.MAX_BREAK_MILLIS - 2_000L
        assertEquals(BreakReturn.Verdict.After(2_000L),
            BreakReturn.decide(view(edge = 170_000L), onScreen, wallNow = 0L, joinOffsetMillis = 15_000L))
    }

    @Test
    fun `the ceiling, blind reads, or no break at all end the commercials at once`() {
        val capped = BreakReturn.decide(view(edge = 150_000L, end = null), start + BreakView.MAX_BREAK_MILLIS, 0L, 15_000L)
        assertTrue(capped is BreakReturn.Verdict.Now)
        assertTrue(BreakReturn.decide(view(edge = 150_000L, blind = true), 120_000L, 0L, 15_000L) is BreakReturn.Verdict.Now)
        assertTrue(BreakReturn.decide(view(edge = 150_000L, start = null, end = null), 120_000L, 0L, 15_000L)
            is BreakReturn.Verdict.Now)
    }
}
