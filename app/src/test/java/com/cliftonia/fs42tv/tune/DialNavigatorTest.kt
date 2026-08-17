package com.cliftonia.fs42tv.tune

import com.cliftonia.fs42tv.sync.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Position on the dial. Surfing is the primary way anyone uses this, so an off-by-one or a
 * failure to wrap is not a subtle bug - it is the channel button not working.
 */
class DialNavigatorTest {

    private fun dial(vararg numbers: Int) = numbers.map {
        Channel(number = it, name = "ch$it", kind = "youtube", rotation = "clock",
            streams = emptyList())
    }

    @Test
    fun `starts at the first channel when no start is given`() {
        assertEquals(2, DialNavigator(dial(2, 9, 63)).currentNumber)
    }

    @Test
    fun `starts at the remembered channel`() {
        assertEquals("last-channel recall is the difference between a TV and a media player",
            63, DialNavigator(dial(2, 9, 63), startNumber = 63).currentNumber)
    }

    @Test
    fun `a remembered channel that has left the dial falls back to the first`() {
        assertEquals("the nightly conveyor can retire a channel; recall must not strand the app",
            2, DialNavigator(dial(2, 9, 63), startNumber = 999).currentNumber)
    }

    @Test
    fun `up moves to the next channel in list order`() {
        val nav = DialNavigator(dial(2, 9, 63))
        assertEquals(9, nav.up().number)
        assertEquals(63, nav.up().number)
    }

    @Test
    fun `up wraps from the end to the start`() {
        val nav = DialNavigator(dial(2, 9, 63), startNumber = 63)
        assertEquals("a dial that stops at the end is not a dial", 2, nav.up().number)
    }

    @Test
    fun `down wraps from the start to the end`() {
        val nav = DialNavigator(dial(2, 9, 63))
        assertEquals(63, nav.down().number)
    }

    @Test
    fun `surfing walks list order, not channel numbers`() {
        // Numbers are sparse and non-contiguous in the real dial; stepping numerically would
        // land on channels that do not exist.
        val nav = DialNavigator(dial(2, 40, 41, 900))
        assertEquals(40, nav.up().number)
        assertEquals(41, nav.up().number)
        assertEquals(900, nav.up().number)
    }

    @Test
    fun `jumpTo moves to a channel by number`() {
        val nav = DialNavigator(dial(2, 9, 63))
        assertEquals(63, nav.jumpTo(63)!!.number)
        assertEquals("a jump must move the position, not just report a channel",
            63, nav.currentNumber)
    }

    @Test
    fun `jumpTo an unknown number changes nothing`() {
        val nav = DialNavigator(dial(2, 9, 63))
        nav.up()
        nav.up()
        assertEquals(63, nav.currentNumber)
        assertNull(nav.jumpTo(404))
        assertEquals("a failed jump must leave the viewer where they were, not reset to the start",
            63, nav.currentNumber)
    }

    @Test
    fun `a single channel dial wraps to itself`() {
        val nav = DialNavigator(dial(7))
        assertEquals(7, nav.up().number)
        assertEquals(7, nav.down().number)
    }

    @Test
    fun `channels exposes exactly the list passed in`() {
        val list = dial(2, 9, 63)
        assertEquals("the overlay renders this list directly; it must be the caller's list, " +
            "not some other one", list, DialNavigator(list).channels)
    }

    @Test
    fun `currentIndex tracks up and down`() {
        val nav = DialNavigator(dial(2, 9, 63))
        assertEquals(0, nav.currentIndex)
        nav.up()
        assertEquals(1, nav.currentIndex)
        nav.up()
        assertEquals(2, nav.currentIndex)
        nav.down()
        assertEquals(1, nav.currentIndex)
    }

    @Test
    fun `currentIndex wraps with up and down`() {
        val nav = DialNavigator(dial(2, 9, 63), startNumber = 63)
        assertEquals(2, nav.currentIndex)
        nav.up()
        assertEquals("wrapping past the end must reset the index, not just the channel",
            0, nav.currentIndex)
        nav.down()
        assertEquals(2, nav.currentIndex)
    }

    @Test
    fun `peeking does not move the dial`() {
        // The whole point: prefetch needs to know where a press WOULD go, and must not move the
        // navigator under the key handling that is its only writer.
        val nav = DialNavigator(dial(1, 2, 3), startNumber = 2)
        assertEquals(3, nav.peekUp(nav.current)?.number)
        assertEquals(1, nav.peekDown(nav.current)?.number)
        assertEquals("the dial moved", 2, nav.currentNumber)
    }

    @Test
    fun `peeking wraps at both ends, like the dial itself`() {
        val channels = dial(1, 2, 3)
        val nav = DialNavigator(channels, startNumber = 1)
        assertEquals(3, nav.peekDown(channels[0])?.number)
        assertEquals(1, nav.peekUp(channels[2])?.number)
    }

    @Test
    fun `peeking from a channel that has left the dial gives nothing`() {
        // The prefetch is queued from a background thread against the channel that came on air,
        // and a re-sync could in principle have replaced the dial by the time it runs.
        val nav = DialNavigator(dial(1, 2))
        assertNull(nav.peekUp(dial(99).first()))
        assertNull(nav.peekDown(dial(99).first()))
    }
}
