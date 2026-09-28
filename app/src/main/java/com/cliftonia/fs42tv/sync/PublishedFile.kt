package com.cliftonia.fs42tv.sync

import android.util.Log
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A file the server jobs publish beside the lineups - `fast_guide.json`, `details.json` - kept on
 * disk and refreshed while the app runs. What [FastGuideStore] was, pulled out so the picker's
 * details file follows exactly the same rules:
 *
 * - The cached copy answers first, straight off disk, so the first ask after a launch has an
 *   answer without waiting on the network.
 * - At launch ([lineupSeen] with the lineup the dial came up on, or the first ask) a copy older
 *   than [launchStaleMillis] is downloaded again; a younger one waits out [refreshMillis].
 * - While running, a download at most every [refreshMillis]; after a failure, not again for
 *   [retryMillis], whatever else asks.
 * - A lineup whose stamp differs from the one the held copy was downloaded under is downloaded
 *   for at once: the server builds its files from the lineup, so a new dial wants a new copy.
 *   The stamp is kept beside the file, so this holds across launches.
 * - One load at a time, on [executor] - the prefetch thread.
 * - Parse before writing: a captive portal's page is never cached over a good copy.
 *
 * Failure answers null, and callers show what they had before the file existed.
 */
class PublishedFile<T : Any>(
    private val file: File,
    private val url: String,
    private val fetch: (String) -> String,
    private val parse: (String) -> T,
    private val executor: Executor,
    /** Wall clock: the file's age is its modification time against this. */
    private val nowMillis: () -> Long,
    private val refreshMillis: Long,
    private val retryMillis: Long,
    /** For the log lines: what this file is, and how big a parsed copy is. */
    private val label: String,
    private val describe: (T) -> String = { "" },
    private val launchStaleMillis: Long = refreshMillis,
) {

    @Volatile private var held: T? = null

    /** Wall-clock time from which the held copy is due for a refresh. Written on [executor]. */
    @Volatile private var dueAtMillis = 0L

    /** No download before this: a failure's wait. Written on [executor]. */
    @Volatile private var retryAtMillis = 0L

    /** Whether the copy on disk has been read - once, on the first load. */
    @Volatile private var diskRead = false

    /** The lineup stamp the held copy was downloaded under, or null when unknown. */
    @Volatile private var takenWith: String? = null

    /** The latest lineup stamp heard of, or null before the dial has come up. */
    @Volatile private var lineup: String? = null

    private val stampFile get() = File(file.path + STAMP_SUFFIX)

    private val loading = AtomicBoolean(false)

    /** Called once the next load lands - the asks made while nothing was held. */
    private val waiters = mutableListOf<() -> Unit>()

    /**
     * The copy held, or null. When nothing is held yet, or the copy held is due for a refresh, a
     * load starts; [onLoaded] - on the executor - runs once it lands, when nothing was held, so
     * the caller can read again. Never called when the load fails.
     */
    fun get(onLoaded: (() -> Unit)? = null): T? {
        val current = held
        if (current == null && onLoaded != null) {
            synchronized(waiters) { if (waiters.size < MAX_WAITERS) waiters += onLoaded }
        }
        // Nothing held is no reason to ask again inside a failure's wait: that would be a
        // request per ask on a television with no network.
        if (wanted()) load()
        return current
    }

    /**
     * The lineup the dial came up on, or one just refreshed, is [stamp]. The first call is the
     * launch: a stale copy is downloaded now rather than when something first asks. A stamp
     * other than the held copy's downloads now too. [load] false only notes it - the feature is
     * off, and nothing is fetched for it.
     */
    fun lineupSeen(stamp: String, load: Boolean = true) {
        lineup = stamp
        if (load && wanted()) load()
    }

    private fun wanted(): Boolean {
        val now = nowMillis()
        if (now < retryAtMillis) return false
        return !diskRead || now >= dueAtMillis || lineupMoved()
    }

    private fun lineupMoved(): Boolean = lineup.let { it != null && it != takenWith }

    private fun load() {
        if (!loading.compareAndSet(false, true)) return
        // Refused once the thread is shut down with the activity: nothing loads, nobody listens.
        runCatching { executor.execute { loadNow() } }.onFailure { loading.set(false) }
    }

    private fun loadNow() {
        var downloaded = false
        try {
            if (!diskRead) fromDisk()
            if (wanted()) downloaded = download()
        } finally {
            loading.set(false)
        }
        // A lineup that moved while this download was under way: its own copy, straight after.
        if (downloaded && lineupMoved()) load()
        if (held == null) return
        val ready = synchronized(waiters) { waiters.toList().also { waiters.clear() } }
        ready.forEach { waiter ->
            runCatching { waiter() }.onFailure { Log.w("fs42", "$label callback: $it") }
        }
    }

    private fun fromDisk() {
        diskRead = true
        if (!file.exists()) return
        runCatching { parse(file.readText()) }
            .onSuccess {
                held = it
                takenWith = runCatching { stampFile.readText() }.getOrNull()?.takeIf { s -> s.isNotBlank() }
                // A copy dated in the future is a wrong clock's reading (no battery, NTP not yet
                // in): not young, so due now.
                val age = nowMillis() - file.lastModified()
                dueAtMillis = if (age in 0 until launchStaleMillis) file.lastModified() + refreshMillis else 0L
            }
            .onFailure { Log.w("fs42", "$label cache unreadable: $it") }
    }

    /** True when a copy was downloaded and kept. */
    private fun download(): Boolean {
        val stamp = lineup
        return runCatching {
            val text = fetch(url)
            val parsed = parse(text)
            file.parentFile?.mkdirs()
            file.writeText(text)
            if (stamp != null) stampFile.writeText(stamp) else stampFile.delete()
            parsed
        }.onSuccess {
            held = it
            takenWith = stamp
            dueAtMillis = nowMillis() + refreshMillis
            Log.i("fs42", "$label: ${describe(it)}")
        }.onFailure {
            retryAtMillis = nowMillis() + retryMillis
            Log.i("fs42", "$label download failed: $it")
        }.isSuccess
    }

    companion object {
        private const val MAX_WAITERS = 32
        private const val STAMP_SUFFIX = ".lineup"

        /** Hourly while running: the server publishes every three hours. */
        const val REFRESH_MILLIS = 60L * 60 * 1000

        /** At launch, a copy older than this is replaced. */
        const val LAUNCH_STALE_MILLIS = 30L * 60 * 1000

        /** After a failure, never sooner than this. */
        const val RETRY_MILLIS = 10L * 60 * 1000

        /** The download, with timeouts, as the lineup's: the default is none at all. */
        fun httpFetch(url: String): String =
            (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 5_000
                readTimeout = 15_000
                try {
                    if (responseCode != 200) error("http $responseCode for $url")
                    inputStream.bufferedReader().use { it.readText() }
                } finally {
                    disconnect()
                }
            }
    }
}
