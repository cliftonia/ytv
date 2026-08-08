package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.schedule.ClockRotation
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.tune.Tuned

/**
 * The strings the overlays draw.
 *
 * Pure Kotlin with no Android imports: this is the only part of the overlay work with
 * decisions in it, so it is the part worth testing. The views render what this returns.
 */
object ChannelLabels {

    /**
     * Zero-padded so the heading does not change width as you surf.
     *
     * One space between number and name, not the two the box uses (`field_player.py:153`).
     * This is a deliberate divergence: at 27.5.sp monospace those two spaces read as a gulf
     * rather than a separator, which the box's smaller effective rendering never showed. The
     * picker keeps a wider gap because its number sits in a fixed-width field so the name
     * column stays straight past channel 100 - see [listRow].
     *
     * The title is used as published, not cleaned here. The server runs every title through
     * `fs42/yt_title.py` - the same cleaner the box's own OSD uses - so marketing tails, ALL
     * CAPS, emoji and hashtags are already gone by the time the app sees them. Cleaning again
     * on this side would be a second implementation of the same rules in a different language,
     * free to drift; and it would run too late anyway, since cleaning a string something else
     * already shortened cannot recover the segment that was cut off.
     *
     * The app did once clean titles itself, and the way that went is worth knowing before
     * reintroducing it: the rules assumed a title's identity is front-loaded, so they cut
     * everything after the first "|" and stripped a leading "Uploader: ". Both silently
     * destroyed the one piece of information distinguishing two videos on a channel - "Tom
     * and Jerry | Mega Episode: Golden Era Vol. 10 | Warner Classics" lost "Tom and Jerry"
     * outright. `yt_title.py` drops segments by what they SAY rather than where they sit,
     * which is why it survives contact with real titles where the positional rules did not.
     */
    fun bannerLines(tuned: Tuned): Pair<String, String> =
        "%02d %s".format(tuned.channel.number, tuned.channel.name.uppercase()) to
            tuned.stream.title.trim()

    /**
     * A picker row: channel number, channel name, and what is on it right now.
     *
     * Returned as two parts rather than one string, because the picker draws them very
     * differently: the channel in full-size capitals, what is on after a colon at about half the
     * size, in italics and dimmed.
     *
     * NOTHING is truncated here. An earlier version budgeted characters, which meant guessing
     * monospace glyph widths at two different font sizes and then converting between them - it
     * cut titles short with most of the row still empty. The picker gives the title whatever
     * width is left and lets Compose ellipsise at the real edge, which is the only thing that
     * actually knows how wide the text is. That is
     * a deliberate choice of contrast over size - people skim a guide rather than read it, and
     * varying the type size would vary the row height and break the vertical rhythm that makes
     * skimming possible. Same size, two brightnesses: the bright names form a scannable column
     * and the titles recede until the eye stops on one.
     *
     * An earlier version padded the name into a fixed column so titles lined up, which left a
     * ragged gap after every short channel name and read worse than the alignment was worth.
     *
     * The NUMBER still sits in a fixed-width field, because the dial runs past 100 and without
     * it every name after a three-digit channel steps one character right.
     *
     * [nowPlaying] is optional because a live channel has no clip list to read a title from, and
     * because a channel whose streams are all zero-duration has no "now" at all. Either way the
     * row degrades to number and name rather than showing an empty separator.
     */
    /**
     * The banner lines for a channel, worked out from the clock alone.
     *
     * The tuning path has [bannerLines], which reads an actual [Tuned]. This one needs no tune
     * at all: the clip list and the wall clock are enough to say what is on, which is what lets
     * the banner carry a programme title the instant a button is pressed rather than after the
     * network has answered.
     */
    fun bannerLinesFor(channel: Channel, nowSeconds: Long): Pair<String, String> {
        val line = "%02d %s".format(channel.number, channel.name.uppercase())
        val title = ClockRotation
            .playPointFor(channel.streams.map { it.duration }, nowSeconds)
            ?.let { channel.streams.getOrNull(it.index)?.title }
            .orEmpty()
            .trim()
        return line to title
    }

    fun listRow(channel: Channel, nowPlaying: String? = null): Pair<String, String> {
        val head = "CH %-4s%s".format("%02d".format(channel.number), channel.name.uppercase())
        val title = nowPlaying?.trim().orEmpty()
        return if (title.isEmpty()) head to "" else "$head:" to title
    }
}
