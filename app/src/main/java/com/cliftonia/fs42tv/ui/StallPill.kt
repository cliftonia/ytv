package com.cliftonia.fs42tv.ui

import androidx.compose.runtime.mutableStateOf

/**
 * A mid-clip stall, shown as a small pill over the FROZEN picture rather than the full stand-by
 * card. The card is for faults; a stall is weather. Covering the programme with TECHNICAL
 * DIFFICULTIES while ExoPlayer was quietly refilling its buffer made every slow patch of Wi-Fi
 * look like a breakdown.
 *
 * Out of [ScreenDirector] - which was at its size limit - once a second thing could stand in
 * front of the picture: a Pluto break card. The pill is never drawn over one, but the stall is
 * remembered under it, so a stall that began during a break still gets its pill when the card
 * comes down with the player still stuck.
 *
 * Main thread only; [post] and [cancel] are the director's stall handler, which nothing else
 * shares - see the director's recovery handler for why.
 */
class StallPill(
    /** Runs [block] after [delayMillis] on the main thread. */
    private val post: (delayMillis: Long, block: () -> Unit) -> Unit,
    /** Cancels everything [post] has pending. */
    private val cancel: () -> Unit,
    private val halted: () -> Boolean,
    /** Something stands for the picture now - a break card - so no pill goes over it. */
    private val covered: () -> Boolean,
) {

    /** Whether the pill is drawn. Compose state. */
    val showing = mutableStateOf(false)

    /** The player's last word on buffering. */
    private var stalled = false

    /**
     * The player started or stopped buffering. The pill waits [STALL_CARD_MILLIS] first, and a
     * stall that clears before then is never shown.
     *
     * The pill is ALL that happens. Re-tuning on a stall was tried and made things far worse: it
     * discards whatever has buffered and restarts the deep seek, so on a connection that cannot
     * sustain the bitrate it produced a permanent cycle of six seconds of picture and twelve of
     * nothing. ExoPlayer keeps filling during a stall and resumes by itself; interrupting that
     * is the one thing that stops it recovering.
     */
    fun buffering(stalled: Boolean) {
        cancel()
        this.stalled = stalled
        if (stalled) arm() else showing.value = false
    }

    /** A new tune or a scheduled card: whatever the last picture was waiting on is moot. */
    fun clear() {
        cancel()
        stalled = false
        showing.value = false
    }

    /** A first frame: down, as before - the player says separately whether it still buffers. */
    fun firstFrame() {
        showing.value = false
    }

    /** A break card went up over the picture: no pill over it, but the stall is remembered. */
    fun cover() {
        cancel()
        showing.value = false
    }

    /** The break card came down: a stall still going gets its pill, after the usual wait. */
    fun uncover() {
        if (stalled) arm()
    }

    private fun arm() {
        cancel()
        post(STALL_CARD_MILLIS) { if (!halted() && !covered()) showing.value = true }
    }

    companion object {
        /** Long enough not to flash on the brief stalls that clear themselves. */
        const val STALL_CARD_MILLIS = 2_500L
    }
}
