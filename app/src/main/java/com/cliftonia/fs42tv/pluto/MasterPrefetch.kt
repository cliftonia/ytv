package com.cliftonia.fs42tv.pluto

import android.util.Log
import com.cliftonia.fs42tv.sync.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads the masters of the Pluto channels either side of the one on screen, a few seconds after
 * its picture arrives, so a surf up or down finds its pick in [VariantCache] and skips the read.
 *
 * WHY THE WAIT: [DELAY_MILLIS] after the first frame, not at the tune. The tune's own reads - the
 * session, the master, the playlist mpv opens, its first segments - are what the viewer is waiting
 * on, and a read ahead racing them on the TCL's one Wi-Fi link slows the picture it is meant to
 * speed up. By a few seconds in, the channel has buffered; a viewer still surfing has pressed
 * again, and [pictureUp] for the next channel cancels this one's wait before it has read anything.
 *
 * CHEAP BY CONSTRUCTION: one master each for the two neighbours - a few kilobytes, no media
 * playlist, no segment - and only on a session already held ([PlutoRoute.masterAhead] never
 * fetches one). At most [MAX_IN_FLIGHT] reads at once, across every channel: a read still running
 * when the next picture asks for two more simply means those two are not read. A neighbour already
 * remembered is not read again. Only while mpv is the engine; Media3 reads a master lazily.
 *
 * NEVER while a break's commercial reel is on the player or the app is out of sight: [pictureUp]'s
 * `stillWanted` is asked when the wait ends, and [stop] (the app's onStop) cancels the wait and
 * every read in flight, and drops whatever a read that could not be interrupted brings back.
 *
 * A read ahead is only ever an optimisation: a miss, a failure, a pick for a session that has
 * since been rebuilt - each leaves the tune to read the master itself, exactly as before.
 *
 * [pictureUp] and [stop] run on the UI thread; the reads on [background]'s threads, low priority.
 */
class MasterPrefetch(
    /** Runs a block on the UI thread after a delay; returns what cancels it. */
    private val schedule: (delayMillis: Long, block: () -> Unit) -> () -> Unit,
    /** Runs a block off the UI thread, at low priority; returns what cancels (interrupts) it. */
    private val background: (block: () -> Unit) -> () -> Unit,
    /** The direct master a tune of the channel would play, if known without a fetch. */
    private val masterAhead: (Channel) -> String?,
    /** Whether a read ahead is any use now - mpv is the engine. */
    private val prefetching: () -> Boolean,
    /** Reads a master and remembers its pick - [MasterPicker.prefetch]. Blocking. */
    private val read: (masterUrl: String, stillWanted: () -> Boolean) -> Boolean,
    private val delayMillis: Long = DELAY_MILLIS,
    private val maxInFlight: Int = MAX_IN_FLIGHT,
) {

    /** Cancels the wait for the picture on screen; null when nothing is waiting. UI thread only. */
    private var waiting: (() -> Unit)? = null

    /** Bumped by [stop]: work begun before it neither starts nor keeps what it read. */
    private val epoch = AtomicInteger(0)

    /** The reads running, each by its cancel. Written by the UI thread and the read's own thread. */
    private val inFlight = ConcurrentHashMap<Any, () -> Unit>()

    /**
     * [channel]'s picture is up. In [delayMillis], if [stillWanted] then - the same tune on air,
     * no commercial reel on the player, the app in front - read its neighbours on [dial] (the
     * list the viewer surfs, in order; both ends wrap, like the dial). A second call before the
     * wait ends replaces the first.
     */
    fun pictureUp(channel: Channel, dial: List<Channel>, stillWanted: () -> Boolean) {
        cancelWait()
        if (channel.pluto == null || !prefetching()) return
        val began = epoch.get()
        waiting = schedule(delayMillis) {
            waiting = null
            if (epoch.get() != began || !stillWanted() || !prefetching()) return@schedule
            neighbours(channel, dial).forEach { launch(it, began) }
        }
    }

    /** The app left the screen: no wait, no read, and nothing kept from a read still returning. */
    fun stop() {
        epoch.incrementAndGet()
        cancelWait()
        inFlight.values.forEach { cancel -> runCatching { cancel() } }
        inFlight.clear()
    }

    /** How many reads are running - for the tests. */
    val running: Int get() = inFlight.size

    private fun cancelWait() {
        waiting?.invoke()
        waiting = null
    }

    private fun launch(neighbour: Channel, began: Int) {
        val url = masterAhead(neighbour) ?: return
        if (inFlight.size >= maxInFlight) {
            Log.i("fs42", "pluto master: ahead for ${neighbour.number} skipped; $maxInFlight reads running")
            return
        }
        val id = Any()
        val current = { epoch.get() == began }
        // Registered before it is handed over, so a read that finishes at once is not left behind
        // as a stale entry by the registration that follows it.
        inFlight[id] = {}
        val cancel = background {
            try {
                if (current()) read(url, current)
            } finally {
                inFlight.remove(id)
            }
        }
        inFlight.replace(id, cancel)
    }

    companion object {
        /** Three to five seconds after the first frame: past the tune's own reads. */
        const val DELAY_MILLIS = 4_000L

        /** Reads ahead running at once, across every channel. */
        const val MAX_IN_FLIGHT = 2

        /**
         * The Pluto channels one step up and one step down from [channel] on [dial], wrapping as
         * the dial does ([com.cliftonia.fs42tv.tune.DialNavigator]). A neighbour that is not a
         * Pluto channel is not read, nor walked past: the surf lands on it, not beyond.
         */
        fun neighbours(channel: Channel, dial: List<Channel>): List<Channel> {
            val at = dial.indexOfFirst { it.number == channel.number }
            if (at < 0 || dial.size < 2) return emptyList()
            return listOf(+1, -1).map { dial[(at + it + dial.size) % dial.size] }
                .distinctBy { it.number }
                .filter { it.number != channel.number && it.pluto != null }
        }

        /**
         * On the television: the wait on the main looper, never firing once [halted]; the reads on
         * two daemon threads at minimum priority, which lapse when idle, so nothing is held for a
         * channel nobody surfs from.
         */
        fun onDevice(route: PlutoRoute, picker: MasterPicker, halted: () -> Boolean): MasterPrefetch {
            val pool = ThreadPoolExecutor(MAX_IN_FLIGHT, MAX_IN_FLIGHT, 30, TimeUnit.SECONDS,
                LinkedBlockingQueue()) { runnable ->
                Thread(runnable, "pluto-ahead").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }.apply { allowCoreThreadTimeOut(true) }
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            return MasterPrefetch(
                schedule = { delay, block ->
                    val runnable = Runnable { if (!halted()) block() }
                    main.postDelayed(runnable, delay)
                    ({ main.removeCallbacks(runnable) })
                },
                background = { block ->
                    val future = pool.submit {
                        runCatching(block).onFailure { Log.w("fs42", "pluto master: ahead failed: ${it.javaClass.simpleName}") }
                    }
                    ({ future.cancel(true) })
                },
                masterAhead = route::masterAhead,
                prefetching = picker::prefetching,
                read = picker::prefetch,
            )
        }
    }
}
