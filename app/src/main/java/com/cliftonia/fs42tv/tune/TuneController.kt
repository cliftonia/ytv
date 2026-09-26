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
import com.cliftonia.fs42tv.schedule.Timetable
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
        /** What is on a clock channel, with the Settings rows applied. */
        val timetable: Timetable = Timetable.PLAIN,
        /**
         * What a live channel actually plays - Pluto's own route on a session, when PLUTO ROUTE
         * is DIRECT (see `pluto/PlutoRoute`). Blocking, since a session may be fetched, so it is
         * asked here on [executor]. The default is the published url, as before the route.
         */
        val livePlayable: (Tuned) -> Playable = { it.playable },
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
        /**
         * The half-hour schedule has an "up next" card on this channel, not a clip: put it up in
         * place of a picture. [Tuned.card] says until when, and what it announces.
         */
        val card: (Tuned) -> Unit,
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
     * The channel, clip index and scheduled end of the clip that just reported ending, or null.
     *
     * The channel number rides along with the index because an end-of-clip retune can be
     * superseded by a channel change: the marker must only steer the tune of the channel whose
     * clip actually ended, not whatever channel the viewer surfed to next. The scheduled end
     * (half-hour schedule only) tells one SHOWING from the next: a programme that fills its slot
     * airs again at the next boundary with the same index, and that is a new showing to join.
     */
    private class Ended(val channel: Int, val index: Int, val endsAt: Long?)

    @Volatile private var justEnded: Ended? = null

    // When the last tune was asked for. Written on the executor, read when the first frame lands.
    @Volatile private var lastTuneRequestedAt: Long = 0

    /** Resolving the scheduled clip, and walking past dead ones. See [ClipFinder]. */
    private val finder = ClipFinder(deps)

    private val prefetch = NeighbourPrefetch(
        executor = deps.prefetchExecutor,
        resolver = deps.resolver,
        ledger = deps.ledger,
        urls = deps.urls,
        ladder = deps.ladder,
        nowSeconds = deps.nowSeconds,
        halted = deps.halted,
        timetable = deps.timetable,
    )

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

    /**
     * Tune [channel] on a fresh generation, superseding anything in flight. Nothing after the
     * activity is destroyed: a late timer would queue onto an executor already shut down.
     */
    fun tune(channel: Channel) {
        if (deps.halted()) return
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
        justEnded = onAir?.let { Ended(it.channel.number, it.streamIndex, it.endsAt) }
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
                resolveMillis = finder.lastResolveMillis,
                firstFrameMillis = deps.elapsedMillis() - lastTuneRequestedAt,
                fromCache = finder.lastResolveWasCached,
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
        val tuned = Tuner.tune(channel, deps.urls, now, deps.ladder(), deps.ledger.refusedSnapshot(),
            deps.timetable)
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
        var tuned = Tuner.tune(channel, deps.urls, now, deps.ladder(), deps.ledger.refusedSnapshot(),
            deps.timetable)

        // If the timetable hands back the clip that just finished, take what follows it instead
        // - see Tuner.following. Read and cleared unconditionally, honoured only when the
        // channel matches - see [justEnded]'s comment.
        val ended = justEnded?.takeIf { it.channel == channel.number }
        justEnded = null
        if (ended != null && tuned != null && tuned.card == null &&
            tuned.streamIndex == ended.index && tuned.endsAt == ended.endsAt) {
            Log.i("fs42", "timetable still on the finished clip ${ended.index}; taking what follows")
            tuned = Tuner.following(tuned, deps.urls, deps.ladder(), deps.ledger.refusedSnapshot(),
                deps.timetable)
        }

        if (tuned == null) {
            Log.w("fs42", "channel ${channel.number} ${channel.name}: nothing on air")
            postChannelUnavailable(channel, requestGeneration)
            return
        }

        // A card resolves nothing and plays nothing: there is no picture to wait for.
        if (tuned.card != null) {
            Log.i("fs42", "channel ${channel.number} ${channel.name}: up next card until " +
                "${tuned.card.until}, announcing clip ${tuned.streamIndex}")
            postCard(tuned, requestGeneration)
            return
        }

        // Into the Tuned, not just the local: onAir must name the url actually playing, since a
        // failure of that url is how the route learns its session went bad.
        if (channel.kind == "live") tuned = tuned.copy(playable = deps.livePlayable(tuned))
        var playable: Playable = tuned.playable

        // A cached URL that the CDN already refused is worse than no cached URL at all: it will
        // be refused again. Treat it as a miss so the server is asked for a fresh one.
        val tunedId = tuned.stream.id
        if (tunedId != null && deps.ledger.isDead(tunedId) && playable is Progressive) {
            playable = NeedsResolving(tunedId)
        }

        if (playable is NeedsResolving) {
            val videoId = playable.videoId
            // Every rung refused means every resolver answers null - so do not ask. A full
            // extraction just to learn that cost 2.4s of black on every retune of a condemned
            // clip before the skip below ran anyway.
            val resolved = if (deps.ledger.allRungsRefused(videoId, deps.ladder())) {
                Log.i("fs42", "every rung of $videoId is refused; skipping it unresolved")
                null
            } else {
                finder.resolveToPlay(videoId, now)
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
                    channel, now, tuned.streamIndex, tuned.endsAt, avoid = ended?.index)
                val substitute = finder.resolveNextPlayable(channel, candidates, now)
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
                    prefetchNeighbours(channel, requestGeneration)
                }
                deps.screen.paint(
                    finalTuned, finalPlayable, requestedAtMillis, playedSuccessfully,
                    requestGeneration,
                )
            }
        }
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
     * Put a card up, claiming the air the way a painted clip does - behind the same two
     * generation checks, so a card for a channel the viewer already left can never land.
     *
     * The card claims [onAir] because it IS what is on: the recovery re-tune, the banner, the
     * resume pref and the guide's seed all read onAir, and leaving it on the previous channel
     * would point every one of them at a channel the viewer has left. Its own channel joins the
     * prefetch, which for a card resolves the programme it announces - so when the card ends
     * the programme starts from a cache hit.
     */
    private fun postCard(tuned: Tuned, requestGeneration: Int) {
        if (deps.halted()) return
        deps.runOnUi {
            if (deps.halted() || requestGeneration != generation.get()) return@runOnUi
            onAir = tuned
            deps.rememberChannel(tuned.channel.number)
            prefetch.resolveAhead(listOf(tuned.channel)) { generation.get() == requestGeneration }
            prefetchNeighbours(tuned.channel, requestGeneration)
            deps.screen.card(tuned)
        }
    }

    /**
     * With the picture up on [from], get the channels either side of it ready - for as long as
     * [tuneGeneration] is still the current one. Any keypress, retune or overlay moves the
     * generation on, and the tune that follows queues its own neighbours.
     */
    private fun prefetchNeighbours(from: Channel, tuneGeneration: Int) {
        val nav = deps.navigator() ?: return
        prefetch.resolveAhead(listOfNotNull(nav.peekUp(from), nav.peekDown(from))) {
            generation.get() == tuneGeneration
        }
    }
}
