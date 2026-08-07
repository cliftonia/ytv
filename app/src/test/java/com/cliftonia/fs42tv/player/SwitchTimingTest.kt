package com.cliftonia.fs42tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The summariser is what turns a logcat scrape into the one line that goes in a report, so its
 * arithmetic needs to be pinned down exactly - especially that it is a median, not a mean.
 */
class SwitchTimingTest {

    @Test
    fun `no samples`() {
        assertEquals("no samples", SwitchTiming.summarise(emptyList()))
    }

    @Test
    fun `single sample`() {
        assertEquals("n=1 min=250 median=250 max=250", SwitchTiming.summarise(listOf(250L)))
    }

    @Test
    fun `odd-sized list reports the middle value as the median`() {
        assertEquals(
            "n=5 min=100 median=300 max=500",
            SwitchTiming.summarise(listOf(500L, 100L, 300L, 200L, 400L)),
        )
    }

    @Test
    fun `even-sized list reports the upper of the two middle values`() {
        // sorted[size/2] on a 4-element list is index 2, the upper middle - not an average
        // of the two middle values. Pinning this down is what stops someone quietly switching
        // it to a true (interpolated) median later.
        assertEquals(
            "n=4 min=100 median=300 max=400",
            SwitchTiming.summarise(listOf(400L, 100L, 300L, 200L)),
        )
    }

    @Test
    fun `a single huge outlier does not move the median`() {
        // This is the case that would catch someone quietly switching the median back to a
        // mean: a mean of these six samples would be dragged well past 1000ms by the one
        // 12-second CDN hiccup, but the median must stay near the five well-behaved samples.
        val samples = listOf(180L, 210L, 190L, 12_000L, 200L, 220L)
        assertEquals("n=6 min=180 median=210 max=12000", SwitchTiming.summarise(samples))
    }
}
