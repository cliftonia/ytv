package com.cliftonia.fs42tv.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the home server keeps a reel, given the resolve server the television can reach. */
class AdMirrorTest {

    @Test
    fun `the reel server is the resolve server's machine on its own port`() {
        assertEquals("http://192.168.4.58:4245/ads/80_s_Australian_Commercials_10.mp4",
            AdMirror.url("http://192.168.4.58:4243", "80_s_Australian_Commercials_10"))
        assertEquals("http://100.74.3.68:4245/ads/aus-ads.1987.mp4",
            AdMirror.url("http://100.74.3.68:4243", "aus-ads.1987"))
    }

    @Test
    fun `no server - away from home - is no mirror`() {
        assertNull(AdMirror.url(null, "reel"))
        assertNull(AdMirror.url("not a url", "reel"))
    }

    @Test
    fun `an id the mirror could not hold is played from the archive`() {
        for (id in listOf("", ".", "..", "../x", "a/b", ".hidden", "a b", "a?b", "x".repeat(201))) {
            assertNull(id, AdMirror.url("http://192.168.4.58:4243", id))
        }
    }
}
