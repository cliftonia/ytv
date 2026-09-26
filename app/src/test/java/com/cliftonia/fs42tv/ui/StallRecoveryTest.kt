package com.cliftonia.fs42tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mpv frozen on a live Pluto stream - ffmpeg's hls demuxer lost at a discontinuity, `cache=0` for
 * ever - reloaded after [StallRecovery.STALL_LIMIT_MILLIS], a bounded number of times.
 */
class StallRecoveryTest {

    private class Fixture {
        var now = 0L
        var channel: Int? = 7
        var deferred = false
        val recovered = mutableListOf<String>()
        val gaveUp = mutableListOf<String>()
        private class Job(val at: Long, val block: () -> Unit) { var cancelled = false }
        private val jobs = mutableListOf<Job>()

        val recovery = StallRecovery(
            schedule = { delay, block ->
                val job = Job(now + delay, block)
                jobs += job
                ({ job.cancelled = true })
            },
            nowMillis = { now },
            channel = { channel },
            deferred = { deferred },
            recover = { recovered += it },
            giveUp = { gaveUp += it },
        )

        fun advance(millis: Long) {
            val until = now + millis
            while (true) {
                val due = jobs.filter { it.at <= until && !it.cancelled }.minByOrNull { it.at } ?: break
                jobs -= due
                now = due.at
                due.block()
            }
            now = until
        }

        /** A stall that lasts past the limit, and the reload's picture: one recovery's worth. */
        fun stallOut() {
            recovery.buffering(true)
            advance(StallRecovery.STALL_LIMIT_MILLIS)
            recovery.buffering(false)
        }
    }

    @Test
    fun `a stall past the limit reloads the channel`() {
        val f = Fixture()
        f.recovery.buffering(true)
        f.advance(StallRecovery.STALL_LIMIT_MILLIS - 1)
        assertTrue(f.recovered.isEmpty())
        f.advance(1)
        assertEquals(1, f.recovered.size)
    }

    @Test
    fun `a reload that never shows a picture is still a stall`() {
        val f = Fixture()
        f.recovery.buffering(true)
        f.advance(StallRecovery.STALL_LIMIT_MILLIS * StallRecovery.MAX_RECOVERIES)
        assertEquals(StallRecovery.MAX_RECOVERIES, f.recovered.size)
        f.advance(StallRecovery.STALL_LIMIT_MILLIS)
        assertEquals(1, f.gaveUp.size)
        f.advance(60_000)
        assertEquals("handed over once, not again and again", 1, f.gaveUp.size)
    }

    @Test
    fun `a stall that clears in time is left to the player`() {
        val f = Fixture()
        f.recovery.buffering(true)
        f.advance(StallRecovery.STALL_LIMIT_MILLIS / 2)
        f.recovery.buffering(false)
        f.advance(StallRecovery.STALL_LIMIT_MILLIS * 2)
        assertTrue(f.recovered.isEmpty())
    }

    @Test
    fun `anything but a live Pluto stream under mpv is never reloaded`() {
        // YouTube on a slow line: a re-tune there discards the buffer and never recovers (StallPill).
        val f = Fixture()
        f.channel = null
        f.stallOut()
        f.advance(60_000)
        assertTrue(f.recovered.isEmpty())
        assertTrue(f.gaveUp.isEmpty())
    }

    @Test
    fun `a channel change clears a stall in progress`() {
        val f = Fixture()
        f.recovery.buffering(true)
        f.recovery.clear()
        f.advance(StallRecovery.STALL_LIMIT_MILLIS * 2)
        assertTrue(f.recovered.isEmpty())
    }

    @Test
    fun `three reloads in the window, then the normal error path`() {
        val f = Fixture()
        repeat(StallRecovery.MAX_RECOVERIES) { f.stallOut(); f.advance(10_000) }
        assertEquals(StallRecovery.MAX_RECOVERIES, f.recovered.size)
        f.stallOut()
        assertEquals(StallRecovery.MAX_RECOVERIES, f.recovered.size)
        assertEquals(1, f.gaveUp.size)
    }

    @Test
    fun `reloads older than the window no longer count`() {
        val f = Fixture()
        repeat(StallRecovery.MAX_RECOVERIES) { f.stallOut() }
        f.advance(StallRecovery.WINDOW_MILLIS)
        f.stallOut()
        assertEquals(StallRecovery.MAX_RECOVERIES + 1, f.recovered.size)
        assertTrue(f.gaveUp.isEmpty())
    }

    @Test
    fun `another channel starts its own count`() {
        val f = Fixture()
        repeat(StallRecovery.MAX_RECOVERIES) { f.stallOut() }
        f.channel = 8
        f.stallOut()
        assertEquals(StallRecovery.MAX_RECOVERIES + 1, f.recovered.size)
        assertTrue(f.gaveUp.isEmpty())
    }

    @Test
    fun `under the guide the reload waits, then happens if still stalled`() {
        val f = Fixture()
        f.deferred = true
        f.recovery.buffering(true)
        f.advance(StallRecovery.STALL_LIMIT_MILLIS)
        assertTrue(f.recovered.isEmpty())
        f.deferred = false
        f.advance(StallRecovery.DEFERRED_RECHECK_MILLIS)
        assertEquals(1, f.recovered.size)
    }
}
