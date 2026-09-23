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
 * - Spaced at least [MIN_GAP_MILLIS] apart and capped at [MAX_QUEUED] waiting - about one
 *   screen of guide rows - so holding DOWN through the guide queues a handful, not a hundred.
 * - Run on [executor] - the prefetch thread. On the Pluto dial nothing needs resolving (every
 *   channel is a live HLS url), so that thread is otherwise idle there; and on the YouTube dial
 *   no channel has a Pluto id, so this never queues in front of a resolve.
 *
 * Failure calls nobody back. The banner and the guide keep what they had, which is the channel
 * name - exactly the behaviour from before this existed.
 */
class PlutoGuide(
    private val fetch: (String) -> String,
    private val executor: Executor,
    private val nowMillis: () -> Long,
    /** The Settings row. Read per call: switched off, nothing is fetched or served. */
    private val enabled: () -> Boolean,
    /** Injected so the spacing is testable without a real clock. */
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    private class Entry(val schedule: PlutoSchedule?, val freshUntilMillis: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** Waiters per channel id with a fetch queued or running. Guarded by itself. */
    private val waiting = HashMap<String, MutableList<(PlutoSchedule) -> Unit>>()

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
            if (waiting.size >= MAX_QUEUED) return
            waiting[id] = mutableListOf(onReady)
        }
        executor.execute { fetchNow(id) }
    }

    private fun fetchNow(id: String) {
        if (!enabled()) {
            // Switched off while this waited: drop it without caching a failure, so switching
            // back on asks afresh rather than sitting out a backoff it never earned.
            synchronized(waiting) { waiting.remove(id) }
            return
        }
        val result = run {
            lastFetchAt?.let { last ->
                val wait = last + MIN_GAP_MILLIS - nowMillis()
                if (wait > 0) sleep(wait)
            }
            lastFetchAt = nowMillis()
            runCatching { PlutoApi.parse(fetch(PlutoApi.url(id, nowMillis()))) }
                .onFailure { Log.i("fs42", "pluto guide for $id failed: $it") }
                .getOrNull()
        }
        val now = nowMillis()
        // Held until the programme on air ends; a reply with nothing on air now, or none at all,
        // is retried after the backoff rather than cached as an answer.
        val freshUntil = result?.freshUntil(now) ?: (now + FAILURE_BACKOFF_MILLIS)
        cache[id] = Entry(result?.takeIf { it.onAt(now) != null }, freshUntil)
        val waiters = synchronized(waiting) { waiting.remove(id).orEmpty() }
        val ready = cache[id]?.schedule ?: return
        waiters.forEach { it(ready) }
    }

    companion object {
        const val FAILURE_BACKOFF_MILLIS = 120_000L
        const val MIN_GAP_MILLIS = 300L
        const val MAX_QUEUED = 10
    }
}
