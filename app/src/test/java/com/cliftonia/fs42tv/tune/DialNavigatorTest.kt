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
}
