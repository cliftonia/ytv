package com.cliftonia.fs42tv.details

import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's pictures: newest ask wins, one fetch per url, memory then disk then network, and a
 * dead link is not asked again on every keypress. Pictures are strings here - the rules do not
 * care what a decoded picture is.
 */
class ArtCacheTest {

    private inner class Fixture(maxBytes: Int = 1_000, maxFiles: Int = 10) {
        val dir: File = java.nio.file.Files.createTempDirectory("art").toFile()
        val queued = mutableListOf<Runnable>()
        val fetched = mutableListOf<String>()
        var now = 0L
        var failing = false
        val cache = ArtCache(
            dir = dir,
            fetch = { url -> fetched += url; if (failing) error("offline"); "img:$url".toByteArray() },
            decode = { String(it) },
            sizeOf = { it.length },
            executor = Executor { queued += it },
            elapsedMillis = { now },
            maxBytes = maxBytes,
            maxFiles = maxFiles,
        )

        fun drain() {
            while (queued.isNotEmpty()) queued.removeAt(0).run()
        }
    }

    @Test
    fun `a picture is fetched once, then answered from memory`() {
        val f = Fixture()
        val got = mutableListOf<String>()
        f.cache.request("https://a/1.jpg") { got += it }
        f.cache.request("https://a/1.jpg") { got += it }
        f.drain()
        assertEquals(listOf("img:https://a/1.jpg", "img:https://a/1.jpg"), got)
        assertEquals(1, f.fetched.size)
        assertEquals("img:https://a/1.jpg", f.cache.cached("https://a/1.jpg"))
    }

    @Test
    fun `a held DOWN keeps only the newest asks`() {
        val f = Fixture()
        val got = mutableListOf<String>()
        (1..10).forEach { f.cache.request("https://a/$it.jpg") { p -> got += p } }
        f.drain()
        assertEquals((7..10).map { "img:https://a/$it.jpg" }, got)
        assertEquals(ArtCache.MAX_QUEUED, f.fetched.size)
    }

    @Test
    fun `a picture on disk needs no network, even in a new cache`() {
        val f = Fixture()
        f.cache.request("https://a/1.jpg") {}
        f.drain()
        val again = ArtCache(f.dir, { error("no network") }, { String(it) }, { it.length },
            Executor { it.run() }, { 0L })
        var got: String? = null
        again.request("https://a/1.jpg") { got = it }
        assertEquals("img:https://a/1.jpg", got)
    }

    @Test
    fun `a failure is not retried until the backoff has passed`() {
        val f = Fixture()
        f.failing = true
        var got: String? = null
        f.cache.request("https://a/x.jpg") { got = it }
        f.drain()
        f.cache.request("https://a/x.jpg") { got = it }
        f.drain()
        assertEquals(1, f.fetched.size)
        assertNull(got)
        f.failing = false
        f.now += ArtCache.FAILURE_BACKOFF_MILLIS
        f.cache.request("https://a/x.jpg") { got = it }
        f.drain()
        assertEquals("img:https://a/x.jpg", got)
    }

    @Test
    fun `a failure says so, then and inside the backoff, so the pane can give the slot back`() {
        val f = Fixture()
        f.failing = true
        var failures = 0
        f.cache.request("https://a/x.jpg", onFailed = { failures++ }) {}
        f.drain()
        assertEquals(1, failures)
        f.cache.request("https://a/x.jpg", onFailed = { failures++ }) {}
        assertEquals("straight back, no fetch", 2, failures)
        assertEquals(1, f.fetched.size)
    }

    @Test
    fun `memory is bounded, oldest out first`() {
        val f = Fixture(maxBytes = 40)
        (1..3).forEach { f.cache.request("https://a/$it.jpg") {}; f.drain() }
        assertNull(f.cache.cached("https://a/1.jpg"))
        assertEquals("img:https://a/3.jpg", f.cache.cached("https://a/3.jpg"))
    }

    @Test
    fun `the disk is bounded too`() {
        val f = Fixture(maxFiles = 3)
        (1..6).forEach { f.cache.request("https://a/$it.jpg") {}; f.drain() }
        assertTrue(f.dir.listFiles()!!.size <= 3)
    }
}
