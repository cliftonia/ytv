package com.cliftonia.fs42tv.ads

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cache: a file, at most a day old, fetched off the caller's thread, never poisoned. */
class AdCatalogStoreTest {

    private val good = """{"generated": 1, "reels": [{"id": "a", "url": "https://archive.org/download/a/a.mp4",
        "duration": 600, "cuts": [0, 30]}]}"""

    private class World {
        val dir: File = Files.createTempDirectory("ads").toFile()
        val file = File(dir, AdCatalogStore.FILE_NAME)
        var now = 1_800_000_000_000L
        var body: String? = null
        var fetches = 0
        val queued = mutableListOf<Runnable>()

        fun store() = AdCatalogStore(
            file = file,
            fetch = { fetches++; body ?: throw IOException("HTTP 404") },
            nowMillis = { now },
            background = { queued += it },
        )

        fun drain() {
            while (queued.isNotEmpty()) queued.removeAt(0).run()
        }
    }

    @Test
    fun `the first refresh fetches, caches and answers - off the caller's thread`() {
        val world = World().apply { body = good }
        val store = world.store()
        store.refreshIfStale()
        assertNull("nothing before the background work has run", store.current())
        world.drain()
        assertEquals("a", store.current()!!.reels.single().id)
        assertTrue(world.file.readText().contains("\"a\""))
        assertFalse("written whole, via a renamed temporary", File(world.dir, "ads.json.tmp").exists())
    }

    @Test
    fun `a 404 is no ads, writes nothing, and is not asked again for an hour`() {
        val world = World()
        val store = world.store()
        store.refreshIfStale()
        world.drain()
        assertNull(store.current())
        assertFalse(world.file.exists())
        world.now += AdCatalogStore.RETRY_MILLIS - 1
        store.refreshIfStale()
        world.drain()
        assertEquals(1, world.fetches)
        world.now += 1
        world.body = good
        store.refreshIfStale()
        world.drain()
        assertEquals(2, world.fetches)
        assertEquals(1, store.current()!!.reels.size)
    }

    @Test
    fun `a fresh file is read, not fetched`() {
        val world = World()
        world.file.writeText(good)
        world.file.setLastModified(world.now - 60_000L)
        val store = world.store()
        store.refreshIfStale()
        world.drain()
        assertEquals(0, world.fetches)
        assertEquals("a", store.current()!!.reels.single().id)
        // And once in hand and fresh, nothing is even started.
        store.refreshIfStale()
        assertTrue(world.queued.isEmpty())
    }

    @Test
    fun `a day-old file is served while the new one is fetched, and a bad body never replaces it`() {
        val world = World()
        world.file.writeText(good)
        world.file.setLastModified(world.now - AdCatalogStore.FRESH_MILLIS - 1_000L)
        world.body = "<html>rate limited</html>"
        val store = world.store()
        store.refreshIfStale()
        world.drain()
        assertEquals(1, world.fetches)
        assertEquals("the old copy stands", "a", store.current()!!.reels.single().id)
        assertEquals(good, world.file.readText())
    }

    @Test
    fun `a file written in the future is not fresh - the box boots with a wrong clock`() {
        val world = World()
        world.file.writeText(good)
        world.file.setLastModified(world.now + 3_600_000L)
        world.body = """{"generated": 2, "reels": []}"""
        val store = world.store()
        store.refreshIfStale()
        world.drain()
        assertEquals(1, world.fetches)
        assertTrue("an empty list that parses is the server saying no ads", store.current()!!.reels.isEmpty())
    }

    @Test
    fun `one refresh at a time`() {
        val world = World().apply { body = good }
        val store = world.store()
        store.refreshIfStale()
        store.refreshIfStale()
        assertEquals(1, world.queued.size)
    }
}
