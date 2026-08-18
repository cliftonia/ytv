package com.cliftonia.fs42tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The ring buffer that answers "why did MPV_SHUTDOWN happen" on a television with no adb.
 *
 * It arrived with the shutdown diagnostics and had no tests at all, which matters more here than
 * usual: the only way to notice it dropping the reason is to be standing in front of a set that
 * has just gone black, which is exactly the situation it exists to explain.
 */
class MpvLogTest {

    /**
     * A process-global object with no per-test isolation, so each test starts by emptying it.
     * Clearing before rather than after means a test that fails half way through cannot leave
     * the next one reading its leftovers.
     */
    @Before
    fun emptyTheBuffer() {
        MpvLog.clear()
    }

    @Test
    fun `the buffer keeps the last twelve lines, in the order mpv said them`() {
        for (i in 1..15) MpvLog.record("mpv", 0, "line $i")
        val kept = MpvLog.recent()
        assertEquals("keeping more would push the fatal reason off a settings screen that has " +
            "room for a handful of lines", 12, kept.size)
        assertEquals("[mpv] line 4", kept.first())
        assertEquals("[mpv] line 15", kept.last())
    }

    @Test
    fun `the last reason is the newest line`() {
        MpvLog.record("mpv", 0, "Failed to open https://x")
        MpvLog.record("mpv", 0, "No video or audio streams selected")
        assertEquals("[mpv] No video or audio streams selected", MpvLog.lastReason())
    }

    @Test
    fun `a blank or missing message is not kept`() {
        // mpv emits empty lines around its own section headers. Keeping them would push real
        // errors out of a twelve-line buffer with nothing to show for it.
        MpvLog.record("mpv", 0, null)
        MpvLog.record("mpv", 0, "")
        MpvLog.record("mpv", 0, "   ")
        assertEquals(emptyList<String>(), MpvLog.recent())
        assertNull(MpvLog.lastReason())
    }

    @Test
    fun `a reason is trimmed of the surrounding whitespace mpv pads with`() {
        MpvLog.record("mpv", 0, "  TLS handshake failed  ")
        assertEquals("[mpv] TLS handshake failed", MpvLog.lastReason())
    }

    @Test
    fun `clear empties the buffer rather than leaving the last reason behind`() {
        MpvLog.record("mpv", 0, "something")
        MpvLog.clear()
        assertNull("a stale reason under a fresh failure is worse than no reason at all",
            MpvLog.lastReason())
        assertEquals(emptyList<String>(), MpvLog.recent())
    }

    @Test
    fun `a long reason is cut to what the stand-by card can draw`() {
        MpvLog.record("mpv", 0, "e".repeat(200))
        assertEquals(90, MpvLog.lastReason()!!.length)
    }
}
