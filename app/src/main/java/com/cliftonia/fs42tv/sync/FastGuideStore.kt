package com.cliftonia.fs42tv.sync

import android.util.Log
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The [FastGuide] the banner and the guide read, kept on disk and refreshed a few times a day.
 *
 * The rules, each one a courtesy to a 2.3 GB television on a shared link:
 *
 * - Nothing happens until a FAST channel is looked at. The YouTube dial never asks, and neither
 *   does a LIVE TV evening spent on Pluto channels.
 * - The cached copy answers first, straight off disk, so the first banner after a launch has a
 *   title without waiting on the network - the file holds thirty hours, so last night's copy is
 *   still right this morning.
 * - A download at most every [REFRESH_MILLIS], counted from the cached file's age, so a relaunch
 *   does not fetch again; after a failure, not again for [RETRY_MILLIS].
 * - One load at a time, on [executor] - the prefetch thread, like Pluto's guide.
 * - Parse before writing: a captive portal's page is never cached over a good guide.
 *
 * Failure answers null, and the banner and the guide keep the channel name - exactly the
 * behaviour from before this existed.
 */
class FastGuideStore(
    private val file: File,
    private val fetch: (String) -> String,
    private val executor: Executor,
    /** Wall clock: the file's age is its modification time against this. */
    private val nowMillis: () -> Long,
    /** The Settings row. Read per call: switched off, nothing is loaded or served. */
    private val enabled: () -> Boolean,
    private val url: String = FastGuide.URL,
) {

    @Volatile private var guide: FastGuide? = null

    /** Wall-clock time from which the next download is due. Read and written on [executor]. */
    @Volatile private var dueAtMillis = 0L

    private val loading = AtomicBoolean(false)

    /** Called once the next load lands - the first banner or rows asked while nothing was held. */
    private val waiters = mutableListOf<() -> Unit>()

    /**
     * What is on [channel] now, or null. When nothing is held yet, or the copy held is due for a
     * refresh, a load starts; [onLoaded] - on the executor - runs once it lands, when nothing
     * was held, so the caller can read again. Never called when the load fails.
     */
    fun titleOn(channel: Channel, onLoaded: (() -> Unit)? = null): String? {
        if (!enabled()) return null
        FastGuide.keyOf(channel) ?: return null
        val held = guide
        if (held == null && onLoaded != null) {
            synchronized(waiters) { if (waiters.size < MAX_WAITERS) waiters += onLoaded }
        }
        // Nothing held is no reason to ask again inside a failure's wait: that would be a
        // request per banner on a television with no network.
        if (nowMillis() >= dueAtMillis) load()
        return held?.titleAt(channel, nowMillis())
    }

    private fun load() {
        if (!loading.compareAndSet(false, true)) return
        // Refused once the thread is shut down with the activity: nothing loads, nobody listens.
        runCatching { executor.execute { loadNow() } }.onFailure { loading.set(false) }
    }

    private fun loadNow() {
        try {
            if (guide == null) fromDisk()
            if (nowMillis() >= dueAtMillis) download()
        } finally {
            loading.set(false)
        }
        if (guide == null) return
        val ready = synchronized(waiters) { waiters.toList().also { waiters.clear() } }
        ready.forEach { waiter ->
            runCatching { waiter() }.onFailure { Log.w("fs42", "fast guide callback: $it") }
        }
    }

    private fun fromDisk() {
        if (!file.exists()) return
        runCatching { FastGuide.parse(file.readText()) }
            .onSuccess {
                guide = it
                dueAtMillis = file.lastModified() + REFRESH_MILLIS
            }
            .onFailure { Log.w("fs42", "fast guide cache unreadable: $it") }
    }

    private fun download() {
        runCatching {
            val text = fetch(url)
            val parsed = FastGuide.parse(text)
            file.parentFile?.mkdirs()
            file.writeText(text)
            parsed
        }.onSuccess {
            guide = it
            dueAtMillis = nowMillis() + REFRESH_MILLIS
            Log.i("fs42", "fast guide: ${it.size} channels")
        }.onFailure {
            dueAtMillis = nowMillis() + RETRY_MILLIS
            Log.i("fs42", "fast guide download failed: $it")
        }
    }

    companion object {
        /** Four hours: the job publishes every six, and the file holds thirty. */
        const val REFRESH_MILLIS = 4L * 60 * 60 * 1000
        const val RETRY_MILLIS = 10L * 60 * 1000
        const val FILE_NAME = "fast_guide.json"
        private const val MAX_WAITERS = 32

        /** The download, with timeouts, as the lineup's: the default is none at all. */
        fun httpFetch(url: String): String =
            (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 5_000
                readTimeout = 15_000
                try {
                    if (responseCode != 200) error("fast guide http $responseCode")
                    inputStream.bufferedReader().use { it.readText() }
                } finally {
                    disconnect()
                }
            }
    }
}
