package com.cliftonia.fs42tv.resolver

import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.sync.Tier
import com.cliftonia.fs42tv.sync.UrlCache

/** What the player should be handed. */
sealed interface Playable

/** Separate video and audio streams, as YouTube serves them above 360p. */
data class Progressive(val videoUrl: String, val audioUrl: String?) : Playable

/** A live HLS feed, played as-is. */
data class Hls(val url: String) : Playable

/** Nothing usable is cached; the caller must ask the server to resolve this id. */
data class NeedsResolving(val videoId: String) : Playable

/** The stream cannot be played and no server round trip would help. */
data class Unplayable(val reason: String) : Playable

/**
 * Decides what to play from what has already been resolved.
 *
 * Deliberately pure: it performs no I/O and makes no network call, so every branch is
 * testable on the JVM. Asking the server is represented as a RESULT, not performed here.
 */
object StreamResolver {

    fun resolve(
        stream: Stream,
        cache: UrlCache?,
        preferUhd: Boolean,
        nowSeconds: Long,
    ): Playable {
        val id = stream.id ?: return Hls(stream.url)
        val tiers = cache?.urls?.get(id) ?: return NeedsResolving(id)

        val order = if (preferUhd) listOf("uhd", "hd") else listOf("hd")
        for (name in order) {
            val tier = tiers[name] ?: continue
            if (!tier.isFresh(nowSeconds)) continue
            return Progressive(tier.video, tier.audio)
        }
        return NeedsResolving(id)
    }
}
