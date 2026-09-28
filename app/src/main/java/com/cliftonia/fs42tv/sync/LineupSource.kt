package com.cliftonia.fs42tv.sync

/**
 * Which dial the television shows. Two whole lineups, never merged: switching replaces one with
 * the other.
 *
 * Each source names everything that must NOT be shared between them. Its own cache file, so a
 * switch never overwrites the other dial's last good copy - which is all there is to fall back on
 * when the television is offline. Its own remembered channel, so going back to YouTube lands on
 * the channel left there rather than on a LIVE TV number reinterpreted as a YouTube one.
 *
 * YOUTUBE keeps the url, file and key the app used before there was a choice, so updating an
 * installed app neither resets the remembered channel nor orphans the cached dial.
 *
 * LIVE replaced PLUTO TV (Sep 2026): the 219 Pluto channels plus picked FAST channels, in genre
 * blocks. A new file and a new channel key rather than PLUTO's: every number moved, so the channel
 * remembered on the old dial means nothing on this one and the dial starts fresh. A saved "pluto"
 * choice reads as LIVE - see [parse] and [migrated]. Nothing Pluto-specific keys off the source:
 * sessions, now/next, breaks and prefetch all follow each channel's own `pluto` ref.
 */
enum class LineupSource(
    /** What the settings row shows. */
    val label: String,
    val url: String,
    val cacheFile: String,
    val channelKey: String,
) {
    YOUTUBE("YOUTUBE", "$REPO_RAW/channels.json", "channels.json", "channel"),
    LIVE("LIVE TV", "$REPO_RAW/live.json", "live.json", "channel.live");

    fun next(): LineupSource = values()[(ordinal + 1) % values().size]

    companion object {
        /** The remembered choice. */
        const val KEY = "source"

        /** Choices a retired source was saved as, and the source each now means. */
        private val RETIRED = mapOf("pluto" to LIVE)

        /**
         * Anything unreadable is YouTube: the dial that existed before there was a choice. A
         * retired source reads as the one that replaced it.
         */
        fun parse(value: String?): LineupSource =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: RETIRED[value?.lowercase()]
                ?: YOUTUBE

        /**
         * What to save in place of [stored], or null when it needs no rewrite. Written back once
         * at launch so the preference names a source that exists, and the settings row, which
         * saves `next().name`, never has to know about the old one.
         */
        fun migrated(stored: String?): String? =
            RETIRED[stored?.lowercase()]?.name?.lowercase()
    }
}

/**
 * Where the lineups live: files in a public git repository, not an endpoint on a machine at home.
 * Both dials are rebuilt nightly by a workflow and committed, so a television picks up new content
 * by fetching one file over the open internet - the point, because one of these televisions lives
 * in a car and is rarely on the house network. `raw.githubusercontent.com` rather than the api: no
 * rate limit worth worrying about, no token, and it serves whatever the branch currently points to.
 */
internal const val REPO_RAW = "https://raw.githubusercontent.com/cliftonia/ytv/main"
