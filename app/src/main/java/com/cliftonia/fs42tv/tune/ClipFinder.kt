package com.cliftonia.fs42tv.tune

import android.util.Log
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.sync.Channel

/**
 * The two resolves a tune can make: the scheduled clip's url, and - when that clip is dead - the
 * walk forward to the next clip that plays.
 *
 * Out of [TuneController] only so that file stays one idea: the generation rules. Everything
 * here runs on the tune executor, inside a tune that has already passed its first supersede
 * check, and reports back through its return value alone.
 */
internal class ClipFinder(private val deps: TuneController.Deps) {

    // How the last resolve spent its time. Written on the executor, read at the first frame.
    @Volatile var lastResolveMillis: Long = 0
        private set
    @Volatile var lastResolveWasCached: Boolean = false
        private set

    /** The scheduled clip's url - from the cache when it holds one, extracted otherwise. */
    fun resolveToPlay(videoId: String, now: Long): Progressive? {
        val resolveStarted = deps.elapsedMillis()
        val remembered = deps.ledger.recallToPlay(videoId, now)
        lastResolveWasCached = remembered != null
        if (remembered != null) {
            Log.d("fs42", "resolve hit from cache for $videoId")
            lastResolveMillis = deps.elapsedMillis() - resolveStarted
            return remembered
        }
        Log.d("fs42", "resolve miss; extracting $videoId")
        val resolved = deps.resolver.resolveDetailed(
            videoId, now, deps.ladder(), deps.ledger.refusedSnapshot())
        lastResolveMillis = deps.elapsedMillis() - resolveStarted
        resolved?.let { deps.ledger.rememberPlayed(videoId, it) }
        return resolved?.playable
    }

    /**
     * Walk forward through a channel's clips until one resolves.
     *
     * Bounded, and deliberately not the whole list: each attempt is a full extraction of several
     * seconds, so trying a hundred would leave the viewer staring at black for minutes while the
     * app worked - far worse than admitting defeat and putting a card up. A handful covers the
     * ordinary case, which is one or two dead clips in a row, and a channel where even that many
     * consecutive clips are dead has a real problem worth showing.
     */
    fun resolveNextPlayable(
        channel: Channel,
        /** Which clips to try, in order - see Timetable.substitutes. */
        candidates: List<Int>,
        now: Long,
    ): Pair<Int, Playable>? {
        for ((position, idx) in candidates.take(SKIP_DEAD_CLIPS).withIndex()) {
            val step = position + 1
            if (deps.halted()) return null
            val next = channel.streams.getOrNull(idx) ?: return null
            val id = next.id ?: continue
            if (deps.ledger.isDead(id)) continue
            deps.ledger.recallToPlay(id, now)?.let {
                Log.i("fs42", "skipped to clip $idx (cached)")
                return idx to it
            }
            val resolved = deps.resolver.resolveDetailed(
                id, now, deps.ladder(), deps.ledger.refusedSnapshot())
            if (resolved != null) {
                deps.ledger.rememberPlayed(id, resolved)
                Log.i("fs42", "skipped to clip $idx after $step dead clip(s)")
                return idx to resolved.playable
            }
            // Remember it so the next tune of this channel does not pay for it again.
            deps.ledger.markDead(id)
        }
        Log.w("fs42", "channel ${channel.number}: $SKIP_DEAD_CLIPS consecutive clips unplayable")
        return null
    }

    /**
     * [unresolved] - the clip [scheduled] names - made playable: its own url, or, when it is dead,
     * the next clip on [channel] that resolves, as a rebuilt [Tuned]. [avoid] is the clip that
     * just ended, which the walk must not land back on. Hands back [scheduled] and [unresolved]
     * unchanged when nothing resolves, for the tune to report as such.
     *
     * The tune's own step, moved here whole from [TuneController] so that file stays the
     * generation rules; it runs inside the tune, between its two supersede checks.
     */
    fun resolveOrSubstitute(
        channel: Channel,
        scheduled: Tuned,
        unresolved: NeedsResolving,
        now: Long,
        avoid: Int?,
    ): Pair<Tuned, Playable> {
        var tuned = scheduled
        var playable: Playable = unresolved
        val videoId = unresolved.videoId
        // Every rung refused means every resolver answers null - so do not ask. A full
        // extraction just to learn that cost 2.4s of black on every retune of a condemned
        // clip before the skip below ran anyway.
        val resolved = if (deps.ledger.allRungsRefused(videoId, deps.ladder())) {
            Log.i("fs42", "every rung of $videoId is refused; skipping it unresolved")
            null
        } else {
            resolveToPlay(videoId, now)
        }
        if (resolved != null) {
            playable = resolved
        } else {
            // Try the NEXT clips in the rotation rather than giving up on the channel.
            //
            // "Leaving the current picture up" was never what happened. Arriving here
            // from a channel change, the previous picture has already been torn down and
            // the black tuning card raised - and that card is only ever cleared by a
            // first frame, which is now never coming. So the channel sat black and silent
            // with no error and no retry until the clock rolled past the clip, which on a
            // documentary channel is ninety minutes. It read as a dead remote.
            //
            // Dead clips are ordinary: the lineup is built nightly and videos are removed,
            // made private or geo-blocked between then and airtime, and a finished
            // livestream offers no progressive rendition at all. A television skips to
            // what it CAN show.
            Log.w("fs42", "channel ${channel.number} ${channel.name}: could not resolve " +
                "$videoId; trying the next clips")
            val candidates = deps.timetable.substitutes(
                channel, now, tuned.streamIndex, tuned.endsAt, avoid = avoid)
            val substitute = resolveNextPlayable(channel, candidates, now)
            if (substitute != null) {
                val (idx, sub) = substitute
                // The whole Tuned is rebuilt, not just the playable: the banner, onAir
                // and the end-of-clip marker all read the identity out of it, and leaving
                // the dead clip's identity there labelled the substitute as a programme
                // it is not. From its beginning because a clip that was never scheduled to be
                // on now has nothing meaningful to seek to; no cut, since it is not the
                // programme the schedule cuts.
                tuned = tuned.copy(
                    streamIndex = idx,
                    stream = channel.streams[idx],
                    playable = sub,
                    offsetSeconds = deps.timetable.startOffset(channel.streams[idx]),
                    cutAt = null,
                    endsAt = deps.timetable.substituteEndsAt(channel, now, idx, tuned.endsAt),
                )
                playable = sub
            }
        }
        return tuned to playable
    }

    private companion object {
        /**
         * How many clips past the scheduled one a tune will try before admitting defeat.
         *
         * Each attempt is a full extraction of several seconds, so the bound is what keeps a
         * channel full of dead clips from looking like a hung television. See
         * [resolveNextPlayable].
         */
        const val SKIP_DEAD_CLIPS = 3
    }
}
