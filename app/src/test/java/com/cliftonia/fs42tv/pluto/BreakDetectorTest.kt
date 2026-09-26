package com.cliftonia.fs42tv.pluto

import com.cliftonia.fs42tv.pluto.BreakDetector.State.IN_BREAK
import com.cliftonia.fs42tv.pluto.BreakDetector.State.PROGRAMME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ad-break detector: Pluto with no ads to sell fills the break with its own logo bumper, and
 * the media playlist then names nothing else. Segment names are the ones measured in Sep 2026.
 */
class BreakDetectorTest {

    private val bumperA = "https://siloh.pluto.tv/clip/58e5397b91586c9d2fbe8d4c_ptv_7424adbumper" +
        "animationdotsinverted30_30fps/1080pDRM/20170414_144140/hls/hls_1080_3.ts"
    private val bumperB = "/clip/58e5397b91586c9d2fbe8d4c_ptv_7424_ad_bumper_animation_dots_normal_30_1" +
        "/720p/20170414/hls/hls_720_1.ts"
    private val programme = "https://siloh.pluto.tv/clip/6123abc_Flicks_of_Fury_Enter_the_Dragon/" +
        "1080p/20210101/hls/hls_1080_77.ts"
    private val live = "https://service-stitcher.clusters.pluto.tv/live/v1/prd/FlicksOfFury/seg_00123.ts"

    private fun playlist(vararg segments: String) = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:6")
        appendLine("#EXT-X-MEDIA-SEQUENCE:1234")
        segments.forEach {
            appendLine("#EXT-X-DISCONTINUITY")
            appendLine("#EXTINF:5.005,")
            appendLine(it)
        }
    }

    /** The clock only matters to the ceiling; everywhere else every read is at time zero. */
    private fun BreakDetector.feed(body: String?) = feed(body, 0L)

    private val allBumper = playlist(bumperA, bumperB, bumperA)
    private val allProgramme = playlist(programme, programme, live)

    @Test
    fun `a window of nothing but bumper is a bumper read, in either spelling`() {
        assertEquals(BreakDetector.Read.BUMPER, BreakDetector.classify(allBumper))
        assertEquals(BreakDetector.Read.BUMPER, BreakDetector.classify(playlist(bumperB.uppercase())))
    }

    @Test
    fun `one programme segment makes the window a programme read`() {
        assertEquals(BreakDetector.Read.PROGRAMME, BreakDetector.classify(playlist(bumperA, bumperB, live)))
        assertEquals(BreakDetector.Read.PROGRAMME, BreakDetector.classify(allProgramme))
    }

    @Test
    fun `nothing readable is unknown - a failure, a master, an empty window`() {
        assertNull(BreakDetector.classify(null))
        assertNull(BreakDetector.classify(""))
        assertNull(BreakDetector.classify("<html>502 Bad Gateway</html>"))
        assertNull(BreakDetector.classify(playlist()))
        assertNull(BreakDetector.classify("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n"))
    }

    @Test
    fun `it starts on the programme`() {
        assertEquals(PROGRAMME, BreakDetector().state)
    }

    @Test
    fun `two bumper reads in a row are a break`() {
        val detector = BreakDetector()
        assertEquals(PROGRAMME, detector.feed(allBumper))
        assertEquals(IN_BREAK, detector.feed(allBumper))
        assertEquals(IN_BREAK, detector.feed(allBumper))
    }

    @Test
    fun `a single bumper read between programme reads never flashes the card`() {
        // A one-segment bumper at a transition fills the whole window for one read.
        val detector = BreakDetector()
        listOf(allProgramme, playlist(bumperA), allProgramme, playlist(bumperA), allProgramme)
            .forEach { assertEquals(PROGRAMME, detector.feed(it)) }
    }

    @Test
    fun `a single bumper segment inside a programme window is the programme`() {
        val detector = BreakDetector()
        repeat(4) { assertEquals(PROGRAMME, detector.feed(playlist(programme, bumperA, programme))) }
    }

    @Test
    fun `the first programme read ends the break`() {
        val detector = BreakDetector()
        detector.feed(allBumper)
        detector.feed(allBumper)
        assertEquals(PROGRAMME, detector.feed(playlist(bumperA, bumperA, programme)))
    }

    @Test
    fun `unknown reads never change the state, either way`() {
        val detector = BreakDetector()
        assertEquals(PROGRAMME, detector.feed(null))
        detector.feed(allBumper)
        detector.feed(allBumper)
        assertEquals(IN_BREAK, detector.feed(null))
        assertEquals(IN_BREAK, detector.feed("garbage"))
        assertEquals(IN_BREAK, detector.feed(playlist()))
    }

    @Test
    fun `an unknown read between two bumper reads neither counts nor resets`() {
        val detector = BreakDetector()
        detector.feed(allBumper)
        assertEquals(PROGRAMME, detector.feed(null))
        assertEquals(IN_BREAK, detector.feed(allBumper))
    }

    @Test
    fun `flapping between bumper and programme stays on the programme`() {
        val detector = BreakDetector()
        repeat(6) {
            assertEquals(PROGRAMME, detector.feed(allBumper))
            assertEquals(PROGRAMME, detector.feed(allProgramme))
        }
    }

    @Test
    fun `flapping inside a break drops the card only on a real programme read`() {
        val detector = BreakDetector()
        detector.feed(allBumper)
        detector.feed(allBumper)
        assertEquals(PROGRAMME, detector.feed(allProgramme))
        // Back into bumper: two reads again before the card returns.
        assertEquals(PROGRAMME, detector.feed(allBumper))
        assertEquals(IN_BREAK, detector.feed(allBumper))
    }

    @Test
    fun `six reads in a row that say nothing end a break`() {
        val detector = BreakDetector()
        detector.feed(allBumper)
        detector.feed(allBumper)
        repeat(BreakDetector.MAX_UNKNOWN_READS - 1) { assertEquals(IN_BREAK, detector.feed(null)) }
        assertEquals(PROGRAMME, detector.feed(null))
        // A fresh two-read confirmation, not one bumper read, brings the card back.
        assertEquals(PROGRAMME, detector.feed(allBumper))
        assertEquals(IN_BREAK, detector.feed(allBumper))
    }

    @Test
    fun `a bumper read between unknown reads restarts their count`() {
        val detector = BreakDetector()
        detector.feed(allBumper)
        detector.feed(allBumper)
        repeat(BreakDetector.MAX_UNKNOWN_READS - 1) { detector.feed(null) }
        detector.feed(allBumper)
        repeat(BreakDetector.MAX_UNKNOWN_READS - 1) { assertEquals(IN_BREAK, detector.feed(null)) }
    }

    @Test
    fun `a break ends when it has run five minutes, however bumper the window`() {
        val detector = BreakDetector()
        detector.feed(allBumper, 0L)
        detector.feed(allBumper, 5_000L)
        assertEquals(IN_BREAK, detector.feed(allBumper, 5_000L + BreakDetector.MAX_BREAK_MILLIS - 1))
        assertEquals(PROGRAMME, detector.feed(allBumper, 5_000L + BreakDetector.MAX_BREAK_MILLIS))
    }

    @Test
    fun `a channel stuck on bumper past the ceiling gets no second card until the programme is seen`() {
        val detector = BreakDetector()
        var now = 0L
        fun read(body: String): BreakDetector.State = detector.feed(body, now).also { now += 5_000L }
        read(allBumper)
        read(allBumper)
        while (detector.state == IN_BREAK) read(allBumper)
        // Stuck: an hour more of bumper never flaps the card back up.
        repeat(720) { assertEquals(PROGRAMME, read(allBumper)) }
        // The programme, then a real break later: two reads, as ever.
        assertEquals(PROGRAMME, read(allProgramme))
        assertEquals(PROGRAMME, read(allBumper))
        assertEquals(IN_BREAK, read(allBumper))
    }
}
