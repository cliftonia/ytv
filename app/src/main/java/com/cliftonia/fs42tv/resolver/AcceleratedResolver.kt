package com.cliftonia.fs42tv.resolver

import android.util.Log

/**
 * Uses a resolve accelerator when one can be reached, and the device otherwise.
 *
 * The whole conditional, in one place, so neither half has to know the other exists. Measured:
 * 5ms through the server against 2374ms on the device, which is most of what a channel change
 * costs before the player is even involved.
 *
 * The fallback is the point, not a safety net bolted on. One of these televisions lives in a car
 * on a phone hotspot and will usually be nowhere near the server; it must behave exactly as it
 * does today, which is to resolve for itself and be a couple of seconds slower. The other sits on
 * the same network as the server and should be instant. The same build does both, decided per
 * tune, with no setting to get wrong.
 */
class AcceleratedResolver(
    private val server: ServerResolver,
    private val device: ClipResolver,
) : ClipResolver {

    override fun resolveDetailed(
        videoId: String,
        nowSeconds: Long,
        ladder: List<String>,
        refused: Set<String>,
    ): ClipResolver.Resolved? {
        // Asked first and answered from a cached health check, so an unreachable server costs
        // nothing per tune. Without the caching this would pay a connection timeout on every
        // channel change - an accelerator that makes the dial slower.
        if (server.isAvailable(nowSeconds * 1000)) {
            server.resolveDetailed(videoId, nowSeconds, ladder, refused)?.let {
                PlaybackDiagnostics.recordSource("server")
                return it
            }
            // A miss is ordinary rather than a fault: the server pre-warms what is on air, and a
            // clip reached some other way - a dead-clip skip, a clip that rolled over early - is
            // simply not in its cache yet.
            Log.d("fs42", "server had nothing for $videoId; resolving here")
        }
        PlaybackDiagnostics.recordSource("device")
        return device.resolveDetailed(videoId, nowSeconds, ladder, refused)
    }
}
