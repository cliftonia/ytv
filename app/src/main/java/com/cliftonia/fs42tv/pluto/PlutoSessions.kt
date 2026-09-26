package com.cliftonia.fs42tv.pluto

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

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
 * Each is kept until [REFRESH_MARGIN_MILLIS] before it expires. A fetch runs under its slot's own
 * fetch lock, so two callers wanting the same session at once share one fetch rather than racing
 * two - which would leave one of them holding a token its twin had already retired. [invalidate]
 * takes NO lock: it is called from the player's error callback on the main thread, and a lock
 * held across a fetch would have stalled the main thread for as long as Pluto took to answer.
 * [forDial] and [beside] block; callers are the tune and prefetch threads, never the UI thread.
 */
class PlutoSessions(
    /** A fresh anonymous session from Pluto's boot service; may throw. */
    boot: () -> PlutoSession?,
    /**
     * The home server's session for a region. Null means the server answered without one; a
     * throw means it could not be reached, which retires the server for every region at once.
     */
    private val server: (region: String) -> PlutoSession?,
    /** Wall-clock milliseconds: expiries are stated in wall-clock time. */
    private val nowMillis: () -> Long,
    /**
     * Whether a failure is the network not being there YET - a television just woken, whose
     * DNS and Wi-Fi come back seconds later - rather than a server that is not there at all.
     */
    private val transient: (Throwable) -> Boolean = PlutoBoot::isTransient,
) {

    /** A session for the dial, and whether the home server supplied it - for the diagnostics. */
    class Choice(val session: PlutoSession, val fromServer: Boolean)

    private val dialLocal = Slot(boot) { BOOT_MISS_RETRY_MILLIS }
    private val besideLocal = Slot(boot) { BOOT_MISS_RETRY_MILLIS }
    private val regions = ConcurrentHashMap<String, Slot>()

    /**
     * Until when the home server is not asked for ANY region. One unreachable server is
     * unreachable for both regions, and paying its timeout once per region doubled the wait.
     */
    @Volatile private var serverDownUntil = 0L

    /**
     * The session a dial channel from [region] plays on: that region's, when the server answers,
     * else this television's own. Null only when neither can be had - the caller then plays the
     * channel's published url exactly as before sessions existed.
     */
    fun forDial(region: String?): Choice? {
        if (region != null) {
            val slot = regions.getOrPut(region) { Slot({ server(region) }, ::serverMiss) }
            val session = slot.cached()
                ?: if (nowMillis() >= serverDownUntil) slot.get() else slot.stillValid()
            session?.let { return Choice(it, fromServer = true) }
        }
        return dialLocal.get()?.let { Choice(it, fromServer = false) }
    }

    /** A session for a player running at the same time as the dial - never the dial's own. */
    fun beside(): PlutoSession? = besideLocal.get()

    /**
     * [session] was refused by the stitcher, or a stream on it would not open: forget it, so the
     * next ask builds a new one. A no-op when it has already been replaced, so two failures
     * reported for one bad token cannot throw away its healthy successor. Never blocks.
     */
    fun invalidate(session: PlutoSession) {
        dialLocal.forget(session)
        besideLocal.forget(session)
        regions.values.forEach { it.forget(session) }
    }

    /** How long a region fetch that failed is left alone - and the server with it, if it threw. */
    private fun serverMiss(failure: Throwable?): Long {
        if (failure == null) return SERVER_MISS_RETRY_MILLIS
        val wait = if (transient(failure)) NETWORK_MISS_RETRY_MILLIS else SERVER_MISS_RETRY_MILLIS
        serverDownUntil = nowMillis() + wait
        return wait
    }

    private inner class Slot(
        private val fetch: () -> PlutoSession?,
        /** How long to leave a failed fetch alone, given what it threw (null: nothing thrown). */
        private val missFor: (Throwable?) -> Long,
    ) {
        private val held = AtomicReference<PlutoSession?>(null)
        @Volatile private var retryAt = 0L
        private val fetchLock = Any()

        /** The held session while it is comfortably inside its life, without waiting on a fetch. */
        fun cached(): PlutoSession? =
            held.get()?.takeIf { nowMillis() < it.expiresAtMillis - REFRESH_MARGIN_MILLIS }

        /** The held session while it has not actually expired, however close it is. */
        fun stillValid(): PlutoSession? = held.get()?.takeIf { nowMillis() < it.expiresAtMillis }

        fun get(): PlutoSession? {
            cached()?.let { return it }
            synchronized(fetchLock) {
                // Again inside the lock: a caller that waited here was waiting for this fetch.
                cached()?.let { return it }
                val now = nowMillis()
                // Past its prime but still valid is better than nothing when refresh cannot happen.
                val usable = stillValid()
                if (now < retryAt) return usable
                var failure: Throwable? = null
                val fresh = try {
                    fetch()
                } catch (e: Exception) {
                    failure = e
                    null
                }
                if (fresh == null) {
                    retryAt = now + missFor(failure)
                    return usable
                }
                held.set(fresh)
                retryAt = 0L
                return fresh
            }
        }

        /** Lock-free on purpose - see the class comment. Identity, not equality. */
        fun forget(session: PlutoSession) {
            // A rebuild asked for now is asked for NOW, whatever an earlier miss said.
            if (held.compareAndSet(session, null)) retryAt = 0L
        }
    }

    companion object {
        /** Refreshed this long before expiry, so no stream starts on a token about to lapse. */
        const val REFRESH_MARGIN_MILLIS = 30 * 60_000L

        /** A server that did not answer is asked again after this - long enough to spare the car. */
        const val SERVER_MISS_RETRY_MILLIS = 10 * 60_000L

        /**
         * A network not up yet - DNS failing, "network unreachable", a refused connection - is
         * looked at again this soon. A television woken from standby has no DNS for its first
         * seconds, and retiring the server for ten minutes over that parked the region channels
         * on this television's session for the whole first evening's viewing.
         */
        const val NETWORK_MISS_RETRY_MILLIS = 30_000L

        /** Pluto's boot failing usually means no network at all; look again soon. */
        const val BOOT_MISS_RETRY_MILLIS = 30_000L
    }
}
