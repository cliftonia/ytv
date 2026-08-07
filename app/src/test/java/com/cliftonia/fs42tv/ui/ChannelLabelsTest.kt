package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.tune.Tuned
import org.junit.Assert.assertEquals
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
        val row = ChannelLabels.listRow(
            Channel(number = 4, name = "Architecture & Interiors", kind = "youtube",
                rotation = "clock", streams = emptyList()))
        assertTrue("the picker is how you find a channel, so both fields must be present",
            row.contains("04") && row.contains("Architecture & Interiors"))
    }
}
