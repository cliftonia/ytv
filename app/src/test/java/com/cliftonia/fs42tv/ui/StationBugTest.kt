package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.sync.Channel
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corner logo: what it says, when it appears, and how its images are held.
 *
 * The rule worth pinning is WHEN. A bug that reappears on every error-recovery retune of the same
 * clip reads as a glitch; one that never reappears on a clock channel's new programme misses the
 * one moment a real station would put it back up.
 */
class StationBugTest {

    @Test
    fun `the label is the number and the name, two spaces apart`() {
        assertEquals("47  PLUTO TV ACTION", StationBug.label(Channel(47, "Pluto TV Action", "live")))
        assertEquals("3  NEWS", StationBug.label(Channel(3, "News", "live")))
    }

    @Test
    fun `a deliberate tune shows it on its first frame`() {
        val trigger = BugTrigger()
        trigger.tuneStarted()
        assertTrue(trigger.firstFrame(channel = 5, clip = 0, clock = false))
    }

    @Test
    fun `the very first picture after launch shows it`() {
        assertTrue(BugTrigger().firstFrame(channel = 5, clip = 0, clock = true))
    }

    @Test
    fun `a new programme on a clock channel shows it again`() {
        val trigger = BugTrigger()
        trigger.tuneStarted()
        trigger.firstFrame(5, 0, clock = true)
        assertTrue(trigger.firstFrame(5, 1, clock = true))
    }

    @Test
    fun `recovering the same clip does not`() {
        val trigger = BugTrigger()
        trigger.tuneStarted()
        trigger.firstFrame(5, 3, clock = true)
        assertFalse(trigger.firstFrame(5, 3, clock = true))
    }

    @Test
    fun `a live feed that re-tunes itself does not`() {
        val trigger = BugTrigger()
        trigger.tuneStarted()
        trigger.firstFrame(9, 0, clock = false)
        assertFalse(trigger.firstFrame(9, 0, clock = false))
    }

    @Test
    fun `a tune back to the same channel does`() {
        // Pressing OK in the guide on another channel and coming back is a tune like any other.
        val trigger = BugTrigger()
        trigger.tuneStarted()
        trigger.firstFrame(5, 0, clock = false)
        trigger.tuneStarted()
        assertTrue(trigger.firstFrame(5, 0, clock = false))
    }

    private class Crank : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    @Test
    fun `an image is downloaded once and then served from memory`() {
        val crank = Crank()
        val loads = mutableListOf<String>()
        val cache = ImageCache(load = { url -> loads += url; "img:$url" }, executor = crank)
        val got = mutableListOf<String>()
        cache.get("a") { got += it }
        cache.get("a") { got += it }
        crank.runAll()
        cache.get("a") { got += it }
        assertEquals(listOf("a"), loads)
        assertEquals(listOf("img:a", "img:a", "img:a"), got)
    }

    @Test
    fun `the cache holds a few, not every logo on the dial`() {
        val crank = Crank()
        val loads = mutableListOf<String>()
        val cache = ImageCache(load = { url -> loads += url; url }, executor = crank, capacity = 2)
        listOf("a", "b", "c").forEach { cache.get(it) {}; crank.runAll() }
        cache.get("a") {}
        crank.runAll()
        assertEquals("a was evicted by c and fetched again", listOf("a", "b", "c", "a"), loads)
    }

    @Test
    fun `a shut-down thread refuses the download without throwing`() {
        // The logo is asked for from the Pluto fetch's callback, on the prefetch thread - which
        // the activity shuts down on destroy. execute() then throws; it must not escape.
        val dead = java.util.concurrent.Executors.newSingleThreadExecutor().apply { shutdownNow() }
        var loads = 0
        val cache = ImageCache(load = { loads++; it }, executor = dead)
        cache.get("a") {}
        cache.get("a") {}
        assertEquals(0, loads)
    }

    @Test
    fun `a logo that failed is not asked for again and again`() {
        val crank = Crank()
        var loads = 0
        val cache = ImageCache<String>(load = { loads++; null }, executor = crank)
        var called = false
        cache.get("x") { called = true }
        crank.runAll()
        cache.get("x") { called = true }
        crank.runAll()
        assertEquals(1, loads)
        assertFalse("a failure never calls back; the bug keeps its text", called)
    }
}
