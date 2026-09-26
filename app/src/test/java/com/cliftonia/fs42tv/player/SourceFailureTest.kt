package com.cliftonia.fs42tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The Media3 source-error line: which request, why - and never a session's token. */
class SourceFailureTest {

    private val variant = "https://cfd.example/v2/stitch/hls/channel/abc/1042180/playlist.m3u8?sid=S&jwt=eyJ.secret"

    @Test
    fun `a request is named by host and path only`() {
        assertEquals("https://cfd.example/v2/stitch/hls/channel/abc/1042180/playlist.m3u8", SourceFailure.redact(variant))
        assertEquals("https://h.example/audio/English(Audio-Description)/audio.m3u8",
            SourceFailure.redact("https://h.example/audio/English(Audio-Description)/audio.m3u8?jwt=x"))
    }

    @Test
    fun `the chain reads outermost first, and a url quoted in a message loses its query`() {
        val line = SourceFailure.describe(variant, listOf(
            "ExoPlaybackException" to "Source error",
            "HttpDataSourceException" to null,
            "SSLHandshakeException" to "Trust anchor for certification path not found. url=$variant",
        ))
        assertEquals(
            "source failed on https://cfd.example/v2/stitch/hls/channel/abc/1042180/playlist.m3u8: " +
                "ExoPlaybackException(Source error) <- HttpDataSourceException <- " +
                "SSLHandshakeException(Trust anchor for certification path not found. " +
                "url=https://cfd.example/v2/stitch/hls/channel/abc/1042180/playlist.m3u8)",
            line,
        )
        assertFalse(line.contains("jwt"))
    }

    @Test
    fun `a relative uri in a message loses its session parameters`() {
        assertEquals("Unexpected 1042180/playlist.m3u8 for audio/a.m3u8",
            SourceFailure.scrub("Unexpected 1042180/playlist.m3u8?jwt=eyJ.x&sid=S for audio/a.m3u8?deviceId=d"))
    }

    @Test
    fun `no request found still says why`() {
        assertEquals("source failed: UnknownHostException(Unable to resolve host \"x.example\")",
            SourceFailure.describe(null, listOf("UnknownHostException" to "Unable to resolve host \"x.example\"")))
    }
}
