package com.cliftonia.fs42tv.sync

import android.util.Log
import java.io.File
import java.util.concurrent.Executor

/**
 * Where the lineup lives.
 *
 * A file in a public git repository, not an endpoint on a machine at home. The dial is rebuilt
 * nightly by a workflow and committed, so the television picks up new content by fetching one
 * file over the open internet - which is the whole point, because one of these televisions lives
 * in a car and is rarely on the house network.
 *
 * `raw.githubusercontent.com` rather than the api: no rate limit worth worrying about, no token,
 * and it serves the file at whatever the branch currently points to.
 */
private const val LINEUP_URL =
    "https://raw.githubusercontent.com/cliftonia/ytv/main/channels.json"

/**
 * How long a launch with no lineup waits before asking again. Long enough not to hammer a
 * hotspot that is still coming up, short enough that the dial appears within a minute of the
 * network doing so.
 */
private const val RETRY_MILLIS = 30_000L

/**
 * Fetches the lineup and keeps trying until there is one.
 *
 * The retry loop exists because its absence was a bricked television: first launch on a dead
 * hotspot (or after the cache was cleared) logged one line and returned, leaving a permanently
 * black screen with a dead remote and the sync exception discarded - even adb could not say
 * whether it was DNS, TLS or a captive portal. [onNoDial] puts a card up saying the app is
 * alive and what it needs, and the retry means a hotspot that comes up a minute later revives
 * the dial without a relaunch.
 */
class DialLoader(
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
) {

    fun load() {
        val requestedAt = elapsedMillis()
        executor.execute {
            val repo = DialRepository(
                // Timeouts, because the default is none at all. This runs on the SAME
                // single-threaded executor as every tune, so one hung connection to a CDN edge
                // meant no channel ever tuned again and every keypress queued silently behind
                // it - a television that looks bricked with nothing on screen to say why.
                fetch = { url ->
                    (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
                        connectTimeout = CONNECT_TIMEOUT_MILLIS
                        readTimeout = READ_TIMEOUT_MILLIS
                        try {
                            inputStream.bufferedReader().use { it.readText() }
                        } finally {
                            disconnect()
                        }
                    }
                },
                cacheDir = cacheDir,
            )
            val synced = runCatching { repo.sync(LINEUP_URL) }
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

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 20_000
    }
}
