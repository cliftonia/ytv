package com.cliftonia.fs42tv

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which process deaths are worth reporting, and what they are called on screen.
 *
 * The classification is the whole value. A television is stopped and reclaimed constantly in
 * normal use, so reporting every exit would bury the one that matters under noise nobody reads -
 * and the point of the feature is to distinguish a native crash from a low-memory kill, which are
 * different faults with different fixes and look identical from the sofa.
 */
class ExitReasonTest {

    @Test
    fun `the faults worth reporting are reported`() {
        assertTrue(ExitReason.isAbnormal(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertTrue(ExitReason.isAbnormal(ApplicationExitInfo.REASON_CRASH))
        assertTrue(ExitReason.isAbnormal(ApplicationExitInfo.REASON_ANR))
        assertTrue(ExitReason.isAbnormal(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertTrue(ExitReason.isAbnormal(ApplicationExitInfo.REASON_SIGNALED))
    }

    @Test
    fun `a self-exit with a non-zero status is a fault, not a clean shutdown`() {
        // libmpv's die() is a log line followed by exit(1). Android files that under
        // REASON_EXIT_SELF, not under any crash reason - so excluding this outright made the
        // reporter blind to the exact fault it was written to catch.
        assertTrue(ExitReason.isAbnormal(ApplicationExitInfo.REASON_EXIT_SELF, status = 1))
        assertFalse("status 0 really is a clean shutdown",
            ExitReason.isAbnormal(ApplicationExitInfo.REASON_EXIT_SELF, status = 0))
    }

    @Test
    fun `ordinary exits are not reported`() {
        // These happen every time the television is switched off or the app is backgrounded.
        // Surfacing them would put a TECHNICAL DIFFICULTIES card up during normal use.
        assertFalse(ExitReason.isAbnormal(ApplicationExitInfo.REASON_EXIT_SELF))
        assertFalse(ExitReason.isAbnormal(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertFalse(ExitReason.isAbnormal(ApplicationExitInfo.REASON_OTHER))
        assertFalse(ExitReason.isAbnormal(ApplicationExitInfo.REASON_UNKNOWN))
    }

    @Test
    fun `a native crash and a memory kill are named differently`() {
        // The distinction the whole class exists for: one means the decoder pipeline is at fault,
        // the other means the app is simply too heavy for a 2.34GB television. Opposite fixes.
        assertEquals("NATIVE CRASH", ExitReason.name(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("KILLED - LOW MEMORY", ExitReason.name(ApplicationExitInfo.REASON_LOW_MEMORY))
    }

    @Test
    fun `every reported reason has words rather than a number`() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_SIGNALED,
        )) {
            assertFalse("reason $reason has no name", ExitReason.name(reason).startsWith("EXIT "))
        }
    }

    @Test
    fun `an unknown future reason still says something`() {
        assertEquals("EXIT 99", ExitReason.name(99))
    }
}
