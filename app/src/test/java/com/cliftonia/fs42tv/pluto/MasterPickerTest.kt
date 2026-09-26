package com.cliftonia.fs42tv.pluto

import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.Progressive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tune-time read of a Pluto master for mpv - and every way it falls back to the master itself,
 * which is exactly how the channel played before.
 */
class MasterPickerTest {

    private val master = "https://jmp2.example/plu-abc.m3u8"
    private val landed = "https://cfd.example/stitch/abc/master.m3u8?jwt=J"
    private val body = "#EXTM3U\n" +
        "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",DEFAULT=YES,URI=\"audio/en.m3u8?jwt=J\"\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f\",AUDIO=\"audio\"\n" +
        "720p.m3u8?jwt=J\n"

    private class Fixture(
        var answer: () -> BreakPoller.Fetched,
        var ladder: List<String>? = listOf("hd", "sd"),
    ) {
        val fetched = mutableListOf<String>()
        var now = 0L
        val picker = MasterPicker(
            fetch = { url -> fetched += url; now += 120; answer() },
            mpvLadder = { ladder },
            elapsedMillis = { now },
        )
    }

    @Test
    fun `mpv gets the chosen playlist and its audio, the master url stays the identity`() {
        val f = Fixture({ BreakPoller.Fetched(landed, body) })
        val out = f.picker.forMpv(Hls(master)) as Hls
        assertEquals(master, out.url)
        // Relative to where the redirect landed, not to the jmp2 url asked for.
        assertEquals("https://cfd.example/stitch/abc/720p.m3u8?jwt=J", out.mediaUrl)
        assertEquals("https://cfd.example/stitch/abc/audio/en.m3u8?jwt=J", out.audioUrl)
        assertEquals(listOf(master), f.fetched)
    }

    @Test
    fun `Media3 is never slowed by a read it does not need`() {
        val f = Fixture({ error("Media3 must not fetch") }, ladder = null)
        val hls = Hls(master)
        assertSame(hls, f.picker.forMpv(hls))
        assertTrue(f.fetched.isEmpty())
    }

    @Test
    fun `a master that does not answer leaves the master to mpv`() {
        val f = Fixture({ throw java.net.SocketTimeoutException("Read timed out") })
        val hls = Hls(master)
        assertSame(hls, f.picker.forMpv(hls))
    }

    @Test
    fun `an HTTP error leaves the master to mpv, so the session's failure is still seen`() {
        val f = Fixture({ throw java.io.IOException("playlist HTTP 403") })
        val hls = Hls(master)
        assertSame(hls, f.picker.forMpv(hls))
    }

    @Test
    fun `a url that is already a media playlist is played as it is`() {
        val f = Fixture({ BreakPoller.Fetched(landed, "#EXTM3U\n#EXTINF:5.0,\nseg.ts\n") })
        val hls = Hls(master)
        assertSame(hls, f.picker.forMpv(hls))
    }

    @Test
    fun `a pick that could play silent leaves the master to mpv`() {
        val silent = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO=\"gone\"\nv.m3u8\n"
        val f = Fixture({ BreakPoller.Fetched(landed, silent) })
        val hls = Hls(master)
        assertSame(hls, f.picker.forMpv(hls))
    }

    @Test
    fun `anything but a live feed is not touched`() {
        val f = Fixture({ error("nothing to fetch") })
        val clip = Progressive("https://v/1", null)
        assertSame(clip, f.picker.forMpv(clip))
    }
}
