package com.cliftonia.fs42tv.prejoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pre-join's memory: served only while young, taken once, and never over its budget. */
class PrejoinCacheTest {

    private var now = 0L

    private fun snapshot(media: String, bytes: Int, target: Long = 6_000L, segments: Int = 1) =
        PrejoinCache.Snapshot(media, "#EXTM3U", media, now, 1_000_000L + now, target,
            (0 until segments).associate { "$media/seg$it.ts" to ByteArray(bytes) })

    @Test
    fun `a young snapshot is served once - a stale one is a miss and dropped`() {
        val cache = PrejoinCache({ now })
        cache.put(snapshot("a", 10))
        now += 5_000
        assertNotNull(cache.take("a"))
        assertNull("taken once", cache.take("a"))
        cache.put(snapshot("b", 10))
        now += PrejoinCache.servableMillis(6_000L) + 1
        val why = mutableListOf<String>()
        assertNull(cache.take("b") { why += it })
        assertTrue(why.single().startsWith("stale"))
        assertEquals(0, cache.size)
    }

    @Test
    fun `the window is about a target duration, within bounds`() {
        assertEquals(9_000L, PrejoinCache.servableMillis(6_000L))
        assertEquals(3_000L, PrejoinCache.servableMillis(1_000L))
        assertEquals(12_000L, PrejoinCache.servableMillis(10_000L))
    }

    @Test
    fun `storing past the budget lets the eldest go, and one snapshot alone over it is not kept`() {
        val cache = PrejoinCache({ now }, maxBytes = 100)
        assertTrue(cache.put(snapshot("a", 40)))
        assertTrue(cache.put(snapshot("b", 40)))
        assertTrue(cache.put(snapshot("c", 40)))
        assertEquals(80L, cache.bytes)
        assertNull("a was eldest", cache.take("a"))
        assertFalse(cache.put(snapshot("huge", 60, segments = 2)))
        assertTrue(cache.bytes <= 100)
        // Re-storing a channel replaces its own snapshot, it does not count twice.
        assertTrue(cache.put(snapshot("c", 50)))
        assertEquals(90L, cache.bytes)
    }

    @Test
    fun `the budget is the one the television can spare`() {
        assertTrue(PrejoinCache.MAX_BYTES in 20L * 1024 * 1024..30L * 1024 * 1024)
    }

    @Test
    fun `prune drops only what is too old to serve`() {
        val cache = PrejoinCache({ now })
        cache.put(snapshot("old", 10))
        now += 8_000
        cache.put(snapshot("young", 10))
        now += 2_000
        cache.prune()
        assertEquals(1, cache.size)
        assertNotNull(cache.take("young"))
    }
}
