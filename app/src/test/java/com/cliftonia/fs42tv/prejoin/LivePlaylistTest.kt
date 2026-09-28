package com.cliftonia.fs42tv.prejoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a live media playlist for its join, and rewriting it to be served from loopback. */
class LivePlaylistTest {

    private val base = "https://cdn.example/live/720p/playlist.m3u8?tok=T"

    @Test
    fun `the join is ffmpeg's - third from the end - and the one after, not the newest`() {
        val parsed = LivePlaylist.parse(FakeNet.live(100, 6), base)!!
        assertEquals(6_000L, parsed.targetMillis)
        assertTrue(parsed.warmable)
        val join = LivePlaylist.joinSegments(parsed, 2)
        assertEquals(listOf(103L, 104L), join.map { it.sequence })
        assertEquals("https://cdn.example/live/720p/seg103.ts?tok=T", join[0].url)
        // A playlist shorter than three segments is joined at its first, as ffmpeg does.
        val short = LivePlaylist.parse(FakeNet.live(7, 2), base)!!
        assertEquals(listOf(7L, 8L), LivePlaylist.joinSegments(short, 2).map { it.sequence })
    }

    @Test
    fun `masters, ended playlists and byte ranges are never warmed`() {
        assertNull(LivePlaylist.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n", base))
        assertNull(LivePlaylist.parse("<html>", base))
        assertFalse(LivePlaylist.parse(FakeNet.live(1, 4) + "#EXT-X-ENDLIST\n", base)!!.warmable)
        val ranged = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n#EXT-X-BYTERANGE:100@0\nall.ts\n"
        assertFalse(LivePlaylist.parse(ranged, base)!!.warmable)
    }

    @Test
    fun `the rewrite makes every uri absolute and changes nothing else`() {
        val body = FakeNet.live(100, 4, key = true) +
            "#EXT-X-MAP:URI=\"init.mp4\"\n#EXT-X-DISCONTINUITY\n#EXT-X-CUE-OUT:30\n#EXTINF:6.000,\n/abs/seg104.ts\n"
        val out = LivePlaylist.rewrite(body, base) { url -> if (url.endsWith("seg102.ts?tok=T")) "s0.ts" else null }!!
        val lines = out.lines()
        assertTrue(lines.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"https://cdn.example/live/720p/keys/k1?t=1\",IV=0x01"))
        assertTrue(lines.contains("#EXT-X-MAP:URI=\"https://cdn.example/live/720p/init.mp4\""))
        assertTrue(lines.contains("https://cdn.example/live/720p/seg100.ts?tok=T"))
        assertTrue("a held segment points back at the proxy", lines.contains("s0.ts"))
        assertTrue(lines.contains("https://cdn.example/abs/seg104.ts"))
        // Sequence, target, discontinuity and cue tags - what ffmpeg and the break detector read.
        listOf("#EXT-X-MEDIA-SEQUENCE:100", "#EXT-X-TARGETDURATION:6", "#EXT-X-DISCONTINUITY", "#EXT-X-CUE-OUT:30")
            .forEach { assertTrue(it, lines.contains(it)) }
        assertEquals(body.lines().size, lines.size)
    }

    @Test
    fun `a key the player fetches itself is left alone`() {
        val body = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key-id\"\n#EXTINF:6,\na.ts\n"
        val out = LivePlaylist.rewrite(body, base) { null }!!
        assertTrue(out.contains("URI=\"skd://key-id\""))
    }
}
