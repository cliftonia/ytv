package com.cliftonia.fs42tv.player

import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.Unplayable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which tracks mpv fetches through the loopback proxy, and which it fetches directly.
 *
 * Unreachable from a test until it left [MpvChannelPlayer], because `MPVLib` loads a native
 * library the moment that class is touched. Getting it wrong is not visible in a log: a track
 * that bypasses the proxy is throttled to roughly its own bitrate, which shows up as mpv taking
 * seven seconds to put a picture up rather than one and a half.
 */
class MpvSourceTest {

    private val proxied: (String) -> String = { "http://127.0.0.1:9/p?$it" }

    @Test
    fun `a video-only progressive goes through the proxy and is not EDL wrapped`() {
        // One file, so there is nothing to splice; an EDL around a single stream would name an
        // audio track that does not exist.
        val url = MpvSource.urlFor(Progressive("https://v/1", null), proxied)
        assertEquals("http://127.0.0.1:9/p?https://v/1", url)
    }

    @Test
    fun `both tracks go through the proxy, video first`() {
        // The audio track is small, but it is fetched over the same throttled connection, and a
        // starved audio track stalls the video just as surely.
        val url = MpvSource.urlFor(Progressive("https://v/1", "https://a/1"), proxied)!!
        assertTrue(url.startsWith("edl://"))
        assertTrue("the video url must be proxied", url.contains("p?https://v/1"))
        assertTrue("the audio url must be proxied too", url.contains("p?https://a/1"))
        assertTrue(url.indexOf("p?https://v/1") < url.indexOf("p?https://a/1"))
    }

    @Test
    fun `a live feed bypasses the proxy entirely`() {
        // HLS is already a series of bounded segment requests, which is why it was never
        // throttled and never slow. Proxying it would add a hop for nothing.
        assertEquals("https://x/abc.m3u8", MpvSource.urlFor(Hls("https://x/abc.m3u8")) {
            error("a live feed must never reach the proxy")
        })
    }

    @Test
    fun `there is no url for a clip that still needs resolving`() {
        assertNull(MpvSource.urlFor(NeedsResolving("abc12345678"), proxied))
    }

    @Test
    fun `there is no url for a clip that cannot play at all`() {
        assertNull(MpvSource.urlFor(Unplayable("no id"), proxied))
    }
}
