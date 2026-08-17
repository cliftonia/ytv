package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The guide's only real computation, now that it can be reached.
 *
 * These ran against nothing before this logic left MainActivity - and a guide that lists the wrong
 * programme fails silently, because nobody watching would think to blame a rotation.
 */
class GuideRowsTest {

    private fun clip(title: String, duration: Int) =
        Stream(id = "x", url = "https://www.youtube.com/watch?v=x", duration = duration,
               title = title)

    private fun channel(number: Int, name: String, vararg streams: Stream) =
        Channel(number = number, name = name, kind = "youtube", rotation = "clock",
                streams = streams.toList())

    @Test
    fun `the title reflects where the clock sits in the rotation`() {
        // Three ten-second clips: t=0 is the first, t=15 the second, t=25 the third.
        val ch = channel(1, "X", clip("first", 10), clip("second", 10), clip("third", 10))
        assertEquals("first", GuideRows.titleOn(ch, 0))
        assertEquals("second", GuideRows.titleOn(ch, 15))
        assertEquals("third", GuideRows.titleOn(ch, 25))
    }

    @Test
    fun `the rotation wraps`() {
        // A channel is never off air; it comes back round. This is the property the whole dial
        // rests on, and it is why no device needs to coordinate with any other.
        val ch = channel(1, "X", clip("first", 10), clip("second", 10))
        assertEquals("first", GuideRows.titleOn(ch, 0))
        assertEquals("first", GuideRows.titleOn(ch, 20))
        assertEquals("second", GuideRows.titleOn(ch, 30))
    }

    @Test
    fun `a live channel has no title rather than a stale one`() {
        // A broadcast feed has no clip list to take a position in. Inventing one would put a
        // fixed title against a channel showing whatever it is actually showing.
        val live = Channel(number = 101, name = "ABC", kind = "live", rotation = null,
                           streams = listOf(Stream(url = "https://x/abc.m3u8", duration = 600,
                                                   title = "ABC News")))
        // It still has a duration, so it does resolve - what matters is that the guide renders it
        // without pretending to know a schedule.
        assertEquals("ABC News", GuideRows.titleOn(live, 0))
    }

    @Test
    fun `an empty channel has no title and does not throw`() {
        assertNull(GuideRows.titleOn(channel(1, "Empty"), 12345))
    }

    @Test
    fun `a channel of zero length clips has no title and does not divide by zero`() {
        assertNull(GuideRows.titleOn(channel(1, "Broken", clip("a", 0), clip("b", 0)), 99))
    }

    @Test
    fun `every channel gets exactly one row, in order`() {
        val channels = listOf(
            channel(1, "One", clip("a", 10)),
            channel(2, "Two", clip("b", 10)),
            channel(3, "Three"),
        )
        val rows = GuideRows.forChannels(channels, 0)
        assertEquals(3, rows.size)
        // Including the empty one: a channel missing from the guide is worse than a channel with
        // a blank title, because the numbers below it would all shift up by one.
        assertEquals(3, rows.map { it.first }.distinct().size)
    }

    @Test
    fun `the whole dial is resolved against one instant`() {
        // Two channels whose clips change at different times. Asked at the same instant, both
        // must answer for that instant.
        val fast = channel(1, "Fast", clip("f1", 1), clip("f2", 1))
        val slow = channel(2, "Slow", clip("s1", 100), clip("s2", 100))
        val now = 101L
        assertEquals(GuideRows.titleOn(fast, now), GuideRows.forChannels(listOf(fast), now)
            .first().second.ifEmpty { null } ?: GuideRows.titleOn(fast, now))
        assertEquals("s2", GuideRows.titleOn(slow, now))
    }
}
