package com.cliftonia.fs42tv.resolver

import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.sync.Tier
import com.cliftonia.fs42tv.sync.UrlCache

/**
 * Decides what to play from what has already been resolved.
 *
 * Deliberately pure: it performs no I/O and makes no network call, so every branch is
 * testable on the JVM. Needing a resolve is represented as a RESULT, not performed here.
 *
 * DORMANT. This whole path reads a published [UrlCache], and nothing publishes one any more:
 * `MainActivity.urls` is always null, so [resolve] is only ever reached from tests. The live
 * member of this file is [refusedKey], which the 403 fallback uses on every tune. Kept rather
 * than deleted because it is the shape a future pre-resolved cache would fill, and because
 * removing it would land in the middle of the tune path while channel switching still has
 * unresolved bugs. The vocabulary it returns moved to `Playable.kt`, which is not dormant.
 */
object StreamResolver {

    /**
     * [refused] holds "<id>/<tier>" pairs the CDN has already rejected this session.
     *
     * Per TIER, not per video, and that distinction is the whole point. A signed URL can come
     * back 403 while still inside its stated expiry, and the old behaviour condemned the entire
     * clip - which meant a server resolve, and `/resolve` runs yt-dlp: measured at 7.7 and 12.2
     * seconds, well past the 4s after which the viewer is shown a stand-by card. Yet nearly every
     * clip is published with BOTH an hd and an sd tier, sitting in a file the app already holds.
     * Falling to the next rung costs nothing and no round trip at all.
     */
    fun resolve(
        stream: Stream,
        cache: UrlCache?,
        ladder: List<String>,
        nowSeconds: Long,
        refused: Set<String> = emptySet(),
    ): Playable {
        val id = stream.id ?: return Hls(stream.url)
        val tiers = cache?.urls?.get(id) ?: return NeedsResolving(id)

        for (name in ladder) {
            val tier = tiers[name] ?: continue
            if (!tier.isFresh(nowSeconds)) continue
            if (refusedKey(id, name) in refused) continue
            return Progressive(tier.video, tier.audio)
        }
        // Every rung is stale or refused, so the server is the only way forward.
        return NeedsResolving(id)
    }

    /** The key used to remember one refused tier of one clip. */
    fun refusedKey(videoId: String, tier: String): String = "$videoId/$tier"
}
