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
    fun `indicator pads a single digit to two`() {
        assertEquals("a jittering-width indicator draws the eye on every channel change",
            "CH 04", ChannelLabels.indicator(4))
    }

    @Test
    fun `indicator does not truncate three digits`() {
        assertEquals("the dial runs past 100, and a truncated number is a wrong number",
            "CH 103", ChannelLabels.indicator(103))
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

    @Test
    fun `a show name ahead of an internal colon survives`() {
        assertEquals("a colon rule that fired here deleted the show name entirely",
            "Tom and Jerry | Mega Episode: Golden Era Vol. 10 | Warner Classics",
            ChannelLabels.cleanTitle(
                "Tom and Jerry | Mega Episode: Golden Era Vol. 10 | Warner Classics"))
    }

    @Test
    fun `a round number in a later pipe segment survives`() {
        assertEquals("a pipe rule that fired here dropped the only thing distinguishing this " +
            "video from every other highlight on the channel",
            "NRL Highlights | NRL 2026 | Round Up | Round 7",
            ChannelLabels.cleanTitle("NRL Highlights | NRL 2026 | Round Up | Round 7"))
    }

    @Test
    fun `an episode number in a later pipe segment survives`() {
        assertEquals("a pipe rule that fired here dropped the episode identifier",
            "Comedy Inc | Season 2 Episode 3 | FULL EPISODE",
            ChannelLabels.cleanTitle("Comedy Inc | Season 2 Episode 3 | FULL EPISODE"))
    }

    @Test
    fun `a series name ahead of an episode colon survives`() {
        assertEquals("a colon rule that fired here deleted the series name and episode number",
            "Can I Trust the Bible - Episode 3: The Council of Nicaea | @WesHuff",
            ChannelLabels.cleanTitle(
                "Can I Trust the Bible - Episode 3: The Council of Nicaea | @WesHuff"))
    }

    @Test
    fun `bracketed noise is removed`() {
        assertEquals("Chanel Fall Winter Show",
            ChannelLabels.cleanTitle("Chanel Fall Winter Show [4K] (Official Video)"))
    }

    @Test
    fun `a title that is merely long is left alone`() {
        val long = "A Very Long But Entirely Legitimate Programme Title About Something"
        assertEquals("truncating here would lose information the viewer wants",
            long, ChannelLabels.cleanTitle(long))
    }

    @Test
    fun `non-latin titles survive cleaning`() {
        val telugu = "కాపులకు క్లారిటీ"
        assertEquals("the real dial carries Telugu and Malayalam titles; mangling them shows as garbage",
            telugu, ChannelLabels.cleanTitle(telugu))
    }

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
