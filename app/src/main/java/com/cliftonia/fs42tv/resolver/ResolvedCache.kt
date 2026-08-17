package com.cliftonia.fs42tv.resolver

import java.util.concurrent.ConcurrentHashMap

/**
 * Server-resolved URLs, remembered for as long as they are usable.
 *
 * Roughly half of all tunes miss `urls.json` and fall through to `GET /resolve`. Without this,
 * every later pass over the same channel pays that round trip again - and the preloader makes it
 * worse, because it resolves neighbours too, multiplying the trips by the preload fan-out on
 * every press.
 *
 * In memory only, on purpose. These URLs are signed and expire in about six hours; persisting
 * them would mean starting up holding URLs that may already be dead, and a stale signed URL is a
 * channel that plays nothing rather than an honest miss.
 *
 * Concurrent because the preloader will resolve neighbours alongside the tune that triggered it.
 * The margin is [SAFETY_MARGIN_SECONDS], shared with `TierFreshness` rather than restated, so
 * the cached path and the published path retire a URL at exactly the same moment.
 */
class ResolvedCache {

    private data class Entry(val playable: Progressive, val expiresAtSeconds: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(videoId: String, nowSeconds: Long): Progressive? {
        val entry = entries[videoId] ?: return null
        if (nowSeconds + SAFETY_MARGIN_SECONDS >= entry.expiresAtSeconds) {
            entries.remove(videoId)
            return null
        }
        return entry.playable
    }

    fun put(videoId: String, playable: Progressive, expiresAtSeconds: Long) {
        entries[videoId] = Entry(playable, expiresAtSeconds)
        if (entries.size > SWEEP_ABOVE) sweep(nowSeconds = expiresAtSeconds - MAX_LIFETIME_SECONDS)
    }

    /**
     * Drop everything already expired.
     *
     * Entries were only ever removed when [get] happened to land on a stale one, so a clip
     * resolved once and never revisited was kept for the life of the process - dead url and all.
     * On a dial of nine thousand clips, an evening of surfing accumulates a few megabytes of
     * urls that can never be used again, on a television with 2.34GB in total.
     *
     * Triggered from [put] rather than on a timer: entries only ever arrive through it, so that
     * is the one moment the cache can have grown, and it costs nothing to check.
     */
    private fun sweep(nowSeconds: Long) {
        val before = entries.size
        entries.entries.removeAll { nowSeconds + SAFETY_MARGIN_SECONDS >= it.value.expiresAtSeconds }
        if (entries.size < before) {
            android.util.Log.d("fs42", "resolve cache swept ${before - entries.size} expired")
        }
    }

    /** Forget an entry whose URL the CDN refused, so the next resolve fetches a fresh one. */
    fun forget(videoId: String) {
        entries.remove(videoId)
    }

    /** For logging and tests; not a correctness signal. */
    val size: Int get() = entries.size

    /**
     * Forget everything resolved so far.
     *
     * Used when the quality ceiling changes: the cache holds urls for the OLD ceiling, and
     * without this the setting would appear to do nothing until every cached entry expired -
     * up to six hours of looking like a broken button.
     */
    fun clear() {
        entries.clear()
    }

    private companion object {
        /**
         * Only sweep once the cache is big enough to be worth it.
         *
         * A dial of a hundred channels is unlikely to hold more live resolutions than this at
         * once, so in ordinary use the sweep never runs at all; it exists for the long session
         * that would otherwise accumulate quietly.
         */
        const val SWEEP_ABOVE = 200

        /**
         * The longest a signed googlevideo url has ever been observed to last - six hours.
         *
         * Used to derive "now" from an entry's own expiry rather than reading the clock, which
         * keeps this class free of any notion of the current time and therefore testable with
         * nothing but the values passed in.
         */
        const val MAX_LIFETIME_SECONDS = 21_600L
    }
}
