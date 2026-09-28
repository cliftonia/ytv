package com.cliftonia.fs42tv.details

import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor

/**
 * The picker's pictures - TMDB posters, Pluto and FAST stills - fetched off the UI thread and kept
 * in memory and on disk, so scrolling back over a channel shows its picture at once.
 *
 * Hand-rolled rather than an image library: the app has none, the job is one picture at a time
 * for one pane, and a library would bring an HTTP stack and a cache framework to a 32-bit
 * television that already has both. The rules, in the manner of Pluto's guide:
 *
 * - Newest wins. At most [MAX_QUEUED] pictures wait; a held DOWN that passes twenty channels
 *   drops the oldest asks, so the picture fetched is the one the highlight stopped on.
 * - One fetch per url in flight, however many ask.
 * - Memory first (an LRU of decoded pictures, [maxBytes] in all), then disk ([dir], at most
 *   [maxFiles] files, oldest removed first), then the network.
 * - A failure is remembered for [FAILURE_BACKOFF_MILLIS]: a dead link is not a request per keypress.
 * - Callbacks run on [executor]; a callback that throws is logged, never propagated.
 *
 * Generic over the decoded picture so the rules are testable on the JVM; the app's is an
 * ImageBitmap (see [ArtLoader]).
 */
class ArtCache<T : Any>(
    private val dir: File?,
    private val fetch: (String) -> ByteArray,
    private val decode: (ByteArray) -> T?,
    private val sizeOf: (T) -> Int,
    private val executor: Executor,
    private val elapsedMillis: () -> Long,
    private val maxBytes: Int = 24 * 1024 * 1024,
    private val maxFiles: Int = 300,
) {

    private val memory = LinkedHashMap<String, T>(16, 0.75f, true)
    private var memoryBytes = 0

    private class Waiter<T>(val onReady: (T) -> Unit, val onFailed: () -> Unit)

    /** Guarded by itself: urls queued or fetching, and who is waiting on each. */
    private val waiting = LinkedHashMap<String, MutableList<Waiter<T>>>()
    private val failedAt = HashMap<String, Long>()

    /** The picture for [url] if it is already in memory. Any thread. */
    fun cached(url: String): T? = synchronized(memory) { memory[url] }

    /**
     * Make sure [url]'s picture is on its way; [onReady] runs on the executor when it is decoded.
     * Straight back when already in memory. [onFailed] runs instead when it cannot be had - at
     * once inside a failure's backoff - but not for an ask dropped for newer ones.
     */
    fun request(url: String, onFailed: () -> Unit = {}, onReady: (T) -> Unit) {
        cached(url)?.let { onReady(it); return }
        val waiter = Waiter(onReady, onFailed)
        val backingOff = synchronized(waiting) {
            val failed = failedAt[url]?.let { elapsedMillis() - it < FAILURE_BACKOFF_MILLIS } == true
            if (!failed) {
                waiting[url]?.let { it += waiter; return }
                while (waiting.size >= MAX_QUEUED) waiting.remove(waiting.keys.first())
                waiting[url] = mutableListOf(waiter)
            }
            failed
        }
        if (backingOff) {
            onFailed()
            return
        }
        runCatching { executor.execute { load(url) } }
            .onFailure { synchronized(waiting) { waiting.remove(url) } }
    }

    private fun load(url: String) {
        // Dropped for newer asks while this waited.
        if (synchronized(waiting) { url !in waiting }) return
        val picture = cached(url) ?: runCatching { fromDisk(url) ?: fromNetwork(url) }
            .onFailure { Log.i("fs42", "art for $url failed: $it") }
            .getOrNull()
        val waiters = synchronized(waiting) {
            if (picture == null) failedAt[url] = elapsedMillis()
            waiting.remove(url).orEmpty()
        }
        if (picture != null) remember(url, picture)
        waiters.forEach { waiter ->
            runCatching { if (picture != null) waiter.onReady(picture) else waiter.onFailed() }
                .onFailure { Log.w("fs42", "art callback: $it") }
        }
    }

    private fun fromDisk(url: String): T? {
        val file = fileFor(url) ?: return null
        if (!file.exists()) return null
        file.setLastModified(System.currentTimeMillis())
        return decode(file.readBytes())
    }

    private fun fromNetwork(url: String): T? {
        val bytes = fetch(url)
        val picture = decode(bytes) ?: return null
        fileFor(url)?.let { file ->
            runCatching {
                file.parentFile?.mkdirs()
                val part = File(file.path + ".part")
                part.writeBytes(bytes)
                part.renameTo(file)
                trimDisk(file.parentFile)
            }.onFailure { Log.w("fs42", "art not kept on disk: $it") }
        }
        return picture
    }

    private fun trimDisk(folder: File?) {
        val files = folder?.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach { it.delete() }
    }

    private fun remember(url: String, picture: T) = synchronized(memory) {
        memory.remove(url)?.let { memoryBytes -= sizeOf(it) }
        memory[url] = picture
        memoryBytes += sizeOf(picture)
        val it = memory.entries.iterator()
        while (memoryBytes > maxBytes && memory.size > 1 && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == url) continue
            memoryBytes -= sizeOf(eldest.value)
            it.remove()
        }
    }

    private fun fileFor(url: String): File? = dir?.let { File(it, sha1(url) + ".img") }

    companion object {
        const val MAX_QUEUED = 4
        const val FAILURE_BACKOFF_MILLIS = 5 * 60_000L

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
