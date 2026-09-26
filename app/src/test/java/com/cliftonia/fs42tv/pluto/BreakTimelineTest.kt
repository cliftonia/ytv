package com.cliftonia.fs42tv.pluto

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The break's start and end, read off the playlist's clock. The playlists are shaped like the
 * captured ones (Sep 2026): target duration 5, a discontinuity and a PROGRAM-DATE-TIME at every
 * programme/bumper change, a window of about five segments.
 */
class BreakTimelineTest {

    private val kong = "https://siloh.pluto.tv/clip/5f1_King_Kong_1933/720p/20200101/hls/seg%d.ts"
    private val dots = "https://siloh.pluto.tv/clip/58e5397b91586c9d2fbe8d4c_ptv_7424_ad_bumper_" +
        "animation_dots_normal_30_1/720p/20170414/hls/seg%d.ts"

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    /** One segment of a window: its kind, its length, and a PDT when a discontinuity starts it. */
    private data class S(val bumper: Boolean, val seconds: Double, val pdt: String? = null)

    private fun p(seconds: Double = 5.0, pdt: String? = null) = S(false, seconds, pdt)
    private fun b(seconds: Double = 5.0, pdt: String? = null) = S(true, seconds, pdt)

    private fun window(firstSeq: Long, vararg segments: S) = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:5")
        appendLine("#EXT-X-MEDIA-SEQUENCE:$firstSeq")
        appendLine("#EXT-X-DISCONTINUITY-SEQUENCE:12")
        appendLine("#PLUTO-SESSION-ID:abc")
        segments.forEachIndexed { i, s ->
            if (s.pdt != null) {
                appendLine("#EXT-X-DISCONTINUITY")
                appendLine("#EXT-X-PROGRAM-DATE-TIME:${s.pdt}")
            }
            appendLine("#EXTINF:${s.seconds},")
            appendLine((if (s.bumper) dots else kong).format(firstSeq + i))
        }
    }

    @Test
    fun `a window parses into segments with sequence, length, kind and date`() {
        val w = HlsWindow.parse(window(100, p(pdt = "2026-09-26T04:46:22.400Z"), p(), p(2.7),
            b(pdt = "2026-09-26T04:46:35.100Z"), b()))!!
        assertEquals(5_000L, w.targetDurationMillis)
        assertEquals(listOf(100L, 101L, 102L, 103L, 104L), w.segments.map { it.seq })
        assertEquals(listOf(5_000L, 5_000L, 2_700L, 5_000L, 5_000L), w.segments.map { it.durationMillis })
        assertEquals(listOf(false, false, false, true, true), w.segments.map { it.bumper })
        assertEquals(at("2026-09-26T04:46:22.400Z"), w.segments[0].programDateTime)
        assertNull(w.segments[1].programDateTime)
    }

    @Test
    fun `not a media playlist is no window`() {
        assertNull(HlsWindow.parse(null))
        assertNull(HlsWindow.parse("<html>502</html>"))
        assertNull(HlsWindow.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n"))
    }

    @Test
    fun `the break starts at the first bumper's PDT, as soon as it is in the window`() {
        val timeline = BreakTimeline()
        timeline.feed(HlsWindow.parse(window(100, p(pdt = "2026-09-26T04:46:22.400Z"), p(), p(2.7),
            b(pdt = "2026-09-26T04:46:37.400Z"), b()))!!, readAt = 0L)
        val view = timeline.view()
        assertTrue(view.timed)
        assertEquals(at("2026-09-26T04:46:37.400Z"), view.start)
        assertNull(view.end)
    }

    @Test
    fun `segments without their own PDT count forward from the last one, and back to the first`() {
        val timeline = BreakTimeline()
        // The PDT is only at the change; the programme before it is counted backwards.
        timeline.feed(HlsWindow.parse(window(100, p(), p(), p(2.7),
            b(pdt = "2026-09-26T04:46:37.400Z"), b()))!!, readAt = 0L)
        val view = timeline.view()
        assertEquals(at("2026-09-26T04:46:37.400Z"), view.start)
        assertEquals(at("2026-09-26T04:46:24.700Z"), view.firstWindowStarts.first())
        // The edge: the last bumper segment's end.
        assertEquals(at("2026-09-26T04:46:47.400Z"), view.edgeEnd)
    }

    @Test
    fun `start and end found in different reads, the window sliding in between`() {
        val timeline = BreakTimeline()
        timeline.feed(HlsWindow.parse(window(100, p(pdt = "2026-09-26T04:46:22.400Z"), p(), p(2.7),
            b(pdt = "2026-09-26T04:46:35.100Z"), b()))!!, readAt = 0L)
        // Twenty seconds on: the programme has slid out of the window entirely.
        timeline.feed(HlsWindow.parse(window(104, b(), b(), b(), b(), b()))!!, readAt = 20_000L)
        var view = timeline.view()
        assertEquals(at("2026-09-26T04:46:35.100Z"), view.start)
        assertNull(view.end)
        timeline.feed(HlsWindow.parse(window(107, b(), b(), p(pdt = "2026-09-26T04:47:10.100Z"),
            p(), p()))!!, readAt = 35_000L)
        view = timeline.view()
        assertEquals(at("2026-09-26T04:46:35.100Z"), view.start)
        assertEquals(at("2026-09-26T04:47:10.100Z"), view.end)
    }

    @Test
    fun `tuned mid-break, the break started no later than the first segment seen`() {
        val timeline = BreakTimeline()
        timeline.feed(HlsWindow.parse(window(200, b(pdt = "2026-09-26T05:00:00Z"), b(), b(), b(), b()))!!, 0L)
        assertEquals(at("2026-09-26T05:00:00Z"), timeline.view().start)
    }

    @Test
    fun `no PDT anywhere is an untimed window`() {
        val timeline = BreakTimeline()
        timeline.feed(HlsWindow.parse(window(100, p(), p(), b(), b(), b()))!!, 0L)
        assertFalse(timeline.view().timed)
    }

    @Test
    fun `a later window without a PDT is still timed by its sequence numbers`() {
        val timeline = BreakTimeline()
        timeline.feed(HlsWindow.parse(window(100, p(pdt = "2026-09-26T04:46:22.400Z"), p(), p(), p(), p()))!!, 0L)
        timeline.feed(HlsWindow.parse(window(102, p(), p(), p(), b(), b()))!!, 10_000L)
        val view = timeline.view()
        assertTrue(view.timed)
        assertEquals(at("2026-09-26T04:46:47.400Z"), view.start)
    }

    @Test
    fun `the latest break is the one that counts`() {
        val timeline = BreakTimeline()
        timeline.feed(HlsWindow.parse(window(100, b(pdt = "2026-09-26T04:00:00Z"), p(pdt = "2026-09-26T04:00:05Z"),
            p(), b(pdt = "2026-09-26T04:00:15Z"), b()))!!, 0L)
        assertEquals(at("2026-09-26T04:00:15Z"), timeline.view().start)
    }

    @Test
    fun `the first window's instants arrive with the next PDT, however long the programme ran`() {
        val timeline = BreakTimeline()
        timeline.feed(HlsWindow.parse(window(100, p(), p(), p(), p(), p()))!!, readAt = 7L)
        assertTrue(timeline.view().firstWindowStarts.isEmpty())
        // Minutes of programme with no PDT, read every few seconds...
        (101L..160L).forEach { first ->
            timeline.feed(HlsWindow.parse(window(first, p(), p(), p(), p(), p()))!!, readAt = first)
        }
        // ...then the break's stamp, from which everything counts back.
        timeline.feed(HlsWindow.parse(window(161, p(), p(), p(), b(pdt = "2026-09-26T05:00:00Z"), b()))!!, 200L)
        val view = timeline.view()
        val stamp = at("2026-09-26T05:00:00Z")
        assertEquals((100L..104L).map { stamp - (164 - it) * 5_000L }, view.firstWindowStarts)
        assertEquals(7L, view.firstReadAt)
        assertEquals(stamp, view.start)
    }

    @Test
    fun `the live offset is the last three segments' EXTINF, and the anchor stamp is the request`() {
        val timeline = BreakTimeline()
        timeline.feed(HlsWindow.parse(window(100, p(pdt = "2026-09-26T04:46:22.400Z"), p(), p(5.005),
            p(5.005), p(5.005)))!!, readAt = 900L, requestedAt = 400L)
        val view = timeline.view()
        assertEquals(15_015L, view.liveOffsetMillis)
        assertEquals(400L, view.firstReadAt)
        assertEquals(900L, view.readAt)
    }
}
