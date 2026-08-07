package com.cliftonia.fs42tv.resolver

import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.sync.Tier
import com.cliftonia.fs42tv.sync.UrlCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking the wrong source is rarely a crash. It is 480p on a 4K panel, silence where
 * there should be audio, or a round trip to the server that a cached URL would have
 * avoided - all of which look like "the app is bad" rather than a bug with a location.
 */
class StreamResolverTest {

    private val hd = Tier(video = "https://v/hd", audio = "https://a/hd", expires = 10_000)
    private val uhd = Tier(video = "https://v/uhd", audio = "https://a/uhd", expires = 10_000)
    private val yt = Stream(id = "abc12345678", url = "https://youtube.com/watch?v=abc12345678",
        duration = 100, title = "t")
    private val live = Stream(id = null, url = "https://x/stream.m3u8", duration = 600, title = "t")

    private fun cacheOf(vararg tiers: Pair<String, Tier>) =
        UrlCache(generated = 0, urls = mapOf("abc12345678" to tiers.toMap()))

    @Test
    fun `a live stream plays directly as HLS`() {
        val result = StreamResolver.resolve(live, null, preferUhd = false, nowSeconds = 0)
        assertEquals("a live stream needs no resolution and must not wait on the server",
            Hls("https://x/stream.m3u8"), result)
    }

    @Test
    fun `a 4K device gets the uhd tier`() {
        val result = StreamResolver.resolve(yt, cacheOf("hd" to hd, "uhd" to uhd),
            preferUhd = true, nowSeconds = 0)
        assertEquals(Progressive("https://v/uhd", "https://a/uhd"), result)
    }

    @Test
    fun `a 1080p device gets hd even when uhd exists`() {
        val result = StreamResolver.resolve(yt, cacheOf("hd" to hd, "uhd" to uhd),
            preferUhd = false, nowSeconds = 0)
        assertEquals("sending 4K to a 1080p device wastes bandwidth it may be paying for",
            Progressive("https://v/hd", "https://a/hd"), result)
    }

    @Test
    fun `a 4K device falls back to hd when there is no uhd tier`() {
        val result = StreamResolver.resolve(yt, cacheOf("hd" to hd), preferUhd = true, nowSeconds = 0)
        assertEquals("not every video offers 4K; refusing to play would be worse than 1080p",
            Progressive("https://v/hd", "https://a/hd"), result)
    }

    @Test
    fun `an expired tier is not used`() {
        val stale = Tier(video = "https://v/old", audio = "https://a/old", expires = 100)
        val result = StreamResolver.resolve(yt, cacheOf("hd" to stale),
            preferUhd = false, nowSeconds = 500)
        assertEquals("a signed URL past its expiry returns 403 and shows as a dead channel",
            NeedsResolving("abc12345678"), result)
    }

    @Test
    fun `a tier inside the safety margin is treated as dead`() {
        val nearExpiry = Tier(video = "https://v/near", audio = "https://a/near", expires = 400)
        val result = StreamResolver.resolve(yt, cacheOf("hd" to nearExpiry),
            preferUhd = false, nowSeconds = 200)
        assertEquals("a URL expiring mid-programme leaves the viewer watching a dead channel, so we retire it early",
            NeedsResolving("abc12345678"), result)
    }

    @Test
    fun `a tier outside the safety margin is used`() {
        val goodLife = Tier(video = "https://v/good", audio = "https://a/good", expires = 700)
        val result = StreamResolver.resolve(yt, cacheOf("hd" to goodLife),
            preferUhd = false, nowSeconds = 200)
        assertEquals("a URL with sufficient life beyond the margin plays without interruption",
            Progressive("https://v/good", "https://a/good"), result)
    }

    @Test
    fun `a missing cache entry needs resolving`() {
        val result = StreamResolver.resolve(yt, UrlCache(), preferUhd = false, nowSeconds = 0)
        assertEquals(NeedsResolving("abc12345678"), result)
    }

    @Test
    fun `a null cache needs resolving rather than throwing`() {
        val result = StreamResolver.resolve(yt, null, preferUhd = false, nowSeconds = 0)
        assertTrue("before the first sync the app must still know what to ask for",
            result is NeedsResolving)
    }
}
