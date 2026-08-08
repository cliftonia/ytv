package com.cliftonia.fs42tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Each threshold is checked exactly on the boundary and one byte either side. An off-by-one
 * here silently hands the 1.5 GB Chromecast a budget sized for the television, and the symptom
 * would be an out-of-memory kill on the device that is hardest to debug on.
 */
class DeviceBudgetTest {

    @Test
    fun `preloading is off whatever the device has`() {
        // DefaultPreloadManager fetches in parallel with playback rather than yielding to it,
        // which made the picture stop and start on the TCL. Delete this and the stutter returns.
        for (ram in listOf(1L, 1_500L, 2_400L, 8_000L)) {
            assertEquals(0, DeviceBudget.forDevice(ram * 1024 * 1024))
        }
    }

    private val gb = 1_024L * 1_024L * 1_024L

    @Test
    fun `three gigabytes exactly gets the full budget`() {
        assertEquals(4, DeviceBudget.budgetForRam(3 * gb))
    }

    @Test
    fun `one byte under three gigabytes drops to the floor`() {
        assertEquals(2, DeviceBudget.budgetForRam(3 * gb - 1))
    }

    @Test
    fun `the Chromecast with Google TV HD still gets a reverse slot`() {
        // 1.5 GB total, the device these thresholds exist for. A budget of 1 would hold only
        // the channel ahead - the forward-only priming that made every reversal on the box a
        // cold open, 5359ms against 350ms. Delete the floor and this is the test that fails.
        assertEquals(2, DeviceBudget.budgetForRam((1.5 * gb).toLong()))
    }

    @Test
    fun `the emulator's odd 1978MB does not fall through a threshold crack`() {
        // Measured, not invented: the fs42tv emulator reports 1978MB, which sits just under a
        // 2GB threshold and originally produced a budget of 1.
        assertEquals(2, DeviceBudget.budgetForRam(1_978L * 1024 * 1024))
    }

    @Test
    fun `a nonsense reading still yields a usable budget`() {
        assertEquals("a zero or negative totalMem must not produce a budget below the floor",
            2, DeviceBudget.budgetForRam(0))
    }
}
