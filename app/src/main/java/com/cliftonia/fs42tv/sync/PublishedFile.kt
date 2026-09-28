package com.cliftonia.fs42tv.sync

import android.util.Log
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A file the curation jobs publish beside the lineups - `fast_guide.json`, `details.json` - kept on
 * disk and refreshed a few times a day. What [FastGuideStore] was, pulled out so the picker's
 * details file follows exactly the same rules:
 *
 * - Nothing happens until something asks.
 * - The cached copy answers first, straight off disk, so the first ask after a launch has an
 *   answer without waiting on the network.
 * - A download at most every [refreshMillis], counted from the cached file's age, so a relaunch
 *   does not fetch again; after a failure, not again for [retryMillis].
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
) {

    @Volatile private var held: T? = null

    /** Wall-clock time from which the next download is due. Read and written on [executor]. */
    @Volatile private var dueAtMillis = 0L

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
        if (nowMillis() >= dueAtMillis) load()
        return current
    }

    private fun load() {
        if (!loading.compareAndSet(false, true)) return
        // Refused once the thread is shut down with the activity: nothing loads, nobody listens.
        runCatching { executor.execute { loadNow() } }.onFailure { loading.set(false) }
    }

    private fun loadNow() {
        try {
            if (held == null) fromDisk()
            if (nowMillis() >= dueAtMillis) download()
        } finally {
            loading.set(false)
        }
        if (held == null) return
        val ready = synchronized(waiters) { waiters.toList().also { waiters.clear() } }
        ready.forEach { waiter ->
            runCatching { waiter() }.onFailure { Log.w("fs42", "$label callback: $it") }
        }
    }

    private fun fromDisk() {
        if (!file.exists()) return
        runCatching { parse(file.readText()) }
            .onSuccess {
                held = it
                dueAtMillis = file.lastModified() + refreshMillis
            }
            .onFailure { Log.w("fs42", "$label cache unreadable: $it") }
    }

    private fun download() {
        runCatching {
            val text = fetch(url)
            val parsed = parse(text)
            file.parentFile?.mkdirs()
            file.writeText(text)
            parsed
        }.onSuccess {
            held = it
            dueAtMillis = nowMillis() + refreshMillis
            Log.i("fs42", "$label: ${describe(it)}")
        }.onFailure {
            dueAtMillis = nowMillis() + retryMillis
            Log.i("fs42", "$label download failed: $it")
        }
    }

    companion object {
        private const val MAX_WAITERS = 32

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
