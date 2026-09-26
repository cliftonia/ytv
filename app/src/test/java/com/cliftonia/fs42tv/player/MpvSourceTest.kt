package com.cliftonia.fs42tv.player

import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.Unplayable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which tracks mpv fetches through the loopback proxy, and how the two YouTube tracks are joined.
 *
 * Unreachable from a test until it left [MpvChannelPlayer], because `MPVLib` loads a native
 * library the moment that class is touched. Getting it wrong is not visible in a log: a track
 * that bypasses the proxy is throttled to roughly its own bitrate, which shows up as mpv taking
 * seven seconds to put a picture up rather than one and a half.
 */
class MpvSourceTest {

    private val proxied: (String) -> String = { "http://127.0.0.1:9/p?$it" }

    @Test
    fun `a video-only progressive has no separate audio track`() {
        // One file, so there is nothing to attach; naming an audio track that does not exist
        // would fail the load rather than play silently.
        val load = MpvSource.loadFor(Progressive("https://v/1", null), proxied)!!
        assertEquals("http://127.0.0.1:9/p?https://v/1", load.url)
        assertNull(load.audioFile)
    }

    @Test
    fun `separate tracks are joined as an external audio file, not an EDL`() {
        // The reason this is not an EDL: a signed googlevideo url can be refused while still
        // inside its stated expiry, and an EDL whose streams all fail is fatal to mpv's core -
        // it shuts down, and a dial has to survive a dead clip. An external track degrades
        // instead, to a silent picture or an ordinary file error.
        val load = MpvSource.loadFor(Progressive("https://v/1", "https://a/1"), proxied)!!
        assertEquals("http://127.0.0.1:9/p?https://v/1", load.url)
        assertEquals("http://127.0.0.1:9/p?https://a/1", load.audioFile)
    }

    @Test
    fun `both tracks go through the proxy`() {
        // The audio track is small, but it is fetched over the same throttled connection, and a
        // starved audio track stalls the video just as surely.
        val seen = mutableListOf<String>()
        MpvSource.loadFor(Progressive("https://v/1", "https://a/1")) { seen.add(it); it }
        assertEquals(listOf("https://v/1", "https://a/1"), seen)
    }

    @Test
    fun `a live feed bypasses the proxy entirely`() {
        // HLS is already a series of bounded segment requests, which is why it was never
        // throttled and never slow. Proxying it would add a hop for nothing.
        val load = MpvSource.loadFor(Hls("https://x/abc.m3u8")) {
            error("a live feed must never reach the proxy")
        }!!
        assertEquals("https://x/abc.m3u8", load.url)
        assertNull(load.audioFile)
    }

    @Test
    fun `a chosen media playlist is opened instead of its master, with its audio beside it`() {
        // mpv opens a master by probing every variant (7-11s for Pluto); one playlist takes ~3s.
        val load = MpvSource.loadFor(Hls("https://x/master.m3u8", "https://x/720p.m3u8", "https://x/en.m3u8")) {
            error("a live feed must never reach the proxy")
        }!!
        assertEquals("https://x/720p.m3u8", load.url)
        assertEquals("https://x/en.m3u8", load.audioFile)
    }

    @Test
    fun `an audio url without a chosen playlist is never attached to the master`() {
        // The master already carries its audio; a second track would double it.
        val load = MpvSource.loadFor(Hls("https://x/master.m3u8", null, "https://x/en.m3u8"), proxied)!!
        assertEquals("https://x/master.m3u8", load.url)
        assertNull(load.audioFile)
    }

    @Test
    fun `a per-file value is escaped only when a comma would cut it`() {
        assertEquals("http://127.0.0.1:9/p/1", MpvSource.perFileValue("http://127.0.0.1:9/p/1"))
        assertEquals("https://a/x?q=1&r=2", MpvSource.perFileValue("https://a/x?q=1&r=2"))
        // Bytes, not characters: mpv reads exactly this many bytes as the value.
        assertEquals("%14%https://a/é,b", MpvSource.perFileValue("https://a/é,b"))
    }

    @Test
    fun `there is nothing to load for a clip that still needs resolving`() {
        assertNull(MpvSource.loadFor(NeedsResolving("abc12345678"), proxied))
    }

    @Test
    fun `there is nothing to load for a clip that cannot play at all`() {
        assertNull(MpvSource.loadFor(Unplayable("no id"), proxied))
    }
}
