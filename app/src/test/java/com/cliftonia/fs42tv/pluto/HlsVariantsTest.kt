package com.cliftonia.fs42tv.pluto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which media playlist the break poller reads, given the master the dial is playing. */
class HlsVariantsTest {

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=4000000,RESOLUTION=1920x1080
        1080p/playlist.m3u8?sid=abc
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=800000,RESOLUTION=640x360
        360p/playlist.m3u8?sid=abc
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=2000000,RESOLUTION=1280x720
        https://other.example/720p.m3u8
    """.trimIndent()

    private val base = "https://service-stitcher.clusters.pluto.tv/stitch/hls/channel/abc/master.m3u8?t=1"

    @Test
    fun `the lowest-bandwidth variant, resolved against the url it came from`() {
        assertEquals(
            "https://service-stitcher.clusters.pluto.tv/stitch/hls/channel/abc/360p/playlist.m3u8?sid=abc",
            HlsVariants.mediaPlaylist(master, base),
        )
    }

    @Test
    fun `without bandwidths, the first variant`() {
        val bare = "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1x1\na.m3u8\n#EXT-X-STREAM-INF:RESOLUTION=2x2\nb.m3u8\n"
        assertEquals("https://h.example/x/a.m3u8", HlsVariants.mediaPlaylist(bare, "https://h.example/x/m.m3u8"))
    }

    @Test
    fun `a url that is already a media playlist is its own`() {
        val media = "#EXTM3U\n#EXTINF:5.0,\nseg1.ts\n"
        assertEquals(base, HlsVariants.mediaPlaylist(media, base))
    }

    @Test
    fun `anything else has no media playlist`() {
        assertNull(HlsVariants.mediaPlaylist("<html>nope</html>", base))
        assertNull(HlsVariants.mediaPlaylist("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n", base))
    }
}
