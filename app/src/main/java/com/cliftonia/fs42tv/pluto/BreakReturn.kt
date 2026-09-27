package com.cliftonia.fs42tv.pluto

/**
 * When to go back to a Pluto channel whose break the viewer spent watching commercials from the
 * archive (ui/BreakAds) - the player was handed a reel, so the programme's return needs a TUNE,
 * and a tune does not land where the viewer left: it lands where a fresh load joins the stream.
 *
 * THE MATHS. A fresh load joins [joinOffsetMillis] behind the live edge of the window it reads:
 *  - mpv: ffmpeg's `live_start_index` -3, the third-from-last segment's start - exactly the last
 *    three segments' lengths behind the edge, which is [BreakView.liveOffsetMillis] (~15s).
 *  - Media3: no hold-back tag on Pluto, so its default live offset is three TARGET durations, and
 *    it starts at the start of the segment holding that point - at most one more segment back.
 *    Four targets bounds it (Pluto's target is 6, its segments ~5.005s: joins at ~20s, bound 24s).
 * So a load whose window ends at edge E joins at E - offset, and it lands on the programme, not
 * the bumper, exactly when E - offset >= the break's end (the programme's first segment). Retune
 * any earlier and the viewer lands on up to a segment of Pluto's logo; any later and the join has
 * moved past the return, and the start of the programme is lost. So: the first moment it holds.
 *
 * Two ways to know it holds:
 *  - PROVEN by a read: the latest window the poller read already ends far enough on. A load now
 *    reads that window or a later one, so it joins at or after the return. Up to a poll (4s) late.
 *  - BOUNDED by the clock: the edge estimate (latest edge + wall time since the read) runs ahead
 *    of what is published by up to one segment - a segment is published whole, when it ends - so
 *    once the estimate is a whole target past the mark, what is published is past it too.
 * Whichever comes first. The load itself takes ~2-3s under mpv (the variant pick, then the read):
 * that only moves the join later, into the programme - never back onto the bumper. The cost is
 * at most a segment or so of the programme's first seconds, against never seeing the logo.
 *
 * Up to the ceilings the card already has: a break past [BreakView.MAX_BREAK_MILLIS] from its
 * start on screen, or reads gone [BreakView.blind], ends the commercials at once - the same rule
 * that takes the card down, since nothing can vouch for the break any more.
 *
 * Pure: every clock is a parameter.
 */
object BreakReturn {

    sealed class Verdict {
        /** Retune now; [reason] is for the log. */
        data class Now(val reason: String) : Verdict()

        /** Not yet: decide again in [millis] (a read may say sooner). */
        data class After(val millis: Long) : Verdict()
    }

    /** Media3 joins at most this many targets behind the edge - see the class comment. */
    const val MEDIA3_JOIN_TARGETS = 4

    /** How far behind the edge of the window it reads a fresh load joins, on this engine. */
    fun joinOffsetMillis(view: BreakView, joinsThirdFromLast: Boolean): Long =
        if (joinsThirdFromLast) view.liveOffsetMillis else MEDIA3_JOIN_TARGETS * view.targetDurationMillis

    /**
     * The verdict at [wallNow], with the viewer notionally at [onScreen] (see OnScreen.continued).
     */
    fun decide(view: BreakView, onScreen: Long, wallNow: Long, joinOffsetMillis: Long): Verdict {
        if (view.blind) return Verdict.Now("the playlist reads went blind")
        val start = view.start ?: return Verdict.Now("the playlist no longer shows a break")
        val ceiling = start + BreakView.MAX_BREAK_MILLIS
        if (onScreen >= ceiling) return Verdict.Now("the break reached its ${BreakView.MAX_BREAK_MILLIS / 60_000}-minute ceiling")
        val toCeiling = ceiling - onScreen
        val end = view.end
        val edge = view.edgeEnd
        if (end == null || edge == null) return Verdict.After(toCeiling)
        val joinsAt = edge - joinOffsetMillis
        if (joinsAt >= end) {
            return Verdict.Now("the programme is back; a tune joins ${(joinsAt - end) / 1000.0}s after its return")
        }
        val estimate = edge + (wallNow - view.readAt).coerceAtLeast(0L)
        val wait = end + joinOffsetMillis + view.targetDurationMillis - estimate
        if (wait <= 0) return Verdict.Now("the programme is back, by the clock since the last read")
        return Verdict.After(minOf(wait, toCeiling))
    }
}
