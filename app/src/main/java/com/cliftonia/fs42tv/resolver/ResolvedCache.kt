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
    }

    /** Forget an entry whose URL the CDN refused, so the next resolve fetches a fresh one. */
    fun forget(videoId: String) {
        entries.remove(videoId)
    }

    /** For logging and tests; not a correctness signal. */
    val size: Int get() = entries.size
}
