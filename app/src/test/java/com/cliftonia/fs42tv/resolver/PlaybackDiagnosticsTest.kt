package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strings the settings screen shows about the last tune.
 *
 * [PlaybackDiagnostics] is a process-global with no reset, and its prefetch hit counters run for
 * the life of the process. So every test here writes once and asserts on that one write, and no
 * test asserts on the running totals - which would depend on the order JUnit happened to pick.
 */
class PlaybackDiagnosticsTest {

    @Test
    fun `an unknown video sync reads as a question mark rather than an empty gap`() {
        PlaybackDiagnostics.recordSync(null, null)
        assertEquals("? @ NO FPS", PlaybackDiagnostics.lastSync)
    }

    @Test
    fun `a zero refresh override is the sentinel meaning mpv is guessing`() {
        // `display-resample` resamples audio to the display's rate. mpv reports an unset
        // override as "0.000000" rather than blank, so treating that as a number would show a
        // confident 0 Hz beside a resampler that is actually working off a guess.
        PlaybackDiagnostics.recordSync("display-resample", "0.000000")
        assertEquals("display-resample @ NO FPS", PlaybackDiagnostics.lastSync)
    }

    @Test
    fun `a real refresh override is shown as mpv reported it`() {
        PlaybackDiagnostics.recordSync("display-resample", "59.940060")
        assertEquals("display-resample @ 59.940060", PlaybackDiagnostics.lastSync)
    }

    @Test
    fun `a blank override is treated the same as an absent one`() {
        PlaybackDiagnostics.recordSync("audio", "   ")
        assertEquals("audio @ NO FPS", PlaybackDiagnostics.lastSync)
    }

    @Test
    fun `play time is measured from the resolve, not from the start of the tune`() {
        // The split is the whole point: resolve is what prefetching removes and play is what it
        // cannot touch. Reporting the total instead would make a successful prefetch look like
        // no improvement at all.
        PlaybackDiagnostics.recordTune(resolveMillis = 100, firstFrameMillis = 350,
            fromCache = false)
        assertTrue("expected RESOLVE 100ms + PLAY 250ms, got ${PlaybackDiagnostics.lastTiming}",
            PlaybackDiagnostics.lastTiming.startsWith("RESOLVE 100ms + PLAY 250ms, READY "))
    }

    @Test
    fun `the stream line names the tier, the resolution and the codec family`() {
        PlaybackDiagnostics.record("uhd", "3840x2160", "avc1.640033")
        assertEquals("UHD 3840x2160 avc", PlaybackDiagnostics.lastStream)
    }

    @Test
    fun `a missing resolution shows as a question mark rather than nothing`() {
        PlaybackDiagnostics.record("hd", null, null)
        assertEquals("HD ? unknown", PlaybackDiagnostics.lastStream)
    }
}
