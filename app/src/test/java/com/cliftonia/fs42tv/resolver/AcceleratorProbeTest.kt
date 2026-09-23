package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background probe's schedule, on a hand-cranked clock.
 *
 * What it must get right is two things at once: a television in the car, where neither address
 * answers, must stop probing every thirty seconds; and one arriving home must find the server
 * again soon - within a tune or two if anyone is surfing.
 */
class AcceleratorProbeTest {

    private class Fixture(var reachable: Boolean = false) {
        var now = 0L
        var probes = 0
        private val pending = mutableListOf<Pair<Long, () -> Unit>>()

        val server = ServerResolver("http://server", { url, _ ->
            if (url.contains("/health")) probes++
            if (reachable) """{"ok":true}""" else error("unreachable")
        }, nowMillis = { now })

        val probe = AcceleratorProbe(
            listOf(server),
            schedule = { delay, block -> pending.add((now + delay) to block) },
            nowMillis = { now },
        )

        /** Run everything that falls due in the next [millis], in order. */
        fun advance(millis: Long) {
            val until = now + millis
            while (true) {
                val next = pending.filter { it.first <= until }.minByOrNull { it.first } ?: break
                pending.remove(next)
                now = next.first
                next.second()
            }
            now = until
        }
    }

    @Test
    fun `the first probe runs at once, off the caller`() {
        val f = Fixture(reachable = true)
        f.probe.start()
        assertEquals("start only queues it", 0, f.probes)
        f.advance(0)
        assertEquals(1, f.probes)
        assertTrue(f.server.isAvailable())
    }

    @Test
    fun `an unreachable server is probed less and less often`() {
        // 30s, 60s, then every 120s - rather than every 30s for as long as the car is driven.
        val f = Fixture(reachable = false)
        f.probe.start()
        f.advance(0)
        f.advance(30_000)
        assertEquals(2, f.probes)
        f.advance(60_000)
        assertEquals(3, f.probes)
        f.advance(120_000)
        assertEquals(4, f.probes)
        f.advance(120_000)
        assertEquals("capped, never abandoned", 5, f.probes)
    }

    @Test
    fun `coming home is noticed by the next periodic round`() {
        val f = Fixture(reachable = false)
        f.probe.start()
        f.advance(0)
        f.reachable = true
        f.advance(AcceleratorProbe.MAX_BACKOFF_MILLIS)
        assertTrue(f.server.isAvailable())
    }

    @Test
    fun `a healthy server is re-read before its reading goes stale`() {
        val f = Fixture(reachable = true)
        f.probe.start()
        f.advance(0)
        f.advance(ServerResolver.FRESH_FOR_MILLIS * 5)
        assertTrue(f.server.isAvailable())
    }

    @Test
    fun `a nudge probes at once, but not twice in quick succession`() {
        val f = Fixture(reachable = false)
        f.probe.start()
        f.advance(0)
        f.advance(30_000)                    // second round; the next waits 60s
        f.reachable = true
        f.advance(AcceleratorProbe.NUDGE_EVERY_MILLIS - 1)
        f.probe.nudge()
        f.advance(0)
        assertEquals("the round a moment ago is recent enough", 2, f.probes)
        f.advance(1)
        f.probe.nudge()
        f.advance(0)
        assertEquals("well before the backoff would have looked", 3, f.probes)
        assertTrue(f.server.isAvailable())
    }

    @Test
    fun `nudges do not multiply the periodic rounds`() {
        val f = Fixture(reachable = false)
        f.probe.start()
        f.advance(0)
        repeat(3) {
            f.advance(AcceleratorProbe.NUDGE_EVERY_MILLIS)
            f.probe.nudge()
        }
        f.advance(0)                          // the last nudge's round
        val before = f.probes
        f.advance(AcceleratorProbe.MAX_BACKOFF_MILLIS - 1)
        assertEquals("the chains the nudges replaced must not wake up", before, f.probes)
    }

    @Test
    fun `stop ends the rounds`() {
        val f = Fixture(reachable = false)
        f.probe.start()
        f.advance(0)
        f.probe.stop()
        f.advance(AcceleratorProbe.MAX_BACKOFF_MILLIS * 3)
        assertEquals(1, f.probes)
        assertFalse(f.server.isAvailable())
    }
}
