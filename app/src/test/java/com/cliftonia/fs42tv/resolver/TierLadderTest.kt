package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TierLadderTest {

    @Test
    fun `a 4K panel tries uhd first`() {
        assertEquals("the whole point of detecting the panel is to use it",
            "uhd", TierLadder.forDisplay(2160).first())
    }

    @Test
    fun `the Chromecast's 1080p output never asks for uhd`() {
        // Named explicitly: this is the 1.5 GB device in the car, where a needless 4K decode
        // is the difference between playing and stuttering.
        assertEquals(listOf("hd", "sd"), TierLadder.forDisplay(1080))
    }

    @Test
    fun `no ladder starts above the panel it is for`() {
        // The invariant is NOT "always descending". A 720p panel gets sd first and hd second:
        // sd is the right match and hd is an over-spec last resort, so that ladder legitimately
        // ascends. What must never happen is asking for a tier the panel cannot use while a
        // tier it CAN use is available.
        val height = mapOf("uhd" to 2160, "hd" to 1080, "sd" to 720)
        for (panel in listOf(480, 720, 1080, 1440, 2160, 4320)) {
            val first = TierLadder.forDisplay(panel).first()
            val fits = height.values.any { it <= panel }
            if (fits) {
                assertTrue("panel ${panel}p opened with $first, which it cannot display, " +
                    "while a tier that fits was available",
                    height.getValue(first) <= panel)
            }
        }
    }

    @Test
    fun `each ladder picks the largest tier the panel can actually use`() {
        assertEquals("uhd", TierLadder.forDisplay(2160).first())
        assertEquals("a 1440p panel cannot show 2160p, so hd is the best it can use",
            "hd", TierLadder.forDisplay(1440).first())
        assertEquals("hd", TierLadder.forDisplay(1080).first())
        assertEquals("sd", TierLadder.forDisplay(720).first())
    }

    @Test
    fun `no ladder is empty`() {
        for (height in listOf(0, 240, 480, 720, 1080, 2160, 4320)) {
            assertTrue("an empty ladder is a black screen: every tier missing means the " +
                "channel plays nothing at height $height",
                TierLadder.forDisplay(height).isNotEmpty())
        }
    }

    @Test
    fun `a small panel still falls back to hd rather than nothing`() {
        // sd can be absent - the server omits it when a video tops out at 720p or below, since
        // it would duplicate hd. A 720p panel must still have somewhere to go.
        assertEquals(listOf("sd", "hd"), TierLadder.forDisplay(720))
    }

    @Test
    fun `a taller than 4K panel does not fall off the top`() {
        assertEquals("an 8K set has no dedicated tier, and must land on the best one there is",
            "uhd", TierLadder.forDisplay(4320).first())
    }
}
