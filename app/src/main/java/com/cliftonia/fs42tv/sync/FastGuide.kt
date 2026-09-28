package com.cliftonia.fs42tv.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What is on the LIVE TV dial's FAST channels: `fast_guide.json`, built every six hours by
 * `curation/build_fast_guide.py` from the Samsung TV Plus, Plex, Roku, Xumo, Tubi, Rakuten TV and
 * Stirr guides.
 *
 * Those guides are whole-service files of up to 43 MB, which no television here should fetch,
 * and the services have no per-channel guide a television could ask the way it asks Pluto. So the
 * job cuts them down to the dial's own channels, the next thirty hours, start times and titles -
 * tens of kilobytes - and this answers "what is on now" from that in memory.
 *
 * The wire format, compact because it is fetched several times a day: `base` in epoch seconds,
 * one shared `titles` table with the empty title at 0, and per channel a flat list of
 * (start minute after base, title index) pairs. A programme runs until the next pair's start, and
 * the list always ends on title 0 - so a time past the listings, or in a hole in them, has no
 * answer rather than whatever aired last.
 *
 * Pure and immutable: parsed once per download, then read from the UI thread and the executors.
 */
class FastGuide private constructor(
    /** Epoch seconds the build was made at, for how stale it is. */
    val generatedSeconds: Long,
    private val baseSeconds: Long,
    private val titles: List<String>,
    /** Per channel key: starts (minutes after base) and title indexes, the same length. */
    private val starts: Map<String, IntArray>,
    private val titleIndexes: Map<String, IntArray>,
) {

    /** How many channels the file covers - for the log line on each download. */
    val size: Int get() = starts.size

    /**
     * The title on air on [channel] at [nowMillis], or null: a channel with no guide in the file,
     * a time before its first listing or after its last, or a hole in its listings.
     */
    fun titleAt(channel: Channel, nowMillis: Long): String? {
        val key = keyOf(channel) ?: return null
        val at = starts[key] ?: return null
        val minute = Math.floorDiv(nowMillis / 1000 - baseSeconds, 60L)
        // The last start at or before now: a binary search, since this runs per guide row.
        var lo = 0
        var hi = at.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (at[mid] <= minute) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return null
        return titles.getOrNull(titleIndexes.getValue(key)[found])?.takeIf { it.isNotBlank() }
    }

    @Serializable
    private class Wire(
        val generated: Long = 0,
        val base: Long = 0,
        val titles: List<String> = emptyList(),
        val channels: Map<String, List<Int>> = emptyMap(),
    )

    companion object {
        /** Where the televisions fetch it: the same branch as the lineups, never the api. */
        const val URL = "$REPO_RAW/fast_guide.json"

        /** The guide services the file carries; see `curation/build_fast_guide.py`. */
        private val SERVICES = setOf("samsung", "plex", "roku", "xumo", "tubi", "rakuten", "stirr")

        private val json = Json { ignoreUnknownKeys = true }

        /** How [channel] is named in the file - `<guide>:<guide_id>` - or null when it is not. */
        fun keyOf(channel: Channel): String? {
            val guide = channel.guide?.takeIf { it in SERVICES } ?: return null
            val id = channel.guideId?.takeIf { it.isNotBlank() } ?: return null
            return "$guide:$id"
        }

        /**
         * The file's text as a guide. Throws on anything that is not one, so a truncated download
         * or a captive portal's page is never cached; a channel whose list is malformed - odd
         * length, starts running backwards - is left out rather than answering wrongly.
         */
        fun parse(text: String): FastGuide {
            val wire = json.decodeFromString(Wire.serializer(), text)
            require(wire.base > 0) { "not a fast guide" }
            val starts = HashMap<String, IntArray>(wire.channels.size * 2)
            val titleIndexes = HashMap<String, IntArray>(wire.channels.size * 2)
            for ((key, flat) in wire.channels) {
                if (flat.isEmpty() || flat.size % 2 != 0) continue
                val s = IntArray(flat.size / 2) { flat[it * 2] }
                if ((1 until s.size).any { s[it] < s[it - 1] }) continue
                starts[key] = s
                titleIndexes[key] = IntArray(flat.size / 2) { flat[it * 2 + 1] }
            }
            return FastGuide(wire.generated, wire.base, wire.titles, starts, titleIndexes)
        }
    }
}
