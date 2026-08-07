package com.cliftonia.fs42tv.tune

import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.sync.Tier
import com.cliftonia.fs42tv.sync.UrlCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tuning seam. Phase 2b's preload manager, banner and reverse slot all compose the same
 * three steps, so getting this wrong is not one bug - it is the same bug in three places.
 */
class TunerTest {

    private fun ytChannel(vararg durations: Int) = Channel(
        number = 9, name = "AFL", kind = "youtube", rotation = "clock",
        streams = durations.mapIndexed { i, d ->
            Stream(id = "vid$i".padEnd(11, 'x'), url = "https://youtube.com/watch?v=vid$i",
                duration = d, title = "clip $i")
        },
    )

    private val liveChannel = Channel(
        number = 103, name = "ABC TV QLD", kind = "live", rotation = null,
        streams = listOf(Stream(id = null, url = "https://x/abc.m3u8", duration = 600,
            title = "ABC")),
    )

    private fun cacheFor(index: Int) = UrlCache(
        urls = mapOf("vid$index".padEnd(11, 'x') to
            mapOf("hd" to Tier(video = "https://v/$index", audio = "https://a/$index",
                expires = 9_999_999_999))),
    )

    @Test
    fun `picks the clip the clock says is on air`() {
        val tuned = Tuner.tune(ytChannel(100, 200, 300), cacheFor(1), nowSeconds = 250)
        assertEquals("tuning the wrong clip means the channel is not where the schedule says",
            1, tuned!!.streamIndex)
        assertEquals(150.0, tuned.offsetSeconds, 0.001)
        assertEquals(Progressive("https://v/1", "https://a/1"), tuned.playable)
    }

    @Test
    fun `a live channel ignores the clock entirely`() {
        val tuned = Tuner.tune(liveChannel, null, nowSeconds = 5000)
        assertEquals("a live channel carries a placeholder duration, so a computed offset " +
            "would seek into the middle of a live window", 0.0, tuned!!.offsetSeconds, 0.001)
        assertEquals(Hls("https://x/abc.m3u8"), tuned.playable)
    }

    @Test
    fun `kind decides live, not a null stream id`() {
        // A youtube channel whose id is missing must NOT be treated as live: handing a watch
        // page to an HLS parser produces a confusing failure far from the cause.
        val oddball = Channel(number = 5, name = "Odd", kind = "youtube", rotation = "clock",
            streams = listOf(Stream(id = null, url = "https://youtube.com/watch?v=x",
                duration = 100, title = "t")))
        val tuned = Tuner.tune(oddball, null, nowSeconds = 10)
        assertTrue("the server publishes a discriminator; guessing from a null id contradicts it",
            tuned!!.playable !is Hls)
    }

    @Test
    fun `a cache miss reports what needs resolving rather than failing`() {
        val tuned = Tuner.tune(ytChannel(100), UrlCache(), nowSeconds = 10)
        assertEquals("a miss is the common case at 46% coverage and must stay recoverable",
            NeedsResolving("vid0xxxxxxx"), tuned!!.playable)
    }

    @Test
    fun `an empty channel yields nothing to tune`() {
        val empty = Channel(number = 1, name = "Empty", kind = "youtube", rotation = "clock",
            streams = emptyList())
        assertNull("returning a Tuned with no stream would crash the caller downstream",
            Tuner.tune(empty, null, nowSeconds = 10))
    }

    @Test
    fun `a non-clock youtube channel plays its first clip from the start`() {
        val noRotation = ytChannel(100, 200).copy(rotation = null)
        val tuned = Tuner.tune(noRotation, null, nowSeconds = 5000)
        assertEquals("without a clock rotation there is no schedule to join mid-way",
            0, tuned!!.streamIndex)
        assertEquals(0.0, tuned.offsetSeconds, 0.001)
    }

    @Test
    fun `the tuned stream matches the tuned index`() {
        val tuned = Tuner.tune(ytChannel(100, 200, 300), null, nowSeconds = 250)
        assertEquals("a mismatched index and stream would show one programme and title another",
            tuned!!.channel.streams[tuned.streamIndex], tuned.stream)
    }
}
