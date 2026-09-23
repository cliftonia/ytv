package com.cliftonia.fs42tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the stand-by card appears, and when a silent tune is retried.
 *
 * The clock is hand-cranked: [Clock.advance] runs whatever falls due, in order, so a test can put
 * an error exactly three seconds into a grace period - the interleaving that kept the card from
 * ever appearing on a source that failed faster than four seconds.
 */
class RecoveryWatchTest {

    private class Clock {
        var now = 0L
        private val pending = mutableListOf<Pair<Long, () -> Unit>>()

        fun schedule(delay: Long, block: () -> Unit): () -> Unit {
            val entry = (now + delay) to block
            pending.add(entry)
            return { pending.remove(entry) }
        }

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

    private class Fixture {
        val clock = Clock()
        var tuning = true
        var overlay = false
        var card = ""
        val retunes = mutableListOf<String>()
        val errorRetunes = mutableListOf<String>()
        val watch = RecoveryWatch(
            schedule = clock::schedule,
            halted = { false },
            stillTuning = { tuning },
            deferred = { overlay },
            cardUp = { card.isNotEmpty() },
            showCard = { card = it },
            retune = { retunes.add(it) },
            retuneAfterError = { errorRetunes.add(it) },
        )
    }

    @Test
    fun `a source failing faster than the grace still gets its card`() {
        // The old behaviour re-armed four fresh seconds per error, so an error every second
        // pushed the card away forever.
        val f = Fixture()
        f.watch.tuneStarted()
        repeat(6) {
            f.watch.error("MPV_ERROR")
            f.clock.advance(1_000)
        }
        assertEquals("MPV_ERROR", f.card)
    }

    @Test
    fun `an error that recovers inside the grace never shows a card`() {
        val f = Fixture()
        f.watch.tuneStarted()
        f.watch.error("MPV_ERROR")
        f.clock.advance(1_500)
        f.tuning = false
        f.watch.firstFrame()
        f.clock.advance(60_000)
        assertEquals("", f.card)
        assertTrue(f.retunes.isEmpty())
    }

    @Test
    fun `a first frame ends the streak, so the next error gets a full grace`() {
        val f = Fixture()
        f.watch.error("MPV_ERROR")
        f.clock.advance(3_000)
        f.watch.firstFrame()
        f.watch.error("BAD_HTTP_STATUS")
        f.clock.advance(3_000)
        assertEquals("the second streak's grace has a second to run", "", f.card)
        f.clock.advance(1_000)
        assertEquals("BAD_HTTP_STATUS", f.card)
    }

    @Test
    fun `a channel change cancels the card its predecessor armed`() {
        val f = Fixture()
        f.watch.error("MPV_ERROR")
        f.clock.advance(3_000)
        f.watch.tuneStarted()
        f.clock.advance(2_000)
        assertEquals("", f.card)
    }

    @Test
    fun `a tune with no picture is retried once, then the card says so`() {
        // The engine reports nothing at all - no error, no end - so only the watchdog notices.
        val f = Fixture()
        f.watch.tuneStarted()
        f.clock.advance(RecoveryWatch.WATCHDOG_MILLIS)
        assertEquals(1, f.retunes.size)
        assertEquals("", f.card)
        f.clock.advance(RecoveryWatch.WATCHDOG_MILLIS)
        assertEquals("retried once, not forever", 1, f.retunes.size)
        assertEquals(RecoveryWatch.NO_PICTURE, f.card)
    }

    @Test
    fun `a slow tune that makes it inside the watchdog is left alone`() {
        val f = Fixture()
        f.watch.tuneStarted()
        f.clock.advance(8_000)
        f.tuning = false
        f.watch.firstFrame()
        f.clock.advance(60_000)
        assertTrue(f.retunes.isEmpty())
    }

    @Test
    fun `the watchdog waits for an open overlay rather than retuning under it`() {
        val f = Fixture()
        f.watch.tuneStarted()
        f.overlay = true
        f.clock.advance(RecoveryWatch.WATCHDOG_MILLIS * 3)
        assertTrue("no retune under the guide", f.retunes.isEmpty())
        f.overlay = false
        f.clock.advance(RecoveryWatch.WATCHDOG_MILLIS)
        assertEquals(1, f.retunes.size)
    }

    @Test
    fun `the watchdog does not overwrite a definitive card`() {
        val f = Fixture()
        f.watch.tuneStarted()
        f.card = "CHANNEL 7 UNAVAILABLE"
        f.clock.advance(RecoveryWatch.WATCHDOG_MILLIS * 2)
        assertEquals("CHANNEL 7 UNAVAILABLE", f.card)
    }

    @Test
    fun `a url that fails at once is not retried several times a second forever`() {
        // A dead live url fails before mpv opens it, in a few hundred milliseconds. Each retry
        // tunes the identical url; retrying each at once was a storm for as long as the
        // channel stayed on.
        val f = Fixture()
        f.watch.tuneStarted()
        repeat(40) {
            f.watch.error("MPV_ERROR")
            f.clock.advance(250)
        }
        assertEquals("only the first few are immediate", RecoveryWatch.IMMEDIATE_RETRIES,
            f.errorRetunes.size)
        assertEquals("and the card still went up", "MPV_ERROR", f.card)
    }

    @Test
    fun `retries back off, then keep going`() {
        val f = Fixture()
        f.watch.tuneStarted()
        repeat(RecoveryWatch.IMMEDIATE_RETRIES) { f.watch.error("MPV_ERROR") }
        f.watch.error("MPV_ERROR")
        f.clock.advance(4_999)
        assertEquals(3, f.errorRetunes.size)
        f.clock.advance(1)
        assertEquals("the fourth waits five seconds", 4, f.errorRetunes.size)
        assertEquals(15_000L, RecoveryWatch.retryDelayMillis(5))
        assertEquals(30_000L, RecoveryWatch.retryDelayMillis(50))
    }

    @Test
    fun `a picture ends the streak, so the next failure retries at once`() {
        val f = Fixture()
        repeat(5) { f.watch.error("MPV_ERROR") }
        f.tuning = false
        f.watch.firstFrame()
        f.tuning = true
        val before = f.errorRetunes.size
        f.watch.error("MPV_ERROR")
        assertEquals(before + 1, f.errorRetunes.size)
    }

    @Test
    fun `an error under an overlay does not retune`() {
        val f = Fixture()
        f.overlay = true
        f.watch.error("MPV_ERROR")
        f.clock.advance(60_000)
        assertTrue(f.errorRetunes.isEmpty())
    }

    @Test
    fun `a pending backed-off retry is dropped by a channel change`() {
        val f = Fixture()
        repeat(4) { f.watch.error("MPV_ERROR") }
        f.watch.tuneStarted()
        f.clock.advance(5_000)
        assertEquals(3, f.errorRetunes.size)
    }
}
