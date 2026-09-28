package com.cliftonia.fs42tv.prejoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeping a neighbour's join warm: the right segments, one new one per refresh, for a minute and
 * no longer, and not one read once it is no longer wanted or no longer that channel's pick.
 */
class PrejoinWarmerTest {

    private val media = "https://cdn.example/n/720p.m3u8?tok=T"
    private fun seg(n: Long) = "https://cdn.example/n/seg$n.ts?tok=T"

    private inner class Fixture(val inline: Boolean = true, window: Long = PrejoinWarmer.WINDOW_MILLIS) {
        var now = 0L
        val net = FakeNet()
        val cache = PrejoinCache({ now })
        val queued = ArrayDeque<() -> Unit>()
        var interrupted = 0
        /** Runs at every sleep: the playlist moves on as time passes. */
        var onSleep: (Long) -> Unit = {}
        val warmer = PrejoinWarmer(
            open = net.open,
            cache = cache,
            elapsedMillis = { now },
            wallMillis = { 1_000_000L + now },
            sleep = { millis -> now += millis; onSleep(millis) },
            background = { block -> if (inline) block() else queued.addLast(block); ({ interrupted++ }) },
            windowMillis = window,
        )

        fun live(first: Long, count: Int = 6) {
            net.text(media, FakeNet.live(first, count))
            (first until first + count).forEach { net.bytes(seg(it), 1_000) }
        }
    }

    @Test
    fun `the join and the next segment are held, then one new segment per move of the playlist`() {
        // Reads at 0, 3, 6 and 9s; the playlist gains a segment at 6s.
        val f = Fixture(window = 10_000L)
        f.live(100)
        f.onSleep = { if (f.now >= 6_000) f.live(101) }
        f.warmer.warm(media, valid = { true }) { true }
        assertEquals(4, f.net.count(media))
        // Held by the last refresh: the join of the latest playlist (101..106) and the one after it.
        val last = f.cache.take(media)
        assertNotNull(last)
        assertEquals(setOf(seg(104), seg(105)), last!!.segments.keys)
        assertEquals("103, 104, then only 105", 3, f.net.asked.count { it.first != media })
        // Each segment fetched once, however many refreshes held it.
        f.net.asked.filter { it.first != media }.groupBy { it.first }.values.forEach { assertEquals(1, it.size) }
        assertTrue("nothing but the pick's own playlist and its segments", f.net.asked.all { it.first.startsWith("https://cdn.example/n/") })
    }

    @Test
    fun `refreshes run twice a target duration, and stop at the end of the window`() {
        val f = Fixture()
        f.live(100)
        f.warmer.warm(media, valid = { true }) { true }
        assertEquals(PrejoinWarmer.WINDOW_MILLIS / 3_000, f.net.count(media).toLong())
        assertTrue(f.now >= PrejoinWarmer.WINDOW_MILLIS)
        assertEquals(0, f.warmer.warming)
    }

    @Test
    fun `a pick that stops standing - a Pluto session re-pointed - ends the warm before its next read`() {
        val f = Fixture()
        f.live(100)
        var valid = true
        f.onSleep = { valid = false }
        f.warmer.warm(media, valid = { valid }) { true }
        assertEquals(1, f.net.count(media))
    }

    @Test
    fun `a read overtaken by the tune keeps nothing`() {
        val f = Fixture()
        f.live(100)
        var wanted = true
        // The tune lands while the join's first segment is being fetched.
        f.net.answers[seg(103)] = FakeNet.Answer(200, ByteArray(10))
        val open = f.net.open
        val warmer = PrejoinWarmer({ url, range -> open(url, range).also { if (url == seg(103)) wanted = false } },
            f.cache, { f.now }, { f.now }, { f.now += it }, { block -> block(); {} })
        warmer.warm(media, valid = { true }) { wanted }
        assertEquals(0, f.cache.size)
        assertEquals("the second segment is not even asked for", 0, f.net.count(seg(104)))
    }

    @Test
    fun `stop ends every warm - nothing starts, nothing is read`() {
        val f = Fixture(inline = false)
        f.live(100)
        assertTrue(f.warmer.warm(media, valid = { true }) { true })
        f.warmer.stop()
        assertEquals(1, f.interrupted)
        f.queued.removeFirst()()
        assertTrue(f.net.asked.isEmpty())
    }

    @Test
    fun `two warms at most, and never the same playlist twice`() {
        val f = Fixture(inline = false)
        assertTrue(f.warmer.warm("a", { true }) { true })
        assertFalse(f.warmer.warm("a", { true }) { true })
        assertTrue(f.warmer.warm("b", { true }) { true })
        assertFalse(f.warmer.warm("c", { true }) { true })
        assertEquals(2, f.warmer.warming)
    }

    @Test
    fun `an ended or byte-range playlist is read once and left to the network`() {
        val f = Fixture()
        f.net.text(media, FakeNet.live(1, 4) + "#EXT-X-ENDLIST\n")
        f.warmer.warm(media, valid = { true }) { true }
        assertEquals(1, f.net.count(media))
        assertNull(f.cache.take(media))
    }

    @Test
    fun `a playlist that will not answer is given up after two tries`() {
        val f = Fixture()
        f.net.text(media, "", code = 403)
        f.warmer.warm(media, valid = { true }) { true }
        assertEquals(PrejoinWarmer.MAX_FAILURES, f.net.count(media))
        assertEquals(0, f.cache.size)
    }

    @Test
    fun `a segment too large to hold is not held`() {
        val f = Fixture()
        f.live(100)
        f.net.bytes(seg(103), PrejoinWarmer.MAX_SEGMENT_BYTES + 1)
        f.warmer.warm(media, valid = { true }) { f.net.count(media) < 2 }
        assertEquals(0, f.cache.size)
    }
}
