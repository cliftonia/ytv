package com.cliftonia.fs42tv.resolver

/**
 * Everything the app knows about urls the CDN has turned down, in one place.
 *
 * Four pieces of state have to move together whenever a 403 arrives: which (id, tier) pairs are
 * refused, which whole clips are condemned, which tier each clip actually played at, and the
 * cache of resolved urls - because a refusal that does not also forget the cached url replays
 * the identical dead link, and a resolve that lands after a refusal must not re-cache what was
 * just condemned. That forget-on-refuse invariant shipped broken twice while these lived as
 * four separate fields in the activity; a ledger cannot ship half an update.
 *
 * [nowElapsedSeconds] is injected so the expiry rule is testable. It must be a MONOTONIC clock:
 * the box has no battery and corrects its wall clock over NTP after boot, and a wall-clock jump
 * would age every refusal instantly.
 */
class RefusalLedger(
    private val nowElapsedSeconds: () -> Long,
    private val cache: ResolvedCache = ResolvedCache(),
) {

    private val refusedTiers =
        java.util.Collections.synchronizedMap(mutableMapOf<String, Long>())
    private val deadIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * The clip and tier most recently handed to the video player, so [condemn] can refuse the
     * rung that actually failed. Written on the tuning executor, read on player callback
     * threads.
     */
    @Volatile private var lastPlayed: Pair<String, String>? = null

    /**
     * The refused (id, tier) pairs still young enough to believe.
     *
     * Refusals expire after the life of the longest signed url. A 403 describes ONE url, and
     * every url it could describe is dead within six hours - but the entries used to live for
     * the whole process, so on an always-on television two network blips condemned a clip's
     * every tier until someone power-cycled the box.
     */
    fun refusedSnapshot(): Set<String> {
        val cutoff = nowElapsedSeconds() - REFUSAL_TTL_SECONDS
        synchronized(refusedTiers) {
            val stale = refusedTiers.entries.iterator()
            while (stale.hasNext()) if (stale.next().value < cutoff) stale.remove()
            return refusedTiers.keys.toSet()
        }
    }

    /**
     * React to the CDN rejecting [id]'s current url.
     *
     * Returns the tier now refused, or null when every rung was already refused and the whole
     * clip has been condemned instead. The recorded last-played tier beats recomputation
     * whenever it is available: "first fresh rung of the ladder" is only right when the clip
     * offered every rung and nothing raced, and when it is wrong the refusal condemns a rung
     * that never played while the guilty one is retried forever. The recomputation stays as the
     * fallback for an error arriving before anything was recorded.
     *
     * Either way the cached resolve is forgotten - it still holds the very url that was just
     * rejected, and replaying it three or four times over was why a 403 used to show as several
     * seconds of unexplained black instead of a quick recovery.
     */
    fun condemn(id: String, ladder: List<String>): String? {
        val fresh = refusedSnapshot()
        val tier = lastPlayed?.takeIf { it.first == id }?.second
            ?: ladder.firstOrNull { StreamResolver.refusedKey(id, it) !in fresh }
        if (tier != null) {
            refusedTiers[StreamResolver.refusedKey(id, tier)] = nowElapsedSeconds()
        } else {
            deadIds.add(id)
        }
        cache.forget(id)
        return tier
    }

    /** Whether [id] has been condemned outright, so a tune should not even try it. */
    fun isDead(id: String): Boolean = id in deadIds

    /** Condemn [id] after a resolve failed, so the next tune does not pay for it again. */
    fun markDead(id: String) {
        deadIds.add(id)
    }

    /** A cached resolve for something OTHER than the main picture - prefetch, guide music. */
    fun recall(id: String, nowSeconds: Long): Progressive? = cache.get(id, nowSeconds)

    /** A cached resolve that is about to be handed to the player; the tier is recorded for [condemn]. */
    fun recallToPlay(id: String, nowSeconds: Long): Progressive? {
        val hit = cache.get(id, nowSeconds) ?: return null
        cache.tierOf(id)?.let { lastPlayed = id to it }
        return hit
    }

    /** Cache a resolve nobody is playing yet. The refusal check inside put still applies. */
    fun remember(id: String, resolved: ClipResolver.Resolved) {
        cache.put(id, resolved, refusedSnapshot())
    }

    /** Cache a resolve that is about to play: remembered, un-condemned, and tier-recorded. */
    fun rememberPlayed(id: String, resolved: ClipResolver.Resolved) {
        cache.put(id, resolved, refusedSnapshot())
        deadIds.remove(id)
        lastPlayed = id to resolved.tier
    }

    /**
     * Forget every cached resolve.
     *
     * Used when the quality ceiling changes: the cache holds urls for the OLD ceiling, and
     * without this the setting would appear to do nothing until every entry expired - up to six
     * hours of looking like a broken button.
     */
    fun clearResolved() {
        cache.clear()
    }

    private companion object {
        /**
         * The longest a signed googlevideo url has been observed to live - six hours, shared in
         * spirit with ResolvedCache.MAX_LIFETIME_SECONDS: a refusal must not outlive every url
         * it could possibly describe.
         */
        const val REFUSAL_TTL_SECONDS = 21_600L
    }
}
