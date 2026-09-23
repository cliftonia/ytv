package com.cliftonia.fs42tv.tune

import android.util.Log
import com.cliftonia.fs42tv.resolver.ClipResolver
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.RefusalLedger
import com.cliftonia.fs42tv.schedule.Timetable
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.UrlCache
import java.util.concurrent.Executor

/**
 * Resolve what is on the channels either side, so pressing up or down is instant.
 *
 * This is what replaced the server's `urls.json`. That file carried signed urls for about half
 * the dial and made those tunes immediate; it could not survive the server going away, because
 * googlevideo signs urls for about six hours and a nightly file would be dead by morning. So the
 * work moved here, to the moment it is actually predictive: the viewer is watching something,
 * and the overwhelmingly likely next press is one channel up or down.
 *
 * On its own thread, so it can never delay a real tune - a prefetch in progress when the viewer
 * presses a button is simply abandoned mid-flight and its result discarded or, if it finishes
 * anyway, kept in the cache where the next press will find it.
 *
 * Costs one metadata extraction per neighbour and downloads no media at all. Its own unit
 * rather than part of [TuneController] only so that file stays one idea long.
 */
class NeighbourPrefetch(
    /** The speculative thread - never the tune executor; see [TuneController.Deps.prefetchExecutor]. */
    private val executor: Executor,
    private val resolver: ClipResolver,
    private val ledger: RefusalLedger,
    private val urls: UrlCache?,
    private val ladder: () -> List<String>,
    private val nowSeconds: () -> Long,
    private val halted: () -> Boolean,
    /** So the prefetch resolves the clip the tuner will actually ask for. */
    private val timetable: Timetable,
) {

    /**
     * Queue a resolve of what is on air now on each of [channels], each abandoned unstarted
     * once [stillWanted] says the viewer has moved on.
     *
     * The check is what keeps a prediction from outliving what it predicted. Every channel that
     * paints queues two resolves, and this thread works through them one extraction at a time -
     * 2.4s each on the device - so a steady run of presses left it resolving the neighbours of
     * channels the viewer left long ago, while the neighbours of the channel they are actually
     * on waited at the back of the queue. The resolve already in progress cannot be stopped,
     * but everything behind it can be skipped for free.
     */
    fun resolveAhead(channels: List<Channel>, stillWanted: () -> Boolean) {
        for (channel in channels) {
            executor.execute {
                if (halted()) return@execute
                if (!stillWanted()) {
                    Log.d("fs42", "prefetch of ${channel.number} is stale; skipping")
                    return@execute
                }
                val now = nowSeconds()
                val tuned = Tuner.tune(channel, urls, now, ladder(), ledger.refusedSnapshot(), timetable)
                    ?: return@execute
                val id = (tuned.playable as? NeedsResolving)?.videoId ?: return@execute
                if (ledger.isDead(id) || ledger.recall(id, now) != null) return@execute
                val resolved = resolver.resolveDetailed(id, now, ladder(), ledger.refusedSnapshot())
                if (resolved != null && !halted()) {
                    ledger.remember(id, resolved)
                    Log.d("fs42", "prefetched channel ${channel.number} ${channel.name}")
                }
            }
        }
    }
}
