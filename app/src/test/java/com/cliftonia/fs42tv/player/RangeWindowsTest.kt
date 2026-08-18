package com.cliftonia.fs42tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the proxy that keeps mpv off googlevideo's throttle.
 *
 * Worth testing precisely because the failure is invisible: an off-by-one here does not throw,
 * it drops or duplicates one byte per 8MB window, and a video stream missing scattered bytes
 * decodes as corruption that looks like a network fault.
 */
class RangeWindowsTest {

    private val mb = 1024L * 1024

    @Test
    fun `windows cover the span exactly, with no gap and no overlap`() {
        val windows = RangeWindows.of(0, 20 * mb - 1, 8 * mb)
        assertEquals(0L, windows.first().first)
        assertEquals(20 * mb - 1, windows.last().last)
        for (i in 0 until windows.size - 1) {
            assertEquals("window ${i + 1} must begin exactly where window $i ended",
                windows[i].last + 1, windows[i + 1].first)
        }
        assertEquals("every byte in the span, once",
            20 * mb, windows.sumOf { it.last - it.first + 1 })
    }

    @Test
    fun `no window is larger than the limit - that is the entire point`() {
        // An over-large window is not a rounding detail: googlevideo throttles an unbounded
        // request to 2.57 Mbps and serves a bounded one at 398.85 Mbps.
        for (w in RangeWindows.of(0, 100 * mb, 8 * mb)) {
            assertTrue("window ${w.first}..${w.last} exceeds the 8MB bound",
                w.last - w.first + 1 <= 8 * mb)
        }
    }

    @Test
    fun `a span smaller than one window is a single window`() {
        assertEquals(listOf(0L..999L), RangeWindows.of(0, 999, 8 * mb))
    }

    @Test
    fun `a span that divides exactly does not produce a trailing empty window`() {
        val windows = RangeWindows.of(0, 16 * mb - 1, 8 * mb)
        assertEquals(16 * mb - 1, windows.last().last)
        assertEquals("no zero-length window at the end", true, windows.all { it.last >= it.first })
    }

    @Test
    fun `the first window is small so an abandoned open wastes little`() {
        // mpv opens, reads the header and seeks away. Fetching a full window before it can do
        // that is megabytes thrown away on every single tune.
        val windows = RangeWindows.of(0, 100 * mb, 8 * mb)
        assertEquals(RangeWindows.FIRST_WINDOW, windows.first().last - windows.first().first + 1)
        // The ramp itself, not just its first rung: the previous assertion here compared the
        // window limit against itself, so it held whatever RangeWindows.of did.
        assertEquals("the first three windows must climb to the full bounded size that defeats " +
            "the throttle, or every tune pays the small-window cost for the whole clip",
            listOf(512 * 1024L, 2 * mb, 8 * mb),
            windows.take(3).map { it.last - it.first + 1 })
    }

    @Test
    fun `windows start at the requested offset, not at zero`() {
        // Every tune on this dial joins a clip partway through, so the first window is almost
        // never at the start of the file.
        val windows = RangeWindows.of(100 * mb, 110 * mb - 1, 8 * mb)
        assertEquals(100 * mb, windows.first().first)
        assertEquals(110 * mb - 1, windows.last().last)
    }

    @Test
    fun `an empty span produces nothing rather than a zero-length request`() {
        assertEquals(emptyList<LongRange>(), RangeWindows.of(10, 9, 8 * mb))
    }

    @Test
    fun `an open-ended range runs to the end of the resource`() {
        // What ffmpeg sends when it wants everything from an offset - the exact shape that gets
        // throttled upstream, and the reason this proxy exists.
        assertEquals(500L..999L, RangeWindows.parse("bytes=500-", totalLength = 1000))
    }

    @Test
    fun `a closed range is honoured, clamped to the resource`() {
        assertEquals(0L..99L, RangeWindows.parse("bytes=0-99", totalLength = 1000))
        assertEquals("a client may ask past the end; the answer must not run past it",
            900L..999L, RangeWindows.parse("bytes=900-5000", totalLength = 1000))
    }

    @Test
    fun `no range header means the whole resource`() {
        assertNull(RangeWindows.parse(null, totalLength = 1000))
    }

    @Test
    fun `unsupported and malformed ranges are declined rather than guessed at`() {
        // A suffix range is valid HTTP that this proxy does not implement. Declining it serves
        // the whole resource, which is correct if wasteful; guessing at it would serve the wrong
        // bytes with a confident Content-Range on top.
        assertNull(RangeWindows.parse("bytes=-500", totalLength = 1000))
        assertNull(RangeWindows.parse("items=0-10", totalLength = 1000))
        assertNull(RangeWindows.parse("bytes=abc-def", totalLength = 1000))
        assertNull("a backwards range is nonsense, not an empty one",
            RangeWindows.parse("bytes=900-100", totalLength = 1000))
    }

    @Test
    fun `a zero or negative window is rejected loudly`() {
        // Silently correcting it would loop forever building windows of no size.
        for (bad in listOf(0L, -1L)) {
            val threw = runCatching { RangeWindows.of(0, 100, bad) }.isFailure
            assertTrue("window size $bad must be rejected", threw)
        }
    }

    @Test
    fun `the total length is read from the tail of a Content-Range header`() {
        // The only place the total appears at all. Without it Media3 treats the stream as
        // unbounded, which costs seeking and changes how it buffers, and the proxy has nothing
        // to clamp a client's range against.
        assertEquals(12_345L, RangeWindows.totalLength("bytes 0-8388607/12345"))
        assertEquals(12_345L, RangeWindows.totalLength("bytes 0-0/ 12345 "))
    }

    @Test
    fun `a missing or unusable Content-Range yields null rather than a guess`() {
        // A guessed total is worse than none: it would be reported upward as fact and every
        // seek past it would land somewhere the file does not go.
        assertNull(RangeWindows.totalLength(null))
        assertNull(RangeWindows.totalLength("bytes 0-99"))
        assertNull("an unsatisfied range carries a * where the total would be",
            RangeWindows.totalLength("bytes */*"))
        assertNull(RangeWindows.totalLength(""))
    }
}
