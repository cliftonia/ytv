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
        "%02d  %s".format(tuned.channel.number, tuned.channel.name.uppercase()) to
            tuned.stream.title.trim()

    fun listRow(channel: Channel): String =
        "CHANNEL %02d   %s".format(channel.number, channel.name)
}
