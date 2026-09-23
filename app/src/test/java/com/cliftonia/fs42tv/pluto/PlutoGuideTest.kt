package com.cliftonia.fs42tv.pluto

import java.time.Instant
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the guide asks Pluto, and how often.
 *
 * The executor is hand-cranked so a test can hold a request in the queue while a second one for
 * the same channel arrives - the dedupe that keeps a guide opened on a channel from asking twice.
 */
class PlutoGuideTest {

    private class Crank : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private fun fixture(): String = javaClass.classLoader!!
        .getResourceAsStream("pluto-channel-sample.json")!!.bufferedReader().readText()

    private class Fixture(
        body: () -> String,
        executor: Executor? = null,
        sleeper: ((Long) -> Unit)? = null,
    ) {
        val crank = Crank()
        var now = Instant.parse("2026-09-23T03:00:00Z").toEpochMilli()
        var elapsed = 1_000L
        var enabled = true
        val fetched = mutableListOf<String>()
        val slept = mutableListOf<Long>()
        val guide = PlutoGuide(
            fetch = { url -> fetched += url; body() },
            executor = executor ?: crank,
            nowMillis = { now },
            enabled = { enabled },
            elapsedMillis = { elapsed },
            sleep = sleeper ?: { slept += it; now += it; elapsed += it },
        )
    }

    private fun idOf(url: String) = url.substringAfter("/channels/").substringBefore('?')

    private val id = "68487fb3f212bedacf5a53e3"

    @Test
    fun `a request fetches once and then serves from the cache`() {
        val f = Fixture(::fixture)
        var ready = 0
        f.guide.request(id) { ready++ }
        assertNull("nothing is cached until the fetch has run", f.guide.cached(id))
        f.crank.runAll()
        assertEquals(1, ready)
        assertNotNull(f.guide.cached(id))
        f.guide.request(id) { ready++ }
        f.crank.runAll()
        assertEquals("a cached channel does not go back to the network", 1, f.fetched.size)
    }

    @Test
    fun `two requests for one channel in flight make one fetch and both hear back`() {
        val f = Fixture(::fixture)
        var ready = 0
        f.guide.request(id) { ready++ }
        f.guide.request(id) { ready++ }
        f.crank.runAll()
        assertEquals(1, f.fetched.size)
        assertEquals(2, ready)
    }

    @Test
    fun `the cache expires when the programme on air ends`() {
        val f = Fixture(::fixture)
        f.guide.request(id) {}
        f.crank.runAll()
        f.now = Instant.parse("2026-09-23T03:55:53Z").toEpochMilli()
        assertNull("the next programme has started; the cached now line is stale",
            f.guide.cached(id))
        f.guide.request(id) {}
        f.crank.runAll()
        assertEquals(2, f.fetched.size)
    }

    @Test
    fun `a failure is remembered for a while rather than retried on every focus`() {
        val f = Fixture(body = { throw java.io.IOException("timed out") })
        var ready = 0
        f.guide.request(id) { ready++ }
        f.crank.runAll()
        f.guide.request(id) { ready++ }
        f.crank.runAll()
        assertEquals(1, f.fetched.size)
        assertEquals("a failure never calls back - the banner stays as it was", 0, ready)
        f.now += PlutoGuide.FAILURE_BACKOFF_MILLIS + 1
        f.guide.request(id) { ready++ }
        f.crank.runAll()
        assertEquals(2, f.fetched.size)
    }

    @Test
    fun `requests are spaced out, never a burst`() {
        val f = Fixture(::fixture)
        listOf("a", "b", "c").forEach { f.guide.request(it.repeat(24)) {} }
        f.crank.runAll()
        assertEquals(3, f.fetched.size)
        // The first goes at once; each later one waits out the gap.
        assertEquals(2, f.slept.size)
        assertTrue(f.slept.all { it in 1..PlutoGuide.MIN_GAP_MILLIS })
    }

    @Test
    fun `a backlog is capped, so scrolling the whole dial does not queue the whole dial`() {
        val f = Fixture(::fixture)
        (1..40).forEach { f.guide.request("%024d".format(it)) {} }
        f.crank.runAll()
        assertEquals(PlutoGuide.MAX_QUEUED, f.fetched.size)
    }

    @Test
    fun `a full queue drops the oldest, so the channel surfed to is still asked about`() {
        val f = Fixture(::fixture)
        (1..PlutoGuide.MAX_QUEUED).forEach { f.guide.request("%024d".format(it)) {} }
        var landed = 0
        val target = "f".repeat(24)
        f.guide.request(target) { landed++ }
        f.crank.runAll()
        assertEquals(1, landed)
        assertTrue(f.fetched.map(::idOf).contains(target))
        assertTrue("the oldest made way", !f.fetched.map(::idOf).contains("%024d".format(1)))
        assertEquals(PlutoGuide.MAX_QUEUED, f.fetched.size)
    }

    @Test
    fun `a wall clock stepped backwards cannot park the thread`() {
        // Spacing runs on the monotonic clock; the wall clock jumping a day back changes nothing.
        val f = Fixture(::fixture)
        f.guide.request("a".repeat(24)) {}
        f.crank.runAll()
        f.now -= 86_400_000L
        f.guide.request("b".repeat(24)) {}
        f.crank.runAll()
        assertTrue(f.slept.all { it <= PlutoGuide.MIN_GAP_MILLIS })
    }

    @Test
    fun `a shut-down thread refuses the fetch without throwing`() {
        // shutdownNow() in onDestroy - also on BACK and the SOURCE row's recreate.
        val dead = java.util.concurrent.Executors.newSingleThreadExecutor().apply { shutdownNow() }
        val f = Fixture(::fixture, executor = dead)
        f.guide.request(id) {}
        f.guide.request(id) {}
        assertTrue(f.fetched.isEmpty())
    }

    @Test
    fun `an interrupted spacing sleep ends the fetch quietly`() {
        var interrupt = false
        val f = Fixture(::fixture, sleeper = { if (interrupt) throw InterruptedException() })
        f.guide.request("a".repeat(24)) {}
        f.crank.runAll()
        interrupt = true
        var called = false
        f.guide.request("b".repeat(24)) { called = true }
        f.crank.runAll()
        assertTrue("the interrupt is kept for the executor", Thread.interrupted())
        assertEquals(1, f.fetched.size)
        assertTrue(!called)
        // Not remembered as a failure: asked again later, it fetches.
        interrupt = false
        f.guide.request("b".repeat(24)) { called = true }
        f.crank.runAll()
        assertTrue(called)
    }

    @Test
    fun `switched off, it neither fetches nor answers from the cache`() {
        val f = Fixture(::fixture)
        f.guide.request(id) {}
        f.crank.runAll()
        f.enabled = false
        assertNull(f.guide.cached(id))
        f.guide.request("b".repeat(24)) {}
        f.crank.runAll()
        assertEquals(1, f.fetched.size)
    }
}
