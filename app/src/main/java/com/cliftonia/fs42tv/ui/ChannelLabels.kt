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

    /** Zero-padded so the indicator does not change width as you surf. */
    fun indicator(number: Int): String = "CH %02d".format(number)

    fun bannerLines(tuned: Tuned): Pair<String, String> =
        "%02d  %s".format(tuned.channel.number, tuned.channel.name.uppercase()) to
            cleanTitle(tuned.stream.title)

    fun listRow(channel: Channel): String =
        "CHANNEL %02d   %s".format(channel.number, channel.name)

    /**
     * Strip bracketed noise - "[4K]", "(Official Video)" and friends - from a raw YouTube
     * title, conservatively.
     *
     * This used to also strip a leading "Uploader: " prefix and cut everything after the
     * first "|", on the assumption that the identifying part of a title is always
     * front-loaded. Verification against real published titles showed that assumption is
     * false often enough to be dangerous: round numbers, episode numbers and even the show
     * name itself regularly land in a later "|" segment, and the colon rule fired on
     * internal title punctuation ("Episode 3:") as readily as on an actual uploader prefix.
     * Both rules were silently deleting the one piece of information that told two videos
     * on the same channel apart - e.g. "Tom and Jerry | Mega Episode: Golden Era Vol. 10 |
     * Warner Classics" lost "Tom and Jerry" entirely. The banner's title line wraps to two
     * lines and ellipsizes, so a title that is merely long is harmless; a title with its
     * meaning removed is not. Do not reintroduce a rule that assumes the identity is in a
     * fixed position without re-verifying against real published titles first.
     */
    fun cleanTitle(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        // "[4K]", "(Official Video)" and friends.
        text = text.replace(Regex("""\s*[\[(][^\])]*[\])]"""), "").trim()

        return text.ifEmpty { raw.trim() }
    }
}
