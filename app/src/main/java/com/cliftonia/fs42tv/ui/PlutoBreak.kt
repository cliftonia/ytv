package com.cliftonia.fs42tv.ui

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.cliftonia.fs42tv.player.ChannelPlayback
import com.cliftonia.fs42tv.pluto.BreakPoller
import com.cliftonia.fs42tv.pluto.BreakReturn
import com.cliftonia.fs42tv.pluto.BreakView
import com.cliftonia.fs42tv.pluto.OnScreen
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.tune.Tuned

/**
 * Pluto's ad breaks, turned into the station's own "we'll be right back" card.
 *
 * Pluto has no ads to sell to Australia, so every ~10-20 minutes a channel plays one to three
 * minutes of its logo bumper instead. [BreakPoller] reads the playlist and says when; this owns
 * what the viewer gets instead: the card, the guide's music under it, and the programme's own
 * audio down while it is up (the director's volume rule reads [muting]).
 *
 * Polling runs only while a Pluto-dial channel is on air - from the load ([loading], whose read
 * anchors mpv's clock) until anything else happens to the screen: a surf, an error, a card, the
 * app leaving ([leave]). The card acts only once there is a picture ([playing]). While the guide
 * or settings is up the card is hidden, not ended - the poller keeps reading, and the card is
 * back the moment the overlay closes if the break is still on.
 *
 * TIMING. The first version put the card up when the playlist's edge said "bumper", and the owner
 * watched Pluto's logo for seconds before it and again after it: the player is ~15s behind the
 * edge. Now the card goes up exactly when the instant on screen ([OnScreen]) reaches the break's
 * start and down when it reaches the end, both read from the playlist's PROGRAM-DATE-TIME
 * ([BreakView]), timed on the main thread and re-timed on every read. A playlist without
 * timestamps falls back to the two-read rule, acted on at once.
 *
 * Unlike the up-next card, the player keeps playing underneath: the bumper IS a picture, so the
 * watchdog has nothing to wait for, and the programme's return needs no tune at all.
 *
 * COMMERCIALS (the BREAK ADS row, [BreakAds]). When a reel can be had, the break's start hands the
 * player a reel of vintage Australian commercials instead: the card shows only while it loads, or
 * for the rest of the break if it fails. Pluto is then not playing at all, so the break's clock
 * carries on from the last instant on screen by wall time ([OnScreen.continued]), and the return
 * is a TUNE, timed so that it lands on the programme ([BreakReturn]) - [Deps.retune].
 *
 * Main thread only, except the poller's reads, which are posted back through [Deps.runOnUi] and
 * dropped if the run they belong to has since been stopped.
 */
class PlutoBreak(private val deps: Deps) {

    class Deps(
        /** The BREAK CARD row, read on every [loading]. OFF: no polling, no card. */
        val enabled: () -> Boolean,
        /** Builds the poller around the read callback - a seam for a hand-cranked clock. */
        val poller: (read: (BreakPoller.Run, BreakView) -> Unit) -> BreakPoller,
        /** Runs a block on the main thread after a delay; returns what cancels it. */
        val later: (delayMillis: Long, block: () -> Unit) -> (() -> Unit),
        /** Wall clock, the clock the poller stamps its reads in. */
        val wallMillis: () -> Long,
        /** Monotonic (elapsedRealtime): playing time, and the countdown's deadline. */
        val elapsedMillis: () -> Long,
        /** The engine's exact PROGRAM-DATE-TIME on screen, when it knows it - Media3. */
        val exactInstant: () -> Long?,
        /** The engine starts live at ffmpeg's third-from-last segment - mpv. */
        val joinsThirdFromLast: () -> Boolean,
        val runOnUi: (() -> Unit) -> Unit,
        val halted: () -> Boolean,
        /** The guide owns the music while it is up; the card must not release it then. */
        val guideOpen: () -> Boolean,
        /** The guide or settings is up: the card is hidden under it. */
        val overlayOpen: () -> Boolean,
        val stoppedNow: () -> Boolean,
        /** Starts the shared music for as long as [wanted] says - [GuideMusic.play]. */
        val playMusic: (wanted: () -> Boolean) -> Unit,
        val releaseMusic: () -> Unit,
        /** What is on [Channel] now from Pluto's guide, cache first; [onUpdate] when it arrives. */
        val nowTitle: (Channel, onUpdate: () -> Unit) -> String?,
        /** The programme's volume must be re-derived - [ScreenDirector.updateProgrammeVolume]. */
        val volumeChanged: () -> Unit,
        /** The card is up: stand down the stall pill and anything else waiting on a picture. */
        val picture: () -> Unit,
        /** The card came down: whatever it stood down may resume - a stall still going. */
        val uncovered: () -> Unit = {},
        /** On destroy, after polling stops: the poller's thread. */
        val shutdown: () -> Unit = {},
        /** The commercials, built around their change callback; null for none - the card only. */
        val ads: (changed: () -> Unit) -> BreakAds? = { null },
        /**
         * Back to [Tuned]'s channel from a reel: the player is on the archive, so the programme's
         * return is a tune - the blank, then the channel, as a channel change.
         */
        val retune: (Tuned) -> Unit = {},
    )

    /** What the overlay draws: null when no break, or when an overlay is over it. Compose state. */
    val state = mutableStateOf<BreakCardState?>(null)

    /** In a break on the channel playing - drawn or not. The programme is silent while true. */
    var inBreak = false
        private set

    /** Silent while the card is up - not while commercials play: they are the sound now. */
    val muting: Boolean get() = inBreak && ads?.picture != true

    /** The break's commercials, when the row and a catalog allow. */
    val ads: BreakAds? = deps.ads(::adsChanged)

    /** The player is on a reel (or parked after one), not on Pluto: a surf or a return must tune. */
    val adsOnPlayer: Boolean get() = ads?.onPlayer == true

    /** The commercials' own level while they are what is heard; null otherwise. */
    val adsGain: Float? get() = ads?.takeIf { it.picture }?.gain

    val showing: Boolean get() = state.value != null

    /** The tune being polled. */
    private var tuned: Tuned? = null

    /** The latest read's account of the break. */
    private var view: BreakView? = null

    /** Wall clock when the stream was handed to the player - the mpv anchor's other half. */
    private var loadedAt = 0L

    /**
     * The poll began with the player's own load, so mpv's anchor holds. Not after a resume from
     * the home screen: the stream was paused, not reloaded, and the edge estimate is all there is.
     */
    private var anchored = false

    /** Elapsed time of the first frame; null until there is a picture. */
    private var pictureAt: Long? = null

    /** Time stalled since the first frame, and when the stall going now began. */
    private var stalledMillis = 0L
    private var stalledSince: Long? = null

    /** The countdown to the break's end, held once known so the border does not jitter. */
    private var countdown: BreakBorder.Countdown? = null

    private var cancelTimer: (() -> Unit)? = null

    /**
     * The instant on screen when a reel took the player, and the monotonic time then: from here
     * the break's clock runs on wall time ([OnScreen.continued]). Null while Pluto is playing.
     */
    private var continuedFrom: Pair<Long, Long>? = null

    /** The app left with a reel on the player: coming back must tune the channel, on any engine. */
    private var leftOnAd = false

    private val poller: BreakPoller = deps.poller { run, read ->
        deps.runOnUi {
            if (!deps.halted() && poller.isCurrent(run)) {
                view = read
                reschedule()
            }
        }
    }

    /**
     * [tuned] was just handed to the player: poll it afresh, if it is a Pluto channel and the row
     * is on. Always a new run, even on the same url - a watchdog retune, an abandoned tune
     * recovered, a reload after the stream ended - because the player has started again from a
     * new point in the window: the old anchor, playing time and any stall open under it are
     * about a stream that no longer exists, and kept they put the card up early or froze it.
     */
    fun loading(tuned: Tuned?) {
        poll(tuned, anchored = true, fresh = true)
        // Whatever was loaded replaced any reel left on the player: its events are the new file's.
        ads?.stop()
        leftOnAd = false
    }

    /** Set by [holdAcrossReload]; spent by the next load, or by anything else leaving. */
    private var holdCard = false

    /**
     * The next load is a reload of the channel on air - StallRecovery's, mpv lost at a
     * discontinuity, which is typically the bumper's own. If a break is on, the card and the
     * programme's silence stay up across it, rather than a flash of Pluto's logo at full volume
     * between two cards; the reload's own reads and first frame then decide as always.
     */
    fun holdAcrossReload() {
        holdCard = inBreak
    }

    /** Between onStop and onResume - a resume from a dialog that only paused is not a return. */
    private var wasStopped = false

    /** The app left the screen: nobody is watching, so no reads; the break is seen afresh after. */
    fun appStopped() {
        val onAd = adsOnPlayer
        leave()
        if (onAd) leftOnAd = true
        wasStopped = true
    }

    /**
     * Called on every resume: whether it should re-tune [tuned] rather than carry on - back from
     * the home screen (not from a dialog that only paused), nothing open over the dial, the row
     * on, a Pluto channel, and an engine timed from its load (mpv). A paused mpv resumes an
     * unknown distance behind the edge - or back at the window's start after half a minute -
     * and a live channel's re-tune is cheap and anchors afresh.
     *
     * And on any engine, overlay or not, when the app left with a break's reel on the player: the
     * paused file under the dial is a commercial, not the channel, and must never simply resume.
     */
    fun retuneOnResume(tuned: Tuned?): Boolean {
        val returning = wasStopped
        wasStopped = false
        val live = tuned != null && tuned.card == null && tuned.channel.pluto != null && tuned.playable is Hls
        if (returning && live && leftOnAd) return true
        return returning && live && !deps.overlayOpen() && deps.enabled() && deps.joinsThirdFromLast()
    }

    /**
     * BREAK CARD switched off - or BREAK ADS, with a reel on the player: stop, and if the player
     * was on a reel, tune the channel back (the fresh load decides the break again, by the rows).
     */
    fun switchedOff() {
        val back = tuned?.takeIf { adsOnPlayer }
        leave()
        back?.let(deps.retune)
    }

    private fun poll(tuned: Tuned?, anchored: Boolean, fresh: Boolean) {
        val hls = tuned?.playable as? Hls
        val url = hls?.url
        if (tuned == null || url == null || !deps.enabled() || tuned.card != null ||
            tuned.channel.pluto == null || deps.stoppedNow()) {
            leave()
            return
        }
        // Only a second first frame on the stream already polled keeps what the poll has seen.
        if (!fresh && poller.pollingUrl == url && this.tuned?.channel?.number == tuned.channel.number) return
        leave(keepCard = holdCard && this.tuned?.channel?.number == tuned.channel.number)
        this.tuned = tuned
        loadedAt = deps.wallMillis()
        this.anchored = anchored
        // The playlist mpv was handed, when the tune chose one: one fetch fewer per tune, and the
        // anchor is read off exactly the window mpv started in.
        poller.start(url, hls?.mediaUrl)
        ads?.warm()
    }

    /** [tuned] has a picture: the card may act, and mpv's playing time starts now. */
    fun playing(tuned: Tuned?) {
        poll(tuned, anchored = false, fresh = false)
        if (this.tuned == null) return
        if (pictureAt == null) pictureAt = deps.elapsedMillis()
        reschedule()
    }

    /** The player stalled or recovered: a stall does not move the picture on. */
    fun buffering(stalled: Boolean) {
        if (continuedFrom != null) return
        val now = deps.elapsedMillis()
        val since = stalledSince
        if (stalled && since == null && pictureAt != null) stalledSince = now
        if (!stalled && since != null) {
            stalledMillis += now - since
            stalledSince = null
            reschedule()
        }
    }

    /** Anything else took the screen: stop polling, and take the card and its music down. */
    fun leave(keepCard: Boolean = false) {
        holdCard = false
        poller.stop()
        cancelTimer?.invoke()
        cancelTimer = null
        tuned = null
        view = null
        pictureAt = null
        stalledMillis = 0L
        stalledSince = null
        // A reel on the player is the caller's to replace: every path here is followed by a load
        // (a surf, the blank, an error's retune) - or it is the app leaving, see [appStopped] - and
        // until that load the reel's events stay the reel's ([BreakAds.Stage.RETIRING]).
        ads?.retire()
        continuedFrom = null
        if (inBreak && !keepCard) endBreak()
    }

    /** An overlay opened or closed: hide or restore the card. Cheap; called on every change. */
    fun refresh() {
        val on = tuned
        val next = if (inBreak && on != null && !deps.overlayOpen() && ads?.picture != true) card(on) else null
        if (state.value != next) state.value = next
    }

    /**
     * The card's music, if a break is on and nothing else owns the music - after the break is
     * seen, after the guide closes over it, and when the app comes back into view.
     */
    fun resumeMusic() {
        if (!inBreak || deps.guideOpen() || deps.stoppedNow() || adsOwnTheScreen()) return
        deps.playMusic { inBreak && !deps.guideOpen() && !deps.stoppedNow() && !adsOwnTheScreen() }
    }

    /** On destroy: no read may start, and no verdict land, after this. */
    fun release() {
        leave()
        deps.shutdown()
    }

    /**
     * Decide now, from the latest read and the instant on screen, and time the next change. Runs
     * on every read, at every timed change, and when a stall ends.
     */
    private fun reschedule() {
        cancelTimer?.invoke()
        cancelTimer = null
        val v = view ?: return
        if (pictureAt == null || tuned == null) return
        continuedFrom?.let { (at, since) ->
            returnFromAds(v, OnScreen.continued(at, since, deps.elapsedMillis()))
            return
        }
        val onScreen = if (v.timed) onScreenNow(v) else null
        if (onScreen == null) {
            apply(v.fallbackInBreak && !v.blind, null)
            return
        }
        val decision = v.at(onScreen)
        val end = v.end
        apply(decision.inBreak, if (decision.inBreak && end != null) end - onScreen else null,
            v.start?.let { it to onScreen })
        decision.nextChangeAt?.let { at ->
            cancelTimer = deps.later((at - onScreen).coerceAtLeast(0L)) { reschedule() }
        }
    }

    private fun onScreenNow(v: BreakView): Long? {
        val now = deps.elapsedMillis()
        val playing = pictureAt?.let { at ->
            now - at - stalledMillis - (stalledSince?.let { now - it } ?: 0L)
        }
        return OnScreen.now(deps.exactInstant(), anchored && deps.joinsThirdFromLast(), v, loadedAt,
            playing, deps.wallMillis())
    }

    /**
     * The player is on a reel: time the return to the programme (see [BreakReturn]), keeping the
     * card's countdown right in case the reel has failed and the card is what is showing.
     */
    private fun returnFromAds(v: BreakView, onScreen: Long) {
        val t = tuned ?: return
        val joins = BreakReturn.joinOffsetMillis(v, deps.joinsThirdFromLast())
        when (val verdict = BreakReturn.decide(v, onScreen, deps.wallMillis(), joins)) {
            is BreakReturn.Verdict.Now -> {
                Log.i("fs42", "break ads over on ${t.channel.number}: ${verdict.reason} - re-tuning")
                // The director's blank leaves this break (and the reel) before the tune.
                deps.retune(t)
            }
            is BreakReturn.Verdict.After -> {
                apply(true, v.end?.let { it - onScreen })
                cancelTimer = deps.later(verdict.millis) { reschedule() }
            }
        }
    }

    /** A reel is on screen or on its way: no card music, and (on screen) no card. */
    private fun adsOwnTheScreen(): Boolean = ads?.picture == true || ads?.loading == true

    /** The reel showed its picture, or failed: re-derive the card, the sound and the music. */
    private fun adsChanged() {
        refresh()
        deps.volumeChanged()
        if (ads?.picture == true) {
            if (!deps.guideOpen()) deps.releaseMusic()
        } else {
            resumeMusic()
        }
    }

    /**
     * Up or down; [endsInMillis] of on-screen time until the break's end, when it is known.
     * [startedAt] - the break's start and the instant on screen, on a timed playlist - lets a
     * break coming up hand the player to the commercials; null never does.
     */
    private fun apply(on: Boolean, endsInMillis: Long?, startedAt: Pair<Long, Long>? = null) {
        val t = tuned ?: return
        if (on && endsInMillis != null) {
            val now = deps.elapsedMillis()
            val until = now + endsInMillis
            val held = countdown
            if (held == null || kotlin.math.abs(held.untilMillis - until) > COUNTDOWN_SLACK_MILLIS) {
                countdown = BreakBorder.Countdown(held?.fromMillis ?: now, until)
                if (inBreak) refresh()
            }
        }
        if (on && !inBreak) {
            inBreak = true
            if (startedAt != null && ads?.start(t.channel.number, startedAt.first) == true) {
                continuedFrom = startedAt.second to deps.elapsedMillis()
                // Pluto is no longer what plays: its stall clock means nothing from here.
                stalledSince = null
            }
            Log.i("fs42", "pluto break on ${t.channel.number}: ${if (adsOnPlayer) "commercials" else "card up"}")
            deps.picture()
            refresh()
            deps.volumeChanged()
            resumeMusic()
        } else if (!on && inBreak) {
            Log.i("fs42", "pluto break over on ${t.channel.number}")
            endBreak()
        }
    }

    private fun endBreak() {
        inBreak = false
        countdown = null
        refresh()
        deps.uncovered()
        // Under the guide the music is the guide's, and it keeps it.
        if (!deps.guideOpen()) deps.releaseMusic()
        deps.volumeChanged()
    }

    private fun card(on: Tuned): BreakCardState {
        val title = deps.nowTitle(on.channel) { if (tuned === on) refresh() }
        return BreakCardState(
            channelLine = ChannelLabels.bannerLines(on).first,
            backTo = title?.trim()?.takeIf { it.isNotEmpty() }?.let { "BACK TO: $it" }.orEmpty(),
            border = countdown ?: BreakBorder.Pulse,
            // The card's words only when there are no commercials: a failed reel re-derives it.
            blank = ads?.loading == true,
        )
    }

    companion object {
        /** A re-read moving the end by less than this leaves the countdown as it is. */
        const val COUNTDOWN_SLACK_MILLIS = 750L

        /** Construction in one call for the director, which is at its size limit. */
        fun create(
            extras: ScreenExtras,
            music: GuideMusic,
            channels: () -> List<Channel>,
            player: () -> ChannelPlayback?,
            runOnUi: (() -> Unit) -> Unit,
            halted: () -> Boolean,
            guideOpen: () -> Boolean,
            overlayOpen: () -> Boolean,
            stoppedNow: () -> Boolean,
            volumeChanged: () -> Unit,
            picture: () -> Unit,
            uncovered: () -> Unit,
            /** Back to a channel from its break's reel - see [Deps.retune]. */
            retune: (Tuned) -> Unit,
        ): PlutoBreak {
            // Its own daemon thread, not the prefetch thread: a read can take its full five
            // seconds of timeouts, and neighbour resolves and the guide's fetches queue there.
            val executor = BreakPoller.daemonExecutor()
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val later: (Long, () -> Unit) -> (() -> Unit) = { delay, block ->
                val runnable = Runnable { if (!halted()) block() }
                main.postDelayed(runnable, delay)
                ({ main.removeCallbacks(runnable) })
            }
            return PlutoBreak(Deps(
                enabled = { extras.features.isOn(Features.Flag.BREAK_CARD) },
                poller = { read ->
                    BreakPoller(BreakPoller::httpFetch, BreakPoller.scheduleOn(executor), read)
                },
                later = later,
                wallMillis = System::currentTimeMillis,
                elapsedMillis = android.os.SystemClock::elapsedRealtime,
                exactInstant = { player()?.programDateTimeMillis() },
                joinsThirdFromLast = { player()?.joinsLiveAtThirdFromLast == true },
                runOnUi = runOnUi,
                halted = halted,
                guideOpen = guideOpen,
                overlayOpen = overlayOpen,
                stoppedNow = stoppedNow,
                // The music on a Pluto channel is already on the 'beside' session: GuideMusic
                // resolves through ScreenExtras.besideTuned, never the dial's token.
                playMusic = { wanted -> music.play(channels(), wanted) },
                releaseMusic = music::release,
                nowTitle = { channel, onUpdate -> extras.bannerLines(channel, onUpdate)?.first },
                volumeChanged = volumeChanged,
                picture = picture,
                uncovered = uncovered,
                shutdown = { executor.shutdownNow() },
                ads = { changed -> BreakAds.create(extras, player, later, changed) },
                retune = retune,
            ))
        }
    }
}
