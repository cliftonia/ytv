package com.cliftonia.fs42tv.pluto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which one media playlist mpv is handed instead of a Pluto master - and the audio beside it.
 *
 * mpv opens a master by fetching and probing every variant and rendition in it (7-11s measured
 * for a five-variant Pluto master, on the TCL's own player build on a Mac); one media playlist
 * opens in about three. So the choice has to be right from the text alone.
 */
class HlsMasterTest {

    private val base = "https://cfd.example/v2/stitch/hls/channel/abc/master.m3u8?jwt=J&sid=S"

    /** Muxed audio, five variants, a SUBTITLES group - the common Pluto shape. */
    private val muxed = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=NO,LANGUAGE="en",URI="subs/en.m3u8?jwt=J"
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=650000,RESOLUTION=416x234,CODECS="avc1.42c01e,mp4a.40.2",SUBTITLES="subs"
        240p/playlist.m3u8?jwt=J&sid=S
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=1200000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",SUBTITLES="subs"
        360p/playlist.m3u8?jwt=J&sid=S
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=2000000,RESOLUTION=854x480,CODECS="avc1.4d401f,mp4a.40.2",SUBTITLES="subs"
        480p/playlist.m3u8?jwt=J&sid=S
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=3000000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",SUBTITLES="subs"
        720p/playlist.m3u8?jwt=J&sid=S
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",SUBTITLES="subs"
        1080p/playlist.m3u8?jwt=J&sid=S
    """.trimIndent()

    /** Video-only variants and one audio rendition, named by group. */
    private val separate = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Spanish",DEFAULT=NO,URI="audio/audio/Spanish/audio.m3u8?jwt=J"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,AUTOSELECT=YES,URI="audio/audio/English/audio.m3u8?jwt=J"
        #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=640x360,CODECS="avc1.4d401e",AUDIO="audio"
        video/360p.m3u8?jwt=J
        #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,CODECS="avc1.4d401f",AUDIO="audio"
        video/720p.m3u8?jwt=J
    """.trimIndent()

    private fun at(path: String) = "https://cfd.example/v2/stitch/hls/channel/abc/$path"

    @Test
    fun `the richest variant the quality setting allows, resolved against the master`() {
        val pick = HlsMaster.choose(muxed, base, maxHeight = 1080)!!
        assertEquals(at("1080p/playlist.m3u8?jwt=J&sid=S"), pick.videoUrl)
        assertEquals(5_000_000L, pick.bandwidth)
        assertNull(pick.audioUrl)
    }

    @Test
    fun `a 720p ceiling takes the 720p variant, not the 1080p`() {
        val pick = HlsMaster.choose(muxed, base, maxHeight = 720)!!
        assertEquals(at("720p/playlist.m3u8?jwt=J&sid=S"), pick.videoUrl)
        assertEquals(720, pick.height)
    }

    @Test
    fun `without resolutions, the highest bandwidth under the default cap`() {
        val bare = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=6000000\nhi.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=3400000\nmid.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=900000\nlo.m3u8\n"
        assertEquals(at("mid.m3u8"), HlsMaster.choose(bare, base, maxHeight = 2160)!!.videoUrl)
    }

    @Test
    fun `nothing within the ceiling takes the smallest`() {
        val big = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=3840x2160\nuhd.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=7000000,RESOLUTION=2560x1440\nqhd.m3u8\n"
        assertEquals(at("qhd.m3u8"), HlsMaster.choose(big, base, maxHeight = 720)!!.videoUrl)
    }

    @Test
    fun `a variant that needs separate audio plays the master while that path is off`() {
        assertEquals(false, HlsMaster.SEPARATE_AUDIO)
        assertNull(HlsMaster.choose(separate, base, maxHeight = 1080))
    }

    @Test
    fun `a separate audio group brings its DEFAULT rendition`() {
        val pick = HlsMaster.choose(separate, base, maxHeight = 1080, separateAudio = true)!!
        assertEquals(at("video/720p.m3u8?jwt=J"), pick.videoUrl)
        assertEquals(at("audio/audio/English/audio.m3u8?jwt=J"), pick.audioUrl)
    }

    @Test
    fun `without a DEFAULT, the group's first rendition`() {
        val noDefault = separate.replace("DEFAULT=YES", "DEFAULT=NO")
        assertEquals(at("audio/audio/Spanish/audio.m3u8?jwt=J"),
            HlsMaster.choose(noDefault, base, 1080, separateAudio = true)!!.audioUrl)
    }

    @Test
    fun `a rendition without a URI means the audio is in the variant`() {
        val inBand = "#EXTM3U\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aac\",NAME=\"English\",DEFAULT=YES\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS=\"avc1.4d401f,mp4a.40.2\",AUDIO=\"aac\"\nv.m3u8\n"
        val pick = HlsMaster.choose(inBand, base, 1080)!!
        assertEquals(at("v.m3u8"), pick.videoUrl)
        assertNull(pick.audioUrl)
    }

    @Test
    fun `the DEFAULT rendition without a URI is muxed audio, whatever an alternate carries`() {
        val defaultMuxed = "#EXTM3U\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"English\",DEFAULT=YES\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"English(Audio-Description)\",DEFAULT=NO," +
            "URI=\"audio/English(Audio-Description)/a.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS=\"avc1.4d401f,mp4a.40.2\",AUDIO=\"a\"\nv.m3u8\n"
        val pick = HlsMaster.choose(defaultMuxed, base, 1080, separateAudio = true)!!
        assertEquals(at("v.m3u8"), pick.videoUrl)
        assertNull(pick.audioUrl)
    }

    @Test
    fun `a named audio group that is not there is not guessed at`() {
        // A video-only variant played alone is a silent channel; the master plays with sound.
        val missing = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO=\"gone\"\nv.m3u8\n"
        assertNull(HlsMaster.choose(missing, base, 1080))
    }

    @Test
    fun `a video-only variant with no audio anywhere is not chosen alone`() {
        val silent = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS=\"avc1.4d401f\"\nv.m3u8\n"
        assertNull(HlsMaster.choose(silent, base, 1080))
    }

    @Test
    fun `absolute variant and audio uris are kept as they are`() {
        val absolute = "#EXTM3U\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",DEFAULT=YES,URI=\"https://aud.example/a.m3u8?x=1\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO=\"a\"\nhttps://vid.example/v.m3u8?y=2\n"
        val pick = HlsMaster.choose(absolute, base, 1080, separateAudio = true)!!
        assertEquals("https://vid.example/v.m3u8?y=2", pick.videoUrl)
        assertEquals("https://aud.example/a.m3u8?x=1", pick.audioUrl)
    }

    @Test
    fun `a media playlist, an error page or an empty master has nothing to choose`() {
        assertNull(HlsMaster.choose("#EXTM3U\n#EXTINF:5.0,\nseg1.ts\n", base, 1080))
        assertNull(HlsMaster.choose("<html>nope</html>", base, 1080))
        assertNull(HlsMaster.choose("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n", base, 1080))
        assertEquals(false, HlsMaster.isMaster("#EXTM3U\n#EXTINF:5.0,\nseg1.ts\n"))
        assertEquals(true, HlsMaster.isMaster(muxed))
    }

    @Test
    fun `quoted attribute values keep their commas`() {
        val attrs = HlsMaster.attributes(
            "#EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS=\"avc1.4d401f,mp4a.40.2\",RESOLUTION=1280x720,AUDIO=\"a\"")
        assertEquals("avc1.4d401f,mp4a.40.2", attrs["CODECS"])
        assertEquals("1280x720", attrs["RESOLUTION"])
        assertEquals("a", attrs["AUDIO"])
        assertEquals("2000000", attrs["BANDWIDTH"])
    }

    @Test
    fun `the quality setting's top rung is the height ceiling`() {
        assertEquals(1080, HlsMaster.heightCap(listOf("hd", "sd")))
        assertEquals(720, HlsMaster.heightCap(listOf("sd")))
        assertEquals(2160, HlsMaster.heightCap(listOf("uhd", "hd", "sd")))
        assertEquals(1080, HlsMaster.heightCap(emptyList()))
    }
}
