package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing between a resolve accelerator and the device.
 *
 * The fallback is what these mostly test, because it is what must never break: one television
 * lives in a car on a phone hotspot, will not reach the server, and has to behave exactly as it
 * did before the server existed. An accelerator that turns into a dependency is worse than no
 * accelerator, and the app has already lost a whole dial that way once.
 */
class AcceleratedResolverTest {

    private val resolved = ClipResolver.Resolved(
        Progressive("https://v/from-device", "https://a/from-device"), expiresAtSeconds = 9_999, tier = "hd")
    private val fromServer = ClipResolver.Resolved(
        Progressive("https://v/from-server", "https://a/from-server"), expiresAtSeconds = 9_999, tier = "hd")

    private class FakeDevice(val answer: ClipResolver.Resolved?) : ClipResolver {
        var calls = 0
        override fun resolveDetailed(
            videoId: String, nowSeconds: Long, ladder: List<String>, refused: Set<String>,
        ): ClipResolver.Resolved? {
            calls++
            return answer
        }
    }

    /**
     * A server whose every response is scripted, and which counts what it was asked. Probed once
     * up front, as the background probe would have by the time anyone tunes; [probed] false
     * leaves it with no reading at all, which is how a launch looks before the first probe lands.
     */
    private fun server(
        health: String?,
        resolve: String?,
        probed: Boolean = true,
    ): Pair<ServerResolver, MutableList<String>> {
        val asked = mutableListOf<String>()
        val s = ServerResolver("http://server", { url, _ ->
            asked.add(url)
            val body = if (url.contains("/health")) health else resolve
            body ?: error("unreachable")
        })
        if (probed) s.probe()
        return s to asked
    }

    private val healthy = """{"ok":true,"cached":180}"""
    private val tiers = """{"id":"x","hd":{"video":"https://v/from-server",
        "audio":"https://a/from-server","expires":9999}}"""

    @Test
    fun `an unreachable server falls through to the device`() {
        // The car. Nothing is thrown, nothing is logged as an error, and the viewer gets a
        // picture exactly as they did before any of this existed.
        val device = FakeDevice(resolved)
        val (s, _) = server(health = null, resolve = null)
        val got = AcceleratedResolver(listOf(s), device).resolveDetailed("abc12345678", 100, listOf("hd"))
        assertEquals("https://v/from-device", (got?.playable)?.videoUrl)
        assertEquals(1, device.calls)
    }

    @Test
    fun `a reachable server is preferred, and the device is not troubled`() {
        val device = FakeDevice(resolved)
        val (s, _) = server(healthy, tiers)
        val got = AcceleratedResolver(listOf(s), device).resolveDetailed("abc12345678", 100, listOf("hd"))
        assertEquals("https://v/from-server", (got?.playable)?.videoUrl)
        assertEquals("the device must not resolve what the server already answered", 0, device.calls)
    }

    @Test
    fun `a server that answers health but not the clip still falls through`() {
        // Ordinary rather than a fault: the server pre-warms what is on air, and a clip reached
        // by skipping a dead one is simply not in its cache yet.
        val device = FakeDevice(resolved)
        val (s, _) = server(health = healthy, resolve = null)
        val got = AcceleratedResolver(listOf(s), device).resolveDetailed("abc12345678", 100, listOf("hd"))
        assertEquals("https://v/from-device", (got?.playable)?.videoUrl)
    }

    @Test
    fun `a server reporting a broken extractor is not asked at all`() {
        // The accelerator's own yt-dlp sat broken for six months once. In that state it answers
        // every resolve with a 404, so trusting mere liveness would make the dial slower than
        // never asking.
        val device = FakeDevice(resolved)
        val (s, asked) = server(health = """{"ok":false,"extractor":{"ok":false}}""", resolve = tiers)
        AcceleratedResolver(listOf(s), device).resolveDetailed("abc12345678", 100, listOf("hd"))
        assertEquals(1, device.calls)
        assertTrue("a server that says it is broken must not be asked to resolve",
            asked.none { it.contains("/resolve") })
    }

    @Test
    fun `the tune path never probes health itself`() {
        // Probing inline paid a connection timeout per address every thirty seconds in front of
        // a channel change - in the car, where nothing answers, an accelerator that made the
        // dial slower. An unknown server is simply skipped.
        val device = FakeDevice(resolved)
        val (s, asked) = server(healthy, tiers, probed = false)
        val accelerated = AcceleratedResolver(listOf(s), device)
        repeat(5) { accelerated.resolveDetailed("abc12345678", 100, listOf("hd")) }
        assertTrue("no health check on the tune path", asked.none { it.contains("/health") })
        assertEquals(5, device.calls)
    }

    @Test
    fun `a stale healthy reading is not trusted`() {
        var now = 0L
        val device = FakeDevice(resolved)
        val s = ServerResolver("http://server", { url, _ ->
            if (url.contains("/health")) healthy else tiers
        }, nowMillis = { now })
        s.probe()
        assertTrue(s.isAvailable())
        now += ServerResolver.FRESH_FOR_MILLIS
        AcceleratedResolver(listOf(s), device).resolveDetailed("abc12345678", 100, listOf("hd"))
        assertEquals("a reading that old could describe a network the set has left", 1, device.calls)
    }

    @Test
    fun `a failed resolve retires the server until the next probe`() {
        val device = FakeDevice(resolved)
        val (s, _) = server(healthy, resolve = null)
        AcceleratedResolver(listOf(s), device).resolveDetailed("abc12345678", 100, listOf("hd"))
        assertTrue("one timeout is enough", !s.isAvailable())
    }

    @Test
    fun `no usable server nudges a background probe`() {
        val (s, _) = server(health = null, resolve = null)
        val scheduled = mutableListOf<() -> Unit>()
        val probe = AcceleratorProbe(listOf(s), { _, block -> scheduled.add(block) }, { 0L })
        AcceleratedResolver(listOf(s), FakeDevice(resolved), probe = probe)
            .resolveDetailed("abc12345678", 100, listOf("hd"))
        assertEquals("queued, not run on the tune path", 1, scheduled.size)
    }

    @Test
    fun `the device still answers when neither has anything`() {
        val device = FakeDevice(null)
        val (s, _) = server(health = null, resolve = null)
        assertNull(AcceleratedResolver(listOf(s), device).resolveDetailed("abc12345678", 100, listOf("hd")))
        assertEquals(1, device.calls)
    }

    @Test
    fun `an unreachable first server falls through to the second, not to the device`() {
        // One machine, two networks: at home the LAN address answers and the tailnet one is
        // never consulted; a set that reaches only the tailnet must still get its 5ms resolves
        // rather than paying a 2.4-second on-device extraction.
        val (dead, _) = server(health = null, resolve = null)
        val (alive, asked) = server(
            health = """{"ok":true}""",
            resolve = """{"hd":{"video":"https://v/from-server","audio":"https://a/1","expires":9999}}""",
        )
        val device = FakeDevice(resolved)
        val got = AcceleratedResolver(listOf(dead, alive), device)
            .resolveDetailed("abc12345678", 100, listOf("hd"))
        assertEquals("https://v/from-server", got?.playable?.videoUrl)
        assertEquals("the device must not have been consulted", 0, device.calls)
        assertTrue(asked.any { it.contains("/resolve") })
    }

    @Test
    fun `a clip with every rung refused asks neither the server nor the device`() {
        // Both skip refused rungs, so both could only answer null - after a round trip or 2.4s
        // of extraction. Condemned clips were paying that on every retune.
        val device = FakeDevice(resolved)
        val (s, asked) = server(healthy, tiers)
        val refused = setOf("abc12345678/hd", "abc12345678/sd")
        val got = AcceleratedResolver(listOf(s), device)
            .resolveDetailed("abc12345678", 100, listOf("hd", "sd"), refused)
        assertNull(got)
        assertEquals(0, device.calls)
        assertTrue("the server is not asked", asked.none { it.contains("/resolve") })
    }
}
