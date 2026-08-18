package com.cliftonia.fs42tv.tune

import com.cliftonia.fs42tv.TestDial
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.Unplayable
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.sync.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tuning seam. Phase 2b's preload manager, banner and reverse slot all compose the same
 * three steps, so getting this wrong is not one bug - it is the same bug in three places.
 */
class TunerTest {

    private fun ytChannel(vararg durations: Int) = TestDial.ytChannel(*durations)

    private val liveChannel = TestDial.liveChannel("ABC")

    private fun cacheFor(index: Int) = TestDial.cacheOf(
        "vid$index".padEnd(11, 'x'),
        "hd" to Tier(video = "https://v/$index", audio = "https://a/$index",
            expires = 9_999_999_999),
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
        // page to an HLS parser produces a confusing failure far from the cause. Nor is there
        // an id to send the server, so it is Unplayable rather than NeedsResolving.
        val oddball = Channel(number = 5, name = "Odd", kind = "youtube", rotation = "clock",
            streams = listOf(Stream(id = null, url = "https://youtube.com/watch?v=x",
                duration = 100, title = "t")))
        val tuned = Tuner.tune(oddball, null, nowSeconds = 10)
        assertEquals("the server publishes a discriminator; guessing from a null id contradicts " +
            "it, and there is no id here to ask the server to resolve",
            Unplayable("Odd: a youtube stream has no video id to resolve"), tuned!!.playable)
    }

    @Test
    fun `a cache miss reports what needs resolving rather than failing`() {
        val tuned = Tuner.tune(ytChannel(100), TestDial.cacheOf("nothing"), nowSeconds = 10)
        assertEquals("a miss is the common case at 46% coverage and must stay recoverable",
            NeedsResolving("vid0xxxxxxx"), tuned!!.playable)
    }

    @Test
    fun `an empty channel yields nothing to tune`() {
        val empty = TestDial.ytChannelOf(1, "Empty")
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

    @Test
    fun `tuning to an index ignores the clock and starts the clip from zero`() {
        // The recovery path for a clip whose published duration is longer than what actually
        // plays: the rotation still believes it is on air, so re-tuning by the clock would land
        // straight back on it.
        val tuned = Tuner.tuneToIndex(ytChannel(100, 200, 300), index = 2)
        assertEquals(2, tuned!!.streamIndex)
        assertEquals("there is nothing meaningful to seek to in a programme that was never " +
            "scheduled to be on now", 0.0, tuned.offsetSeconds, 0.001)
        assertEquals(NeedsResolving("vid2xxxxxxx"), tuned.playable)
    }

    @Test
    fun `tuning a live channel to an index still yields HLS`() {
        val tuned = Tuner.tuneToIndex(liveChannel, index = 0)
        assertEquals(Hls("https://x/abc.m3u8"), tuned!!.playable)
        assertEquals(0.0, tuned.offsetSeconds, 0.001)
    }

    @Test
    fun `tuning to an index with no video id is unplayable rather than resolvable`() {
        val oddball = Channel(number = 5, name = "Odd", kind = "youtube", rotation = "clock",
            streams = listOf(Stream(id = null, url = "https://youtube.com/watch?v=x",
                duration = 100, title = "t")))
        assertEquals(Unplayable("Odd: a youtube stream has no video id to resolve"),
            Tuner.tuneToIndex(oddball, index = 0)!!.playable)
    }

    @Test
    fun `tuning past the end of the clip list yields nothing rather than throwing`() {
        // The rollover path computes the next index by arithmetic, so an off-by-one here would
        // crash the executor rather than show a stand-by card.
        assertNull(Tuner.tuneToIndex(ytChannel(100, 200), index = 2))
        assertNull(Tuner.tuneToIndex(ytChannel(100, 200), index = -1))
    }
}
