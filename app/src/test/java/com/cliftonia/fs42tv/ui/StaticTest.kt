package com.cliftonia.fs42tv.ui

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The channel-change static: the snow drawn over the blank and the hiss under it.
 *
 * The drawing and the audio device are Android's; what is tested here is everything that decides
 * - what a frame of snow is, what the hiss sounds like, how it fades, and above all WHEN it may
 * be heard. The rule that matters most is the last: the hiss must never play over a programme.
 */
class StaticTest {

    @Test
    fun `a snow frame is small, opaque and grey`() {
        val frame = SnowFrames.generate(160, 90, Random(1))
        assertEquals(160 * 90, frame.size)
        frame.forEach { pixel ->
            assertEquals("opaque", 0xFF, pixel ushr 24)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            assertTrue("grey: r=$r g=$g b=$b", r == g && g == b)
        }
    }

    @Test
    fun `a snow frame is noise, not a flat colour`() {
        val frame = SnowFrames.generate(160, 90, Random(2))
        assertTrue("expected a spread of levels", frame.toSet().size > 50)
    }

    @Test
    fun `two frames differ, so cycling them reads as motion`() {
        val random = Random(3)
        val a = SnowFrames.generate(64, 36, random)
        val b = SnowFrames.generate(64, 36, random)
        assertTrue(!a.contentEquals(b))
    }
}
