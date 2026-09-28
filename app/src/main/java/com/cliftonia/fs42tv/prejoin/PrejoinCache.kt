package com.cliftonia.fs42tv.prejoin

/**
 * What the pre-join holds for the channels either side of the one on screen: per media playlist,
 * the playlist as it was last read and the segment ffmpeg would join it on (and the one after),
 * in memory - see [Prejoin].
 *
 * A WINDOW, NOT A STORE. A snapshot is only ever handed out while it is young - [servableMillis],
 * about one target duration - so ffmpeg starts at most a segment behind where a fresh read would
 * have put it, and every segment after the join is still in the live playlist to be read from the
 * network. An older snapshot is a miss, and is dropped.
 *
 * BOUNDED: [maxBytes] of segments across every snapshot (the TCL has 2.3 GB in all). Storing past
 * it lets go of other channels' snapshots, least recently stored first; a snapshot that alone is
 * over it is not kept at all. Only segment bytes count: a playlist is a few kilobytes.
 *
 * Thread-safe: the warmers store, the tune thread takes, the UI thread clears - each a short
 * critical section. Holds urls with tokens in them: never log a key or a value.
 */
class PrejoinCache(
    /** Monotonic milliseconds, for a snapshot's age. */
    private val elapsedMillis: () -> Long,
    private val maxBytes: Long = MAX_BYTES,
) {

    /**
     * A media playlist read at [readAt] (monotonic; [readAtWall] the wall clock, for the break
     * card's anchor) from [finalUrl], and the bytes of the segments ffmpeg would start on.
     */
    class Snapshot(
        val mediaUrl: String,
        val body: String,
        val finalUrl: String,
        val readAt: Long,
        val readAtWall: Long,
        val targetMillis: Long,
        val segments: Map<String, ByteArray>,
    ) {
        val bytes: Long get() = segments.values.sumOf { it.size.toLong() }
    }

    // Insertion order: re-stored snapshots move to the young end, and the eldest go first.
    private val entries = LinkedHashMap<String, Snapshot>()

    /**
     * Keep [snapshot] in place of any earlier one for its playlist. False when it alone is over
     * the budget - then nothing is kept for it.
     */
    fun put(snapshot: Snapshot): Boolean = synchronized(entries) {
        entries.remove(snapshot.mediaUrl)
        if (snapshot.bytes > maxBytes) return false
        while (total() + snapshot.bytes > maxBytes && entries.isNotEmpty()) {
            entries.remove(entries.keys.first())
        }
        entries[snapshot.mediaUrl] = snapshot
        true
    }

    /** The bytes held for segment [url] of [mediaUrl]'s snapshot - to carry into the next one. */
    fun segment(mediaUrl: String, url: String): ByteArray? = synchronized(entries) { entries[mediaUrl]?.segments?.get(url) }

    /**
     * [mediaUrl]'s snapshot, taken out - at most once - while it is young enough to serve and
     * holds its join segment; else null, and anything stale is dropped. [why] says which.
     */
    fun take(mediaUrl: String, why: (String) -> Unit = {}): Snapshot? = synchronized(entries) {
        val snapshot = entries.remove(mediaUrl) ?: run { why("nothing warmed"); return null }
        val age = elapsedMillis() - snapshot.readAt
        if (age > servableMillis(snapshot.targetMillis)) {
            why("stale (${age}ms)")
            return null
        }
        if (snapshot.segments.isEmpty()) {
            why("no segment held")
            return null
        }
        snapshot
    }

    /** Drop everything - the viewer has tuned, or left the screen. */
    fun clear() = synchronized(entries) { entries.clear() }

    /** Drop [mediaUrl]'s snapshot: its channel failed, or its session was re-pointed. */
    fun drop(mediaUrl: String) = synchronized(entries) { entries.remove(mediaUrl) }

    /** Drop every snapshot too old to be served - a warmer that ended leaves nothing useful. */
    fun prune() = synchronized(entries) {
        val now = elapsedMillis()
        entries.values.removeAll { now - it.readAt > servableMillis(it.targetMillis) }
    }

    /** Segment bytes held, across every snapshot. */
    val bytes: Long get() = synchronized(entries) { total() }

    val size: Int get() = synchronized(entries) { entries.size }

    private fun total(): Long = entries.values.sumOf { it.bytes }

    companion object {
        /** The segments held at once: two neighbours, two segments each, at up to ~6 MB apiece. */
        const val MAX_BYTES = 24L * 1024 * 1024

        /**
         * How old a snapshot may be and still be served: one and a half target durations - so
         * ffmpeg's join is at most a segment behind a fresh read - never under 3s, never over 12s.
         */
        fun servableMillis(targetMillis: Long): Long = (targetMillis * 3 / 2).coerceIn(3_000L, 12_000L)
    }
}
