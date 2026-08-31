package com.cliftonia.fs42tv.resolver

import android.util.Log

/**
 * Asks a resolve accelerator on the local network, when there is one.
 *
 * Measured across the whole dial: resolving on the device takes 2374ms at the median and 3822ms
 * at worst, which is most of what a channel change costs before the player has even been handed a
 * url. The same lookup against a server that has pre-warmed the dial takes 5ms.
 *
 * The server can do that because the dial is deterministic - what is on air on every channel is a
 * pure function of the wall clock and channels.json, both of which it can read - so it resolves
 * all ninety channels before anyone touches the remote. That is why it beats the neighbour
 * prefetch on the device rather than duplicating it: the app can only guess "one up or one down",
 * and cannot know you are about to jump to channel 63 from the picker.
 *
 * NEVER A DEPENDENCY. Everything here fails to null and the caller falls back to resolving on the
 * device, because one television lives in a car on a phone hotspot and will usually not be able
 * to reach this at all - and because the last machine this app depended on died and took the
 * whole dial with it.
 */
class ServerResolver(
    private val baseUrl: String,
    /**
     * Whether to read the caption track out of the response.
     *
     * The same lambda [DeviceResolver] takes, for the same reason: the viewer can change it while
     * the app is running. Without it here, captions worked only when this server was unreachable.
     */
    private val fetch: (String, Int) -> String,
) : ClipResolver {

    /**
     * Whether the server answered its health check recently, and when that was last asked.
     *
     * Cached because the question is asked on every tune and the answer changes rarely. Without
     * this, a set out of range would pay a connection timeout per channel change - turning an
     * accelerator into the very delay it exists to remove.
     */
    @Volatile private var healthyUntil: Long = 0
    @Volatile private var healthy: Boolean = false

    fun isAvailable(nowMillis: Long): Boolean {
        if (nowMillis < healthyUntil) return healthy
        val body = runCatching { fetch("$baseUrl/health", HEALTH_TIMEOUT_MILLIS) }.getOrNull()
        // The server reports its own extractor as well as its liveness, and says ok:false when
        // extraction is broken. A server that cannot extract is worse than none, because the
        // television would wait for it and then resolve anyway.
        healthy = body != null && Health.isUsable(body)
        healthyUntil = nowMillis + (if (healthy) HEALTHY_FOR_MILLIS else UNHEALTHY_FOR_MILLIS)
        Log.i("fs42", "resolve server ${if (healthy) "available" else "unavailable"}")
        return healthy
    }

    override fun resolveDetailed(
        videoId: String,
        nowSeconds: Long,
        ladder: List<String>,
        refused: Set<String>,
    ): ClipResolver.Resolved? {
        if (!isAvailable(nowSeconds * 1000)) return null
        val body = runCatching {
            fetch("$baseUrl/resolve?v=$videoId", RESOLVE_TIMEOUT_MILLIS)
        }.getOrNull() ?: run {
            // One failure retires the server until the next health check rather than for this
            // clip alone. A server that has stopped answering will not answer the next clip
            // either, and paying the timeout ninety more times is the worst possible outcome.
            healthyUntil = 0
            return null
        }
        return ServerTiers.parse(body, ladder, refused, videoId, nowSeconds)
    }

    companion object {
        /**
         * The standard way to reach an accelerator over plain http, with the timeouts the
         * repository fetch uses and for the same reason: the default is to wait forever, and
         * forever is what an idle hotspot delivers.
         */
        fun overHttp(baseUrl: String): ServerResolver = ServerResolver(baseUrl) { url, timeout ->
            (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = timeout
                readTimeout = timeout
                try {
                    inputStream.bufferedReader().use { it.readText() }
                } finally {
                    disconnect()
                }
            }
        }

        /**
         * Short on purpose. This is the question "is it worth asking", and a set that has to wait
         * for the answer has already lost more than the server could save.
         */
        const val HEALTH_TIMEOUT_MILLIS = 400

        /** Generous by comparison: a cold lookup on the server still beats resolving here. */
        const val RESOLVE_TIMEOUT_MILLIS = 4_000

        const val HEALTHY_FOR_MILLIS = 60_000L

        /**
         * Rechecked sooner than a healthy one, not later. Coming home should make the dial fast
         * again within a minute, and the check costs one refused connection.
         */
        const val UNHEALTHY_FOR_MILLIS = 30_000L
    }

}
