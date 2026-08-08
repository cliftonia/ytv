package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.tune.Tuned
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the viewer reads on screen. These are the only overlay decisions with logic in them,
 * so they are the only part worth a test - the views below just draw the strings.
 */
class ChannelLabelsTest {

    private fun tunedWith(title: String, number: Int = 4, name: String = "Architecture") =
        Channel(number = number, name = name, kind = "live", rotation = null,
            streams = listOf(Stream(id = null, url = "u", duration = 1, title = title)))
            .let { ch ->
                Tuned(ch, 0, ch.streams[0], Hls("u"), 0.0)
            }

    @Test
    fun `banner leads with the channel number and name`() {
        val (first, _) = ChannelLabels.bannerLines(tunedWith("Some Programme"))
        assertTrue("the banner must answer 'what am I watching' before anything else",
            first.contains("4") && first.contains("Architecture", ignoreCase = true))
    }

    @Test
    fun `banner second line carries the programme title`() {
        val (_, second) = ChannelLabels.bannerLines(tunedWith("Inside The Bear"))
        assertEquals("Inside The Bear", second)
    }

    // The title-cleaning tests that used to sit here went with the app-side cleaner. The rules
    // they guarded now live in `fs42/yt_title.py` on the server, and all six cases were
    // re-checked against it before the tests were removed - including the Telugu title and
    // every case where the identifying segment sits after a "|". The stronger evidence is the
    // audit that ran the published cleaner over all 2939 titles on the dial: 2495 changed,
    // 0 emptied.

    @Test
    fun `an empty title yields an empty second line rather than a placeholder`() {
        val (_, second) = ChannelLabels.bannerLines(tunedWith(""))
        assertEquals("a placeholder like 'Unknown' is worse than showing nothing", "", second)
    }

    @Test
    fun `a list row shows number and name`() {
        val (head, _) = ChannelLabels.listRow(
            Channel(number = 4, name = "Architecture & Interiors", kind = "youtube",
                rotation = "clock", streams = emptyList()))
        assertTrue("the picker is how you find a channel, so both fields must be present",
            head.contains("04") && head.contains("ARCHITECTURE & INTERIORS"))
    }

    @Test
    fun `list rows align the name column across two and three digit channels`() {
        fun nameStart(number: Int) =
            ChannelLabels.listRow(
                Channel(number = number, name = "Anything", kind = "youtube",
                    rotation = "clock", streams = emptyList())).first.indexOf("Anything")
        assertEquals("the dial runs past 100, and a name column that steps right at channel " +
            "100 is what the eye catches when scanning 111 rows",
            nameStart(4), nameStart(106))
    }

    @Test
    fun `what is playing comes back separately so it can be drawn dimmer`() {
        val (head, title) = ChannelLabels.listRow(
            Channel(number = 5, name = "Wrestling", kind = "youtube", rotation = "clock",
                streams = emptyList()),
            nowPlaying = "Royal Rumble 1990")
        assertEquals("Royal Rumble 1990", title)
        assertFalse("glued into one string the picker could not draw them at two brightnesses",
            head.contains("Royal Rumble"))
    }

    @Test
    fun `a row with no title yields an empty second part`() {
        val (head, title) = ChannelLabels.listRow(
            Channel(number = 5, name = "ABC News", kind = "live", rotation = null,
                streams = emptyList()))
        assertEquals("", title)
        assertEquals("CH 05  ABC NEWS", head)
    }

    @Test
    fun `a long title is passed through whole for the layout to ellipsise`() {
        val long = "A Very Long Programme Title That Would Otherwise Run Past The Right Edge Of The Screen"
        val (_, title) = ChannelLabels.listRow(
            Channel(number = 5, name = "Docos", kind = "youtube", rotation = "clock",
                streams = emptyList()), nowPlaying = long)
        assertEquals("truncating here means guessing glyph widths at two font sizes, which cut " +
            "titles short with most of the row still empty - the layout knows the real width",
            long, title)
    }

    @Test
    fun `banner lines can be worked out without tuning`() {
        val channel = Channel(number = 5, name = "Wrestling", kind = "youtube", rotation = "clock",
            streams = listOf(
                Stream(id = "a", url = "u1", duration = 100, title = "First Programme"),
                Stream(id = "b", url = "u2", duration = 100, title = "Second Programme"),
            ))
        // 150s into a 200s cycle is halfway through the SECOND clip.
        val (line, title) = ChannelLabels.bannerLinesFor(channel, nowSeconds = 150)
        assertEquals("05 WRESTLING", line)
        assertEquals("waiting for the tune meant a bare channel name whenever the tune was " +
            "superseded, which is every press but the last when surfing quickly",
            "Second Programme", title)
    }

    @Test
    fun `a channel with no clips yields no title rather than a wrong one`() {
        val live = Channel(number = 103, name = "ABC News", kind = "live", rotation = null,
            streams = listOf(Stream(id = null, url = "u", duration = 0, title = "placeholder")))
        val (line, title) = ChannelLabels.bannerLinesFor(live, nowSeconds = 50)
        assertEquals("103 ABC NEWS", line)
        assertEquals("a live channel has no clip cycle to read a position from, and inventing " +
            "one would put a stale title under a live picture", "", title)
    }
}
