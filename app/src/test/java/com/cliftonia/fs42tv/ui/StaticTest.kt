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

    @Test
    fun `the hiss is quiet by construction`() {
        val pcm = WhiteNoise.pcm(22_050, amplitude = 0.06f, random = Random(4))
        assertEquals(22_050, pcm.size)
        val ceiling = (0.06f * Short.MAX_VALUE).toInt() + 1
        assertTrue("no sample may exceed the amplitude", pcm.all { kotlin.math.abs(it.toInt()) <= ceiling })
        assertTrue("and it is not silence", pcm.any { it.toInt() != 0 })
    }

    @Test
    fun `the fade runs from full to nothing over its length`() {
        assertEquals(1f, HissEnvelope.fadeGain(0, 300), 0.0001f)
        assertEquals(0.5f, HissEnvelope.fadeGain(150, 300), 0.0001f)
        assertEquals(0f, HissEnvelope.fadeGain(300, 300), 0.0001f)
        assertEquals(0f, HissEnvelope.fadeGain(9_999, 300), 0.0001f)
    }

    @Test
    fun `a tune starts the hiss and its first frame fades it`() {
        val gate = HissGate()
        assertEquals(HissGate.Action.START, gate.update(active = true))
        assertEquals(HissGate.Action.NONE, gate.update(active = true))
        assertEquals(HissGate.Action.FADE, gate.update(active = false))
        assertEquals(HissGate.Action.NONE, gate.update(active = false))
    }

    @Test
    fun `a hiss that ran out is not restarted by the same long tune`() {
        // A dead channel stays "tuning" through its retries. Hiss for its whole outage would be
        // a noise complaint, so the cap holds until the tune actually ends.
        val gate = HissGate()
        gate.update(active = true)
        assertEquals(HissGate.Action.FADE, gate.timedOut())
        assertEquals(HissGate.Action.NONE, gate.update(active = true))
        assertEquals(HissGate.Action.NONE, gate.update(active = false))
        assertEquals("the next tune hisses again", HissGate.Action.START, gate.update(active = true))
    }

    @Test
    fun `a timeout after the fade has already happened does nothing`() {
        val gate = HissGate()
        gate.update(active = true)
        gate.update(active = false)
        assertEquals(HissGate.Action.NONE, gate.timedOut())
    }

    @Test
    fun `the hiss is wanted only for a tune nobody is covering`() {
        assertTrue(HissGate.wanted(enabled = true, tuning = true, covered = false))
        // A programme is playing: never.
        assertTrue(!HissGate.wanted(enabled = true, tuning = false, covered = false))
        // The guide's music, settings, the app in the background: never.
        assertTrue(!HissGate.wanted(enabled = true, tuning = true, covered = true))
        // Switched off: today's silent blank.
        assertTrue(!HissGate.wanted(enabled = false, tuning = true, covered = false))
    }
}
