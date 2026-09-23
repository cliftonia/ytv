package com.cliftonia.fs42tv.resolver

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps the accelerator's health readings current, off the tune path.
 *
 * The probe used to run inline, from whichever executor asked [ServerResolver.isAvailable] once
 * its cached answer expired - and the tune executor asks on every channel change. In the car,
 * where neither address answers, that put 400ms per address in front of a channel change every
 * thirty seconds. Here the probing happens on its own thread; the tune path only reads the
 * answer, and treats "unknown" as "no".
 *
 * Two goals pull against each other, and the schedule serves both:
 * - Fast again soon after coming home. [nudge] is called by every tune that finds no usable
 *   server, so a viewer who is surfing gets a fresh look within [NUDGE_EVERY_MILLIS].
 * - Not probing idly where nothing answers. While unreachable, the periodic round backs off
 *   from [FIRST_BACKOFF_MILLIS] to [MAX_BACKOFF_MILLIS]; a healthy round resets it.
 *
 * [schedule] is injected so the whole schedule is testable on a hand-cranked clock.
 */
class AcceleratorProbe(
    private val servers: List<ServerResolver>,
    /** Runs [block] after [delayMillis] on the probing thread. */
    private val schedule: (delayMillis: Long, block: () -> Unit) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Releases whatever [schedule] runs on. */
    private val onStop: () -> Unit = {},
) {

    private val lock = Any()

    /**
     * Which chain of rounds is the live one. A nudge starts a fresh round immediately; bumping
     * this retires the chain that was waiting out its backoff, so nudges never multiply into
     * several periodic chains probing side by side.
     */
    private var chain = 0
    private var backoffMillis = FIRST_BACKOFF_MILLIS
    private var lastRoundMillis: Long? = null
    @Volatile private var stopped = false

    fun start() = kick()

    fun stop() {
        stopped = true
        onStop()
    }

    /** No usable server on a tune: look again soon, without anyone waiting for the answer. */
    fun nudge() {
        synchronized(lock) {
            val last = lastRoundMillis
            if (last != null && nowMillis() - last < NUDGE_EVERY_MILLIS) return
            // Claimed here rather than when the round runs, so a burst of tunes queues one.
            lastRoundMillis = nowMillis()
        }
        kick()
    }

    private fun kick() {
        if (stopped) return
        val token = synchronized(lock) { ++chain }
        schedule(0) { round(token) }
    }

    private fun round(token: Int) {
        if (stopped) return
        synchronized(lock) {
            if (token != chain) return
            lastRoundMillis = nowMillis()
        }
        // Every address, not just until one answers: the tune path walks them in order, and a
        // LAN address that dies later must find the tailnet one's reading already fresh.
        val anyHealthy = servers.map { it.probe() }.any { it }
        val delay = synchronized(lock) {
            if (token != chain) return
            if (anyHealthy) {
                backoffMillis = FIRST_BACKOFF_MILLIS
                HEALTHY_EVERY_MILLIS
            } else {
                backoffMillis.also {
                    backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
                }
            }
        }
        if (!stopped) schedule(delay) { round(token) }
    }

    companion object {
        /** A healthy server is re-read this often, inside [ServerResolver.FRESH_FOR_MILLIS]. */
        const val HEALTHY_EVERY_MILLIS = 60_000L

        /** The first wait after an unreachable round; doubled each time after. */
        const val FIRST_BACKOFF_MILLIS = 30_000L

        /**
         * The longest an idle television waits between looks. Two minutes is the worst case for
         * noticing home with nobody touching the remote; anyone surfing nudges it sooner.
         */
        const val MAX_BACKOFF_MILLIS = 120_000L

        /**
         * The closest together two tune-driven rounds may be. The same thirty seconds the inline
         * check used to wait - the difference is that now nobody waits on the probe itself.
         */
        const val NUDGE_EVERY_MILLIS = 30_000L

        /**
         * A probe on a thread of its own: not the tune executor, which it was moved off, and not
         * the prefetch one, whose queue a 400ms timeout would hold up for nothing. Daemon, so a
         * missed [stop] can never keep the process alive.
         */
        fun onOwnThread(servers: List<ServerResolver>): AcceleratorProbe {
            val thread: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "accelerator-probe").apply { isDaemon = true }
            }
            return AcceleratorProbe(
                servers,
                schedule = { delay, block ->
                    // After shutdown the executor refuses new work; the round is simply not run.
                    runCatching { thread.schedule(Runnable { block() }, delay, TimeUnit.MILLISECONDS) }
                },
                onStop = { thread.shutdownNow() },
            )
        }
    }
}
