package com.cliftonia.fs42tv.resolver

import com.cliftonia.fs42tv.sync.Tier
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Asks the publisher to resolve a clip the cache missed.
 *
 * At 46% cache coverage this runs more often than the cached path. `fetch` is injected so the
 * whole class is testable on the JVM with no network.
 *
 * Every failure returns null rather than throwing: the caller's correct response to "cannot
 * resolve" is to skip the clip, and an exception here would take the player down instead.
 */
class ServerResolver(
    private val fetch: (String) -> String,
    private val baseUrl: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A resolved clip together with the moment its signed URLs die.
     *
     * The expiry is what lets a caller cache the result without guessing a lifetime. It is
     * carried here rather than folded into [Progressive] because [Progressive] is a `Playable`
     * that flows all the way to the player, and the player has no business knowing about it.
     */
    data class Resolved(val playable: Progressive, val expiresAtSeconds: Long)

    fun resolve(videoId: String, nowSeconds: Long, preferUhd: Boolean = false): Progressive? =
        resolveDetailed(videoId, nowSeconds, preferUhd)?.playable

    fun resolveDetailed(
        videoId: String,
        nowSeconds: Long,
        preferUhd: Boolean = false,
    ): Resolved? {
        val encodedId = URLEncoder.encode(videoId, "UTF-8")
        val body = runCatching { fetch("$baseUrl/resolve?v=$encodedId") }.getOrNull() ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null

        val order = if (preferUhd) listOf("uhd", "hd") else listOf("hd")
        for (name in order) {
            val tier = tierAt(root, name) ?: continue
            if (!tier.isFresh(nowSeconds)) continue
            return Resolved(Progressive(tier.video, tier.audio), tier.expires)
        }
        return null
    }

    /** The response carries `id` alongside the tiers, so a non-object value is expected. */
    private fun tierAt(root: JsonObject, name: String): Tier? {
        val element = root[name] ?: return null
        return runCatching { json.decodeFromJsonElement(Tier.serializer(), element) }.getOrNull()
    }
}
