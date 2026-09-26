package com.cliftonia.fs42tv.pluto

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Reads the playlist of the Pluto channel on air every [POLL_MILLIS] and hands over what it says
 * about the break - a [BreakView] per read, on the stream's own clock ([BreakTimeline]) with the
 * two-read [BreakDetector] kept for a playlist without timestamps.
 *
 * The first read is at the tune itself, not after the picture: it is the mpv clock's anchor
 * (see [OnScreen]), and the closer it lands to mpv's own read of the window, the surer that is.
 *
 * Measured Sep 2026: re-reading the same session's master and variant every five seconds while a
 * player streams does not disturb the stream (four minutes, no player errors). Under mpv the tune
 * has already chosen the variant mpv opens ([MasterPicker]); [start] is handed it, so the poller
 * reads the very playlist on screen with no master fetch at all. Otherwise the master is read once
 * per tune and its lowest variant kept. Either way a failed variant read forgets it, so the next
 * read asks the master again - a stitcher that moved the variant is followed rather than read wrong
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
    /** A read landed on [Run] - every read, said something or not; on the polling thread. */
    private val read: (Run, BreakView) -> Unit,
    /** Monotonic milliseconds, for the detector's time ceiling - never the wall clock, which steps. */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Wall-clock milliseconds, the clock the tune's load time is taken in - see [OnScreen]. */
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {

    class Fetched(val url: String, val body: String)

    /** One tune's polling: its url, its detector, its variant. Never reused across tunes. */
    class Run internal constructor(val masterUrl: String) {
        internal val detector = BreakDetector()
        internal val timeline = BreakTimeline()
        internal var silentReads = 0
        /** When the latest variant read was sent - the anchor's clock. See [BreakView.firstReadAt]. */
        internal var requestedAt: Long? = null
        internal var lastStart: Long? = null
        internal var lastEnd: Long? = null
        @Volatile internal var variant: String? = null
        @Volatile internal var stopped = false
        @Volatile internal var cancel: (() -> Unit)? = null
    }

    @Volatile private var current: Run? = null

    /** The url being polled, or null. */
    val pollingUrl: String? get() = current?.takeUnless { it.stopped }?.masterUrl

    /** Whether [run] is still the one wanted - false once stopped or replaced. */
    fun isCurrent(run: Run): Boolean = current === run && !run.stopped

    /**
     * Poll [masterUrl] from scratch - a new detector - replacing any other run. [variant] is the
     * media playlist the player itself opened, when the tune chose one; else the master's lowest.
     */
    fun start(masterUrl: String, variant: String? = null): Run {
        stop()
        val run = Run(masterUrl).also { it.variant = variant }
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
        run.requestedAt = null
        val body = fetchPlaylist(run)
        val readAt = wallMillis()
        val requestedAt = run.requestedAt ?: readAt
        val before = run.detector.state
        val window = HlsWindow.parse(body)
        val longEnough = window != null && window.segments.all { it.bumper } &&
            window.segments.sumOf { it.durationMillis } >= BreakView.MIN_BREAK_MILLIS
        val after = run.detector.feed(body, nowMillis(), longEnough)
        if (window != null) {
            run.timeline.feed(window, readAt, requestedAt)
            run.silentReads = 0
        } else {
            run.silentReads++
        }
        if (!isCurrent(run)) return
        val view = run.timeline.view().copy(
            blind = run.silentReads >= BreakDetector.MAX_UNKNOWN_READS,
            fallbackInBreak = after == BreakDetector.State.IN_BREAK,
        )
        log(run, view, before, after)
        read(run, view)
        next(run, POLL_MILLIS)
    }

    /** What changed, once each - never the url: a direct-route one carries the session's token. */
    private fun log(run: Run, view: BreakView, before: BreakDetector.State, after: BreakDetector.State) {
        if (view.start != run.lastStart && view.start != null) {
            android.util.Log.i("fs42", "pluto break starts at ${java.time.Instant.ofEpochMilli(view.start)}")
        }
        if (view.end != run.lastEnd && view.end != null) {
            android.util.Log.i("fs42", "pluto break ends at ${java.time.Instant.ofEpochMilli(view.end)}")
        }
        run.lastStart = view.start
        run.lastEnd = view.end
        if (!view.timed && after != before) android.util.Log.i("fs42", "pluto break (untimed): $after")
    }

    /** The media playlist's body, or null for a read that says nothing. */
    private fun fetchPlaylist(run: Run): String? {
        val variant = run.variant ?: runCatching {
            val master = fetch(run.masterUrl)
            HlsVariants.mediaPlaylist(master.body, master.url)
        }.getOrNull()?.also { run.variant = it } ?: return null
        run.requestedAt = wallMillis()
        return runCatching { fetch(variant).body }
            .onFailure { run.variant = null }
            .getOrNull()
    }

    companion object {
        /**
         * Under a segment (5s): every segment is seen while it is at the edge, so a break's start
         * and end are known about three segments before the player reaches them.
         */
        const val POLL_MILLIS = 4_000L

        /** At the tune: the mpv clock's anchor. See the class comment. */
        const val FIRST_READ_MILLIS = 0L

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
