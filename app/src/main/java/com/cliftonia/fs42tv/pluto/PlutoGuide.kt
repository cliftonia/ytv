package com.cliftonia.fs42tv.pluto

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Pluto's guide, fetched per channel and only when a channel is actually being looked at.
 *
 * The rules, each of which is the difference between a courtesy and a nuisance on a 2.3GB
 * television sharing one Wi-Fi link with a 4K stream:
 *
 * - Asked on demand - the tuned channel's banner, the few guide rows around the highlight - never
 *   for the whole dial. 219 channels at 10KB is 2MB of JSON nobody reads.
 * - Cached until the programme on air ends, which is exactly when the answer changes.
 * - A failure is remembered for [FAILURE_BACKOFF_MILLIS], so a dead network does not become a
 *   request per guide keypress.
 * - One request per channel in flight, however many callers ask.
 * - Spaced at least [MIN_GAP_MILLIS] apart and capped at [MAX_QUEUED] waiting - about one screen
 *   of guide rows. When full, the OLDEST waiting channel is dropped, never the newest: after a
 *   fast surf the channel landed on is the one worth asking about, not the ten passed on the way.
 * - Run on [executor] - the prefetch thread. On the Pluto dial nothing needs resolving (every
 *   channel is a live HLS url), so that thread is otherwise idle there. The YouTube dial is NOT
 *   free of Pluto ids: two of its live news channels are jmp2.uk `plu-` feeds (110 Euronews and
 *   115 CBS News in Sep 2026), and their banner and guide row use this too. That is
 *   intended - they are Pluto channels with a real Pluto schedule - and cheap: one ~10KB request
 *   per programme for a channel actually being looked at, each costing a neighbour resolve queued
 *   behind it on the same thread at most one fetch (4s timeouts) plus a sleep never longer than
 *   [MIN_GAP_MILLIS].
 * - Safe on a dying thread. The activity shuts the prefetch thread down with shutdownNow() on
 *   destroy - BACK, and the SOURCE row's recreate, as well as a real exit - and an uncaught
 *   exception on it takes the whole process down (the lesson written up in ChunkedProxy). So an
 *   interrupted sleep ends the fetch quietly, a refused execute() is swallowed, and a callback
 *   that throws is logged rather than propagated.
 *
 * Failure calls nobody back. The banner and the guide keep what they had, which is the channel
 * name - exactly the behaviour from before this existed.
 */
class PlutoGuide(
    private val fetch: (String) -> String,
    private val executor: Executor,
    /** Wall clock, for the programme times the guide is written in. */
    private val nowMillis: () -> Long,
    /** The Settings row. Read per call: switched off, nothing is fetched or served. */
    private val enabled: () -> Boolean,
    /**
     * A monotonic clock for the spacing, never the wall clock: a wall clock stepped backwards
     * (NTP correcting a television that booted with the wrong date) would make the computed wait
     * enormous and park the shared prefetch thread. The wait is capped at [MIN_GAP_MILLIS]
     * besides, so even a broken clock costs a third of a second.
     */
    private val elapsedMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /** Injected so the spacing is testable without a real clock. */
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    private class Entry(val schedule: PlutoSchedule?, val freshUntilMillis: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * Waiters per channel id with a fetch queued or running, oldest request first. Guarded by
     * itself. An id dropped from here while its task is still queued turns that task into a no-op.
     */
    private val waiting = LinkedHashMap<String, MutableList<(PlutoSchedule) -> Unit>>()

    /** Only touched on [executor], which is single-threaded. */
    private var lastFetchAt: Long? = null

    /** The schedule for [id] if one is held and still true, without asking the network. */
    fun cached(id: String): PlutoSchedule? {
        if (!enabled()) return null
        val entry = cache[id] ?: return null
        return entry.schedule.takeIf { nowMillis() < entry.freshUntilMillis }
    }

    /**
     * Make sure [id]'s schedule is on its way, and call [onReady] - on the fetching thread - when
     * it arrives. Called straight back when already cached; never called on failure.
     */
    fun request(id: String, onReady: (PlutoSchedule) -> Unit) {
        if (!enabled()) return
        cache[id]?.let { entry ->
            if (nowMillis() < entry.freshUntilMillis) {
                entry.schedule?.let(onReady)
                return
            }
        }
        synchronized(waiting) {
            waiting[id]?.let { it += onReady; return }
            // Newest wins: the oldest waiting channel is the one the viewer has moved past.
            while (waiting.size >= MAX_QUEUED) waiting.remove(waiting.keys.first())
            waiting[id] = mutableListOf(onReady)
        }
        // Refused once the thread has been shut down with the activity. Nothing will be fetched
        // and nobody is listening, so the entry goes and the refusal goes no further.
        runCatching { executor.execute { fetchNow(id) } }
            .onFailure { synchronized(waiting) { waiting.remove(id) } }
    }

    private fun fetchNow(id: String) {
        // Dropped for a newer channel while this waited in the queue.
        if (synchronized(waiting) { id !in waiting }) return
        if (!enabled()) {
            // Switched off while this waited: drop it without caching a failure, so switching
            // back on asks afresh rather than sitting out a backoff it never earned.
            synchronized(waiting) { waiting.remove(id) }
            return
        }
        lastFetchAt?.let { last ->
            val wait = (last + MIN_GAP_MILLIS - elapsedMillis()).coerceAtMost(MIN_GAP_MILLIS)
            if (wait > 0) {
                try {
                    sleep(wait)
                } catch (e: InterruptedException) {
                    // shutdownNow(): the activity is gone. Leave quietly, and keep the interrupt
                    // set for the executor that asked for it.
                    synchronized(waiting) { waiting.remove(id) }
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        lastFetchAt = elapsedMillis()
        val result = runCatching { PlutoApi.parse(fetch(PlutoApi.url(id, nowMillis()))) }
            .onFailure { Log.i("fs42", "pluto guide for $id failed: $it") }
            .getOrNull()
        val now = nowMillis()
        // Held until the programme on air ends; a reply with nothing on air now, or none at all,
        // is retried after the backoff rather than cached as an answer.
        val freshUntil = result?.freshUntil(now) ?: (now + FAILURE_BACKOFF_MILLIS)
        cache[id] = Entry(result?.takeIf { it.onAt(now) != null }, freshUntil)
        val waiters = synchronized(waiting) { waiting.remove(id).orEmpty() }
        val ready = cache[id]?.schedule ?: return
        waiters.forEach { waiter ->
            runCatching { waiter(ready) }.onFailure { Log.w("fs42", "pluto guide callback: $it") }
        }
    }

    companion object {
        const val FAILURE_BACKOFF_MILLIS = 120_000L
        const val MIN_GAP_MILLIS = 300L
        const val MAX_QUEUED = 10
    }
}
