package com.cliftonia.fs42tv.ui

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.cliftonia.fs42tv.pluto.BreakDetector
import com.cliftonia.fs42tv.pluto.BreakPoller
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
 * Polling runs only while a Pluto-dial channel is actually playing - from its first frame until
 * anything else happens to the screen: a surf, an error, a card, the app leaving. [playing] is
 * the one way in and [leave] the one way out, so every rule about when is in the director's
 * calls, not here. While the guide or settings is up the card is hidden, not ended - the poller
 * keeps reading, and the card is back the moment the overlay closes if the break is still on.
 *
 * Unlike the up-next card, the player keeps playing underneath: the bumper IS a picture, so the
 * watchdog has nothing to wait for, and the programme's return needs no tune at all.
 *
 * Main thread only, except the poller's reads, whose verdicts are posted back through
 * [Deps.runOnUi] and dropped if the run they belong to has since been stopped.
 */
class PlutoBreak(private val deps: Deps) {

    class Deps(
        /** The BREAK CARD row, read on every [playing]. OFF: no polling, no card. */
        val enabled: () -> Boolean,
        /** Builds the poller around the verdict callback - a seam for a hand-cranked clock. */
        val poller: (changed: (BreakPoller.Run, BreakDetector.State) -> Unit) -> BreakPoller,
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

    private val poller: BreakPoller = deps.poller { run, verdict ->
        deps.runOnUi { if (!deps.halted() && poller.isCurrent(run)) changed(verdict) }
    }

    /**
     * [tuned] has a picture and nothing is over the programme's timing: poll it, if it is a Pluto
     * channel and the row is on. The same url already being polled carries on - a second first
     * frame, a resume - so a break already seen is not forgotten by it.
     */
    fun playing(tuned: Tuned?) {
        val url = (tuned?.playable as? Hls)?.url
        if (tuned == null || url == null || !deps.enabled() || tuned.card != null ||
            tuned.channel.pluto == null || deps.stoppedNow()) {
            leave()
            return
        }
        if (poller.pollingUrl == url && this.tuned?.channel?.number == tuned.channel.number) return
        leave()
        this.tuned = tuned
        poller.start(url)
    }

    /** Anything else took the screen: stop polling, and take the card and its music down. */
    fun leave() {
        poller.stop()
        tuned = null
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

    private fun changed(verdict: BreakDetector.State) {
        val on = tuned ?: return
        when (verdict) {
            BreakDetector.State.IN_BREAK -> if (!inBreak) {
                Log.i("fs42", "pluto break on ${on.channel.number}: card up")
                inBreak = true
                deps.picture()
                refresh()
                deps.volumeChanged()
                resumeMusic()
            }
            BreakDetector.State.PROGRAMME -> if (inBreak) {
                Log.i("fs42", "pluto break over on ${on.channel.number}")
                endBreak()
            }
        }
    }

    private fun endBreak() {
        inBreak = false
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
        )
    }

    companion object {
        /** Construction in one call for the director, which is at its size limit. */
        fun create(
            extras: ScreenExtras,
            music: GuideMusic,
            channels: () -> List<Channel>,
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
            return PlutoBreak(Deps(
                enabled = { extras.features.isOn(Features.Flag.BREAK_CARD) },
                poller = { changed ->
                    BreakPoller(BreakPoller::httpFetch, BreakPoller.scheduleOn(executor), changed)
                },
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
