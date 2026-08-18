package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one shared decision in the player's vocabulary: whether an engine has anything to play.
 *
 * Both engines used to carry their own copy of these two branches. A copy that drifts is a
 * black screen with a log line above it that names the wrong reason, which is worse than no
 * line at all because it sends the next hour of debugging somewhere else.
 */
class PlayableTest {

    @Test
    fun `a progressive pair is playable`() {
        assertNull(unplayableReason(Progressive("https://v/1", "https://a/1")))
    }

    @Test
    fun `a video-only progressive is playable`() {
        // Below 360p YouTube serves one muxed stream, so a null audio url is normal rather
        // than a half-resolved clip.
        assertNull(unplayableReason(Progressive("https://v/1", null)))
    }

    @Test
    fun `a live feed is playable`() {
        assertNull(unplayableReason(Hls("https://x/abc.m3u8")))
    }

    @Test
    fun `an unresolved id names the id, so the log says which clip`() {
        assertEquals("no cached stream for video id abc12345678; needs server resolve",
            unplayableReason(NeedsResolving("abc12345678")))
    }

    @Test
    fun `an unplayable stream carries its own reason through`() {
        assertEquals("cannot play: Odd: a youtube stream has no video id to resolve",
            unplayableReason(Unplayable("Odd: a youtube stream has no video id to resolve")))
    }
}
