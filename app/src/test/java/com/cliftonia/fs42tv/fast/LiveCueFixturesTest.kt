package com.cliftonia.fs42tv.fast

import com.cliftonia.fs42tv.pluto.BreakView
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real FAST media playlists, captured from this network on 2026-09-28 (direct-route channels of
 * curation/fast_candidates.json, read four times ~35s apart; query strings stripped), in
 * `resources/fast-cues/<source>-<channel>-<read>.m3u8`. Every one carries cue tags. Each
 * channel's reads are fed in order through the real parser and timeline, and the break found must
 * be sane: a start on or before the window's edge, a length within the junk guards.
 */
class LiveCueFixturesTest {

    private val cueTag = Regex("""^#EXT-X-CUE-(OUT|OUT-CONT|IN)\b""")

    private fun channels(): Map<String, List<String>> {
        val dir = File(javaClass.classLoader!!.getResource("fast-cues")!!.toURI())
        return dir.listFiles()!!.filter { it.name.endsWith(".m3u8") }
            .groupBy { it.name.substringBeforeLast('-') }
            .mapValues { (_, files) -> files.sortedBy { it.name }.map { it.readText() } }
            .toSortedMap()
    }

    @Test
    fun `every explicit cue tag of every live playlist is read, none dropped`() {
        channels().forEach { (name, reads) ->
            reads.forEach { body ->
                val window = CuePlaylist.parse(body)
                assertNotNull(name, window)
                // With the raw SCTE-35 lines removed, every cue left came from a CUE tag.
                val bare = CuePlaylist.parse(body.lines().filterNot { it.startsWith("#EXT-OATCLS") }.joinToString("\n"))!!
                val tags = body.lines().count { cueTag.containsMatchIn(it.trim()) }
                assertEquals(name, tags, bare.segments.sumOf { it.cues.size })
                assertEquals(name, window!!.segments.size, bare.segments.size)
            }
        }
    }

    @Test
    fun `each live channel's break is found with a sane start and length`() {
        val report = StringBuilder()
        channels().forEach { (name, reads) ->
            val source = CueBreaks()
            var view: BreakView? = null
            reads.forEachIndexed { i, body ->
                val at = 1_790_000_000_000L + i * 35_000L
                view = source.read(body, at, at, at)
            }
            val v = view!!
            val cues = reads.flatMap { CuePlaylist.parse(it)!!.segments.flatMap { s -> s.cues } }
            val opens = cues.any { it is Cue.Out || it is Cue.Cont }
            val length = v.start?.let { s -> v.end?.let { it - s } }
            val onScreen = v.edgeEnd?.let { it - v.liveOffsetMillis }
            report.appendLine("$name: timed=${v.timed} counted=${v.counted} length=$length " +
                "edge-start=${v.start?.let { s -> v.edgeEnd?.let { it - s } }} " +
                "onScreenInBreak=${onScreen?.let { v.at(it).inBreak }}")
            assertTrue(name, v.timed)
            if (!opens) {
                assertNull("$name: a CUE-IN alone is no break", v.start)
                return@forEach
            }
            val start = v.start
            assertNotNull("$name: an out or a CONT is a break", start)
            assertTrue("$name: the break starts on or before the edge", start!! <= v.edgeEnd!!)
            if (length != null) {
                assertTrue("$name: $length", length in CueBreak.MIN_DURATION_MILLIS..CueBreak.MAX_DURATION_MILLIS)
            }
        }
        println(report)
    }

    @Test
    fun `a CONT that has run past its own duration is a break long over, not one on now`() {
        // RightNow TV (Stirr) read ElapsedTime=272..416 of a Duration=150 break, no CUE-IN in
        // sight: the stitcher never closed it. The break it describes ended minutes ago.
        val source = CueBreaks()
        var view: BreakView? = null
        channels().getValue("us_stirr-rightnow-tv").forEachIndexed { i, body ->
            view = source.read(body, i * 35_000L, i * 35_000L, 0)
        }
        val v = view!!
        assertEquals(150_000L, v.end!! - v.start!!)
        assertTrue(v.end!! < v.edgeEnd!! - v.liveOffsetMillis)
        assertTrue(!v.at(v.edgeEnd!! - v.liveOffsetMillis).inBreak)
    }
}
