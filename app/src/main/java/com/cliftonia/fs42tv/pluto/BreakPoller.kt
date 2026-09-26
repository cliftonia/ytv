package com.cliftonia.fs42tv.pluto

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Reads the playlist of the Pluto channel on air every [POLL_MILLIS] and tells a [BreakDetector]
 * what it says - so the dial knows when Pluto is showing its bumper instead of a programme.
 *
 * Measured Sep 2026: re-reading the same session's master and variant every five seconds while a
 * player streams does not disturb the stream (four minutes, no player errors). The master is read
 * once per tune and its lowest variant kept; a failed variant read forgets it, so the next read
 * asks the master again - a stitcher that moved the variant is followed rather than read wrong
 * forever. A failure of any kind is an unknown read, which counts only toward a break's ceiling.
 *
 * Threading: [start] and [stop] from the UI thread; every fetch on [schedule]'s thread, which must
 * never be the UI or the tune thread - a read may take its full timeouts. [changed] runs on that
 * thread too, and a stop that raced it is the caller's to check with [isCurrent]. Safe after the
 * executor is shut down: a refused schedule simply ends the run (this codebase has crashed on
 * RejectedExecutionException before).
 */
class BreakPoller(
    /** GET a playlist: its body and the url it finally came from. Blocking; throws on failure. */
    private val fetch: (String) -> Fetched,
    /** Runs a block after a delay OFF the UI and tune threads; returns what cancels it. May throw. */
    private val schedule: (delayMillis: Long, block: () -> Unit) -> (() -> Unit),
    /** The detector's state changed on [Run]; on the polling thread. */
    private val changed: (Run, BreakDetector.State) -> Unit,
    /** Monotonic milliseconds, for the break's time ceiling - never the wall clock, which steps. */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    class Fetched(val url: String, val body: String)

    /** One tune's polling: its url, its detector, its variant. Never reused across tunes. */
    class Run internal constructor(val masterUrl: String) {
        internal val detector = BreakDetector()
        @Volatile internal var variant: String? = null
        @Volatile internal var stopped = false
        @Volatile internal var cancel: (() -> Unit)? = null
    }

    @Volatile private var current: Run? = null

    /** The url being polled, or null. */
    val pollingUrl: String? get() = current?.takeUnless { it.stopped }?.masterUrl

    /** Whether [run] is still the one wanted - false once stopped or replaced. */
    fun isCurrent(run: Run): Boolean = current === run && !run.stopped

    /** Poll [masterUrl] from scratch - a new detector, a new variant - replacing any other run. */
    fun start(masterUrl: String): Run {
        stop()
        val run = Run(masterUrl)
        current = run
        next(run, FIRST_READ_MILLIS)
        return run
    }

    fun stop() {
        val run = current ?: return
        current = null
        run.stopped = true
        run.cancel?.invoke()
        run.cancel = null
    }

    private fun next(run: Run, delayMillis: Long) {
        if (!isCurrent(run)) return
        run.cancel = runCatching { schedule(delayMillis) { tick(run) } }
            .onFailure { run.stopped = true }
            .getOrNull()
    }

    private fun tick(run: Run) {
        if (!isCurrent(run)) return
        val before = run.detector.state
        val after = run.detector.feed(read(run), nowMillis())
        if (!isCurrent(run)) return
        if (after != before) {
            // Never the url: a direct-route one carries the session's token.
            if (after == BreakDetector.State.PROGRAMME) {
                android.util.Log.i("fs42", "pluto break ended: ${run.detector.lastEnd}")
            }
            changed(run, after)
        }
        next(run, POLL_MILLIS)
    }

    /** The media playlist's body, or null for a read that says nothing. */
    private fun read(run: Run): String? {
        val variant = run.variant ?: runCatching {
            val master = fetch(run.masterUrl)
            HlsVariants.mediaPlaylist(master.body, master.url)
        }.getOrNull()?.also { run.variant = it } ?: return null
        return runCatching { fetch(variant).body }
            .onFailure { run.variant = null }
            .getOrNull()
    }

    companion object {
        /** About one segment: often enough that the programme's return drops the card promptly. */
        const val POLL_MILLIS = 5_000L

        /** The first read after a picture - soon, but after the player's own first fetches. */
        const val FIRST_READ_MILLIS = 2_000L

        /**
         * Tight timeouts: a read slower than a poll interval is worth less than none, and the
         * thread must be free for the next one. Cap far above a real playlist (a few KB).
         */
        private const val CONNECT_MILLIS = 2_000
        private const val READ_MILLIS = 3_000
        private const val MAX_BYTES = 256 * 1024

        /** The polling thread: its own daemon, never the prefetch thread neighbour resolves wait on. */
        fun daemonExecutor(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "pluto-breaks").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }

        /** [schedule] on [executor], with the block's own failures kept off the thread. */
        fun scheduleOn(executor: ScheduledExecutorService): (Long, () -> Unit) -> (() -> Unit) =
            { delay, block ->
                val future = executor.schedule({ runCatching(block) }, delay, TimeUnit.MILLISECONDS)
                ({ future.cancel(false) })
            }

        /**
         * GET [url], following redirects (the legacy jmp2 url is one). The url is never logged:
         * a direct-route url carries the session's token.
         */
        fun httpFetch(url: String): Fetched {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_MILLIS
                readTimeout = READ_MILLIS
                instanceFollowRedirects = true
            }
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw java.io.IOException("playlist HTTP $code")
                val body = connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (out.size() < MAX_BYTES) {
                        val n = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size()))
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    String(out.toByteArray(), Charsets.UTF_8)
                }
                return Fetched(connection.url.toString(), body)
            } finally {
                connection.disconnect()
            }
        }
    }
}
