package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The margin tests below are positioned deliberately close to the boundary. A fixture set to
 * "grossly expired" would pass whether the margin existed or not - that exact defect was found
 * three times in an earlier phase of this project, in suites that were entirely green.
 */
class ResolvedCacheTest {

    private fun progressive(url: String) = Progressive(url, null)

    @Test
    fun `a stored entry comes back before it expires`() {
        val cache = ResolvedCache()
        cache.put("abcdefghijk", progressive("https://v/1"), expiresAtSeconds = 10_000)
        assertEquals("a hit here is the whole point - it removes a server round trip",
            "https://v/1", cache.get("abcdefghijk", nowSeconds = 1_000)?.videoUrl)
    }

    @Test
    fun `an expired entry is not returned`() {
        val cache = ResolvedCache()
        cache.put("abcdefghijk", progressive("https://v/1"), expiresAtSeconds = 10_000)
        assertNull("a signed URL past its expiry plays nothing, which is worse than a miss",
            cache.get("abcdefghijk", nowSeconds = 10_001))
    }

    @Test
    fun `an entry one second inside the margin is treated as already gone`() {
        val cache = ResolvedCache()
        cache.put("abcdefghijk", progressive("https://v/1"), expiresAtSeconds = 10_000)
        // Not yet expired by its own timestamp - only inside the margin. Delete the margin from
        // the implementation and this is the test that fails.
        assertNull("a URL with under five minutes left expires mid-buffer, which surfaces as a " +
            "stall rather than a clean miss",
            cache.get("abcdefghijk", nowSeconds = 10_000 - SAFETY_MARGIN_SECONDS + 1))
    }

    @Test
    fun `an entry one second outside the margin still returns`() {
        val cache = ResolvedCache()
        cache.put("abcdefghijk", progressive("https://v/1"), expiresAtSeconds = 10_000)
        // The other side of the same boundary: widen the margin and this one fails. The pair
        // pins the margin to a value rather than merely asserting one exists.
        assertEquals("https://v/1",
            cache.get("abcdefghijk", nowSeconds = 10_000 - SAFETY_MARGIN_SECONDS - 1)?.videoUrl)
    }

    @Test
    fun `an unknown id misses`() {
        assertNull(ResolvedCache().get("zzzzzzzzzzz", nowSeconds = 0))
    }

    @Test
    fun `a re-resolved id replaces the entry rather than accumulating`() {
        val cache = ResolvedCache()
        cache.put("abcdefghijk", progressive("https://v/old"), expiresAtSeconds = 10_000)
        cache.put("abcdefghijk", progressive("https://v/new"), expiresAtSeconds = 20_000)
        assertEquals("the later resolve is the live one; serving the old URL would play nothing",
            "https://v/new", cache.get("abcdefghijk", nowSeconds = 1_000)?.videoUrl)
        assertEquals("this map is never pruned, so a second entry per id would leak all session",
            1, cache.size)
    }

    @Test
    fun `reading an expired entry drops it`() {
        val cache = ResolvedCache()
        cache.put("abcdefghijk", progressive("https://v/1"), expiresAtSeconds = 10_000)
        cache.get("abcdefghijk", nowSeconds = 10_001)
        assertEquals("a dead entry left in place is a slow leak over a long viewing session",
            0, cache.size)
    }

    @Test
    fun `a refused url is forgotten so the next resolve fetches a fresh one`() {
        // A 403 on a url that is still inside its stated expiry showed as several seconds of
        // unexplained black: the cache kept handing the same dead url back, and every retry
        // failed the same way. Forgetting it is what turns that into one slow tune.
        val cache = ResolvedCache()
        cache.put("abcdefghijk", progressive("https://v/refused"), expiresAtSeconds = 10_000)
        cache.forget("abcdefghijk")
        assertNull(cache.get("abcdefghijk", nowSeconds = 1_000))
        assertEquals(0, cache.size)
    }

    @Test
    fun `forgetting an id that was never cached is not an error`() {
        // It is called from the playback error path, which fires for live channels and for
        // clips that never reached the cache at all.
        val cache = ResolvedCache()
        cache.put("abcdefghijk", progressive("https://v/1"), expiresAtSeconds = 10_000)
        cache.forget("zzzzzzzzzzz")
        assertEquals("forgetting an unknown id must not disturb the entries that are there",
            1, cache.size)
    }

    @Test
    fun `crossing the sweep threshold drops the entries that are already dead`() {
        // Pins SWEEP_ABOVE to a value. Entries were only ever removed when a read happened to
        // land on a stale one, so a clip resolved once and never revisited was kept for the life
        // of the process. 201 entries is one past the threshold, which is where put() sweeps.
        val cache = ResolvedCache()
        val newest = 100_000L
        // Half of them expired long before the sweep's reference point, half well after it.
        for (i in 0 until 200) {
            val expiry = if (i % 2 == 0) newest - 21_600 - 1_000 else newest
            cache.put("id$i", progressive("https://v/$i"), expiresAtSeconds = expiry)
        }
        assertEquals("nothing should have been swept below the threshold", 200, cache.size)
        cache.put("id200", progressive("https://v/200"), expiresAtSeconds = newest)
        assertEquals("the hundred dead entries should be gone and the live ones untouched",
            101, cache.size)
    }
}
