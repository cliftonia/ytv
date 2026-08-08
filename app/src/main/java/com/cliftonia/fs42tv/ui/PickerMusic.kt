package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.sync.Channel

/**
 * Which channel supplies the music that plays under the channel picker.
 *
 * Cable guide channels always had music over the listings, and a silent list of 111 rows feels
 * like a settings screen rather than part of a television.
 *
 * Chosen by name against an ordered preference rather than a hardcoded channel number, so the
 * dial can be renumbered - which the conveyor does routinely - without silently swapping the
 * guide music for whatever now sits at that number. Adding a channel named "Bossa Nova" makes it
 * take precedence automatically, with no code change.
 *
 * Pure Kotlin with no Android imports, so the choosing is testable without a device.
 */
object PickerMusic {

    /** Best first. Matched case-insensitively as a substring, so "Bossa Nova Classics" counts. */
    private val PREFERRED = listOf("bossa nova", "bossa", "jazz", "classical music", "music")

    /**
     * The channel to play under the picker, or null when the dial has nothing suitable - in
     * which case the picker is simply silent, which is the right failure: guide music is
     * atmosphere, and atmosphere is never worth an error.
     */
    fun choose(channels: List<Channel>): Channel? {
        for (want in PREFERRED) {
            val hit = channels.firstOrNull { it.name.lowercase().contains(want) }
            if (hit != null) return hit
        }
        return null
    }
}
