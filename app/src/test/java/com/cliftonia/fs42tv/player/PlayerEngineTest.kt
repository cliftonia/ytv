package com.cliftonia.fs42tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerEngineTest {

    @Test
    fun `a single-mode panel gets mpv`() {
        // The TCL: 3840x2160 at 60.000004Hz, alternativeRefreshRates empty. Nothing to switch
        // to, so pacing 24 and 25fps evenly has to happen in software - and Media3 measured 5 of
        // 12 tunes juddering on this panel where mpv measured none.
        assertEquals(PlayerEngine.MPV, PlayerEngine.default(displayModeCount = 1))
    }

    @Test
    fun `a panel that can change mode keeps Media3`() {
        // A Chromecast drives the TV over HDMI and can switch output mode, which is why
        // androidx/media issue 2941 reports this fault on built-in sets and not on sticks.
        // Media3 is a fifth of the install size and starts faster, so it stays where it works.
        assertEquals(PlayerEngine.MEDIA3, PlayerEngine.default(displayModeCount = 3))
    }

    @Test
    fun `an unreadable mode list falls back to mpv rather than to the known-bad case`() {
        // getSupportedModes() returning nothing means the question could not be answered. The
        // asymmetry is deliberate: mpv on a device that did not need it costs install size and
        // start-up, while Media3 on a device that did need it is the bug this all exists for.
        assertEquals(PlayerEngine.MPV, PlayerEngine.default(displayModeCount = 0))
    }

    @Test
    fun `an explicit override wins, in either direction`() {
        assertEquals(PlayerEngine.MPV, PlayerEngine.parse("mpv"))
        assertEquals(PlayerEngine.MEDIA3, PlayerEngine.parse("media3"))
        assertEquals("the flag exists to be flipped back in a hurry, and 'exo' is what a person " +
            "types under pressure", PlayerEngine.MEDIA3, PlayerEngine.parse("exo"))
        assertEquals(PlayerEngine.MEDIA3, PlayerEngine.parse("EXOPLAYER"))
    }

    @Test
    fun `nonsense is not an override`() {
        // Must be distinguishable from a real choice, so a typo falls back to the device default
        // instead of silently pinning the engine the typo happened to resemble.
        assertNull(PlayerEngine.parse(null))
        assertNull(PlayerEngine.parse(""))
        assertNull(PlayerEngine.parse("vlc"))
    }
}
