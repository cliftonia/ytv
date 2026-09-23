package com.cliftonia.fs42tv.sync

import android.util.Log
import java.io.File
import java.util.concurrent.Executor

/**
 * How long a launch with no lineup waits before asking again. Long enough not to hammer a
 * hotspot that is still coming up, short enough that the dial appears within a minute of the
 * network doing so.
 */
private const val RETRY_MILLIS = 30_000L

/**
 * Delivers a lineup - the cached one at once when there is one, the network's otherwise - and
 * keeps trying until there is one.
 *
 * Cache first because the fetch sat in front of the first picture on every launch: up to ten
 * seconds to connect and twenty to read on a hotspot that is still waking, on the same executor
 * as every tune, with the lineup from last night already on disk the whole time. The dial is a
 * pure function of the clock and that file, so yesterday's copy tunes correctly today; the fetch
 * still runs, in the background, and what it brings is the NEXT launch's lineup. It is never
 * delivered on top of the cached one - swapping the dial under a viewer who is already watching
 * would re-tune them to whatever the new file says is on.
 *
 * The retry loop exists because its absence was a bricked television: first launch on a dead
 * hotspot (or after the cache was cleared) logged one line and returned, leaving a permanently
 * black screen with a dead remote and the sync exception discarded - even adb could not say
 * whether it was DNS, TLS or a captive portal. [onNoDial] puts a card up saying the app is
 * alive and what it needs, and the retry means a hotspot that comes up a minute later revives
 * the dial without a relaunch.
 */
class DialLoader(
    /** Which dial to fetch; see [LineupSource]. */
    private val source: LineupSource,
    private val cacheDir: File,
    private val executor: Executor,
    private val runOnUi: (() -> Unit) -> Unit,
    private val halted: () -> Boolean,
    /** Whether a dial already arrived, making a queued retry a no-op. */
    private val loaded: () -> Boolean,
    /** Schedules the retry; the caller's handler is drained on destroy, taking retries with it. */
    private val retry: (delayMillis: Long, block: () -> Unit) -> Unit,
    /** On the UI thread: no lineup and no cache - say so on the stand-by card. */
    private val onNoDial: () -> Unit,
    /**
     * On the executor: the dial arrived. [requestedAtMillis] is when THIS attempt began, so the
     * first tune's latency figure measures the tune rather than the retries before it.
     */
    private val onDial: (List<Channel>, requestedAtMillis: Long) -> Unit,
    private val elapsedMillis: () -> Long,
    /** Fetches a url's body; throws when it cannot. Injected so the loader is testable. */
    private val fetch: (String) -> String = ::fetchOverHttp,
    /**
     * Runs the background refresh after a cache-first delivery. Its own short-lived thread by
     * default: not [executor], where a fetch that hangs for thirty seconds would hold up every
     * channel change, and not the prefetch thread, whose neighbour resolves it would stall.
     */
    private val refresh: (Runnable) -> Unit = { Thread(it, "dial-refresh").start() },
) {

    fun load() {
        val requestedAt = elapsedMillis()
        executor.execute {
            val repo =
                DialRepository(fetch = fetch, cacheDir = cacheDir, cacheFile = source.cacheFile)
            val cached = repo.cachedDial()?.channels
            if (!cached.isNullOrEmpty()) {
                onDial(cached, requestedAt)
                refresh(Runnable { refreshForNextLaunch(repo) })
                return@execute
            }
            val synced = runCatching { repo.sync(source.url) }
                .onFailure { Log.w("fs42", "lineup sync failed", it) }
                .getOrNull()
            val dial = synced?.dial ?: repo.cachedDial()

            val channels = dial?.channels
            if (channels.isNullOrEmpty()) {
                Log.e("fs42", "no dial available; retrying in ${RETRY_MILLIS / 1000}s")
                runOnUi {
                    if (halted()) return@runOnUi
                    onNoDial()
                    // The halted check must run before anything re-enters the executor: destroy
                    // shuts it down, and posting to a dead one throws rather than being quietly
                    // dropped. The loaded check makes a retry that raced a success a no-op.
                    retry(RETRY_MILLIS) { if (!halted() && !loaded()) load() }
                }
                return@execute
            }
            onDial(channels, requestedAt)
        }
    }

    /** Fetch the lineup into the cache file and nothing else; see the class comment for why. */
    private fun refreshForNextLaunch(repo: DialRepository) {
        if (halted()) return
        runCatching { repo.sync(source.url) }
            .onSuccess { Log.i("fs42", "lineup refreshed for next launch") }
            .onFailure { Log.w("fs42", "lineup refresh failed; the cached dial stands: $it") }
    }
}

/**
 * The lineup fetch, with timeouts, because the default is none at all. The no-cache path runs
 * this on the SAME single-threaded executor as every tune, so one hung connection to a CDN edge
 * meant no channel ever tuned again and every keypress queued silently behind it - a television
 * that looks bricked with nothing on screen to say why.
 */
private fun fetchOverHttp(url: String): String =
    (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
        connectTimeout = CONNECT_TIMEOUT_MILLIS
        readTimeout = READ_TIMEOUT_MILLIS
        try {
            inputStream.bufferedReader().use { it.readText() }
        } finally {
            disconnect()
        }
    }

private const val CONNECT_TIMEOUT_MILLIS = 10_000
private const val READ_TIMEOUT_MILLIS = 20_000
