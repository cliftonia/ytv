package com.cliftonia.fs42tv.player

import com.cliftonia.fs42tv.player.MpvLoadGuard.End
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules for which of mpv's events belong to the clip on screen.
 *
 * Every one of these was a symptom on the television before it was a rule: a surf that skipped a
 * programme, a beat of the channel just left, a channel change re-tuning itself six times in
 * fifty milliseconds - and the one this file was written for, a dead url that failed before
 * mpv opened it and left the screen black with no card and no retry.
 */
class MpvLoadGuardTest {

    private val farFromEnd = { false }
    private val nearTheEnd = { true }

    @Test
    fun `a load that dies before opening is reported, not swallowed`() {
        // The silent black screen: a dead HLS link or a 403 at open ends the file in error
        // before FILE_LOADED, while the latch that hides the outgoing file's end is still up.
        val guard = MpvLoadGuard()
        guard.asked(nowMillis = 1_000)
        guard.entryIdIs(7)
        assertEquals(End.FAILED, guard.endFile("error", 7, 1_200, farFromEnd))
    }

    @Test
    fun `the same failed load is reported only once`() {
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.entryIdIs(7)
        guard.endFile("error", 7, 1_200, farFromEnd)
        assertEquals(End.IGNORE, guard.endFile("error", 7, 1_300, farFromEnd))
    }

    @Test
    fun `without ids, a lone pending load owns the error`() {
        // The id read can fail; the counters still say this is the only load in flight.
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        assertEquals(End.FAILED, guard.endFile("error", null, 1_100, farFromEnd))
    }

    @Test
    fun `an error from a replaced entry does not fail the new load`() {
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.entryIdIs(1)
        guard.asked(1_016)          // surfed on sixteen milliseconds later
        guard.entryIdIs(2)
        assertEquals(End.IGNORE, guard.endFile("error", 1, 1_100, farFromEnd))
        assertEquals("and the new load's own failure still reports",
            End.FAILED, guard.endFile("error", 2, 1_200, farFromEnd))
    }

    @Test
    fun `without ids, an error while two loads are in flight is presumed stale`() {
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.asked(1_016)
        assertEquals(End.IGNORE, guard.endFile("error", null, 1_100, farFromEnd))
        assertEquals("the second error is the newest load's once the first is accounted for",
            End.FAILED, guard.endFile("error", null, 1_200, farFromEnd))
    }

    @Test
    fun `without ids, time repairs skew from a load that never reported`() {
        // mpv coalesces loads asked for before it started either; the first then produces no
        // events at all and the counters never balance. Seconds of quiet settle it.
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.asked(1_016)
        assertEquals(End.FAILED, guard.endFile("error", null, 5_000, farFromEnd))
    }

    @Test
    fun `the outgoing file's end during a load is ignored`() {
        // `loadfile replace` ends the file being replaced with "stop", read as eof. Treating it
        // as the new clip finishing re-tuned in a tight loop.
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.loaded()
        guard.firstFrame(1_500)
        guard.asked(9_000)
        assertEquals(End.IGNORE, guard.endFile("eof", null, 9_010, farFromEnd))
    }

    @Test
    fun `a deliberate stop silences the stopped clip's late error`() {
        // A channel change stops the clip; its error arriving afterwards names a channel the
        // viewer has left, and re-tuning would steal the screen back.
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.stopped()
        assertEquals(End.IGNORE, guard.endFile("error", null, 1_100, farFromEnd))
    }

    @Test
    fun `a clip that played to the end finishes`() {
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.loaded()
        assertTrue(guard.firstFrame(1_500))
        assertEquals(End.FINISHED, guard.endFile("eof", null, 60_000, farFromEnd))
        assertEquals("and only once", End.IGNORE, guard.endFile("eof", null, 60_001, farFromEnd))
    }

    @Test
    fun `an error at the tail of a clip with a picture is a roll-over`() {
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.loaded()
        guard.firstFrame(1_500)
        assertEquals(End.FINISHED, guard.endFile("error", null, 60_000, nearTheEnd))
    }

    @Test
    fun `an error mid-clip with a picture is a failure`() {
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.loaded()
        guard.firstFrame(1_500)
        assertEquals(End.FAILED, guard.endFile("error", null, 30_000, farFromEnd))
    }

    @Test
    fun `an opened file that errors before a frame is a failure`() {
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.loaded()
        assertEquals(End.FAILED, guard.endFile("error", null, 1_400, farFromEnd))
    }

    @Test
    fun `a non-error end from a file that never showed a frame is a ghost`() {
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.loaded()
        assertEquals(End.IGNORE, guard.endFile("eof", null, 1_400, farFromEnd))
    }

    @Test
    fun `the first frame of a replaced file keeps the blank up`() {
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.asked(1_016)
        guard.loaded()                       // the superseded file opens first
        assertFalse(guard.firstFrame(1_200))
        guard.loaded()
        assertTrue(guard.firstFrame(1_400))
        assertFalse("one first frame per load", guard.firstFrame(1_500))
    }

    @Test
    fun `a first frame after seconds of quiet is accepted despite skew`() {
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.asked(1_016)
        guard.loaded()
        assertTrue(guard.firstFrame(4_000))
    }

    @Test
    fun `a load that failed unopened does not hold back the next load's first frame`() {
        // Before the failure was counted, a dead load left the counters short and the next
        // clip's picture was taken for a replaced file's.
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.endFile("error", null, 1_100, farFromEnd)
        guard.asked(1_200)
        guard.loaded()
        assertTrue(guard.firstFrame(1_300))
    }

    @Test
    fun `a load replaced before opening does not reject the next fast first frame`() {
        // The fast-surf black dial on the TCL: X is replaced before mpv opened it, mpv ends it
        // with "stop" (read as eof) and never reports FILE_LOADED, and the counters stayed one
        // short for the life of the process. Pluto's first frames arrive well inside the
        // resync window since single-variant loading, so every one was rejected after that.
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.entryIdIs(1)
        guard.asked(1_016)
        guard.entryIdIs(2)
        assertEquals(End.IGNORE, guard.endFile("eof", 1, 1_050, farFromEnd))
        guard.loaded(2)
        assertTrue(guard.firstFrame(1_400, playingEntryId = 2))
    }

    @Test
    fun `without a playing id, a stop of an unopened entry still settles its ask`() {
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.entryIdIs(1)
        guard.asked(1_016)
        guard.entryIdIs(2)
        guard.endFile("eof", 1, 1_050, farFromEnd)
        guard.loaded(2)
        assertTrue(guard.firstFrame(1_400, playingEntryId = null))
    }

    @Test
    fun `an unopened entry's end is never counted past the loads asked for`() {
        // A duplicate or late end must not over-settle, or a stale frame would pass as current.
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.entryIdIs(1)
        guard.asked(1_016)
        guard.entryIdIs(2)
        guard.endFile("eof", 1, 1_050, farFromEnd)
        guard.endFile("eof", 1, 1_060, farFromEnd)
        guard.asked(1_100)
        guard.entryIdIs(3)
        guard.loaded(2)
        assertFalse("entry 2 is not the newest", guard.firstFrame(1_200, playingEntryId = null))
    }

    @Test
    fun `a first frame of the entry asked for is accepted however fast and however skewed`() {
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.entryIdIs(1)
        guard.asked(1_016)           // X replaced; its end never arrives at all
        guard.entryIdIs(2)
        guard.loaded(2)
        assertTrue(guard.firstFrame(1_300, playingEntryId = 2))
    }

    @Test
    fun `a stale first frame of the entry just replaced is rejected`() {
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.entryIdIs(1)
        guard.loaded(1)
        guard.asked(1_016)
        guard.entryIdIs(2)
        assertFalse(guard.firstFrame(1_100, playingEntryId = 1))
        assertFalse("even after the resync window", guard.firstFrame(9_000, playingEntryId = 1))
        guard.loaded(2)
        assertTrue("and the wanted one still gets through", guard.firstFrame(9_100, playingEntryId = 2))
    }

    @Test
    fun `a burst of unopened replacements then ten fast tunes all show a picture`() {
        for (idsReadable in listOf(true, false)) {
            val guard = MpvLoadGuard()
            var now = 1_000L
            var id = 0L
            // Five tunes surfed past before mpv opened any of them, each ended with "stop".
            repeat(5) {
                guard.asked(now)
                guard.entryIdIs(++id)
                if (id > 1) guard.endFile("eof", id - 1, now + 5, farFromEnd)
                now += 16
            }
            // Then ten ordinary tunes, each with a first frame well inside the resync window.
            repeat(10) {
                guard.asked(now)
                guard.entryIdIs(++id)
                guard.endFile("eof", id - 1, now + 5, farFromEnd)
                guard.loaded(id)
                val playing = if (idsReadable) id else null
                assertTrue("tune $id (ids readable: $idsReadable)",
                    guard.firstFrame(now + 400, playingEntryId = playing))
                now += 5_000
            }
        }
    }

    @Test
    fun `with no playing id the counters and time still decide`() {
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.entryIdIs(1)
        guard.asked(1_016)
        guard.entryIdIs(2)
        guard.loaded(1)              // the superseded file opens first
        assertFalse(guard.firstFrame(1_200, playingEntryId = null))
        assertTrue("seconds of quiet settle it", guard.firstFrame(4_000, playingEntryId = null))
    }

    @Test
    fun `an opened file's stop is not counted twice`() {
        // X opened and showed a picture; replacing it ends it with "stop". It was counted when it
        // opened, so counting its end too would let a stale frame through the counters.
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.entryIdIs(1)
        guard.loaded(1)
        assertTrue(guard.firstFrame(1_300))
        guard.asked(5_000)
        guard.entryIdIs(2)
        guard.asked(5_016)
        guard.entryIdIs(3)
        guard.endFile("eof", 1, 5_020, farFromEnd)
        guard.loaded(2)
        assertFalse("entry 2 is not the newest", guard.firstFrame(5_100))
    }

    @Test
    fun `the rejection names the counters for the log`() {
        val guard = MpvLoadGuard()
        guard.asked(1_000)
        guard.entryIdIs(4)
        assertEquals(
            "mpv guard: asked=1 seen=0 failedUnopened=0 sinceAsk=250ms current=4 playing=3",
            guard.describe(1_250, 3))
        guard.entryIdIs(null)
        assertEquals("unknown ids read as null",
            "mpv guard: asked=1 seen=0 failedUnopened=0 sinceAsk=250ms current=null playing=null",
            guard.describe(1_250, null))
    }

    @Test
    fun `the end-file node yields its reason and entry id`() {
        val (reason, id) = MpvLoadGuard.parseEndFile(
            """{"event":"end-file","reason":"error","playlist_entry_id":12,"file_error":"loading failed"}""")
        assertEquals("error", reason)
        assertEquals(12L, id)
        val (eof, none) = MpvLoadGuard.parseEndFile("""{"reason":"stop"}""")
        assertEquals("eof", eof)
        assertNull(none)
        assertEquals("unreadable is eof, never a false error", "eof", MpvLoadGuard.parseEndFile("").first)
    }
}
