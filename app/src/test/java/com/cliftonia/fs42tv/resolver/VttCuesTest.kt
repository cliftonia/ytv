package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a caption file turns into.
 *
 * The two fixtures below are verbatim excerpts of tracks the timedtext endpoint really served
 * for channel 21 clips - the hand-authored one from `NILBm7_iAoc`, the automatic one from
 * `jhAtKUb3B8o`. They are here rather than invented because the shape of an automatic track is
 * the whole difficulty: a synthetic fixture written from the WebVTT spec would have passed a
 * parser that produced nothing whatsoever from the real thing.
 */
class VttCuesTest {

    /** Hand-authored: no cue settings, no inline markup, one line per cue. */
    private val authored = """
        WEBVTT
        Kind: captions
        Language: en

        00:00:32.590 --> 00:00:34.840
        During the Ming Dynasty, especially during the reign of Wanli,

        00:00:34.840 --> 00:00:36.640
        Japanese bandits were rampant.

        00:00:37.080 --> 00:00:40.110
        Corrupt officials in the court colluded with the Japanese bandits
    """.trimIndent()

    /**
     * Automatic: cue settings after the timestamps, per-word karaoke timing, a rolling second
     * line carrying the previous cue, and - the part that matters - a line holding a single
     * SPACE between the timestamps and the words.
     */
    private val automatic =
        "WEBVTT\n" +
            "Kind: captions\n" +
            "Language: en\n" +
            "\n" +
            "00:00:08.880 --> 00:00:12.630 align:start position:0%\n" +
            " \n" +
            "It<00:00:09.040><c> was</c><00:00:09.240><c> in</c><00:00:09.360><c> the</c>" +
            "<00:00:09.440><c> 21st</c><00:00:10.200><c> year</c><00:00:10.440><c> of</c>" +
            "<00:00:10.560><c> Daoguang.</c>\n" +
            "\n" +
            "00:00:12.630 --> 00:00:12.640 align:start position:0%\n" +
            "It was in the 21st year of Daoguang.\n" +
            " \n" +
            "\n" +
            "00:00:12.640 --> 00:00:15.510 align:start position:0%\n" +
            "It was in the 21st year of Daoguang.\n" +
            "The<00:00:12.800><c> Opium</c><00:00:13.160><c> War</c><00:00:13.400><c> broke</c>" +
            "<00:00:13.720><c> out.</c>\n"

    @Test
    fun `a hand-authored track parses to its cues`() {
        val cues = VttCues.parse(authored)
        assertEquals(3, cues.size)
        assertEquals(32.590, cues[0].startSeconds, 0.001)
        assertEquals(34.840, cues[0].endSeconds, 0.001)
        assertEquals(
            "During the Ming Dynasty, especially during the reign of Wanli,",
            cues[0].text,
        )
    }

    @Test
    fun `an automatic track parses despite the space-only line before every cue`() {
        // The regression this file exists for. A cue block terminated on `isBlank` rather than
        // `isEmpty` ends at that space, so every cue comes out empty and gets dropped - and an
        // automatically captioned clip shows nothing while a hand-authored one works, which
        // reads as "captions are broken on some clips" rather than as a parser bug.
        val cues = VttCues.parse(automatic)
        assertEquals(3, cues.size)
        assertEquals("It was in the 21st year of Daoguang.", cues[0].text)
    }

    @Test
    fun `per-word karaoke timing never reaches the screen`() {
        val cues = VttCues.parse(automatic)
        assertTrue(cues.none { '<' in it.text || '>' in it.text })
    }

    @Test
    fun `the rolling previous line is kept, as two lines`() {
        // Automatic captions carry the previous sentence above the one being spoken. That is
        // how YouTube itself draws them and it is worth keeping - a single line replaced every
        // two seconds is much harder to follow.
        val cues = VttCues.parse(automatic)
        assertEquals(
            "It was in the 21st year of Daoguang.\nThe Opium War broke out.",
            cues.last().text,
        )
    }

    @Test
    fun `cue settings after the timestamps are ignored, not treated as text`() {
        val cues = VttCues.parse(automatic)
        assertTrue(cues.none { "position" in it.text || "align" in it.text })
        assertEquals(8.880, cues[0].startSeconds, 0.001)
    }

    @Test
    fun `a NOTE block is not read as a cue`() {
        val body = """
            WEBVTT

            NOTE
            This is a comment and contains 00:00:01.000 --> 00:00:02.000

            00:00:05.000 --> 00:00:06.000
            Real text
        """.trimIndent()
        val cues = VttCues.parse(body)
        assertEquals(1, cues.size)
        assertEquals("Real text", cues[0].text)
    }

    @Test
    fun `a cue identifier line before the timestamps is skipped`() {
        val body = "WEBVTT\n\ncue-7\n00:00:05.000 --> 00:00:06.000\nHello\n"
        assertEquals(listOf("Hello"), VttCues.parse(body).map { it.text })
    }

    @Test
    fun `speaker and style tags are stripped but their words survive`() {
        val body = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n" +
            "<v Roger Bingham><i>We are</i> in New York\n"
        assertEquals(listOf("We are in New York"), VttCues.parse(body).map { it.text })
    }

    @Test
    fun `escaped entities are decoded`() {
        // `&gt;&gt;` is the caption convention for a change of speaker and appears 1720 times in
        // the one automatic track measured, so this is the common case rather than an edge one.
        val body = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n&gt;&gt; Tom &amp; Jerry\n"
        assertEquals(listOf(">> Tom & Jerry"), VttCues.parse(body).map { it.text })
    }

    @Test
    fun `CRLF line endings parse the same as LF`() {
        val body = "WEBVTT\r\n\r\n00:00:01.000 --> 00:00:02.000\r\nHello\r\n"
        assertEquals(listOf("Hello"), VttCues.parse(body).map { it.text })
    }

    @Test
    fun `a timestamp with no hours field is read as minutes and seconds`() {
        assertEquals(90.5, VttCues.seconds("01:30.500")!!, 0.001)
        assertEquals(3690.5, VttCues.seconds("01:01:30.500")!!, 0.001)
        // SRT's comma, which Captions lets through as a readable format.
        assertEquals(90.5, VttCues.seconds("01:30,500")!!, 0.001)
        assertNull(VttCues.seconds("not a timestamp"))
        assertNull(VttCues.seconds("00:00:0x.000"))
    }

    @Test
    fun `a cue with nothing left after stripping is dropped`() {
        // The one-hundredth-of-a-second bridging cues in automatic tracks are exactly this, and
        // an empty cue reaching the overlay is a caption box drawn over the picture with no
        // words in it.
        val body = "WEBVTT\n\n00:00:01.000 --> 00:00:01.010\n \n\n" +
            "00:00:02.000 --> 00:00:03.000\nReal\n"
        assertEquals(listOf("Real"), VttCues.parse(body).map { it.text })
    }

    private val cues = listOf(
        VttCues.Cue(1.0, 3.0, "first"),
        VttCues.Cue(2.5, 5.0, "second"),
        VttCues.Cue(9.0, 10.0, "third"),
    )

    @Test
    fun `the cue covering a position is what shows`() {
        assertEquals("first", VttCues.activeAt(cues, 1.0))
        assertEquals("third", VttCues.activeAt(cues, 9.5))
    }

    @Test
    fun `where cues overlap the later one wins`() {
        // Automatic captions overlap constantly - the cue holding the previous line is still
        // open when the next begins. Taking the first match leaves the display a line behind
        // the audio for the whole clip.
        assertEquals("second", VttCues.activeAt(cues, 2.6))
    }

    @Test
    fun `a gap between cues shows nothing`() {
        assertNull(VttCues.activeAt(cues, 7.0))
        assertNull(VttCues.activeAt(cues, 0.0))
        assertNull(VttCues.activeAt(cues, 99.0))
        assertNull(VttCues.activeAt(emptyList(), 1.0))
    }

    @Test
    fun `a cue ends the instant its end time arrives`() {
        assertEquals("second", VttCues.activeAt(cues, 4.999))
        assertNull(VttCues.activeAt(cues, 5.0))
    }
}
