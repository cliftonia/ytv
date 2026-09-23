package com.cliftonia.fs42tv.sync

/**
 * Which dial the television shows. Two whole lineups, never merged: switching replaces one with
 * the other.
 *
 * Each source names everything that must NOT be shared between them. Its own cache file, so a
 * switch never overwrites the other dial's last good copy - which is all there is to fall back on
 * when the television is offline. Its own remembered channel, so going back to YouTube lands on
 * the channel left there rather than on a Pluto number reinterpreted as a YouTube one.
 *
 * YOUTUBE keeps the url, file and key the app used before there was a choice, so updating an
 * installed app neither resets the remembered channel nor orphans the cached dial.
 */
enum class LineupSource(
    /** What the settings row shows. */
    val label: String,
    val url: String,
    val cacheFile: String,
    val channelKey: String,
) {
    YOUTUBE("YOUTUBE", "$REPO_RAW/channels.json", "channels.json", "channel"),
    PLUTO("PLUTO TV", "$REPO_RAW/pluto.json", "pluto.json", "channel.pluto");

    fun next(): LineupSource = values()[(ordinal + 1) % values().size]

    companion object {
        /** The remembered choice. */
        const val KEY = "source"

        /** Anything unreadable is YouTube: the dial that existed before there was a choice. */
        fun parse(value: String?): LineupSource =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: YOUTUBE
    }
}

/**
 * Where the lineups live: files in a public git repository, not an endpoint on a machine at home.
 * Both dials are rebuilt nightly by a workflow and committed, so a television picks up new content
 * by fetching one file over the open internet - the point, because one of these televisions lives
 * in a car and is rarely on the house network. `raw.githubusercontent.com` rather than the api: no
 * rate limit worth worrying about, no token, and it serves whatever the branch currently points to.
 */
private const val REPO_RAW = "https://raw.githubusercontent.com/cliftonia/ytv/main"
