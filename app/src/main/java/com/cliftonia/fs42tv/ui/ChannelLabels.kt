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
     * Strip the boilerplate YouTube titles carry, conservatively.
     *
     * A long title is fine - the goal is removing the uploader's furniture, not summarising.
     * Anything that would empty the title is skipped, because showing the raw title beats
     * showing nothing.
     */
    fun cleanTitle(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        // "Uploader: Real Title" - drop the prefix, but only when something survives.
        val colon = text.indexOf(": ")
        if (colon in 1..40 && text.length > colon + 2) text = text.substring(colon + 2).trim()

        // "Real Title | Series | Uploader" - the first segment is the programme.
        text = text.substringBefore('|').trim().ifEmpty { text }

        // "[4K]", "(Official Video)" and friends.
        text = text.replace(Regex("""\s*[\[(][^\])]*[\])]"""), "").trim()

        return text.ifEmpty { raw.trim() }
    }
}
