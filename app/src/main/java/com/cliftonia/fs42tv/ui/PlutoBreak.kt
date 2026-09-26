package com.cliftonia.fs42tv.ui

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.cliftonia.fs42tv.player.ChannelPlayback
import com.cliftonia.fs42tv.pluto.BreakPoller
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
    )

    /** What the overlay draws: null when no break, or when an overlay is over it. Compose state. */
    val state = mutableStateOf<BreakCardState?>(null)

    /** In a break on the channel playing - drawn or not. The programme is silent while true. */
    var inBreak = false
        private set

    val muting: Boolean get() = inBreak

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
    fun loading(tuned: Tuned?) = poll(tuned, anchored = true, fresh = true)

    /** Between onStop and onResume - a resume from a dialog that only paused is not a return. */
    private var wasStopped = false

    /** The app left the screen: nobody is watching, so no reads; the break is seen afresh after. */
    fun appStopped() {
        leave()
        wasStopped = true
    }

    /**
     * Called on every resume: whether it should re-tune [tuned] rather than carry on - back from
     * the home screen (not from a dialog that only paused), nothing open over the dial, the row
     * on, a Pluto channel, and an engine timed from its load (mpv). A paused mpv resumes an
     * unknown distance behind the edge - or back at the window's start after half a minute -
     * and a live channel's re-tune is cheap and anchors afresh.
     */
    fun retuneOnResume(tuned: Tuned?): Boolean {
        val returning = wasStopped
        wasStopped = false
        return returning && !deps.overlayOpen() && deps.enabled() && tuned != null &&
            tuned.card == null && tuned.channel.pluto != null && tuned.playable is Hls &&
            deps.joinsThirdFromLast()
    }

    private fun poll(tuned: Tuned?, anchored: Boolean, fresh: Boolean) {
        val url = (tuned?.playable as? Hls)?.url
        if (tuned == null || url == null || !deps.enabled() || tuned.card != null ||
            tuned.channel.pluto == null || deps.stoppedNow()) {
            leave()
            return
        }
        // Only a second first frame on the stream already polled keeps what the poll has seen.
        if (!fresh && poller.pollingUrl == url && this.tuned?.channel?.number == tuned.channel.number) return
        leave()
        this.tuned = tuned
        loadedAt = deps.wallMillis()
        this.anchored = anchored
        poller.start(url)
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
    fun leave() {
        poller.stop()
        cancelTimer?.invoke()
        cancelTimer = null
        tuned = null
        view = null
        pictureAt = null
        stalledMillis = 0L
        stalledSince = null
        if (inBreak) endBreak()
    }

    /** An overlay opened or closed: hide or restore the card. Cheap; called on every change. */
    fun refresh() {
        val on = tuned
        val next = if (inBreak && on != null && !deps.overlayOpen()) card(on) else null
        if (state.value != next) state.value = next
    }

    /**
     * The card's music, if a break is on and nothing else owns the music - after the break is
     * seen, after the guide closes over it, and when the app comes back into view.
     */
    fun resumeMusic() {
        if (!inBreak || deps.guideOpen() || deps.stoppedNow()) return
        deps.playMusic { inBreak && !deps.guideOpen() && !deps.stoppedNow() }
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
        val onScreen = if (v.timed) onScreenNow(v) else null
        if (onScreen == null) {
            apply(v.fallbackInBreak && !v.blind, null)
            return
        }
        val decision = v.at(onScreen)
        val end = v.end
        apply(decision.inBreak, if (decision.inBreak && end != null) end - onScreen else null)
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

    /** Up or down; [endsInMillis] of on-screen time until the break's end, when it is known. */
    private fun apply(on: Boolean, endsInMillis: Long?) {
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
            Log.i("fs42", "pluto break on ${t.channel.number}: card up")
            inBreak = true
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
        ): PlutoBreak {
            // Its own daemon thread, not the prefetch thread: a read can take its full five
            // seconds of timeouts, and neighbour resolves and the guide's fetches queue there.
            val executor = BreakPoller.daemonExecutor()
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            return PlutoBreak(Deps(
                enabled = { extras.features.isOn(Features.Flag.BREAK_CARD) },
                poller = { read ->
                    BreakPoller(BreakPoller::httpFetch, BreakPoller.scheduleOn(executor), read)
                },
                later = { delay, block ->
                    val runnable = Runnable { if (!halted()) block() }
                    main.postDelayed(runnable, delay)
                    ({ main.removeCallbacks(runnable) })
                },
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
            ))
        }
    }
}
