package com.cliftonia.fs42tv.ui

/**
 * The two timers that stand between a failing channel and a silent black screen: the grace
 * before a playback error is announced, and the watchdog on a tune that never produces a picture.
 *
 * Separate from [ScreenDirector] so the rules are testable: everything Android-shaped arrives as
 * a lambda, and the clock is whatever [schedule] says it is. Main-thread only, like the
 * director - every entry point is a player callback or a tune step already posted there.
 */
class RecoveryWatch(
    /** Runs [block] after [delayMillis]; returns something that cancels it. */
    private val schedule: (delayMillis: Long, block: () -> Unit) -> (() -> Unit),
    private val halted: () -> Boolean,
    /** Still no first frame since the tune began. */
    private val stillTuning: () -> Boolean,
    /**
     * An overlay is open or the app is in the background - a retune now would change the
     * channel under the guide, which is exactly what its supersede exists to prevent.
     */
    private val deferred: () -> Boolean,
    /** Whether a card is already up - a definitive "CHANNEL UNAVAILABLE" outranks the watchdog's. */
    private val cardUp: () -> Boolean,
    /** Puts [reason] on the stand-by card. */
    private val showCard: (String) -> Unit,
    /** Re-tunes the channel the viewer is on - the watchdog's retry. */
    private val retune: (String) -> Unit,
    /**
     * How long the tune being watched gets for its first frame - [WATCHDOG_MILLIS], or
     * [SLOW_WATCHDOG_MILLIS] for a feed known to open slowly. Asked each time the watchdog arms.
     */
    private val watchdogMillis: () -> Long = { WATCHDOG_MILLIS },
    /** Re-tunes the channel whose playback failed - the error path's retry. */
    private val retuneAfterError: (String) -> Unit,
    /** Whether the engine can be rebuilt as a last resort - mpv only. */
    private val rebuildable: () -> Boolean = { false },
    /** Tears the engine down, builds a fresh one and re-tunes the channel the viewer is on. */
    private val rebuildAndRetune: (String) -> Unit = {},
    private val nowMillis: () -> Long = { 0L },
) {

    /** When the engine was last rebuilt by the watchdog; see [REBUILD_SPACING_MILLIS]. */
    private var lastRebuildMillis: Long? = null

    private var cancelGrace: (() -> Unit)? = null
    private var cancelWatchdog: (() -> Unit)? = null
    private var cancelRetry: (() -> Unit)? = null
    private var watchdogRetuned = false

    /** Errors since the last picture or channel change; decides how soon the next retry runs. */
    private var errorsInStreak = 0

    /** A deliberate channel change: whatever the last channel was failing at is moot. */
    fun tuneStarted() {
        reset()
        armWatchdog()
    }

    /**
     * A playback error. The card is armed ONCE per streak of errors, not re-armed per error.
     *
     * Re-arming was the bug: every error cancelled the pending card and started four fresh
     * seconds, so a source that failed faster than four seconds - a dead HLS link fails in a few
     * hundred milliseconds - re-tuned forever and the card never appeared. The screen sat black
     * with no word of explanation, which is the one outcome the card exists to prevent.
     */
    fun error(code: String) {
        if (cancelGrace == null) {
            cancelGrace = schedule(RECOVERY_GRACE_MILLIS) {
                if (!halted()) showCard(code)
            }
        }
        if (cancelWatchdog == null) armWatchdog()
        errorsInStreak++
        cancelRetry?.invoke()
        cancelRetry = null
        // Never under an overlay: the guide superseded the dial on purpose, and a retune there
        // changes the channel under the open list. Closing it re-tunes an unfinished tune anyway
        // - see ScreenDirector.recoverIfAbandoned.
        if (deferred()) return
        val reason = "playback error $code"
        val delay = retryDelayMillis(errorsInStreak)
        if (delay == 0L) {
            retuneAfterError(reason)
        } else {
            cancelRetry = schedule(delay) {
                cancelRetry = null
                if (!halted() && stillTuning() && !deferred()) retuneAfterError(reason)
            }
        }
    }

    /** A picture: the streak is over, and both timers stand down. */
    fun firstFrame() = reset()

    private fun reset() {
        cancelGrace?.invoke()
        cancelGrace = null
        cancelWatchdog?.invoke()
        cancelWatchdog = null
        cancelRetry?.invoke()
        cancelRetry = null
        watchdogRetuned = false
        errorsInStreak = 0
    }

    /**
     * The engine-agnostic backstop: a tune still waiting for its first frame after
     * [WATCHDOG_MILLIS] is retried once, and if the retry does no better, the card says so.
     *
     * Needed because every other path to the card depends on the ENGINE reporting something -
     * an error, an end - and a player that simply never produces a frame reports nothing. That
     * is the silent black screen in its purest form: blank up, audio muted, no card, no retry.
     */
    private fun armWatchdog() {
        val patience = watchdogMillis()
        cancelWatchdog = schedule(patience) {
            cancelWatchdog = null
            when {
                halted() || !stillTuning() -> Unit
                // Not now, but not never: look again once the overlay has had a chance to close.
                deferred() -> armWatchdog()
                !watchdogRetuned -> {
                    watchdogRetuned = true
                    retune("no picture after ${patience / 1000}s")
                    armWatchdog()
                }
                // Retried once already and still silent. On mpv, one more chance with a FRESH
                // engine before the card: the fast-surf black dial was a load guard skewed for
                // the life of the process, rejecting every first frame, so the retune above fared
                // no better and the card stayed until a force-stop. A new engine is a new guard.
                // Spaced, so a channel that is genuinely dead does not rebuild on every visit.
                rebuildDue() -> {
                    lastRebuildMillis = nowMillis()
                    rebuildAndRetune("no picture after a retune; rebuilding the engine")
                    armWatchdog()
                }
                // The card is the honest answer now. It stays until a picture arrives or the
                // viewer changes channel, like every other card.
                !cardUp() -> showCard(NO_PICTURE)
                else -> Unit
            }
        }
    }

    private fun rebuildDue(): Boolean {
        if (!rebuildable()) return false
        val last = lastRebuildMillis ?: return true
        return nowMillis() - last >= REBUILD_SPACING_MILLIS
    }

    companion object {
        /**
         * The least time between two watchdog rebuilds of the engine. A rebuild is seconds of
         * black and a torn-down decoder; worth it once for a stuck engine, not on every visit to
         * a channel that is simply dead.
         */
        const val REBUILD_SPACING_MILLIS = 120_000L

        /**
         * How long to wait before retrying after the [errorCount]th error of a streak.
         *
         * The first few retry at once, because they are usually progress rather than repetition:
         * a 403 falls to the next rung, then the next clip, each a genuinely different url. After
         * that the same thing is failing again, and at once was a storm - a dead live or file url
         * fails before mpv even opens it, in a few hundred milliseconds, and it was re-tuned to
         * the identical url several times a second for as long as the channel stayed on. Backing
         * off keeps trying (a feed that comes back is picked up) without hammering anything.
         */
        fun retryDelayMillis(errorCount: Int): Long = when {
            errorCount <= IMMEDIATE_RETRIES -> 0L
            errorCount == IMMEDIATE_RETRIES + 1 -> 5_000L
            errorCount == IMMEDIATE_RETRIES + 2 -> 15_000L
            else -> 30_000L
        }

        /** hd refused, sd refused, the next clip - the most a streak needs to make progress. */
        const val IMMEDIATE_RETRIES = 3

        /**
         * How long a playback error is given to fix itself before the stand-by card appears.
         *
         * A 403 on a signed URL recovers by dropping the dead id, asking the server for a
         * fresh one and tuning again. Measured end to end that is about 1.5s; 4s covers the
         * slow end with room, while still being short enough that a channel which is genuinely
         * dead says so rather than sitting blank.
         */
        const val RECOVERY_GRACE_MILLIS = 4_000L

        /**
         * How long a tune may go without a first frame before it is retried.
         *
         * A device resolve is 2.4s at the median and 3.8s at worst, and mpv's first frame
         * after that is a few seconds more on a slow link - so twelve seconds is well past any
         * tune that is merely slow, and short enough that a stuck one is noticed before the
         * viewer reaches for the remote.
         */
        const val WATCHDOG_MILLIS = 12_000L

        /**
         * The LIVE TV dial's FAST feeds (Samsung TV Plus, Rakuten, Roku...): measured on the TCL's
         * network, 5-11s to a first frame on one variant, over 20s through Amazon's ad-inserting
         * CDN in Ireland. At 12s the watchdog restarted a load that was working, and the restart
         * fared no better - a channel that only ever showed a picture with the guide open, where
         * the watchdog waits.
         */
        const val SLOW_WATCHDOG_MILLIS = 25_000L

        const val NO_PICTURE = "NO PICTURE"
    }
}

/**
 * A schedule on [handler] whose cancel removes only its own runnable - the dial loader's retry
 * shares the recovery handler, and a blanket clear would take the retry with it.
 */
fun cancellable(handler: android.os.Handler): (Long, () -> Unit) -> (() -> Unit) = { delay, block ->
    val runnable = Runnable(block)
    handler.postDelayed(runnable, delay)
    ({ handler.removeCallbacks(runnable) })
}
