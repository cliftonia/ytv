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
    /** Re-tunes the channel the viewer is on. */
    private val retune: (String) -> Unit,
) {

    private var cancelGrace: (() -> Unit)? = null
    private var cancelWatchdog: (() -> Unit)? = null
    private var watchdogRetuned = false

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
    }

    /** A picture: the streak is over, and both timers stand down. */
    fun firstFrame() = reset()

    private fun reset() {
        cancelGrace?.invoke()
        cancelGrace = null
        cancelWatchdog?.invoke()
        cancelWatchdog = null
        watchdogRetuned = false
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
        cancelWatchdog = schedule(WATCHDOG_MILLIS) {
            cancelWatchdog = null
            when {
                halted() || !stillTuning() -> Unit
                // Not now, but not never: look again once the overlay has had a chance to close.
                deferred() -> armWatchdog()
                !watchdogRetuned -> {
                    watchdogRetuned = true
                    retune("no picture after ${WATCHDOG_MILLIS / 1000}s")
                    armWatchdog()
                }
                // Retried once already; the card is the honest answer now. It stays until a
                // picture arrives or the viewer changes channel, like every other card.
                !cardUp() -> showCard(NO_PICTURE)
                else -> Unit
            }
        }
    }

    companion object {
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

        const val NO_PICTURE = "NO PICTURE"
    }
}
