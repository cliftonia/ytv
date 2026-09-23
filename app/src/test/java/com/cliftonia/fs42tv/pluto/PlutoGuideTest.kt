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

    private class Fixture(body: () -> String) {
        val crank = Crank()
        var now = Instant.parse("2026-09-23T03:00:00Z").toEpochMilli()
        var enabled = true
        val fetched = mutableListOf<String>()
        val slept = mutableListOf<Long>()
        val guide = PlutoGuide(
            fetch = { url -> fetched += url; body() },
            executor = crank,
            nowMillis = { now },
            enabled = { enabled },
            sleep = { slept += it; now += it },
        )
    }

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
        val f = Fixture { throw java.io.IOException("timed out") }
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
