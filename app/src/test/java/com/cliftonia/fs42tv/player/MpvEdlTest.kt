package com.cliftonia.fs42tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The EDL string that makes mpv play a separate video and audio file as one stream.
 *
 * The length prefixes are what let a googlevideo URL full of `&`, `;` and `=` be embedded with no
 * escaping at all. Get a length wrong and mpv opens a truncated address, which fails as a TLS or
 * DNS error a long way from the cause.
 */
class MpvEdlTest {

    @Test
    fun `each url is prefixed with its own length and the video comes first`() {
        val edl = MpvEdl.of("https://v/1", "https://a/12")
        assertEquals(
            "edl://!no_clip;!track_meta,title=video;%11%https://v/1" +
                ";!new_stream;!no_clip;!track_meta,title=audio;%12%https://a/12",
            edl,
        )
        assertTrue("mpv takes the first stream as the video track; swapping them plays a still " +
            "picture with the sound of the video track",
            edl.indexOf("title=video") < edl.indexOf("title=audio"))
    }

    @Test
    fun `the prefix counts UTF-8 bytes, not characters`() {
        // A URL carrying a multi-byte character is where a char count and a byte count diverge,
        // and mpv reads exactly the number of BYTES the prefix names. A char count would cut the
        // address short by the difference, silently.
        val url = "https://v/é"
        assertEquals("the character is two bytes, so this must be 12 rather than 11",
            12, url.toByteArray(Charsets.UTF_8).size)
        assertTrue(MpvEdl.of(url, "https://a/1").contains("%12%$url"))
    }

    @Test
    fun `a url full of separators is embedded untouched`() {
        // The whole point of the length prefix: no escaping, and a signed googlevideo url is
        // nothing but `&`, `;` and `=`.
        val url = "https://v/x?expire=1&sig=a;b&c=d"
        assertTrue(MpvEdl.of(url, "https://a/1").contains(url))
    }
}
