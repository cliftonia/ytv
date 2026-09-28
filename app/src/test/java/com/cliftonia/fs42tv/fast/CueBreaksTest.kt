package com.cliftonia.fs42tv.fast

import com.cliftonia.fs42tv.pluto.BreakView
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A FAST channel's cues, read after read, into a [BreakView]: 5s segments, segment `i` stamped
 * t0 + 5s*i and at the edge from clock 5s*(i+1), a window of five.
 */
class CueBreaksTest {

    private val t0 = Instant.parse("2026-09-28T01:00:00Z").toEpochMilli()

    private class Channel(val tags: Map<Long, List<String>>, val pdt: Boolean = true, val dates: Map<Long, String> = emptyMap())

    private fun Channel.window(now: Long, t0: Long): String {
        val newest = now / 5_000 - 1
        val first = maxOf(0L, newest - 4)
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:5")
            appendLine("#EXT-X-MEDIA-SEQUENCE:$first")
            (first..newest).forEach { i ->
                dates[i]?.let { appendLine(it) }
                tags[i]?.forEach { appendLine(it) }
                if (pdt) appendLine("#EXT-X-PROGRAM-DATE-TIME:${Instant.ofEpochMilli(t0 + i * 5_000)}")
                appendLine("#EXTINF:5.0,")
                appendLine("seg$i.ts")
            }
        }
    }

    /** Reads every 4s from [from] to [to]; the view after the last. */
    private fun Channel.read(from: Long, to: Long, source: CueBreaks = CueBreaks()): BreakView {
        var view: BreakView? = null
        var now = from
        while (now <= to) {
            view = source.read(window(now, t0), now, now, now)
            now += 4_000
        }
        return view!!
    }

    /** A minute's break over segments 20..31, cued the usual way. */
    private val minute = Channel(buildMap {
        put(20L, listOf("#EXT-X-CUE-OUT:DURATION=60"))
        (21L..31L).forEach { put(it, listOf("#EXT-X-CUE-OUT-CONT:ElapsedTime=${(it - 20) * 5},Duration=60")) }
        put(32L, listOf("#EXT-X-CUE-IN"))
    })

    @Test
    fun `CUE-OUT with a duration - the end is known before it is on air`() {
        val view = minute.read(60_000, 108_000)
        assertEquals(t0 + 100_000, view.start)
        assertEquals(t0 + 160_000, view.end)
        assertTrue(view.at(t0 + 100_000).inBreak)
        assertFalse(view.at(t0 + 160_000).inBreak)
    }

    @Test
    fun `CUE-IN ends it where the duration would not`() {
        val short = Channel(mapOf(20L to listOf("#EXT-X-CUE-OUT:DURATION=90"), 26L to listOf("#EXT-X-CUE-IN")))
        assertEquals(t0 + 130_000, short.read(60_000, 140_000).end)
    }

    @Test
    fun `a bare CUE-OUT is open until CUE-IN`() {
        val bare = Channel(mapOf(20L to listOf("#EXT-X-CUE-OUT"), 44L to listOf("#EXT-X-CUE-IN")))
        val during = bare.read(60_000, 180_000)
        assertEquals(t0 + 100_000, during.start)
        assertNull(during.end)
        assertTrue(during.at(t0 + 170_000).inBreak)
        assertEquals(t0 + 220_000, bare.read(60_000, 230_000).end)
    }

    @Test
    fun `surfed in mid-break - CUE-OUT-CONT places the start and the end`() {
        val view = minute.read(130_000, 130_000)
        assertEquals(t0 + 100_000, view.start)
        assertEquals(t0 + 160_000, view.end)
        assertTrue(view.at(t0 + 115_000).inBreak)
    }

    @Test
    fun `no PDT - instants are counted from the first read, and the break lands the same`() {
        val counted = Channel(minute.tags, pdt = false)
        val view = counted.read(60_000, 108_000)
        assertTrue(view.counted && view.timed)
        // The first read at 60s saw segment 11 end at its edge: segment i is counted 5s*i.
        assertEquals(100_000L, view.start)
        assertEquals(160_000L, view.end)
        assertEquals(listOf(35_000L, 40_000L, 45_000L, 50_000L, 55_000L), view.firstWindowStarts)
    }

    @Test
    fun `no PDT, surfed in mid-break`() {
        val view = Channel(minute.tags, pdt = false).read(130_000, 130_000)
        assertEquals(100_000L, view.start)
        assertEquals(160_000L, view.end)
    }

    @Test
    fun `junk durations - under ten seconds ignored, over six minutes capped`() {
        val blip = Channel(mapOf(20L to listOf("#EXT-X-CUE-OUT:DURATION=4")))
        assertNull(blip.read(60_000, 130_000).start)
        val huge = Channel(mapOf(20L to listOf("#EXT-X-CUE-OUT:DURATION=3600")))
        val view = huge.read(60_000, 130_000)
        assertEquals(t0 + 100_000 + CueBreak.MAX_DURATION_MILLIS, view.end)
        assertFalse("the five-minute ceiling still holds", view.at(t0 + 100_000 + 5 * 60_000).inBreak)
    }

    @Test
    fun `a stream that never says CUE-IN still gets its next break`() {
        val events = listOf(CueBreak.Event.Out(0, null), CueBreak.Event.Out(10 * 60_000L, 60_000))
        assertEquals(CueBreak.Found(10 * 60_000L, 11 * 60_000L), CueBreak.latest(events))
    }

    @Test
    fun `a DATERANGE break, on the PDT clock`() {
        val start = Instant.ofEpochMilli(t0 + 100_000)
        val ranged = Channel(emptyMap(), dates = mapOf(
            20L to "#EXT-X-DATERANGE:ID=\"ad-1\",START-DATE=\"$start\",PLANNED-DURATION=45,SCTE35-OUT=0xFC",
        ))
        val view = ranged.read(60_000, 120_000)
        assertEquals(t0 + 100_000, view.start)
        assertEquals(t0 + 145_000, view.end)
    }

    @Test
    fun `OATCLS alone opens and closes a break`() {
        val oatcls = Channel(mapOf(
            20L to listOf("#EXT-OATCLS-SCTE35:${Scte35Bytes.spliceInsert(true, null)}"),
            30L to listOf("#EXT-OATCLS-SCTE35:${Scte35Bytes.spliceInsert(false, null)}"),
        ))
        val view = oatcls.read(60_000, 170_000)
        assertEquals(t0 + 100_000, view.start)
        assertEquals(t0 + 150_000, view.end)
    }

    @Test
    fun `reads that fail go blind after six`() {
        val source = CueBreaks()
        minute.read(60_000, 108_000, source)
        repeat(5) { assertFalse(source.read(null, 0, 0, 0).blind) }
        assertTrue(source.read(null, 0, 0, 0).blind)
    }

    @Test
    fun `a channel with no cues has no break`() {
        val view = Channel(emptyMap()).read(60_000, 300_000)
        assertNull(view.start)
        assertTrue(view.timed)
    }
}
