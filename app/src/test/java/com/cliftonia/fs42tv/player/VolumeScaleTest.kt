package com.cliftonia.fs42tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * mpv's `volume` is not a linear gain: its software volume applies (volume/100)^3. Media3's
 * `player.volume` IS linear. The same level-matching gain has to reach both engines as the same
 * loudness, so mpv gets the cube root.
 */
class VolumeScaleTest {

    @Test
    fun `mute and unity are what they always were`() {
        // The only two values the app ever sent before matched volume; they must not move.
        assertEquals(0, VolumeScale.mpvPercent(0f))
        assertEquals(100, VolumeScale.mpvPercent(1f))
    }

    @Test
    fun `a linear gain becomes its cube root in percent`() {
        assertEquals(50, VolumeScale.mpvPercent(0.125f))
        assertEquals(79, VolumeScale.mpvPercent(0.5f))
    }

    @Test
    fun `out-of-range input is clamped rather than amplified or inverted`() {
        assertEquals(100, VolumeScale.mpvPercent(3f))
        assertEquals(0, VolumeScale.mpvPercent(-1f))
        assertEquals(0, VolumeScale.mpvPercent(Float.NaN))
    }
}
