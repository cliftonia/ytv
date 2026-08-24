package com.cliftonia.fs42tv.tune

import android.util.Log
import com.cliftonia.fs42tv.resolver.ClipResolver
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.PlaybackDiagnostics
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.RefusalLedger
import com.cliftonia.fs42tv.resolver.Unplayable
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.UrlCache
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Turns "the viewer wants this channel" into a playing picture, and owns every rule about which
 * of two competing tunes wins.
 *
 * This is the heart of the dial: the generation counter that lets a burst of presses collapse to
 * the last one, the supersede checks on both sides of the executor-to-UI hop, the dead-clip
 * substitution, the neighbour prefetch, and the promise that [onAir] only ever names a Playable
 * that genuinely reached the player. None of it needs an Activity - what it needs from the
 * screen is named in [Screen], and everything Android-shaped arrives as a closure - so the
 * supersede logic is testable on the JVM, where it never was while this lived inside two
 * thousand lines of activity.
 */
class TuneController(private val deps: Deps) {

    class Deps(
        /**
         * Single-threaded, so a rapid burst of channel presses queues in order rather than
         * racing each other over the shared navigator and player. Owned by the activity because
         * the guide and the dial loader share it - ordering against THEM matters too.
         */
        val executor: Executor,
        /**
         * A second thread, for resolving channels nobody has asked for yet. Separate from
         * [executor] on purpose: that one serves the channel the viewer is actually waiting
         * for, and a speculative resolve queued ahead of a real keypress would make surfing
         * slower rather than faster - the exact opposite of why the prefetch exists.
         */
        val prefetchExecutor: Executor,
        val resolver: ClipResolver,
        val ledger: RefusalLedger,
        /**
         * Signed urls published alongside the lineup - always null now, and deliberately still
         * threaded through. The server used to publish a `urls.json` covering about half the
         * dial; nothing publishes it any more, but this is the seam a future pre-resolved cache
         * would fill, and the tier machinery reads null as simply "nothing cached".
         */
        val urls: UrlCache?,
        /** Live, not captured: the quality ceiling can change in settings mid-session. */
        val ladder: () -> List<String>,
        val navigator: () -> DialNavigator?,
        val nowSeconds: () -> Long,
        /** A monotonic millisecond clock - SystemClock.elapsedRealtime on the device. */
        val elapsedMillis: () -> Long,
        /** True once the activity is destroyed; every path checks it before touching anything. */
        val halted: () -> Boolean,
        val runOnUi: (() -> Unit) -> Unit,
        val screen: Screen,
        /** Persists the channel to resume on next launch; only ever called for a genuine success. */
        val rememberChannel: (Int) -> Unit,
    )

    /**
     * What a tune is allowed to do to the screen. Every callback runs on the UI thread, after
     * the controller's own generation and halted checks have passed.
     */
    class Screen(
        /**
         * A channel change has begun: stop the outgoing picture at the source, clear any
         * pending error card, raise the blank, and announce the target on the banner.
         */
        val startBlank: (Channel) -> Unit,
        /**
         * Hand the result to the player. [paint]'s generation argument is the tune's own, for
         * anything downstream - the caption fetch - that must discard itself if superseded.
         */
        val paint: (Tuned, Playable, Long, Boolean, Int) -> Unit,
        /** A definitive failure: nothing on this channel can play right now, and the card says so. */
        val channelUnavailable: (Channel) -> Unit,
    )

    // Bumped on every keypress. A tune captures the current value when queued and abandons
    // itself if the value has since moved on - that is how a burst of presses on the dial
    // collapses to only the last one actually reaching the player, instead of running every
    // intermediate channel to completion.
    private val generation = AtomicInteger(0)

    /**
     * What is actually on air right now, as opposed to where the navigator points. A failed
     * tune leaves the previous picture up with the navigator already moved on, so this is set
     * only on a genuine success. Written on the UI thread beside the play it certifies, read
     * everywhere; `@Volatile` is enough because `Tuned` is immutable.
     */
    @Volatile var onAir: Tuned? = null
        private set

    /**
     * The channel and clip index that just reported ending, or null.
     *
     * The channel number rides along with the index because an end-of-clip retune can be
     * superseded by a channel change: the marker must only steer the tune of the channel whose
     * clip actually ended, not whatever channel the viewer surfed to next.
     */
    @Volatile private var justEnded: Pair<Int, Int>? = null

    // How the last tune spent its time. Written on the executor, read when the first frame lands.
    @Volatile private var lastResolveMillis: Long = 0
    @Volatile private var lastResolveWasCached: Boolean = false
    @Volatile private var lastTuneRequestedAt: Long = 0

    /** The current generation, for callers whose own async work must notice being superseded. */
    fun generationNow(): Int = generation.get()

    /**
     * Invalidate every tune in flight without starting a new one.
     *
     * Opening an overlay is a supersede point: a keypress a moment earlier still has a tune in
     * flight, and letting it land would change the channel under the open list - reproduced on
     * device before this existed, not theorised.
     */
    fun supersede() {
        generation.incrementAndGet()
    }

    /** A deliberate channel change: blank the screen, announce the target, tune it. */
    fun surfTo(target: Channel) {
        deps.screen.startBlank(target)
        val gen = generation.incrementAndGet()
        val requestedAt = deps.elapsedMillis()
        deps.executor.execute { tuneTo(target, gen, requestedAt) }
    }

    /**
     * The first tune after the dial loads, on the CURRENT generation rather than a fresh one:
     * a viewer already pressing keys while the lineup fetched has expressed a newer wish, and
     * this must lose to it.
     *
     * Runs INLINE rather than queueing, and the caller must already be on [Deps.executor] - the
     * dial loader is. Queued, it would land BEHIND a surf queued while the lineup fetched and
     * then pass the same generation check that surf just passed, stealing the screen back for
     * the remembered channel; inline, it runs first and the surf supersedes it.
     */
    fun tuneFirst(channel: Channel, requestedAtMillis: Long) {
        tuneTo(channel, generation.get(), requestedAtMillis)
    }

    /**
     * Re-tune whatever is on air, with a fresh generation.
     *
     * The answer to both silent stops - a finished clip and a rejected URL - because the clock
     * rotation will pick whatever should be on now, which after a finished clip is the next one
     * along.
     */
    fun retuneCurrent(reason: String) {
        if (deps.halted()) return
        val channel = onAir?.channel ?: return
        Log.i("fs42", "re-tuning ${channel.number} ${channel.name} after $reason")
        tune(channel)
    }

    /** Tune [channel] on a fresh generation, superseding anything in flight. */
    fun tune(channel: Channel) {
        val gen = generation.incrementAndGet()
        val at = deps.elapsedMillis()
        deps.executor.execute { tuneTo(channel, gen, at) }
    }

    /**
     * A clip reported ending: remember which one, so the re-tune cannot land back on it.
     *
     * The published duration comes from yt-dlp's metadata; what actually plays is the shorter
     * of the separately-muxed video and audio tracks. Whenever that is less than the published
     * figure, the clip ends while the clock still says it is on - so the rotation returns the
     * SAME index, at an offset a fraction from the end, and the app re-tunes into the programme
     * it just finished. That is the flash of black at roll-over, and with a badly truncated
     * track it repeats.
     */
    fun clipEnded() {
        justEnded = onAir?.let { it.channel.number to it.streamIndex }
        retuneCurrent("clip ended")
    }

    /**
     * The picture arrived: record how long the tune took, split into the two halves that have
     * different fixes - resolving the url, which the neighbour prefetch removes, and everything
     * the player does afterwards, which it cannot touch.
     */
    fun noteFirstFrame() {
        if (lastTuneRequestedAt > 0) {
            PlaybackDiagnostics.recordTune(
                resolveMillis = lastResolveMillis,
                firstFrameMillis = deps.elapsedMillis() - lastTuneRequestedAt,
                fromCache = lastResolveWasCached,
            )
        }
    }

    /**
     * A playable url for [channel] right now - for the guide music, which wants sound but is
     * not a tune: no generation, no banner, no claim on [onAir]. Blocking; call it off the UI
     * thread.
     */
    fun resolveForAudio(channel: Channel): Tuned? {
        val now = deps.nowSeconds()
        val tuned = Tuner.tune(channel, deps.urls, now, deps.ladder(), deps.ledger.refusedSnapshot())
            ?: return null
        val playable = tuned.playable as? NeedsResolving ?: return tuned
        val resolved = deps.ledger.recall(playable.videoId, now)
            ?: deps.resolver.resolveDetailed(
                playable.videoId, now, deps.ladder(), deps.ledger.refusedSnapshot())?.also {
                deps.ledger.remember(playable.videoId, it)
            }?.playable ?: return null
        return tuned.copy(playable = resolved)
    }

    /**
     * Work out what [channel] is showing right now and hand it to the player.
     *
     * [requestGeneration] is checked at the start and again right before the result would reach
     * the player: if a later keypress has since bumped the generation, this tune is superseded
     * and abandons without touching the player, prefs, or [onAir]. That is what lets a burst of
     * presses skip every intermediate channel instead of running each one to completion.
     */
    private fun tuneTo(channel: Channel, requestGeneration: Int, requestedAtMillis: Long) {
        if (requestGeneration != generation.get()) {
            Log.d("fs42", "channel ${channel.number} ${channel.name}: superseded before tuning; abandoning")
            return
        }

        val now = deps.nowSeconds()
        lastTuneRequestedAt = requestedAtMillis
        var tuned = Tuner.tune(channel, deps.urls, now, deps.ladder(), deps.ledger.refusedSnapshot())

        // If the rotation hands back the clip that just finished, take the next one instead.
        // Read and cleared unconditionally, honoured only when the channel matches - see
        // [justEnded]'s comment.
        val je = justEnded
        justEnded = null
        val ended = if (je?.first == channel.number) je.second else -1
        if (ended >= 0 && tuned != null && tuned.streamIndex == ended &&
            channel.streams.size > 1) {
            Log.i("fs42", "rotation still on the finished clip $ended; taking the next")
            val next = (ended + 1) % channel.streams.size
            tuned = Tuner.tuneToIndex(channel, next, deps.ledger.refusedSnapshot())
        }

        if (tuned == null) {
            Log.w("fs42", "channel ${channel.number} ${channel.name}: nothing on air")
            postChannelUnavailable(channel, requestGeneration)
            return
        }

        var playable: Playable = tuned.playable

        // A cached URL that the CDN already refused is worse than no cached URL at all: it will
        // be refused again. Treat it as a miss so the server is asked for a fresh one.
        val tunedId = tuned.stream.id
        if (tunedId != null && deps.ledger.isDead(tunedId) && playable is Progressive) {
            playable = NeedsResolving(tunedId)
        }

        if (playable is NeedsResolving) {
            val videoId = playable.videoId
            val resolveStarted = deps.elapsedMillis()
            val remembered = deps.ledger.recallToPlay(videoId, now)
            if (remembered != null) {
                Log.d("fs42", "resolve hit from cache for $videoId")
                playable = remembered
                lastResolveMillis = deps.elapsedMillis() - resolveStarted
                lastResolveWasCached = true
            } else {
                Log.d("fs42", "resolve miss; extracting $videoId")
                lastResolveWasCached = false
                val resolved = deps.resolver.resolveDetailed(
                    videoId, now, deps.ladder(), deps.ledger.refusedSnapshot())
                lastResolveMillis = deps.elapsedMillis() - resolveStarted
                if (resolved != null) {
                    deps.ledger.rememberPlayed(videoId, resolved)
                    playable = resolved.playable
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
                    val substitute = resolveNextPlayable(channel, tuned.streamIndex, now)
                    if (substitute != null) {
                        val (idx, sub) = substitute
                        // The whole Tuned is rebuilt, not just the playable: the banner, onAir
                        // and the end-of-clip marker all read the identity out of it, and leaving
                        // the dead clip's identity there labelled the substitute as a programme
                        // it is not. Offset zero because a clip that was never scheduled to be on
                        // now has nothing meaningful to seek to.
                        tuned = tuned.copy(
                            streamIndex = idx,
                            stream = channel.streams[idx],
                            playable = sub,
                            offsetSeconds = 0.0,
                        )
                        playable = sub
                    }
                }
            }
        }

        Log.i(
            "fs42",
            "channel ${channel.number} ${channel.name}: clip ${tuned.streamIndex} at " +
                "${tuned.offsetSeconds}s -> ${playable::class.simpleName}",
        )

        // Only a Playable that genuinely reaches the player is a successful tune. A cache miss
        // the server also could not resolve, and anything Unplayable, must leave onAir and the
        // remembered channel as whatever last actually played - otherwise a dead channel becomes
        // what the app reports as on air, and what it resumes on next launch, with no picture
        // and no obvious reason why.
        val playedSuccessfully = when (playable) {
            is Progressive, is Hls -> true
            is NeedsResolving, is Unplayable -> false
        }

        if (requestGeneration != generation.get()) {
            Log.d("fs42", "channel ${channel.number} ${channel.name}: superseded before posting; abandoning")
            return
        }

        // A tune that failed outright must say so. The blank is already up and only a first
        // frame ever clears it, so without the card this is a permanently black, muted channel
        // indistinguishable from a dead remote. The card draws above the blank; this is a
        // definitive failure, not a grace-period case.
        if (!playedSuccessfully) postChannelUnavailable(channel, requestGeneration)

        // NeedsResolving here means the resolve above also failed: play nothing and leave
        // whatever was already on screen rather than blanking it. The halted check guards
        // against a tune completing after the activity is gone.
        if (playable !is NeedsResolving && !deps.halted()) {
            val finalTuned = tuned
            val finalPlayable = playable
            deps.runOnUi {
                // The generation is re-checked HERE, not only on the executor. The UI queue runs
                // behind whatever the main thread is already doing, so a tune that was current
                // when it posted can run after a newer keypress has already moved the dial -
                // snapping the picture and banner back to a channel the viewer surfed past, and
                // leaving it there if the newer tune then fails to resolve.
                if (requestGeneration != generation.get()) {
                    Log.d("fs42", "channel ${channel.number}: superseded before painting; abandoning")
                    return@runOnUi
                }
                if (deps.halted()) return@runOnUi
                // The commit lives HERE, behind the authoritative generation check and next to
                // the play it certifies - not on the executor side of the hop. Committed there,
                // a tune superseded in the hop window still claimed to be on air, and if the
                // superseding tune then failed, the picker seed, the resume pref and every
                // recovery re-tune all pointed at a channel whose picture never reached the
                // screen. Before paint, because the player callbacks read onAir and the field
                // must be fresh before playback can emit its first event.
                if (playedSuccessfully) {
                    onAir = finalTuned
                    deps.rememberChannel(channel.number)
                    // With the picture up, get the neighbours ready. Surfing is overwhelmingly
                    // up and down one at a time, and the next press is usually a second or two
                    // away - exactly long enough to have resolved where it is going.
                    prefetchNeighbours(channel)
                }
                deps.screen.paint(
                    finalTuned, finalPlayable, requestedAtMillis, playedSuccessfully,
                    requestGeneration,
                )
            }
        }
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
    private fun resolveNextPlayable(
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

    /**
     * Raise the stand-by card for a channel that definitively failed to tune.
     *
     * The generation check runs INSIDE the runnable, matching the paint path: the UI queue runs
     * behind main-thread work, so a check done on the executor can pass and then a keypress can
     * move the dial before the runnable executes - after which a stale card names a channel the
     * viewer already left, painted over the new tune.
     */
    private fun postChannelUnavailable(channel: Channel, requestGeneration: Int) {
        if (deps.halted()) return
        deps.runOnUi {
            if (!deps.halted() && requestGeneration == generation.get()) {
                deps.screen.channelUnavailable(channel)
            }
        }
    }

    /**
     * Resolve what is on the channels either side, so pressing up or down is instant.
     *
     * This is what replaced the server's `urls.json`. That file carried signed urls for about
     * half the dial and made those tunes immediate; it could not survive the server going away,
     * because googlevideo signs urls for about six hours and a nightly file would be dead by
     * morning. So the work moved here, to the moment it is actually predictive: the viewer is
     * watching something, and the overwhelmingly likely next press is one channel up or down.
     *
     * On its own thread, so it can never delay a real tune - a prefetch in progress when the
     * viewer presses a button is simply abandoned mid-flight and its result discarded or, if it
     * finishes anyway, kept in the cache where the next press will find it.
     *
     * Costs one metadata extraction per neighbour and downloads no media at all.
     */
    private fun prefetchNeighbours(from: Channel) {
        val nav = deps.navigator() ?: return
        val around = listOfNotNull(nav.peekUp(from), nav.peekDown(from))
        for (channel in around) {
            deps.prefetchExecutor.execute {
                if (deps.halted()) return@execute
                val now = deps.nowSeconds()
                val tuned = Tuner.tune(
                    channel, deps.urls, now, deps.ladder(), deps.ledger.refusedSnapshot())
                    ?: return@execute
                val id = (tuned.playable as? NeedsResolving)?.videoId ?: return@execute
                if (deps.ledger.isDead(id) || deps.ledger.recall(id, now) != null) return@execute
                val resolved = deps.resolver.resolveDetailed(
                    id, now, deps.ladder(), deps.ledger.refusedSnapshot())
                if (resolved != null && !deps.halted()) {
                    deps.ledger.remember(id, resolved)
                    Log.d("fs42", "prefetched channel ${channel.number} ${channel.name}")
                }
            }
        }
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
