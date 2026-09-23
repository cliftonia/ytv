package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.player.SkipWatch
import com.cliftonia.fs42tv.sync.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Sponsor skips fed everything a malformed lineup can carry, and the watch-to-file mapping
 * checked as a property: never inside a range, never past the end, monotonic, and invertible.
 */
class SkipsEdgeTest {

    private fun stream(duration: Int, vararg skip: List<Double>) =
        Stream(id = "abc", url = "u", duration = duration, skip = skip.toList())

    private fun r(a: Double, b: Double) = SkipRange(a, b)

    /** Watched seconds before file position [media]: the inverse of [Skips.mediaTime]. */
    private fun watchTime(ranges: List<SkipRange>, media: Double): Double =
        media - ranges.sumOf { maxOf(0.0, minOf(it.end, media) - it.start) }

    @Test
    fun `overlapping, unsorted, out-of-range, negative, empty and malformed ranges are sanitised`() {
        val s = stream(800,
            listOf(50.0, 60.0), listOf(10.0, 20.0), listOf(15.0, 30.0), listOf(-5.0, 2.0),
            listOf(790.0, 900.0), listOf(100.0, 100.0), listOf(200.0, 150.0), listOf(Double.NaN, 5.0),
            listOf(1.0), emptyList(), listOf(900.0, 1000.0), listOf(-10.0, -1.0), listOf(55.0, 58.0, 99.0),
        )
        assertEquals(listOf(r(0.0, 2.0), r(10.0, 30.0), r(50.0, 60.0), r(790.0, 800.0)), Skips.ranges(s))
        assertEquals(800 - 2 - 20 - 10 - 10, Skips.watchDuration(s))
    }

    @Test
    fun `infinities clamp to the clip`() {
        val s = stream(100, listOf(Double.NEGATIVE_INFINITY, 5.0), listOf(90.0, Double.POSITIVE_INFINITY))
        assertEquals(listOf(r(0.0, 5.0), r(90.0, 100.0)), Skips.ranges(s))
        assertEquals(85, Skips.watchDuration(s))
    }

    @Test
    fun `adjacent ranges merge into one, so a join at the seam lands after both`() {
        val ranges = Skips.ranges(stream(100, listOf(10.0, 20.0), listOf(20.0, 30.0)))
        assertEquals(listOf(r(10.0, 30.0)), ranges)
        assertEquals(30.0, Skips.mediaTime(ranges, 10.0), 0.0)
        assertEquals(9.999, Skips.mediaTime(ranges, 9.999), 1e-12)
    }

    @Test
    fun `a range at zero - a join at zero opens after it`() {
        val ranges = Skips.ranges(stream(100, listOf(0.0, 12.5)))
        assertEquals(12.5, Skips.mediaTime(ranges, 0.0), 0.0)
        assertEquals(87, Skips.watchDuration(stream(100, listOf(0.0, 12.5))))
    }

    @Test
    fun `a range ending at the duration - the last watched second is before it`() {
        val s = stream(100, listOf(90.0, 100.0))
        val ranges = Skips.ranges(s)
        assertEquals(90, Skips.watchDuration(s))
        assertEquals(89.999, Skips.mediaTime(ranges, 89.999), 1e-12)
        // The watcher treats it as the end of the clip.
        assertEquals(SkipWatch.Action.EndClip, SkipWatch(ranges, 100.0).check(90.0))
    }

    @Test
    fun `skips covering 99 percent or all of a clip`() {
        assertEquals(10, Skips.watchDuration(stream(1000, listOf(0.0, 990.0))))
        assertEquals(0, Skips.watchDuration(stream(1000, listOf(0.0, 1000.0))))
        assertEquals(0, Skips.watchDuration(stream(1000, listOf(0.0, 600.0), listOf(500.0, 1200.0))))
        // 999.5 of 1000 skipped: floored to nothing, and a clock clip of 0 is never on air.
        assertEquals(0, Skips.watchDuration(stream(1000, listOf(0.0, 999.5))))
    }

    @Test
    fun `a clip with no duration or a negative one does not throw, with skips`() {
        assertEquals(0, Skips.watchDuration(stream(0, listOf(0.0, 10.0))))
        // A malformed lineup must degrade to "play it" (or "skip the clip"), never an exception.
        Skips.ranges(stream(-5, listOf(0.0, 1.0)))
        assertTrue(Skips.watchDuration(stream(-5, listOf(0.0, 1.0))) <= 0)
    }

    @Test
    fun `watch to file mapping at the exact edges of ranges`() {
        val ranges = listOf(r(10.0, 20.0), r(25.0, 30.0))
        assertEquals(9.0, Skips.mediaTime(ranges, 9.0), 0.0)
        assertEquals("exactly at a range's start is its end", 20.0, Skips.mediaTime(ranges, 10.0), 0.0)
        assertEquals(24.0, Skips.mediaTime(ranges, 14.0), 0.0)
        assertEquals("landing exactly on the next range's start steps it too", 30.0, Skips.mediaTime(ranges, 15.0), 0.0)
        assertEquals(31.0, Skips.mediaTime(ranges, 16.0), 0.0)
    }

    @Test
    fun `random ranges - file time is never inside a range, never past the end, monotonic, and round-trips`() {
        val rnd = Random(77)
        repeat(2000) { case ->
            val duration = rnd.nextInt(1, 21_600)
            val raw = List(rnd.nextInt(0, 12)) {
                val a = rnd.nextDouble(-50.0, duration + 50.0)
                listOf(a, a + rnd.nextDouble(-20.0, duration / 3.0 + 1))
            }
            val s = stream(duration, *raw.toTypedArray())
            val ranges = Skips.ranges(s)
            val watch = Skips.watchDuration(s)
            for ((a, b) in ranges.zipWithNext()) assertTrue("case $case sorted, disjoint: $ranges", a.end < b.start)
            assertTrue("case $case watch $watch within the clip", watch in 0..duration)
            var last = -1.0
            val samples = (0 until 60).map { rnd.nextDouble(0.0, maxOf(watch.toDouble(), 1e-9)) }.sorted() +
                ranges.map { watchTime(ranges, it.start) }.filter { it < watch }
            for (w in samples.sorted()) {
                val m = Skips.mediaTime(ranges, w)
                val ctx = "case $case duration $duration ranges $ranges watch $w -> file $m"
                assertTrue("$ctx: past the end", m < duration || (m == duration.toDouble() && watch == 0))
                assertTrue("$ctx: inside a range", ranges.none { m >= it.start && m < it.end })
                assertTrue("$ctx: not monotonic", m >= last)
                assertEquals("$ctx: round trip", w, watchTime(ranges, m), 1e-6)
                assertEquals("$ctx: the watcher has nothing to do at a join", SkipWatch.Action.None,
                    SkipWatch(ranges, duration.toDouble()).check(m))
                last = m
            }
        }
    }

    // --- the watcher, fed raw input ---------------------------------------------------------------

    @Test
    fun `the watcher at range edges - start seeks, end does nothing`() {
        val w = SkipWatch(listOf(r(0.0, 10.0), r(50.0, 60.0)), 100.0)
        assertEquals(SkipWatch.Action.SeekTo(10.0), w.check(0.0))
        assertEquals(SkipWatch.Action.None, w.check(10.0))
        assertEquals(SkipWatch.Action.None, w.check(60.0))
        assertEquals(SkipWatch.Action.SeekTo(60.0), w.check(50.0))
    }

    @Test
    fun `the watcher - a trailing range within a second of the end ends the clip, once`() {
        val w = SkipWatch(listOf(r(95.0, 99.5)), 100.0)
        assertEquals(SkipWatch.Action.EndClip, w.check(96.0))
        assertEquals(SkipWatch.Action.None, w.check(97.0))
    }

    @Test
    fun `the watcher - garbage positions do nothing`() {
        val w = SkipWatch(listOf(r(10.0, 20.0)), 100.0)
        assertEquals(SkipWatch.Action.None, w.check(Double.NaN))
        assertEquals(SkipWatch.Action.None, w.check(-1.0))
        assertEquals(SkipWatch.Action.None, w.check(Double.POSITIVE_INFINITY))
    }
}
