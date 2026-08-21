package com.cliftonia.fs42tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding whether a resolution is actually holding together.
 *
 * Invisible from the sofa either way, which is why it is decided here: demote too eagerly and a
 * capable panel is capped at 1080p forever; too reluctantly and the viewer keeps seeing stutter
 * the app could have prevented and switches quality by hand, which is what happened.
 */
class DropWatchTest {

    @Test
    fun `the ragged first seconds of a tune are not evidence`() {
        // Every tune is a deep seek: the decoder is filling and the proxy is ramping its window.
        // Judging on that would demote every channel on the dial.
        assertFalse(DropWatch.shouldDemote(dropped = 20, late = 20, elapsedSeconds = 3.0))
    }

    @Test
    fun `sustained losses demote`() {
        // The reported 4K case: frames going constantly, badly enough to switch back by hand.
        assertTrue(DropWatch.shouldDemote(dropped = 30, late = 40, elapsedSeconds = 20.0))
    }

    @Test
    fun `an occasional hiccup does not`() {
        // Five frames over a minute is less visible than a permanently softer picture.
        assertFalse(DropWatch.shouldDemote(dropped = 5, late = 0, elapsedSeconds = 60.0))
    }

    @Test
    fun `late frames count as much as dropped ones`() {
        // The viewer cannot tell a decoder that fell behind from an output that missed its
        // deadline; both are stutter. Measured on this panel, display-resample produced 45 late
        // frames and 5 dropped over fifty seconds, and looked wrong.
        assertTrue(DropWatch.shouldDemote(dropped = 0, late = 90, elapsedSeconds = 50.0))
        assertTrue(DropWatch.shouldDemote(dropped = 90, late = 0, elapsedSeconds = 50.0))
    }

    @Test
    fun `a clean clip is never demoted`() {
        // The measured result under video-sync=audio: two dropped, none late, over fifty seconds.
        assertFalse(DropWatch.shouldDemote(dropped = 2, late = 0, elapsedSeconds = 50.0))
    }
}
