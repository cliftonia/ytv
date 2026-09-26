package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.tune.Tuned

/**
 * Reloads a live Pluto stream that mpv has stopped playing and will never resume by itself.
 *
 * Captured on the TCL (Sep 2026): fine at `cache=3.0s`, then at a programme-to-bumper join mpv
 * logs an audio underrun, ffmpeg `mpegts: Packet corrupt` and `hls: DTS 90000 < 2787000 out of
 * order` - repeating every ~27s as the bumper loops - and `cache=0.000000s` from then on. ffmpeg's
 * hls demuxer does not handle an EXT-X-DISCONTINUITY's timestamp reset (no AVFMT_TS_DISCONT), so
 * the demuxer is lost, not waiting: the stall pill stayed up for good. A fresh load starts a new
 * demuxer at the live edge, which is past the join.
 *
 * NOT the rule [StallPill] warns against. Re-tuning YouTube on a stall discarded a buffer that was
 * refilling and never recovered on a slow line; that is still never done - [eligible] is mpv
 * (the ffmpeg demuxer) on a live Pluto HLS stream only, where there is no buffer to lose and the
 * player cannot get out on its own.
 *
 * Bounded: [MAX_RECOVERIES] reloads within [WINDOW_MILLIS] on one channel; the next stall goes
 * to [giveUp] - the ordinary playback-error path, with its retries, backoff and card - rather than
 * reloading a channel that stalls every time for as long as it is watched.
 *
 * Main thread only; the clock is [schedule]'s.
 */
class StallRecovery(
    /** Runs [block] after [delayMillis]; returns what cancels it. */
    private val schedule: (delayMillis: Long, block: () -> Unit) -> (() -> Unit),
    /** Monotonic milliseconds. */
    private val nowMillis: () -> Long,
    /** The channel on air when a stall on it is this class's to recover ([eligible]), else null. */
    private val channel: () -> Int?,
    /** An overlay is up or the app is away: not now - look again shortly. */
    private val deferred: () -> Boolean,
    /** Reload the channel on air. */
    private val recover: (reason: String) -> Unit,
    /** Too many reloads: hand it to the normal error path. */
    private val giveUp: (reason: String) -> Unit,
) {

    private var stalled = false
    private var cancel: (() -> Unit)? = null
    private var countedChannel: Int? = null
    private val recoveries = ArrayDeque<Long>()

    /**
     * The player started or stopped buffering - or showed a first frame, which the director
     * reports as `false`: a reload that plays is a stall over.
     */
    fun buffering(stalled: Boolean) {
        this.stalled = stalled
        cancelTimer()
        if (stalled && channel() != null) arm(STALL_LIMIT_MILLIS)
    }

    /** A channel change or a card: any stall is moot. */
    fun clear() {
        stalled = false
        cancelTimer()
    }

    private fun arm(delayMillis: Long) {
        cancel = schedule(delayMillis) {
            cancel = null
            fire()
        }
    }

    private fun cancelTimer() {
        cancel?.invoke()
        cancel = null
    }

    private fun fire() {
        if (!stalled) return
        val on = channel() ?: return
        if (deferred()) {
            arm(DEFERRED_RECHECK_MILLIS)
            return
        }
        if (on != countedChannel) {
            countedChannel = on
            recoveries.clear()
        }
        val now = nowMillis()
        while (recoveries.isNotEmpty() && now - recoveries.first() >= WINDOW_MILLIS) recoveries.removeFirst()
        stalled = false
        if (recoveries.size >= MAX_RECOVERIES) {
            giveUp("stalled again after $MAX_RECOVERIES reloads in ${WINDOW_MILLIS / 1000}s")
            return
        }
        recoveries.addLast(now)
        // Watched like the stall it replaces: a reload that never shows a picture is still stuck.
        stalled = true
        arm(STALL_LIMIT_MILLIS)
        recover("a stall of ${STALL_LIMIT_MILLIS / 1000}s on a live stream (reload ${recoveries.size} of $MAX_RECOVERIES)")
    }

    companion object {
        /**
         * Past the stall pill's own wait and past an ordinary Wi-Fi hiccup, which mpv rides out;
         * short of the viewer reaching for the remote.
         */
        const val STALL_LIMIT_MILLIS = 6_000L

        const val MAX_RECOVERIES = 3
        const val WINDOW_MILLIS = 2 * 60_000L

        /** Under the guide or away: how soon to look again. */
        const val DEFERRED_RECHECK_MILLIS = 2_000L

        /** The code the error path is told, when the reloads run out. */
        const val STALLED = "MPV_LIVE_STALL"

        /**
         * A stall this class may reload: [ffmpegDemuxer] (mpv - the engine that joins live at the
         * third-from-last segment is the one with ffmpeg's hls demuxer) playing a live Pluto HLS
         * stream, not a card.
         */
        fun eligible(ffmpegDemuxer: Boolean, tuned: Tuned?): Boolean =
            ffmpegDemuxer && tuned != null && tuned.card == null && tuned.channel.pluto != null &&
                tuned.playable is Hls
    }
}
