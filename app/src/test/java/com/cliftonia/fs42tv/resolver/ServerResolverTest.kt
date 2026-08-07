package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fallback for a cache miss. At 46% cache coverage this path runs more often than the
 * cached one, so a failure here is not an edge case - it is most of the dial going dark.
 */
class ServerResolverTest {

    private val hdOnly = """{"id":"abc12345678",
        "hd":{"video":"https://v/hd","audio":"https://a/hd","expires":9999999999}}"""

    private val bothTiers = """{"id":"abc12345678",
        "hd":{"video":"https://v/hd","audio":"https://a/hd","expires":9999999999},
        "uhd":{"video":"https://v/uhd","audio":"https://a/uhd","expires":9999999999}}"""

    private fun resolver(body: String, capture: MutableList<String>? = null) =
        ServerResolver(fetch = { url -> capture?.add(url); body }, baseUrl = "http://server")

    @Test
    fun `returns the hd tier`() {
        assertEquals(Progressive("https://v/hd", "https://a/hd"),
            resolver(hdOnly).resolve("abc12345678"))
    }

    @Test
    fun `a 4K device prefers uhd when the server offers it`() {
        assertEquals(Progressive("https://v/uhd", "https://a/uhd"),
            resolver(bothTiers).resolve("abc12345678", preferUhd = true))
    }

    @Test
    fun `a 1080p device takes hd even when uhd is offered`() {
        assertEquals("sending 4K to a 1080p device wastes bandwidth it may be paying for",
            Progressive("https://v/hd", "https://a/hd"),
            resolver(bothTiers).resolve("abc12345678", preferUhd = false))
    }

    @Test
    fun `a 4K device falls back to hd when the server has no uhd`() {
        assertEquals("not every video offers 4K; refusing to play would be worse than 1080p",
            Progressive("https://v/hd", "https://a/hd"),
            resolver(hdOnly).resolve("abc12345678", preferUhd = true))
    }

    @Test
    fun `the id key alongside the tiers does not break parsing`() {
        assertTrue("the endpoint returns id next to the tiers; treating it as a tier would throw",
            resolver(hdOnly).resolve("abc12345678") != null)
    }

    @Test
    fun `asks the server for the right video`() {
        val urls = mutableListOf<String>()
        resolver(hdOnly, urls).resolve("abc12345678")
        assertEquals("http://server/resolve?v=abc12345678", urls.single())
    }

    @Test
    fun `a server error yields null rather than throwing`() {
        val failing = ServerResolver(fetch = { throw java.io.IOException("unreachable") },
            baseUrl = "http://server")
        assertNull("an unreachable server must skip the clip, not crash the app",
            failing.resolve("abc12345678"))
    }

    @Test
    fun `a malformed response yields null rather than throwing`() {
        assertNull("a half-written response must not take the player down",
            resolver("{ not json").resolve("abc12345678"))
    }

    @Test
    fun `a 404 body with no tiers yields null`() {
        assertNull("the server returns 404 when it cannot resolve; that is a skip, not a crash",
            resolver("""{"detail":"could not resolve"}""").resolve("abc12345678"))
    }
}
