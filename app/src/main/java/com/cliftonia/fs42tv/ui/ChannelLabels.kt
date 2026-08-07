package com.cliftonia.fs42tv.ui

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
     * The gap was three spaces, which at monospace 20.sp opened a visible gulf down a column of
     * 111 rows. It is now the same two the box puts between number and name
     * (`field_player.py:153`), so the picker and the OSD read as one product.
     *
     * The number is zero-padded for the retro look but the FIELD is left-aligned to a fixed
     * width, because the dial runs past 100: with a plain "%02d  " the names after a
     * three-digit channel would sit one character right of the names after a two-digit one, and
     * a ragged column is exactly what the eye catches when scanning a long list.
     */
    fun listRow(channel: Channel): String =
        "CHANNEL %-4s%s".format("%02d".format(channel.number), channel.name)
}
