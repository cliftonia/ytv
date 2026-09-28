package com.cliftonia.fs42tv.pluto

/**
 * The playlist [MasterPicker] chose out of a Pluto master, remembered for a few minutes, so a surf
 * back to a channel - or onto a neighbour [MasterPrefetch] read ahead - skips the master read.
 *
 * WHY: the TCL logged 655-955ms for that read on every Pluto tune, inside a first frame of
 * 2.3-2.8s. The pick does not change from one tune to the next on the same session - the master
 * lists the same variants - so reading it again is time spent in the black for nothing.
 *
 * KEYED BY THE MASTER URL AND THE ENGINE. A direct-route master url names the channel AND the
 * session: the stitcher, its params and the jwt are all in it, so an entry is only ever found by
 * a tune on the very session it was read on. A session rebuilt after a failure - playbackFailed,
 * noPicture, a refresh near expiry - has a new jwt, hence new urls, and every entry for the old one
 * simply stops being asked for and ages out. The engine is the QUALITY ceiling mpv picked for
 * ("mpv@1080"): a different ceiling picks a different variant, and Media3 never picks at all, so an
 * entry is never handed to anything but the engine it was chosen for.
 *
 * Small and short-lived on purpose: [CAPACITY] channels, least recently used first out, each for
 * [TTL_MILLIS] - well inside [PlutoSessions.REFRESH_MARGIN_MILLIS], so no entry can outlive the
 * token its urls carry. An entry is dropped at once when its channel fails ([evict]), so a bad
 * pick is never played twice from here: the re-tune after the error reads the master afresh.
 *
 * Only the direct route is cached. LEGACY's jmp2 url is the same whatever session it redirects
 * to, so it says nothing about which session a remembered pick belongs to.
 *
 * Thread-safe: the tune thread reads and writes, the prefetch threads write, and the player's
 * error callback evicts on the UI thread - every operation is a short critical section on the map,
 * never held across a fetch. Holds urls with tokens in them - never log a key or a value.
 */
class VariantCache(
    /** Monotonic milliseconds, for the age of an entry. */
    private val elapsedMillis: () -> Long,
    private val ttlMillis: Long = TTL_MILLIS,
    private val capacity: Int = CAPACITY,
) {

    /** What mpv is handed instead of the master: the chosen playlist, and its audio if separate. */
    data class Choice(val mediaUrl: String, val audioUrl: String?)

    private class Entry(val choice: Choice, val storedAt: Long)

    private data class Key(val masterUrl: String, val engine: String)

    // Access order: a hit moves the entry to the young end, and the eldest is the one let go.
    private val entries = object : LinkedHashMap<Key, Entry>(capacity + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean = size > capacity
    }

    /** The pick remembered for [masterUrl] under [engine], or null - absent, or older than the TTL. */
    fun get(masterUrl: String, engine: String): Choice? = synchronized(entries) {
        val key = Key(masterUrl, engine)
        val entry = entries[key] ?: return null
        if (elapsedMillis() - entry.storedAt >= ttlMillis) {
            entries.remove(key)
            return null
        }
        entry.choice
    }

    /** Whether [masterUrl] is already remembered, fresh, under [engine] - so a prefetch can skip it. */
    fun has(masterUrl: String, engine: String): Boolean = get(masterUrl, engine) != null

    fun put(masterUrl: String, engine: String, choice: Choice) = synchronized(entries) {
        entries[Key(masterUrl, engine)] = Entry(choice, elapsedMillis())
    }

    /**
     * The channel at [masterUrl] failed to play: forget it under every engine, so the re-tune
     * reads the master afresh. A failing channel must never be made stickier by a remembered pick.
     */
    fun evict(masterUrl: String) = synchronized(entries) {
        entries.keys.removeAll { it.masterUrl == masterUrl }
    }

    val size: Int get() = synchronized(entries) { entries.size }

    companion object {
        /** A surf's reach: the channels either side, and a few recently left to come back to. */
        const val CAPACITY = 8

        /** Far inside a session's life; a pick older than this is read again. */
        const val TTL_MILLIS = 5 * 60_000L
    }
}
