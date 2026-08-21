package com.cliftonia.fs42tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign of the A/V trim, and the line that names the audio route.
 *
 * Both are here for the same reason: they are the two places where being wrong is invisible.
 * A trim applied with the sign inverted DOUBLES the fault it was reached for, and from the sofa
 * that is indistinguishable from the trim having no effect. A route line naming the wrong output
 * sends the next investigation back into the player, which is where the last five went.
 */
class AudioSyncTest {

    @Test
    fun `holding the picture back is a negative mpv audio-delay`() {
        // mpv: "positive values delay the audio, and negative values delay the video". Audio that
        // arrives late needs the VIDEO delayed, so the sign flips. This assertion is the whole
        // reason the conversion is a named function.
        assertEquals(-0.2, AudioSync.mpvAudioDelaySeconds(200), 1e-9)
        assertEquals(-0.04, AudioSync.mpvAudioDelaySeconds(40), 1e-9)
        assertEquals(0.0, AudioSync.mpvAudioDelaySeconds(0), 1e-9)
        // And the opposite set-up - a soundbar whose picture lags its own sound - flips back.
        assertEquals(0.08, AudioSync.mpvAudioDelaySeconds(-80), 1e-9)
    }

    @Test
    fun `the ladder starts at off, reaches the useful range early, and wraps`() {
        assertEquals(0, AudioSync.HOLD_MILLIS.first())
        // A2DP SBC costs 150-250ms in practice, so that band must be a few presses away, not
        // buried at the end of a list somebody gives up on.
        assertTrue(200 in AudioSync.HOLD_MILLIS.take(6))
        var at = 0
        repeat(AudioSync.HOLD_MILLIS.size) { at = AudioSync.next(at) }
        assertEquals("a full cycle returns to OFF", 0, at)
        // A value left in preferences by an older ladder must not strand the row.
        assertEquals(0, AudioSync.next(37))
    }

    @Test
    fun `the row says which way the trim goes`() {
        assertEquals("OFF", AudioSync.label(0))
        assertEquals("+200MS", AudioSync.label(200))
        assertEquals("-80MS", AudioSync.label(-80))
    }

    @Test
    fun `a paired bluetooth speaker outranks the panel it is paired to`() {
        // Exactly the set this television reports: the built-in speaker and HDMI are still
        // "available" while every sound actually leaves over Bluetooth. Ranking by presence in
        // the list rather than by routing preference would name the wrong one, which is the
        // failure this test exists to prevent.
        val outputs = listOf(
            AudioSync.TYPE_BUILTIN_SPEAKER to "TCL",
            AudioSync.TYPE_HDMI to "HDMI",
            AudioSync.TYPE_BLUETOOTH_A2DP to "iLoud Micro-Monitor",
        )
        assertEquals("BLUETOOTH (iLoud Micro-Monitor)", AudioSync.describeRoute(outputs))
        assertTrue(AudioSync.needsManualTrim(outputs))
    }

    @Test
    fun `a television on its own speakers needs no trim`() {
        val outputs = listOf(AudioSync.TYPE_BUILTIN_SPEAKER to "TCL")
        assertEquals("TV SPEAKER (TCL)", AudioSync.describeRoute(outputs))
        assertFalse(AudioSync.needsManualTrim(outputs))
    }

    @Test
    fun `an unrecognised output never outranks a known one`() {
        val outputs = listOf(
            9999 to "something new",
            AudioSync.TYPE_BUILTIN_SPEAKER to "TCL",
        )
        assertEquals("TV SPEAKER (TCL)", AudioSync.describeRoute(outputs))
        assertEquals("UNKNOWN", AudioSync.describeRoute(emptyList()))
    }
}
