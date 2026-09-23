package com.cliftonia.fs42tv.tune

import android.util.Log
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
        failedIndex: Int,
        now: Long,
    ): Pair<Int, Playable>? {
        for (step in 1..SKIP_DEAD_CLIPS) {
            if (deps.halted()) return null
            // The wrapped index is what gets returned, because the caller rebuilds the Tuned
            // around it and channel.streams is indexed by the wrapped value, not the raw sum.
            val idx = (failedIndex + step) % channel.streams.size
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
