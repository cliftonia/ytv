package com.cliftonia.fs42tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The line in the log that says why a channel judders.
 *
 * Judder on a single-mode panel is not a bug with a stack trace: nothing drops a frame, nothing
 * errors, and the dropped-frame counter reads zero while the picture visibly stutters. This
 * string is the only thing that attributes it, so a wrong answer here sends the next judder hunt
 * somewhere else - and reaching it before meant a real ExoPlayer rendering to a real surface.
 */
class FrameCadenceTest {

    @Test
    fun `every frame rate on the dial is described, none as an oddity`() {
        val cases = listOf(
            23.976f to "24fps film - 3:2 pulldown on a 60Hz panel",
            24f to "24fps film - 3:2 pulldown on a 60Hz panel",
            25f to "25fps PAL - UNEVEN on a 60Hz panel",
            29.97f to "30fps - clean 2:2 on a 60Hz panel",
            30f to "30fps - clean 2:2 on a 60Hz panel",
            50f to "50fps PAL - UNEVEN on a 60Hz panel",
            59.94f to "60fps - clean on a 60Hz panel",
            60f to "60fps - clean on a 60Hz panel",
        )
        for ((fps, expected) in cases) {
            assertEquals("${fps}fps", expected, FrameCadence.describe(fps))
        }
    }

    @Test
    fun `film gets a wider window than every other rate`() {
        // 23.976 is 0.024 outside a 1f window around 24 only if the window is measured the other
        // way, but 22.6 and 25.4 are what a 1.5f window really admits - and admitting 25.4 is
        // harmless where excluding 23.976 is not, because 23.976 is the commonest film rate on
        // the dial and "non-standard" would be an outright wrong answer for it.
        assertEquals("24fps film - 3:2 pulldown on a 60Hz panel", FrameCadence.describe(22.6f))
        assertEquals("non-standard", FrameCadence.describe(22.4f))
        // The neighbouring bands stay at 1f, so 26 is not PAL and 31 is not 30fps.
        assertEquals("non-standard", FrameCadence.describe(26.1f))
        assertEquals("non-standard", FrameCadence.describe(31.1f))
    }

    @Test
    fun `an unreported frame rate reads as unknown rather than as a number`() {
        // The player reports -1.0 when the format is not populated yet, and 0 when there is no
        // video track at all. Neither is a cadence, and describing either as one would put a
        // confident wrong answer in the log.
        assertEquals("unknown", FrameCadence.describe(-1f))
        assertEquals("unknown", FrameCadence.describe(0f))
    }
}
