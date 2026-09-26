package com.cliftonia.fs42tv.pluto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What instant of the stream the viewer is looking at, per engine, and what the break does at
 * that instant. Instants are small numbers here; only differences matter.
 */
class OnScreenTest {

    /** A window of five 5s segments starting at [first], read at wall time [readAt]. */
    private fun view(
        first: Long = 100_000L,
        readAt: Long = 1_000L,
        start: Long? = null,
        end: Long? = null,
        edgeEnd: Long? = first + 25_000L,
        blind: Boolean = false,
    ) = BreakView(
        timed = true, start = start, end = end, edgeEnd = edgeEnd, targetDurationMillis = 5_000L,
        firstWindowStarts = (0 until 5).map { first + it * 5_000L }, firstReadAt = readAt,
        readAt = readAt, blind = blind,
    )

    @Test
    fun `Media3 knows the instant exactly, and that wins`() {
        assertEquals(123L, OnScreen.now(exact = 123L, joinsThirdFromLast = true, view = view(),
            loadedAt = 0L, playingMillis = 9_999L, wallNow = 50_000L))
    }

    @Test
    fun `mpv starts at the third-from-last segment of the window read at the tune`() {
        // Read 200ms after the load: the window mpv read is the one read here.
        assertEquals(110_000L, OnScreen.mpvAnchor(view(readAt = 1_200L), loadedAt = 1_000L))
        // And moves on with the playing time from the first frame.
        assertEquals(117_500L, OnScreen.now(exact = null, joinsThirdFromLast = true,
            view = view(readAt = 1_200L), loadedAt = 1_000L, playingMillis = 7_500L, wallNow = 0L))
    }

    @Test
    fun `a first read well after the load assumes the window slid meanwhile`() {
        // Six seconds late: about one segment has been added since mpv read its window.
        assertEquals(105_000L, OnScreen.mpvAnchor(view(readAt = 7_000L), loadedAt = 1_000L))
        // So late that the start is out of the window: no anchor.
        assertNull(OnScreen.mpvAnchor(view(readAt = 20_000L), loadedAt = 1_000L))
    }

    @Test
    fun `without either, three target durations behind the edge, moving with the wall clock`() {
        val v = view(readAt = 1_000L) // edge at 125_000
        assertEquals(110_000L + 4_000L, OnScreen.now(exact = null, joinsThirdFromLast = false,
            view = v, loadedAt = 0L, playingMillis = null, wallNow = 5_000L))
        // mpv without a picture yet has no playing time: the edge estimate too.
        assertEquals(110_000L, OnScreen.now(exact = null, joinsThirdFromLast = true,
            view = v, loadedAt = 0L, playingMillis = null, wallNow = 1_000L))
    }

    @Test
    fun `the card is up from start to end, with the next change timed`() {
        val v = view(start = 200_000L, end = 260_000L)
        assertEquals(BreakView.Decision(false, 200_000L), v.at(185_000L))
        assertEquals(BreakView.Decision(true, 260_000L), v.at(200_000L))
        assertEquals(BreakView.Decision(true, 260_000L), v.at(259_999L))
        assertEquals(BreakView.Decision(false, null), v.at(260_000L))
    }

    @Test
    fun `a break without a known end is up until the ceiling`() {
        val v = view(start = 200_000L, end = null, edgeEnd = 240_000L)
        assertEquals(BreakView.Decision(true, 200_000L + BreakView.MAX_BREAK_MILLIS), v.at(210_000L))
        assertFalse(v.at(200_000L + BreakView.MAX_BREAK_MILLIS).inBreak)
    }

    @Test
    fun `too little bumper known is not a break yet - a one-segment bumper never shows`() {
        assertFalse(view(start = 200_000L, end = 205_000L).at(201_000L).inBreak)
        val unsure = view(start = 200_000L, end = null, edgeEnd = 205_000L)
        assertEquals(BreakView.Decision(false, null), unsure.at(201_000L))
    }

    @Test
    fun `blind reads take the card down whatever the timeline says`() {
        assertFalse(view(start = 200_000L, end = 260_000L, blind = true).at(210_000L).inBreak)
    }

    @Test
    fun `no break at all is no card and nothing to wait for`() {
        assertEquals(BreakView.Decision(false, null), view().at(110_000L))
        assertTrue(view(start = 0L, end = null, edgeEnd = 20_000L).at(10_000L).inBreak)
    }

    @Test
    fun `the anchor counts only whole target durations of lateness`() {
        // 4.9s is under a segment's worth: mpv spent the same master fetch before its own read.
        assertEquals(110_000L, OnScreen.mpvAnchor(view(readAt = 5_900L), loadedAt = 1_000L))
        assertEquals(105_000L, OnScreen.mpvAnchor(view(readAt = 6_000L), loadedAt = 1_000L))
    }

    @Test
    fun `the edge estimate stands back by the last three segments' own lengths`() {
        val v = view(readAt = 1_000L).copy(liveOffsetMillis = 15_015L)
        assertEquals(125_000L - 15_015L, OnScreen.edge(v, wallNow = 1_000L))
    }
}
