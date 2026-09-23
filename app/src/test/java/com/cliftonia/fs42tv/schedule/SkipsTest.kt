package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.sync.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sponsor skips as the clock sees them: a clip is only as long as the part of it that is
 * watched, and a position in that watched time has to land on the right second of the file.
 *
 * Wrong here is not a crash. It is two televisions joining the same programme a minute apart, or
 * a channel that seeks into the middle of the very advert it was meant to jump over.
 */
class SkipsTest {

    private fun clip(duration: Int, vararg skip: List<Double>) =
        Stream(id = "abc123def45", url = "u", duration = duration, title = "t", skip = skip.toList())

    @Test
    fun `a clip with nothing to skip is watched in full`() {
        val plain = clip(812)
        assertTrue(Skips.ranges(plain).isEmpty())
        assertEquals(812, Skips.watchDuration(plain))
        assertEquals(400.0, Skips.mediaTime(emptyList(), 400.0), 0.0)
    }

    @Test
    fun `the watched length is the raw length less every range`() {
        val sponsored = clip(812, listOf(31.2, 74.9), listOf(790.0, 812.0))
        // 812 - 43.7 - 22 = 746.3, floored: the clock counts whole seconds, and every television
        // must floor the same way to agree.
        assertEquals(746, Skips.watchDuration(sponsored))
    }

    @Test
    fun `watch time steps over each range that starts at or before it`() {
        val ranges = listOf(SkipRange(10.0, 20.0), SkipRange(50.0, 60.0))
        assertEquals("before the first range nothing moves", 5.0, Skips.mediaTime(ranges, 5.0), 1e-9)
        assertEquals("the instant a range begins is its end - never its first frame",
            20.0, Skips.mediaTime(ranges, 10.0), 1e-9)
        assertEquals(25.0, Skips.mediaTime(ranges, 15.0), 1e-9)
        assertEquals("between the ranges only the first has been stepped over",
            49.0, Skips.mediaTime(ranges, 39.0), 1e-9)
        assertEquals("both stepped over", 70.0, Skips.mediaTime(ranges, 50.0), 1e-9)
    }

    @Test
    fun `a range at the very start is skipped by joining at zero`() {
        val ranges = listOf(SkipRange(0.0, 12.5))
        assertEquals(12.5, Skips.mediaTime(ranges, 0.0), 1e-9)
    }

    @Test
    fun `untidy ranges are tolerated - clamped, sorted, merged, and junk dropped`() {
        // The curation side promises clean ranges. A lineup published by a buggy build must
        // still not throw on a television nobody can attach a debugger to.
        val messy = clip(100,
            listOf(90.0, 130.0),          // past the end: clamped to 100
            listOf(10.0, 20.0),
            listOf(15.0, 25.0),           // overlaps the one before: merged
            listOf(40.0, 40.0),           // empty
            listOf(60.0, 50.0),           // backwards
            listOf(5.0),                  // not a pair
            listOf(-3.0, 2.0),            // before the start: clamped to 0
        )
        assertEquals(
            listOf(SkipRange(0.0, 2.0), SkipRange(10.0, 25.0), SkipRange(90.0, 100.0)),
            Skips.ranges(messy),
        )
        assertEquals(100 - 2 - 15 - 10, Skips.watchDuration(messy))
    }

    @Test
    fun `a clip that is all sponsor has no watched length`() {
        assertEquals(0, Skips.watchDuration(clip(60, listOf(0.0, 60.0))))
    }
}
