package com.cliftonia.fs42tv.ads

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `ads.json`, kept the way the dial keeps its lineup: a file in the cache directory, fetched at
 * most once a day, off the UI thread, never in front of anything.
 *
 * A break never waits for it. [current] answers from memory only - the reels the last refresh
 * or the file on disk gave, or null - and [refreshIfStale] does the disk read and the fetch on a
 * thread of its own, so the first Pluto tune after launch warms it and the break minutes later
 * finds it ready. Missing is a normal answer: until the server job publishes the file, and on a
 * television that cannot reach GitHub (the car), every break is the card, as before.
 *
 * A failed fetch - a 404 while the file does not exist yet, a dead hotspot, a body that does not
 * parse - never overwrites the last good copy, and is not retried for [RETRY_MILLIS] rather than
 * on every tune. An empty `reels` list that parses IS written: that is the server saying "no ads",
 * and it is obeyed.
 */
class AdCatalogStore(
    private val file: File,
    /** GET a url's body; throws on any failure, a non-200 included. Blocking. */
    private val fetch: (String) -> String,
    /** Wall clock, compared with the file's modification time like the dial's cache. */
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Runs the refresh off the calling thread. Its own short-lived daemon by default. */
    private val background: (Runnable) -> Unit = { Thread(it, "ads-refresh").apply { isDaemon = true }.start() },
    private val url: String = ADS_URL,
) {

    @Volatile private var memory: AdCatalog? = null

    /** The file has been read into [memory] (or found missing) once this run. */
    @Volatile private var loaded = false

    /** When the copy in hand was written - the file's mtime, or the fetch that wrote it. */
    @Volatile private var writtenAt: Long? = null

    /** No fetch before this (wall clock) - after a failure. */
    @Volatile private var nextAttemptAt = 0L

    private val inFlight = AtomicBoolean(false)

    /** The catalog in hand, never blocking; null when there is none yet. */
    fun current(): AdCatalog? = memory

    /**
     * Read the file if not yet read, and fetch a new copy if it is older than a day. Returns at
     * once; the work runs on [background]. Cheap to call on every tune: nothing is started while
     * the copy in hand is fresh or a failure is being waited out.
     */
    fun refreshIfStale() {
        val now = nowMillis()
        if (loaded && (isFresh(writtenAt, now) || now < nextAttemptAt)) return
        if (!inFlight.compareAndSet(false, true)) return
        val started = runCatching { background(Runnable { refresh() }) }
        if (started.isFailure) inFlight.set(false)
    }

    private fun refresh() {
        try {
            if (!loaded) {
                if (file.exists()) {
                    memory = AdCatalog.parse(runCatching { file.readText() }.getOrNull())
                    writtenAt = file.lastModified()
                }
                loaded = true
            }
            val now = nowMillis()
            if (isFresh(writtenAt, now) || now < nextAttemptAt) return
            val body = fetch(url)
            // Parse BEFORE writing, as the dial does: a malformed body must not poison the copy
            // that exists for exactly when the network has nothing good to say.
            val catalog = AdCatalog.parse(body) ?: error("ads.json did not parse")
            file.parentFile?.mkdirs()
            // Whole or not at all: a write cut short (power off, a full disk) must not leave half
            // a file that parses as nothing where the last good copy was.
            val partial = File(file.path + ".tmp")
            partial.writeText(body)
            if (!partial.renameTo(file)) {
                partial.delete()
                error("could not replace ${file.name}")
            }
            memory = catalog
            writtenAt = now
            Log.i("fs42", "ads: ${catalog.usableReels.size} of ${catalog.reels.size} reels usable")
        } catch (e: Exception) {
            nextAttemptAt = nowMillis() + RETRY_MILLIS
            Log.i("fs42", "ads: no new catalog (${e.javaClass.simpleName}: ${e.message}); " +
                "${memory?.usableReels?.size ?: 0} reels in hand")
        } finally {
            inFlight.set(false)
        }
    }

    /** Written within the day. A time in the future is not fresh: the box boots with a wrong clock. */
    private fun isFresh(at: Long?, now: Long): Boolean = at != null && (now - at) in 0 until FRESH_MILLIS

    companion object {
        const val ADS_URL = "https://raw.githubusercontent.com/cliftonia/ytv/main/ads.json"
        const val FILE_NAME = "ads.json"

        /** The job publishes nightly; a copy younger than this is today's. */
        const val FRESH_MILLIS = 24L * 60 * 60 * 1000

        /** After a failure: an hour, not every tune - a 404 until the job ships is the norm. */
        const val RETRY_MILLIS = 60L * 60 * 1000

        private const val CONNECT_MILLIS = 5_000
        private const val READ_MILLIS = 10_000
        private const val MAX_BYTES = 4 * 1024 * 1024

        /** GET [url]: its body on a 200, an exception on anything else. Bounded, with timeouts. */
        fun httpFetch(url: String): String {
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = CONNECT_MILLIS
                readTimeout = READ_MILLIS
            }
            try {
                val code = connection.responseCode
                if (code != java.net.HttpURLConnection.HTTP_OK) throw java.io.IOException("HTTP $code")
                val bytes = connection.inputStream.use { it.readNBytesCompat(MAX_BYTES) }
                return String(bytes, Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }

        private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() < max) {
                val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
