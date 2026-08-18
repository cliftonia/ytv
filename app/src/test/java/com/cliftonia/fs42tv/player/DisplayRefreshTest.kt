package com.cliftonia.fs42tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The gate on the refresh rate mpv resamples audio against.
 *
 * The newest fix in the tree, and it guards the audio-sync fault currently under investigation:
 * `video-sync=display-resample` resamples the audio to whatever mpv thinks the panel's refresh
 * rate is, so a placeholder answered by a display query becomes audio sliding steadily against
 * the picture. It was a private method of an Android `View`, which no test could reach.
 */
class DisplayRefreshTest {

    @Test
    fun `a real television refresh rate passes through untouched`() {
        // This panel reports 60.000004Hz rather than 60, and that difference is the difference
        // between resampling to the right rate and slowly drifting against it - so the value is
        // passed on as read, never rounded.
        assertEquals(60.000004f, DisplayRefresh.plausible(60.000004f))
        assertEquals(59.94f, DisplayRefresh.plausible(59.94f))
        assertEquals(23.976f, DisplayRefresh.plausible(23.976f))
    }

    @Test
    fun `zero and negative rates are refused rather than resampled against`() {
        // Resampling audio to 0Hz is not a slow drift, it is arithmetic on a value that cannot
        // be right. The caller's answer to null is video-sync=audio, which cannot drift at all.
        assertNull(DisplayRefresh.plausible(0f))
        assertNull(DisplayRefresh.plausible(-1f))
    }

    @Test
    fun `the plausible band is open at both ends, so its edges are pinned`() {
        assertNull("20Hz exactly is below the band", DisplayRefresh.plausible(20f))
        assertEquals(20.1f, DisplayRefresh.plausible(20.1f))
        assertEquals(249f, DisplayRefresh.plausible(249f))
        assertNull("250Hz exactly is above the band", DisplayRefresh.plausible(250f))
        assertNull(DisplayRefresh.plausible(251f))
        assertNull(DisplayRefresh.plausible(19.9f))
    }

    @Test
    fun `no rate at all stays no rate`() {
        // The display query answers null before the view is attached to a window, which is
        // exactly when mpv's options are set.
        assertNull(DisplayRefresh.plausible(null))
    }
}
