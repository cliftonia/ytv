package com.cliftonia.fs42tv.pluto

import java.util.concurrent.atomic.AtomicReference

/**
 * One Pluto session held by [PlutoSessions], and what it is fetched with. Out of PlutoSessions so
 * the pools of them ([SessionPool]) sit beside it rather than inside it.
 *
 * A fetch runs under the slot's own fetch lock, so two callers wanting the same session at once
 * share one fetch rather than racing two - which would leave one of them holding a token its twin
 * had already retired. [forget] takes NO lock: it is called from the player's error callback on
 * the main thread, and a lock held across a fetch would have stalled the main thread for as long
 * as Pluto took to answer.
 */
class SessionSlot internal constructor(
    /** Fetches a session; true when the last one was forgotten as bad - see [PlutoSessions]. */
    private val fetch: (fresh: Boolean) -> PlutoSession?,
    /** How long to leave a failed fetch alone, given what it threw (null: nothing thrown). */
    private val missFor: (Throwable?) -> Long,
    private val nowMillis: () -> Long,
    /** Whether a session just fetched may be held - [SessionPool] refuses a twin of another slot's. */
    private val accept: (SessionSlot, PlutoSession) -> Boolean = { _, _ -> true },
) {
    private val held = AtomicReference<PlutoSession?>(null)
    @Volatile private var retryAt = 0L
    @Volatile private var forgotten = false
    private val fetchLock = Any()

    /**
     * Which channel's master was read on the held session last - see [SessionPool]. Null for a
     * session nothing has been read on, and whenever the session changes. Written under the
     * pool's lock; read anywhere.
     */
    @Volatile var claim: SessionPool.Claim? = null

    /** The session held now, whatever its age - for identity checks only. */
    fun holding(): PlutoSession? = held.get()

    /** Whether the last session was forgotten as bad, so the next fetch is a rebuild. */
    val rebuilding: Boolean get() = forgotten

    /** The held session while it is comfortably inside its life, without waiting on a fetch. */
    fun cached(): PlutoSession? =
        held.get()?.takeIf { nowMillis() < it.expiresAtMillis - PlutoSessions.REFRESH_MARGIN_MILLIS }

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
                fetch(forgotten)
            } catch (e: Exception) {
                failure = e
                null
            }
            if (fresh == null || !accept(this, fresh)) {
                retryAt = now + missFor(failure)
                return usable
            }
            held.set(fresh)
            // A new session has had nothing read on it.
            claim = null
            retryAt = 0L
            forgotten = false
            return fresh
        }
    }

    /** Lock-free on purpose - see the class comment. Identity, not equality. */
    fun forget(session: PlutoSession) {
        // A rebuild asked for now is asked for NOW, whatever an earlier miss said.
        if (held.compareAndSet(session, null)) {
            claim = null
            forgotten = true
            retryAt = 0L
        }
    }
}
