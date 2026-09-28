package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.sync.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The LIVE TV guide's block headings: "MOVIES — ACTION" above the first channel of each run, and
 * nothing at all on a dial without blocks.
 */
class GuideHeadingsTest {

    private fun ch(number: Int, block: String?, sub: String?) =
        Channel(number = number, name = "C$number", kind = "live", block = block, sub = sub)

    @Test
    fun `a heading goes above the first channel of each block and sub-block`() {
        val dial = listOf(
            ch(100, "Movies", "Action"), ch(101, "Movies", "Action"),
            ch(110, "Movies", "Comedy"),
            ch(300, "Series", "Comedy"), ch(301, "Series", "Comedy"),
        )
        assertEquals(
            listOf("MOVIES — ACTION", null, "MOVIES — COMEDY", "SERIES — COMEDY", null),
            ChannelLabels.headings(dial),
        )
    }

    @Test
    fun `a dial without blocks has no headings`() {
        val youtube = listOf(ch(1, null, null), ch(2, null, null))
        assertEquals(listOf(null, null), ChannelLabels.headings(youtube))
    }

    @Test
    fun `a sub-block that only repeats its block reads as the block`() {
        assertEquals("ANIME", ChannelLabels.heading(ch(800, "Anime", "Anime")))
        assertEquals("SITCOMS (USA)", ChannelLabels.heading(ch(500, "Sitcoms (USA)", "Sitcoms")))
        assertEquals("RELAX", ChannelLabels.heading(ch(1700, "Relax", null)))
    }

    @Test
    fun `a sub-block named after its block drops the repeat`() {
        assertEquals("MOVIES — MIXED", ChannelLabels.heading(ch(250, "Movies", "Movies – Mixed")))
    }

    @Test
    fun `no block, no heading`() {
        assertNull(ChannelLabels.heading(ch(1, null, "Action")))
    }

    @Test
    fun `a four-digit channel keeps a space before its name in the list`() {
        assertEquals("CH 1625 C1625", ChannelLabels.listRow(ch(1625, null, null)).first)
        assertEquals("CH 05  C5", ChannelLabels.listRow(ch(5, null, null)).first)
        assertEquals("CH 999 C999", ChannelLabels.listRow(ch(999, null, null)).first)
    }

    @Test
    fun `headings for the whole LIVE TV dial are one pass, fast enough to build on open`() {
        val dial = (0 until 900).map { ch(100 + it, "Block${it / 100}", "Sub${it / 10}") }
        val started = System.nanoTime()
        val headings = ChannelLabels.headings(dial)
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals(90, headings.count { it != null })
        assert(tookMillis < 200) { "headings took ${tookMillis}ms" }
    }
}
