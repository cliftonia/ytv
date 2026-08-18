package com.cliftonia.fs42tv.resolver

import com.cliftonia.fs42tv.TestDial
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.sync.Tier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A refused tier must fall to the next rung, not condemn the clip.
 *
 * A signed googlevideo URL can be rejected with 403 while still inside its stated expiry.
 * Condemning the whole id means a `/resolve`, which runs yt-dlp - measured at 7.7 and 12.2
 * seconds against a 4s grace period before the viewer sees a stand-by card. The next rung is
 * already published in a file the app is holding.
 *
 * Dormant, like [StreamResolverTest]: nothing publishes the file these read. The rule itself is
 * not dormant - `refusedKey` is how the live device path skips a rung the CDN has refused.
 */
class RefusedTierTest {

    private val now = 1_000_000L
    private val fresh = now + 100_000

    private fun cacheOf(vararg tiers: Pair<String, Tier>) = TestDial.cacheOf("vid1", *tiers)

    private val stream = Stream(id = "vid1", url = "https://youtube.com/watch?v=vid1", duration = 600)

    @Test
    fun `the best rung is used when nothing has been refused`() {
        val playable = StreamResolver.resolve(
            stream,
            cacheOf("hd" to Tier("hd-video", "hd-audio", fresh), "sd" to Tier("sd-video", null, fresh)),
            listOf("hd", "sd"),
            now,
        )
        assertEquals(Progressive("hd-video", "hd-audio"), playable)
    }

    @Test
    fun `a refused rung falls to the next one, with no server round trip`() {
        val playable = StreamResolver.resolve(
            stream,
            cacheOf("hd" to Tier("hd-video", "hd-audio", fresh), "sd" to Tier("sd-video", null, fresh)),
            listOf("hd", "sd"),
            now,
            refused = setOf(StreamResolver.refusedKey("vid1", "hd")),
        )
        assertEquals("the sd tier is already published beside hd; using it costs nothing",
            Progressive("sd-video", null), playable)
    }

    @Test
    fun `only when every rung is refused does the server get asked`() {
        val playable = StreamResolver.resolve(
            stream,
            cacheOf("hd" to Tier("hd-video", "hd-audio", fresh), "sd" to Tier("sd-video", null, fresh)),
            listOf("hd", "sd"),
            now,
            refused = setOf(
                StreamResolver.refusedKey("vid1", "hd"),
                StreamResolver.refusedKey("vid1", "sd"),
            ),
        )
        assertEquals(NeedsResolving("vid1"), playable)
    }

    @Test
    fun `refusing one clip's tier does not touch another clip's same tier`() {
        // The key is per clip AND per tier. Keying on the tier alone would take every hd stream
        // on the dial out of service because one URL was refused.
        val playable = StreamResolver.resolve(
            stream,
            cacheOf("hd" to Tier("hd-video", "hd-audio", fresh)),
            listOf("hd", "sd"),
            now,
            refused = setOf(StreamResolver.refusedKey("someOtherVideo", "hd")),
        )
        assertEquals(Progressive("hd-video", "hd-audio"), playable)
    }

    @Test
    fun `a stale rung is skipped even when nothing was refused`() {
        // Freshness and refusal are separate reasons to skip, and both must apply.
        val playable = StreamResolver.resolve(
            stream,
            cacheOf("hd" to Tier("hd-video", "hd-audio", now - 1), "sd" to Tier("sd-video", null, fresh)),
            listOf("hd", "sd"),
            now,
        )
        assertEquals(Progressive("sd-video", null), playable)
    }
}
