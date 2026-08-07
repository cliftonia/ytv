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
}
