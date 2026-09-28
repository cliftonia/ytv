package com.cliftonia.fs42tv.pluto

import com.cliftonia.fs42tv.resolver.Hls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A remembered master pick: found only by the session and engine it was read for, never past its
 * TTL, never after its channel failed - and through [MasterPicker], a hit is a tune with no read.
 */
class VariantCacheTest {

    private fun master(jwt: String, channel: String = "abc") =
        "https://s.pluto.tv/v2/stitch/hls/channel/$channel/master.m3u8?sid=1&jwt=$jwt"

    private val muxed = "#EXTM3U\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=3321000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f,mp4a.40.2\"\n" +
        "720p.m3u8\n"

    private val choice = VariantCache.Choice("https://s/720p.m3u8", null)

    private class Picking(var ladder: List<String>? = listOf("hd", "sd")) {
        var now = 0L
        val fetched = mutableListOf<String>()
        var answer: (String) -> BreakPoller.Fetched = { error("no answer set") }
        val cache = VariantCache({ now })
        val picker = MasterPicker(
            fetch = { url -> fetched += url; now += 700; answer(url) },
            mpvLadder = { ladder },
            elapsedMillis = { now },
            cache = cache,
        )
    }

    private fun picking() = Picking().apply { answer = { url -> BreakPoller.Fetched(url, muxed) } }

    @Test
    fun `a remembered pick is a tune with no master read`() {
        val p = picking()
        val first = p.picker.forMpv(Hls(master("J")), cacheable = true) as Hls
        val second = p.picker.forMpv(Hls(master("J")), cacheable = true) as Hls
        assertEquals(1, p.fetched.size)
        assertEquals(first, second)
        assertEquals(master("J"), second.url)
        assertEquals("https://s.pluto.tv/v2/stitch/hls/channel/abc/720p.m3u8", second.mediaUrl)
    }

    @Test
    fun `the legacy route reads every time and remembers nothing`() {
        val p = picking()
        p.picker.forMpv(Hls(master("J")), cacheable = false)
        p.picker.forMpv(Hls(master("J")), cacheable = false)
        assertEquals(2, p.fetched.size)
        assertEquals(0, p.cache.size)
    }

    @Test
    fun `a pick read on another session is not found`() {
        val p = picking()
        p.picker.forMpv(Hls(master("OLD")), cacheable = true)
        p.picker.forMpv(Hls(master("NEW")), cacheable = true)
        assertEquals(2, p.fetched.size)
        val cache = VariantCache({ 0L })
        cache.put(master("OLD"), "mpv@1080", choice)
        assertNull(cache.get(master("NEW"), "mpv@1080"))
    }

    @Test
    fun `a pick made for another engine or ceiling is not found`() {
        val cache = VariantCache({ 0L })
        cache.put(master("J"), "mpv@1080", choice)
        assertNull(cache.get(master("J"), "mpv@480"))
        assertNull(cache.get(master("J"), "media3"))
        assertSame(choice, cache.get(master("J"), "mpv@1080"))
        // Through the picker: QUALITY lowered between two tunes reads the master again.
        val p = picking()
        p.picker.forMpv(Hls(master("J")), cacheable = true)
        p.ladder = listOf("sd")
        p.picker.forMpv(Hls(master("J")), cacheable = true)
        assertEquals(2, p.fetched.size)
    }

    @Test
    fun `Media3 neither reads nor consults the cache`() {
        val p = Picking(ladder = null)
        p.cache.put(master("J"), "mpv@1080", choice)
        val hls = Hls(master("J"))
        assertSame(hls, p.picker.forMpv(hls, cacheable = true))
        assertFalse(p.picker.prefetching())
        assertFalse(p.picker.prefetch(master("J")))
        assertTrue(p.fetched.isEmpty())
    }

    @Test
    fun `a pick past its TTL is read again`() {
        val p = picking()
        p.picker.forMpv(Hls(master("J")), cacheable = true)
        p.now += VariantCache.TTL_MILLIS - 1_000
        p.picker.forMpv(Hls(master("J")), cacheable = true)
        assertEquals(1, p.fetched.size)
        p.now += 1_000
        p.picker.forMpv(Hls(master("J")), cacheable = true)
        assertEquals(2, p.fetched.size)
    }

    @Test
    fun `a channel that failed is read afresh on the re-tune`() {
        val p = picking()
        val played = p.picker.forMpv(Hls(master("J")), cacheable = true)
        p.picker.forget(played)
        p.picker.forMpv(Hls(master("J")), cacheable = true)
        assertEquals(2, p.fetched.size)
    }

    @Test
    fun `a failed read is never remembered`() {
        val p = Picking().apply { answer = { throw java.io.IOException("playlist HTTP 403") } }
        val hls = Hls(master("J"))
        assertSame(hls, p.picker.forMpv(hls, cacheable = true))
        assertSame(hls, p.picker.forMpv(hls, cacheable = true))
        assertEquals(2, p.fetched.size)
        assertEquals(0, p.cache.size)
    }

    @Test
    fun `eviction takes the channel out under every engine and leaves the rest`() {
        val cache = VariantCache({ 0L })
        cache.put(master("J"), "mpv@1080", choice)
        cache.put(master("J"), "mpv@480", choice)
        cache.put(master("J", channel = "other"), "mpv@1080", choice)
        cache.evict(master("J"))
        assertEquals(1, cache.size)
        assertSame(choice, cache.get(master("J", channel = "other"), "mpv@1080"))
    }

    @Test
    fun `the least recently used channel is the one let go`() {
        val cache = VariantCache({ 0L }, capacity = 3)
        cache.put("a", "e", choice)
        cache.put("b", "e", choice)
        cache.put("c", "e", choice)
        cache.get("a", "e")
        cache.put("d", "e", choice)
        assertNull(cache.get("b", "e"))
        assertSame(choice, cache.get("a", "e"))
        assertEquals(3, cache.size)
    }

    @Test
    fun `a read ahead fills the cache once, and a tune then reads nothing`() {
        val p = picking()
        assertTrue(p.picker.prefetch(master("J")))
        assertFalse(p.picker.prefetch(master("J")))
        p.picker.forMpv(Hls(master("J")), cacheable = true)
        assertEquals(1, p.fetched.size)
    }

    @Test
    fun `a read ahead that returns after the app stopped keeps nothing`() {
        val p = picking()
        assertFalse(p.picker.prefetch(master("J")) { false })
        assertEquals(0, p.cache.size)
    }
}
