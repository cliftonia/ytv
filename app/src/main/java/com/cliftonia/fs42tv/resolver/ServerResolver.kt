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
    /**
     * A monotonic millisecond clock, for the age of the last health reading. Not the resolve's
     * `nowSeconds`: that is wall-clock time, pinned for measurement runs and corrected over NTP
     * after boot, and a reading aged by it could look fresh forever or stale instantly. And
     * elapsedRealtime rather than nanoTime, like the ledger: nanoTime stops in deep sleep on
     * some devices, so a reading taken before the set slept could come back looking current.
     */
    private val nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : ClipResolver {

    /**
     * The last health reading: the verdict and when it was taken, swapped as ONE immutable
     * object. They used to be two separate volatile fields written by whichever executor probed,
     * so a reader could pair one probe's verdict with another's timestamp.
     */
    private class Reading(val healthy: Boolean, val atMillis: Long)

    @Volatile private var reading: Reading? = null

    /**
     * The last known answer to "is it worth asking", never a network call.
     *
     * The probe used to run right here, on whichever thread asked - the tune executor, every
     * thirty seconds - and in the car, where neither address answers, that was 400ms per address
     * of dead time in front of a channel change the viewer was waiting on. The probing now
     * happens in the background ([AcceleratorProbe]); an answer that is missing or older than
     * [FRESH_FOR_MILLIS] counts as "no", because a guess of "yes" costs a resolve timeout.
     */
    fun isAvailable(): Boolean {
        val last = reading ?: return false
        return last.healthy && nowMillis() - last.atMillis < FRESH_FOR_MILLIS
    }

    /**
     * Ask the server's health endpoint and remember the answer. Blocking, up to
     * [HEALTH_TIMEOUT_MILLIS] - background threads only. Synchronized so two callers cannot
     * probe at once and land their answers out of order.
     */
    @Synchronized
    fun probe(): Boolean {
        val body = runCatching { fetch("$baseUrl/health", HEALTH_TIMEOUT_MILLIS) }.getOrNull()
        // The server reports its own extractor as well as its liveness, and says ok:false when
        // extraction is broken. A server that cannot extract is worse than none, because the
        // television would wait for it and then resolve anyway.
        val healthy = body != null && Health.isUsable(body)
        val was = reading?.healthy
        reading = Reading(healthy, nowMillis())
        if (was != healthy) {
            Log.i("fs42", "resolve server $baseUrl ${if (healthy) "available" else "unavailable"}")
        }
        return healthy
    }

    override fun resolveDetailed(
        videoId: String,
        nowSeconds: Long,
        ladder: List<String>,
        refused: Set<String>,
    ): ClipResolver.Resolved? {
        if (!isAvailable()) return null
        val body = runCatching {
            fetch("$baseUrl/resolve?v=$videoId", RESOLVE_TIMEOUT_MILLIS)
        }.getOrNull() ?: run {
            // One failure retires the server until the next health probe rather than for this
            // clip alone. A server that has stopped answering will not answer the next clip
            // either, and paying the timeout ninety more times is the worst possible outcome.
            reading = Reading(false, nowMillis())
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
        fun overHttp(baseUrl: String): ServerResolver = ServerResolver(baseUrl, { url, timeout ->
            (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = timeout
                readTimeout = timeout
                try {
                    inputStream.bufferedReader().use { it.readText() }
                } finally {
                    disconnect()
                }
            }
        })

        /**
         * Short on purpose. This is the question "is it worth asking", and a set that has to wait
         * for the answer has already lost more than the server could save.
         */
        const val HEALTH_TIMEOUT_MILLIS = 400

        /** Generous by comparison: a cold lookup on the server still beats resolving here. */
        const val RESOLVE_TIMEOUT_MILLIS = 4_000

        /**
         * How long a healthy reading is believed. Twice the healthy re-probe interval in
         * [AcceleratorProbe], so one late round does not retire a server that is fine.
         */
        const val FRESH_FOR_MILLIS = 120_000L
    }

}
