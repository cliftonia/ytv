package com.cliftonia.fs42tv.pluto

import com.cliftonia.fs42tv.pluto.BreakDetector.State.IN_BREAK
import com.cliftonia.fs42tv.pluto.BreakDetector.State.PROGRAMME
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The break poller, on a hand-cranked clock: nothing runs until [Clock.advance] says so, so a
 * stop can be placed between any two reads.
 */
class BreakPollerTest {

    /** A fake scheduler: blocks wait for their time, and a cancelled one never runs. */
    class Clock {
        var now = 0L
        var refuse = false
        private class Job(val at: Long, val block: () -> Unit) { var cancelled = false }
        private val jobs = mutableListOf<Job>()
        val pending: Int get() = jobs.count { !it.cancelled }

        val schedule: (Long, () -> Unit) -> (() -> Unit) = { delay, block ->
            if (refuse) throw RejectedExecutionException("shut down")
            val job = Job(now + delay, block)
            jobs += job
            ({ job.cancelled = true })
        }

        fun advance(millis: Long) {
            val until = now + millis
            while (true) {
                val due = jobs.filter { it.at <= until }.minByOrNull { it.at } ?: break
                jobs -= due
                now = due.at
                if (!due.cancelled) due.block()
            }
            now = until
        }
    }

    private val master = "https://stitcher.example/channel/abc/master.m3u8?sid=1"
    private val variant = "https://stitcher.example/channel/abc/360p.m3u8"
    private val masterBody = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n360p.m3u8\n"
    private fun media(segment: String) = "#EXTM3U\n#EXTINF:5.0,\n$segment\n#EXTINF:5.0,\n$segment\n"
    private val bumper = media("/clip/x_ptv_7424_ad_bumper_animation_dots_normal_30_1/720p/a.ts")
    private val show = media("/clip/6123_Enter_the_Dragon/720p/b.ts")

    private class Subject(
        val clock: Clock,
        val poller: BreakPoller,
        val fetched: MutableList<String>,
        val changes: MutableList<BreakDetector.State>,
        val views: MutableList<BreakView>,
    )

    /** [answers] per url: each fetch takes the next answer (null throws), the last repeats. */
    private fun subject(answers: Map<String, List<String?>>): Subject {
        val clock = Clock()
        val fetched = mutableListOf<String>()
        val changes = mutableListOf<BreakDetector.State>()
        val views = mutableListOf<BreakView>()
        val served = mutableMapOf<String, Int>()
        val poller = BreakPoller(
            fetch = { url ->
                fetched += url
                val list = answers[url] ?: throw java.io.IOException("404")
                val i = served.getOrDefault(url, 0)
                served[url] = i + 1
                BreakPoller.Fetched(url, list[minOf(i, list.lastIndex)] ?: throw java.io.IOException("down"))
            },
            schedule = clock.schedule,
            read = { _, view ->
                views += view
                // The two-read detector's transitions, as the old callback reported them.
                val state = if (view.fallbackInBreak) IN_BREAK else PROGRAMME
                if (state != (changes.lastOrNull() ?: PROGRAMME)) changes += state
            },
            nowMillis = { clock.now },
            wallMillis = { 1_000_000L + clock.now },
        )
        return Subject(clock, poller, fetched, changes, views)
    }

    @Test
    fun `the first read is at the tune, then one every four seconds`() {
        val s = subject(mapOf(master to listOf(masterBody), variant to listOf(show)))
        s.poller.start(master)
        assertTrue(s.fetched.isEmpty())
        s.clock.advance(0)
        assertEquals(listOf(master, variant), s.fetched)
        s.clock.advance(BreakPoller.POLL_MILLIS - 1)
        assertEquals(2, s.fetched.size)
        s.clock.advance(1)
        s.clock.advance(BreakPoller.POLL_MILLIS)
        // The master once per tune; the variant each time.
        assertEquals(listOf(master, variant, variant, variant), s.fetched)
    }

    @Test
    fun `two bumper reads raise the break and the first programme read ends it`() {
        val s = subject(mapOf(master to listOf(masterBody), variant to listOf(show, bumper, bumper, bumper, show)))
        s.poller.start(master)
        s.clock.advance(BreakPoller.FIRST_READ_MILLIS)
        s.clock.advance(BreakPoller.POLL_MILLIS * 2)
        assertEquals(listOf(IN_BREAK), s.changes)
        s.clock.advance(BreakPoller.POLL_MILLIS * 2)
        assertEquals(listOf(IN_BREAK, PROGRAMME), s.changes)
    }

    @Test
    fun `a failed variant read is unknown, and the next read asks the master again`() {
        val s = subject(mapOf(master to listOf(masterBody), variant to listOf(bumper, null, bumper)))
        s.poller.start(master)
        s.clock.advance(BreakPoller.FIRST_READ_MILLIS)
        s.clock.advance(BreakPoller.POLL_MILLIS)
        s.clock.advance(BreakPoller.POLL_MILLIS)
        assertEquals(listOf(master, variant, variant, master, variant), s.fetched)
        // bumper, unknown, bumper: the unknown neither counted nor reset.
        assertEquals(listOf(IN_BREAK), s.changes)
    }

    @Test
    fun `a master that will not answer keeps polling without a state change`() {
        val s = subject(mapOf(master to listOf(null)))
        s.poller.start(master)
        s.clock.advance(BreakPoller.FIRST_READ_MILLIS + BreakPoller.POLL_MILLIS * 3)
        assertEquals(4, s.fetched.size)
        assertTrue(s.changes.isEmpty())
        assertEquals(1, s.clock.pending)
    }

    @Test
    fun `stop ends the polling - nothing is read or reported after it`() {
        val s = subject(mapOf(master to listOf(masterBody), variant to listOf(bumper)))
        s.poller.start(master)
        s.clock.advance(BreakPoller.FIRST_READ_MILLIS)
        s.poller.stop()
        assertNull(s.poller.pollingUrl)
        s.clock.advance(BreakPoller.POLL_MILLIS * 5)
        assertEquals(listOf(master, variant), s.fetched)
        assertTrue(s.changes.isEmpty())
        assertEquals(0, s.clock.pending)
    }

    @Test
    fun `a stop landing during a read drops that read's verdict`() {
        var calls = 0
        val clock = Clock()
        val changes = mutableListOf<BreakDetector.State>()
        lateinit var poller: BreakPoller
        poller = BreakPoller(
            fetch = { url ->
                calls++
                // The surf arrives while the second bumper read is on the wire.
                if (calls == 3) poller.stop()
                BreakPoller.Fetched(url, if (url == master) masterBody else bumper)
            },
            schedule = clock.schedule,
            read = { _, view -> if (view.fallbackInBreak) changes += IN_BREAK },
        )
        poller.start(master)
        clock.advance(BreakPoller.FIRST_READ_MILLIS + BreakPoller.POLL_MILLIS * 3)
        assertTrue(changes.isEmpty())
        assertEquals(0, clock.pending)
    }

    @Test
    fun `a new start is a new tune - fresh detector, fresh variant, old run dead`() {
        val s = subject(mapOf(master to listOf(masterBody), variant to listOf(bumper)))
        val first = s.poller.start(master)
        s.clock.advance(BreakPoller.FIRST_READ_MILLIS)
        val second = s.poller.start(master)
        assertFalse(s.poller.isCurrent(first))
        assertTrue(s.poller.isCurrent(second))
        s.clock.advance(BreakPoller.FIRST_READ_MILLIS)
        // One bumper read on the new run is not a break, whatever the old run had seen.
        assertTrue(s.changes.isEmpty())
        assertEquals(listOf(master, variant, master, variant), s.fetched)
        assertEquals(1, s.clock.pending)
    }

    @Test
    fun `an executor already shut down ends the run quietly`() {
        val s = subject(mapOf(master to listOf(masterBody), variant to listOf(show)))
        s.clock.refuse = true
        val run = s.poller.start(master)
        assertFalse(s.poller.isCurrent(run))
        assertTrue(s.fetched.isEmpty())
    }

    @Test
    fun `a channel stuck on bumper has its card taken down at the ceiling, on the poller's clock`() {
        val s = subject(mapOf(master to listOf(masterBody), variant to listOf(bumper)))
        s.poller.start(master)
        s.clock.advance(BreakPoller.FIRST_READ_MILLIS + BreakPoller.POLL_MILLIS)
        assertEquals(listOf(IN_BREAK), s.changes)
        s.clock.advance(BreakDetector.MAX_BREAK_MILLIS)
        assertEquals(listOf(IN_BREAK, PROGRAMME), s.changes)
        s.clock.advance(BreakDetector.MAX_BREAK_MILLIS * 4)
        assertEquals(listOf(IN_BREAK, PROGRAMME), s.changes)
    }

    @Test
    fun `a network lost mid-break ends it after six silent reads`() {
        val s = subject(mapOf(master to listOf(masterBody), variant to listOf(bumper, bumper, null)))
        s.poller.start(master)
        s.clock.advance(BreakPoller.FIRST_READ_MILLIS + BreakPoller.POLL_MILLIS)
        s.clock.advance(BreakPoller.POLL_MILLIS * (BreakDetector.MAX_UNKNOWN_READS - 1))
        assertEquals(listOf(IN_BREAK), s.changes)
        s.clock.advance(BreakPoller.POLL_MILLIS)
        assertEquals(listOf(IN_BREAK, PROGRAMME), s.changes)
    }

    private val timed = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:40\n" +
        "#EXT-X-DISCONTINUITY\n#EXT-X-PROGRAM-DATE-TIME:2026-09-26T04:46:22.400Z\n" +
        "#EXTINF:5.0,\n/clip/6123_King_Kong/720p/a.ts\n" +
        "#EXT-X-DISCONTINUITY\n#EXT-X-PROGRAM-DATE-TIME:2026-09-26T04:46:27.400Z\n" +
        "#EXTINF:5.0,\n/clip/x_ptv_7424_ad_bumper_animation_dots_normal_30_1/720p/b.ts\n" +
        "#EXTINF:5.0,\n/clip/x_ptv_7424_ad_bumper_animation_dots_normal_30_1/720p/c.ts\n"

    @Test
    fun `every read hands over the timeline, stamped with the wall clock it was read at`() {
        val s = subject(mapOf(master to listOf(masterBody), variant to listOf(timed)))
        s.poller.start(master)
        s.clock.advance(BreakPoller.POLL_MILLIS)
        assertEquals(2, s.views.size)
        val view = s.views.last()
        assertTrue(view.timed)
        assertEquals(java.time.Instant.parse("2026-09-26T04:46:27.400Z").toEpochMilli(), view.start)
        assertEquals(1_000_000L + BreakPoller.POLL_MILLIS, view.readAt)
        assertEquals(1_000_000L, view.firstReadAt)
        assertEquals(3, view.firstWindowStarts.size)
    }

    @Test
    fun `six silent reads in a row make the view blind, and one good read clears it`() {
        val s = subject(mapOf(master to listOf(masterBody), variant to listOf(timed, null, null, null, null, null, null, timed)))
        s.poller.start(master)
        s.clock.advance(BreakPoller.POLL_MILLIS * 6)
        assertTrue(s.views.last().blind)
        assertFalse(s.views[s.views.size - 2].blind)
        s.clock.advance(BreakPoller.POLL_MILLIS)
        assertFalse(s.views.last().blind)
    }
}
