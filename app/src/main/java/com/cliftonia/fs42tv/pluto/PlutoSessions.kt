package com.cliftonia.fs42tv.pluto

import java.util.concurrent.ConcurrentHashMap

/**
 * The Pluto sessions this television holds, and which one a stream plays on.
 *
 * Three kinds, kept apart on purpose:
 *  - a REGION session per country ("uk", "us"), from the home server - some channels show their
 *    programmes only to a session from home, and loop Pluto's logo bumper for anyone else;
 *  - the dial's LOCAL session, booted anonymously from this television (so, from Australia), for
 *    channels without a region and whenever the server cannot be reached - the car, always;
 *  - a second local session for anything playing BESIDE the dial - the guide music can be a Pluto
 *    channel. Pluto allows one stream per session, so music on the dial's token would end the
 *    programme under the guide. The home server hands out one session per caller per region, so
 *    the player beside the dial can never share a region session either.
 *
 * Each is kept until [REFRESH_MARGIN_MILLIS] before it expires, and fetched under its own lock, so
 * two callers wanting the same session at once share one fetch rather than racing two - which
 * would leave one of them holding a token its twin had already retired. Blocking throughout:
 * callers are the tune and prefetch threads, never the UI thread.
 */
class PlutoSessions(
    /** A fresh anonymous session from Pluto's boot service; may throw. */
    boot: () -> PlutoSession?,
    /** The home server's session for a region; null or a throw when no server answered. */
    private val server: (region: String) -> PlutoSession?,
    /** Wall-clock milliseconds: expiries are stated in wall-clock time. */
    private val nowMillis: () -> Long,
) {

    /** A session for the dial, and whether the home server supplied it - for the diagnostics. */
    class Choice(val session: PlutoSession, val fromServer: Boolean)

    private val dialLocal = Slot(boot, BOOT_MISS_RETRY_MILLIS)
    private val besideLocal = Slot(boot, BOOT_MISS_RETRY_MILLIS)
    private val regions = ConcurrentHashMap<String, Slot>()

    /**
     * The session a dial channel from [region] plays on: that region's, when the server answers,
     * else this television's own. Null only when neither can be had - the caller then plays the
     * channel's published url exactly as before sessions existed.
     */
    fun forDial(region: String?): Choice? {
        if (region != null) {
            val slot = regions.getOrPut(region) { Slot({ server(region) }, SERVER_MISS_RETRY_MILLIS) }
            slot.get()?.let { return Choice(it, fromServer = true) }
        }
        return dialLocal.get()?.let { Choice(it, fromServer = false) }
    }

    /** A session for a player running at the same time as the dial - never the dial's own. */
    fun beside(): PlutoSession? = besideLocal.get()

    /**
     * [session] was refused by the stitcher, or a stream on it would not open: forget it, so the
     * next ask builds a new one. A no-op when it has already been replaced, so two failures
     * reported for one bad token cannot throw away its healthy successor.
     */
    fun invalidate(session: PlutoSession) {
        dialLocal.forget(session)
        besideLocal.forget(session)
        regions.values.forEach { it.forget(session) }
    }

    private inner class Slot(
        private val fetch: () -> PlutoSession?,
        /** How long a failed fetch is remembered, so a missing server is not asked every tune. */
        private val missRetryMillis: Long,
    ) {
        private var held: PlutoSession? = null
        private var retryAt = 0L

        @Synchronized
        fun get(): PlutoSession? {
            val now = nowMillis()
            val current = held
            if (current != null && now < current.expiresAtMillis - REFRESH_MARGIN_MILLIS) return current
            // Past its prime but still valid is better than nothing when the refresh cannot happen.
            val usable = current?.takeIf { now < it.expiresAtMillis }
            if (now < retryAt) return usable
            val fresh = runCatching { fetch() }.getOrNull()
            if (fresh == null) {
                retryAt = now + missRetryMillis
                return usable
            }
            held = fresh
            retryAt = 0L
            return fresh
        }

        @Synchronized
        fun forget(session: PlutoSession) {
            if (held !== session) return
            held = null
            // A rebuild asked for now is asked for NOW, whatever an earlier miss said.
            retryAt = 0L
        }
    }

    companion object {
        /** Refreshed this long before expiry, so no stream starts on a token about to lapse. */
        const val REFRESH_MARGIN_MILLIS = 30 * 60_000L

        /** A server that did not answer is asked again after this - long enough to spare the car. */
        const val SERVER_MISS_RETRY_MILLIS = 10 * 60_000L

        /** Pluto's boot failing usually means no network at all; look again soon. */
        const val BOOT_MISS_RETRY_MILLIS = 30_000L
    }
}
