package com.cliftonia.fs42tv.pluto

import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.PlutoRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the Pluto neighbours' masters ahead of a surf: after the wait, only while still wanted,
 * never more than two at once, and nothing at all once the app has stopped.
 */
class MasterPrefetchTest {

    private fun pluto(number: Int) = Channel(number = number, name = "Pluto $number", kind = "live",
        pluto = PlutoRef("id$number"))

    private fun youtube(number: Int) = Channel(number = number, name = "Clips $number", kind = "clock")

    private val dial = listOf(pluto(1), pluto(2), pluto(3), youtube(4), pluto(5), pluto(6))

    private class Fixture(var prefetching: Boolean = true) {
        /** The one wait scheduled, if any; [fire] runs it. */
        var waiting: (() -> Unit)? = null
        var waitCancelled = 0
        val delays = mutableListOf<Long>()
        /** Reads handed to the background, not yet run; [finish] runs them. */
        val queued = ArrayDeque<() -> Unit>()
        var interrupted = 0
        val read = mutableListOf<String>()
        var kept = 0
        var released = 0
        val keeps = mutableListOf<Set<String>>()
        private val session = PlutoSession("https://s.pluto.tv", "sid=1", "J", "AU", Long.MAX_VALUE)

        val prefetch = MasterPrefetch(
            schedule = { delay, block ->
                delays += delay
                waiting = block
                ({ waitCancelled++; waiting = null })
            },
            background = { block -> queued.addLast(block); ({ interrupted++ }) },
            ahead = { channel, keep ->
                keeps += keep
                channel.pluto?.let {
                    SessionPool.Lease(session, "https://s.pluto.tv/${it.id}/master.m3u8?jwt=J") { released++ }
                }
            },
            prefetching = { prefetching },
            read = { url, stillWanted -> read += url; if (stillWanted()) { kept++; true } else false },
        )

        fun fire() {
            val block = waiting ?: error("nothing waiting")
            waiting = null
            block()
        }

        fun finish() {
            while (queued.isNotEmpty()) queued.removeFirst()()
        }
    }

    @Test
    fun `the neighbours are read a few seconds after the picture, not at the tune`() {
        val f = Fixture()
        f.prefetch.pictureUp(pluto(2), dial) { true }
        assertTrue(f.queued.isEmpty())
        assertEquals(listOf(MasterPrefetch.DELAY_MILLIS), f.delays)
        assertTrue(MasterPrefetch.DELAY_MILLIS in 3_000L..5_000L)
        f.fire()
        f.finish()
        assertEquals(listOf("https://s.pluto.tv/id3/master.m3u8?jwt=J", "https://s.pluto.tv/id1/master.m3u8?jwt=J"), f.read)
        assertEquals(0, f.prefetch.running)
        // Each on a lent session, given back; neither may re-point the screen's or the other's.
        assertEquals(2, f.released)
        assertEquals(setOf("id1", "id2", "id3"), f.keeps.first())
    }

    @Test
    fun `a surf before the wait ends abandons it`() {
        val f = Fixture()
        var sameTune = true
        f.prefetch.pictureUp(pluto(2), dial) { sameTune }
        sameTune = false
        f.fire()
        assertTrue(f.queued.isEmpty())
    }

    @Test
    fun `the next picture replaces the wait for the last one`() {
        val f = Fixture()
        f.prefetch.pictureUp(pluto(2), dial) { true }
        f.prefetch.pictureUp(pluto(3), dial) { true }
        assertEquals(1, f.waitCancelled)
        f.fire()
        f.finish()
        // Channel 4 is not a Pluto channel: not read, and not walked past to 5.
        assertEquals(listOf("https://s.pluto.tv/id2/master.m3u8?jwt=J"), f.read)
    }

    @Test
    fun `no more than two reads at once, across channels`() {
        val f = Fixture()
        f.prefetch.pictureUp(pluto(2), dial) { true }
        f.fire()
        assertEquals(2, f.prefetch.running)
        // The next channel's picture comes up while both are still reading: its neighbours wait.
        f.prefetch.pictureUp(pluto(6), dial) { true }
        f.fire()
        assertEquals(2, f.queued.size)
        f.finish()
        assertEquals(0, f.prefetch.running)
        f.prefetch.pictureUp(pluto(6), dial) { true }
        f.fire()
        assertEquals(2, f.queued.size)
    }

    @Test
    fun `app stop cancels the wait and the reads, and keeps nothing they bring back`() {
        val f = Fixture()
        f.prefetch.pictureUp(pluto(2), dial) { true }
        f.fire()
        f.prefetch.pictureUp(pluto(3), dial) { true }
        f.prefetch.stop()
        assertEquals(2, f.interrupted)
        assertEquals(1, f.waitCancelled)
        // Reads that could not be interrupted: they neither start nor keep anything.
        f.finish()
        assertEquals(0, f.kept)
        assertEquals("no session is even borrowed", 0, f.keeps.size)
        assertEquals(0, f.prefetch.running)
    }

    @Test
    fun `a wait begun before the app stopped does nothing when it fires`() {
        val f = Fixture()
        f.prefetch.pictureUp(pluto(2), dial) { true }
        val stale = f.waiting!!
        f.prefetch.stop()
        stale()
        assertTrue(f.queued.isEmpty())
    }

    @Test
    fun `nothing is read under Media3, or from a channel that is not Pluto`() {
        val media3 = Fixture(prefetching = false)
        media3.prefetch.pictureUp(pluto(2), dial) { true }
        assertTrue(media3.delays.isEmpty())
        val f = Fixture()
        f.prefetch.pictureUp(youtube(4), dial) { true }
        assertTrue(f.delays.isEmpty())
    }

    @Test
    fun `the neighbours wrap at the ends of the dial, like the dial`() {
        assertEquals(listOf(2, 6), MasterPrefetch.neighbours(pluto(1), dial).map { it.number })
        assertEquals(listOf(1), MasterPrefetch.neighbours(pluto(2), listOf(pluto(1), pluto(2))).map { it.number })
        assertTrue(MasterPrefetch.neighbours(pluto(9), dial).isEmpty())
    }
}
