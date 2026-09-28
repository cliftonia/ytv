package com.cliftonia.fs42tv.pluto

import android.util.Log
import com.cliftonia.fs42tv.sync.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads the masters of the channels either side of the one on screen - Pluto channels and the
 * LIVE TV dial's FAST feeds - a few seconds after its picture arrives, so a surf up or down finds
 * its pick in [VariantCache] and skips the read. Then, while the viewer stays, reads the Pluto
 * neighbours again every [REFRESH_MILLIS], before their picks age out ([VariantCache.TTL_MILLIS]):
 * the viewer who sat on a channel for ten minutes surfs as fast as one who just arrived.
 *
 * WHY THE WAIT: [DELAY_MILLIS] after the first frame, not at the tune. The tune's own reads - the
 * session, the master, the playlist mpv opens, its first segments - are what the viewer is waiting
 * on, and a read ahead racing them on the TCL's one Wi-Fi link slows the picture it is meant to
 * speed up. By a few seconds in, the channel has buffered; a viewer still surfing has pressed
 * again, and [pictureUp] for the next channel cancels this one's wait before it has read anything.
 *
 * NEVER ON THE SESSION ON SCREEN. Pluto keeps one channel per session, and reading a neighbour's
 * master on the dial's own session ends the programme being watched (measured Sep 2026). Each
 * Pluto read - the first and every refresh - is on a session of its own, lent by
 * [PlutoRoute.readAhead] from the dial's pool - see [SessionPool] for the rotation that makes a
 * surf onto that neighbour play on it, and for why a lease never names the slot on screen.
 *
 * FAST feeds have no session: their masters are read as they are, remembered for hours in the
 * picker's FAST cache, so they are read once, not refreshed.
 *
 * CHEAP BY CONSTRUCTION: one master each for the two neighbours - a few kilobytes, no media
 * playlist, no segment - and every four minutes two more for Pluto. A free slot's session may be
 * fetched for it - never fresh, never while the home server is away - on these threads, never the
 * tune's. At most [MAX_IN_FLIGHT] reads at once, across every channel: a read still running when
 * the next picture asks for two more simply means those two are not read. A neighbour already
 * remembered is not read again. Only while mpv is the engine; Media3 reads a master lazily.
 *
 * NEVER while a break's commercial reel is on the player or the app is out of sight: [pictureUp]'s
 * `stillWanted` is asked when each wait ends, and [stop] (the app's onStop) cancels the wait and
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
    /**
     * A session of its own for reading the channel's master ahead - [PlutoRoute.readAhead] - never
     * re-pointing one carrying a channel in the set. Blocking; on [background]'s threads.
     */
    private val ahead: (Channel, keep: Set<String>) -> SessionPool.Lease?,
    /** Whether a read ahead is any use now - mpv is the engine. */
    private val prefetching: () -> Boolean,
    /**
     * Reads a Pluto master and remembers its pick - [MasterPicker.prefetch]; [refresh] reads one
     * remembered for [REFRESH_AGE_MILLIS] or more again. Blocking.
     */
    private val read: (masterUrl: String, refresh: Boolean, stillWanted: () -> Boolean) -> Boolean,
    /** Reads a FAST feed's master into the FAST cache; false when there was nothing to read. */
    private val readFast: (Channel, stillWanted: () -> Boolean) -> Boolean = { _, _ -> false },
    /**
     * A neighbour's pick is remembered - read now, or already - on [masterUrl] (the lease's, for
     * Pluto). The first round only: the pre-join warms that neighbour for a minute after the tune.
     */
    private val ready: (Channel, masterUrl: String?) -> Unit = { _, _ -> },
    private val delayMillis: Long = DELAY_MILLIS,
    private val maxInFlight: Int = MAX_IN_FLIGHT,
    private val refreshMillis: Long = REFRESH_MILLIS,
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
     * list the viewer surfs, in order; both ends wrap, like the dial), and then refresh the Pluto
     * ones every [refreshMillis] on the same terms. A second call replaces the first.
     */
    fun pictureUp(channel: Channel, dial: List<Channel>, stillWanted: () -> Boolean) {
        cancelWait()
        if (!readable(channel) || !prefetching()) return
        wait(channel, dial, stillWanted, epoch.get(), delayMillis, round = 0)
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

    private fun wait(channel: Channel, dial: List<Channel>, stillWanted: () -> Boolean, began: Int, delay: Long, round: Int) {
        waiting = schedule(delay) {
            waiting = null
            if (epoch.get() != began || !stillWanted() || !prefetching()) return@schedule
            val either = neighbours(channel, dial)
            // The channel on screen and both neighbours: no read ahead may re-point their sessions.
            val keep = (either + channel).mapNotNull { it.pluto?.id }.toSet()
            // A refresh is for Pluto alone: a FAST pick is good for hours.
            val due = if (round == 0) either else either.filter { it.pluto != null }
            due.forEach { launch(it, keep, began, refresh = round > 0) }
            if (round < MAX_REFRESHES && due.any { it.pluto != null }) {
                wait(channel, dial, stillWanted, began, refreshMillis, round + 1)
            }
        }
    }

    private fun cancelWait() {
        waiting?.invoke()
        waiting = null
    }

    private fun launch(neighbour: Channel, keep: Set<String>, began: Int, refresh: Boolean) {
        if (inFlight.size >= maxInFlight) {
            Log.i("fs42", "master ahead for ${neighbour.number} skipped; $maxInFlight reads running")
            return
        }
        val id = Any()
        val current = { epoch.get() == began }
        // Registered before it is handed over, so a read that finishes at once is not left behind
        // as a stale entry by the registration that follows it.
        inFlight[id] = {}
        val cancel = background {
            try {
                if (!current()) return@background
                if (neighbour.pluto != null) readOn(neighbour, keep, refresh, current)
                else if (readFast(neighbour, current) && !refresh && current()) ready(neighbour, null)
            } finally {
                inFlight.remove(id)
            }
        }
        inFlight.replace(id, cancel)
    }

    /** Borrow a session for [neighbour], read its master on it, and give the session back. */
    private fun readOn(neighbour: Channel, keep: Set<String>, refresh: Boolean, current: () -> Boolean) {
        val lease = ahead(neighbour, keep) ?: return
        try {
            if (!current()) return
            read(lease.masterUrl, refresh, current)
        } finally {
            lease.release()
        }
        // Outside the lease: the pre-join reads media, never a master, and holds no slot busy.
        if (!refresh && current()) ready(neighbour, lease.masterUrl)
    }

    companion object {
        /** Three to five seconds after the first frame: past the tune's own reads. */
        const val DELAY_MILLIS = 4_000L

        /** Reads ahead running at once, across every channel. */
        const val MAX_IN_FLIGHT = 2

        /**
         * Pluto neighbours are read again this often while the viewer stays: inside the picks'
         * five minutes ([VariantCache.TTL_MILLIS]), so a surf after a long sit still finds one.
         */
        const val REFRESH_MILLIS = 4 * 60_000L

        /** A pick at least this old is read again by a refresh; a younger one is left alone. */
        const val REFRESH_AGE_MILLIS = 3 * 60_000L

        /** Refreshes per picture: three hours of one channel, then the neighbours are left be. */
        const val MAX_REFRESHES = 45

        /** A FAST feed of the LIVE TV dial: a genre block, and no Pluto session to build. */
        fun isFast(channel: Channel): Boolean = channel.pluto == null && channel.block != null

        /** A channel whose master is read ahead: a Pluto channel or a FAST feed. */
        fun readable(channel: Channel): Boolean = channel.pluto != null || isFast(channel)

        /**
         * The Pluto channels and FAST feeds one step up and one step down from [channel] on
         * [dial], wrapping as the dial does ([com.cliftonia.fs42tv.tune.DialNavigator]). Any
         * other neighbour is not read, nor walked past: the surf lands on it, not beyond.
         */
        fun neighbours(channel: Channel, dial: List<Channel>): List<Channel> {
            val at = dial.indexOfFirst { it.number == channel.number }
            if (at < 0 || dial.size < 2) return emptyList()
            return listOf(+1, -1).map { dial[(at + it + dial.size) % dial.size] }
                .distinctBy { it.number }
                .filter { it.number != channel.number && readable(it) }
        }

        /**
         * On the television: the waits on the main looper, never firing once [halted]; the reads
         * on two daemon threads at minimum priority, which lapse when idle, so nothing is held for
         * a channel nobody surfs from. [fastMaster] is a FAST feed's master url as a tune would
         * open it - through the US relay when the lineup says so - or null.
         */
        fun onDevice(
            route: PlutoRoute,
            picker: MasterPicker,
            halted: () -> Boolean,
            fastMaster: (Channel) -> String?,
            ready: (Channel, String?) -> Unit,
        ): MasterPrefetch {
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
                        runCatching(block).onFailure { Log.w("fs42", "master ahead failed: ${it.javaClass.simpleName}") }
                    }
                    ({ future.cancel(true) })
                },
                ahead = route::readAhead,
                prefetching = picker::prefetching,
                read = { url, refresh, stillWanted ->
                    picker.prefetch(url, maxAgeMillis = if (refresh) REFRESH_AGE_MILLIS else null, stillWanted = stillWanted)
                },
                readFast = { channel, stillWanted ->
                    val url = fastMaster(channel)
                    if (url == null) false
                    else {
                        picker.prefetch(url, fast = true, stillWanted = stillWanted)
                        picker.remembered(url, fast = true) != null
                    }
                },
                ready = ready,
            )
        }
    }
}
