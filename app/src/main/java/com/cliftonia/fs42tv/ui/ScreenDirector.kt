package com.cliftonia.fs42tv.ui

import android.os.Handler
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.cliftonia.fs42tv.player.ChannelPlayback
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.tune.TuneController
import com.cliftonia.fs42tv.tune.Tuned
import java.util.concurrent.Executor

/**
 * Everything the viewer sees between frames of the programme: the blank, the banner, the
 * stand-by card, the captions, and the volume - and every rule about when each appears.
 *
 * One class rather than a dozen activity fields because these states are coupled by rules that
 * have each been wrong at least once: a failed re-tune must not bump the banner, a stall must
 * not cancel a pending error card, a channel change must clear the card its predecessor armed,
 * and the volume must be derived from both silencing conditions rather than whichever wrote
 * last. Holding the states and the rules in one place is what keeps a new rule from missing a
 * state.
 */
class ScreenDirector(internal val deps: Deps) {

    class Deps(
        val player: () -> ChannelPlayback?,
        val tune: () -> TuneController,
        /** The guide ducks the programme audio while it is open. */
        val pickerOpen: () -> Boolean,
        /** Where the dial points when nothing is on air, for the banner's fallback. */
        val fallbackChannel: () -> Channel?,
        val nowSeconds: () -> Long,
        val halted: () -> Boolean,
        /** True between onStop and onStart; a tune landing then must not leave playback running. */
        val stoppedNow: () -> Boolean,
        val runOnUi: (() -> Unit) -> Unit,
        /** Two handlers on the main looper - see [recoveryHandler] for why they cannot be one. */
        val stallHandler: Handler,
        val recoveryHandler: Handler,
        /** The guide or settings is open - the watchdog must not retune under an overlay. */
        val overlayOpen: () -> Boolean,
        /** The ledger's verdict on a rejected url: the tier condemned, or null for the whole clip. */
        val condemn: (String) -> String?,
        /** Tears the engine down and builds a fresh one; only mpv ever needs it. */
        val rebuildEngine: () -> Unit,
        /** The cached resolve for a clip, so the caption toggle can find the current track. */
        val recallResolved: (String, Long) -> Progressive?,
        val persistCaptionsOn: (Boolean) -> Unit,
        val captionExecutor: Executor,
        /** The switchable extras - Pluto's guide and friends. See [ScreenExtras]. */
        val extras: ScreenExtras,
        /** The guide's music, which the "up next" card plays too. */
        val music: GuideMusic,
        /** The dial, for choosing that music. */
        val channels: () -> List<Channel>,
    )

    // True from choosing a channel until its first frame arrives, so the previous channel is
    // not left playing under a banner announcing a different one.
    val tuning = mutableStateOf(false)

    // Backs the stand-by card. A black screen is indistinguishable from a dead app or a dead
    // TV; the card says the app knows and is retrying.
    val standByReason = mutableStateOf("")

    /** The mid-clip stall pill - see [StallPill]. Never over a break card. */
    internal val stall: StallPill = StallPill(
        post = { delay, block -> deps.stallHandler.postDelayed(block, delay) },
        cancel = { deps.stallHandler.removeCallbacksAndMessages(null) },
        halted = deps.halted,
        covered = { plutoBreak.showing },
    )

    /** Whether the stall pill is drawn. */
    val buffering: androidx.compose.runtime.State<Boolean> get() = stall.showing

    /** The tune banner's lines and the rules for what they say. See [Banner]. */
    val banner = Banner(deps.extras, deps.nowSeconds)

    /** The error grace and the no-picture watchdog, with mpv's engine-rebuild backstop. */
    internal val watch = deps.recoveryWatch(tuning, standByReason)

    /** The half-hour schedule's "up next" card: up, timed, and gone. See [UpNextBreak]. */
    val upNext = UpNextBreak(
        handler = Handler(android.os.Looper.getMainLooper()),
        music = deps.music,
        channels = deps.channels,
        timetable = deps.extras.timetable,
        stoppedNow = deps.stoppedNow,
        guideOpen = deps.pickerOpen,
        ended = ::cardEnded,
    )

    /**
     * Pluto's ad breaks: the card over the logo bumper, with the music. See [PlutoBreak]. The
     * card is a picture, like the up-next card: the watchdog and the stall pill stand down for it,
     * and a stall still going when it comes down gets its pill then.
     */
    val plutoBreak: PlutoBreak = PlutoBreak.create(deps.extras, deps.music, deps.channels,
        deps.player, deps.runOnUi, deps.halted, guideOpen = deps.pickerOpen, overlayOpen = deps.overlayOpen,
        stoppedNow = deps.stoppedNow, volumeChanged = ::updateProgrammeVolume,
        picture = { watch.firstFrame(); stall.cover() },
        uncovered = { if (!tuning.value) stall.uncover() }, retune = { returnFromBreak(it.channel) })

    /** mpv lost on a live Pluto stream - ffmpeg at a discontinuity - reloaded. See [StallRecovery]. */
    internal val stallRecovery = StallRecovery(
        schedule = cancellable(deps.recoveryHandler),
        nowMillis = android.os.SystemClock::elapsedRealtime,
        channel = { deps.tune().onAir?.takeIf { !tuning.value && !plutoBreak.adsOnPlayer && StallRecovery.eligible(
            deps.player()?.joinsLiveAtThirdFromLast == true, it) }?.channel?.number },
        deferred = { deps.overlayOpen() || deps.stoppedNow() },
        recover = { reason -> plutoBreak.holdAcrossReload(); deps.tune().retuneCurrent(reason) },
        giveUp = { reason -> Log.w("fs42", "mpv: $reason"); playbackFailed(StallRecovery.STALLED) })

    /** SKIP SPONSORS during playback; a range reaching the end ends the clip the usual way. */
    internal val skipper = SponsorSkipper(
        handler = Handler(android.os.Looper.getMainLooper()),
        player = deps.player,
        timetable = deps.extras.timetable,
        ended = {
            // Covered and silenced first. A natural end leaves the player at the end of the file;
            // this one leaves the skipped tail playing, and on mpv `stop` only mutes - the tail
            // would stay on screen until the next clip loaded.
            raiseBlank()
            deps.player()?.stop()
            deps.tune().clipEnded()
        },
    )

    init {
        // A Pluto channel on its legacy url for want of a session: re-tuned once one can be had,
        // if the viewer is still there with nothing open over it. See ScreenExtras.
        deps.extras.retuneWhenPlutoSessionReady(deps.tune) {
            !tuning.value && !deps.overlayOpen() && !deps.pickerOpen() && !deps.stoppedNow()
        }
    }

    /** The captions drawn over the programme, and the viewer's switch for them. */
    val captions = CaptionState.forDirector(deps)

    /**
     * Set the channel's volume from the two things that can silence it, rather than from
     * whichever happened last.
     *
     * Both the guide music and a channel change want the programme audio down, and they
     * overlap: selecting from the picker closes it - restoring volume - immediately AFTER the
     * tune has muted, so a last-writer-wins approach let the previous channel's audio out for
     * exactly the split second the new one took to arrive.
     */
    fun updateProgrammeVolume() {
        // Never above the level gain, never unmuting a blank: the gain only replaces the 1f.
        // The card too: under it the outgoing file may still be loaded, and it must stay silent.
        // And a Pluto break, hidden under an overlay or not: its audio is the bumper's jingle.
        deps.player()?.setVolume(if (tuning.value || deps.pickerOpen() || upNext.showing ||
            plutoBreak.muting) 0f else plutoBreak.adsGain ?: deps.extras.programmeGain())
        syncCovered()
    }

    /** The switchable extras, for the overlay stack. */
    val extras: ScreenExtras get() = deps.extras

    /**
     * Re-derive whether anything is in front of the tuning screen - the overlays, or the app out
     * of sight - so the snow stops animating behind them. Public because opening settings and
     * leaving the app change that without changing the volume.
     */
    fun syncCovered() {
        deps.extras.syncCovered(
            deps.pickerOpen() || deps.overlayOpen() || deps.stoppedNow() || deps.halted())
        // The same transitions hide a break card under the guide or settings, and restore it.
        plutoBreak.refresh()
    }

    /** The screen's half of every tune, handed to [TuneController]. */
    fun screen() = TuneController.Screen(
        startBlank = ::startBlank,
        paint = ::paint,
        channelUnavailable = { channel ->
            leaveCard()
            plutoBreak.leave()
            standByReason.value = "CHANNEL ${channel.number} UNAVAILABLE"
        },
        card = ::showCard,
    )

    /**
     * The schedule has a card on, not a clip. It IS what is on, so everything that waits for a
     * picture stands down exactly as a first frame would stand it down - the blank, the watchdog,
     * the error grace, the stall pill. RecoveryWatch must not read a card as a stuck tune.
     *
     * The outgoing clip is stopped at the source and paused: on mpv `stop` only mutes, and the
     * file would otherwise decode on under the card, for up to hours on a deferred prime time.
     */
    private fun showCard(tuned: Tuned) {
        deps.player()?.stop()
        deps.player()?.setPaused(true)
        skipper.stop()
        // After the pause: the volume it re-derives on the way out is a paused file's.
        plutoBreak.leave()
        upNext.stopCut()
        watch.firstFrame()
        stall.clear()
        standByReason.value = ""
        tuning.value = false
        captions.clear()
        banner.painted(tuned)
        upNext.show(tuned)
        updateProgrammeVolume()
    }

    /**
     * The card's time is up: tune its channel for what the schedule has next, as a channel
     * change - the blank and the watchdog.
     */
    private fun cardEnded(channel: Channel) {
        // A timer can outlive the activity; nothing may be queued onto its shut-down executors.
        if (deps.halted()) return
        // The card is already down (UpNextBreak.endNow), so leaveCard would see nothing to do -
        // but the player under it is still paused, and mpv would load the programme paused.
        if (!deps.stoppedNow()) deps.player()?.setPaused(false)
        raiseBlank()
        deps.tune().tune(channel)
    }

    /** The blank, the silence and the watchdog of a channel change, for a scheduled one. */
    private fun raiseBlank() {
        tuning.value = true
        // After the blank is up, so the volume it re-derives stays down: on mpv the outgoing
        // file is only muted by stop(), and a moment at full gain is heard.
        plutoBreak.leave()
        updateProgrammeVolume()
        watch.tuneStarted()
    }

    /** On destroy: stop every timer this owns, so none fires into a dead activity. */
    fun release() {
        upNext.release()
        skipper.stop()
        plutoBreak.release()
    }

    /** Anything else taking the screen takes the card down, and un-pauses the player under it. */
    private fun leaveCard() {
        if (!upNext.showing) return
        upNext.cancel()
        if (!deps.stoppedNow()) deps.player()?.setPaused(false)
    }

    /** The guide closed over a card - either kind: the card's music again. */
    fun resumeBreakMusic() {
        upNext.resumeMusic()
        plutoBreak.resumeMusic()
    }

    private fun startBlank(target: Channel) {
        // Stop the old channel at the SOURCE rather than covering it. A Compose overlay needs
        // a recomposition and a frame to appear, and the previous channel keeps rendering
        // underneath in the meantime - which showed up as an intermittent flash of the old
        // picture right after choosing a new one. stop() ends that render immediately, and the
        // blank covers the gap between the shutter and the first frame of the new channel.
        deps.player()?.stop()
        skipper.stop()
        upNext.stopCut()
        // Surfing away cancels a card as it cancels anything else - either kind.
        leaveCard()
        // A deliberate channel change supersedes any error still waiting to be announced: the
        // card would name a channel the viewer has already left. It also starts the watchdog on
        // the new channel's first frame.
        watch.tuneStarted()
        stall.clear()
        stallRecovery.clear()
        standByReason.value = ""
        tuning.value = true
        // The break card too - after the blank is up, for the reason in [raiseBlank].
        plutoBreak.leave()
        updateProgrammeVolume()
        banner.announce(target)
    }

    private fun paint(
        tuned: Tuned,
        playable: com.cliftonia.fs42tv.resolver.Playable,
        requestedAtMillis: Long,
        played: Boolean,
        generation: Int,
    ) {
        // Before the load: the watcher is reading the OUTGOING clip's ranges, and the new file's
        // position must never be checked against them.
        skipper.stop()
        leaveCard()
        upNext.watchCut(tuned.takeIf { played })
        deps.player()?.play(playable, tuned.offsetSeconds, requestedAtMillis)
        // A Pluto channel's playlist is read at the load itself: the mpv clock's anchor.
        plutoBreak.loading(tuned.copy(playable = playable))
        // Only when the level gain actually changed - with LEVEL VOLUME off it never does, and
        // this call is not made at all.
        if (deps.extras.clipPainted(playable)) updateProgrammeVolume()
        // A tune that lands while the app is in the background must not leave the player
        // running: onStop already paused whatever was playing, and this tune would otherwise
        // stream and decode to a screen nobody is watching.
        if (deps.stoppedNow()) deps.player()?.setPaused(true)
        captions.clipStarting(playable, generation)
        // Only a genuine success touches the banner.
        if (played) banner.painted(deps.tune().onAir)
    }

    /** Put the channel banner back up, recomputed rather than replayed - see [Banner.show]. */
    fun showBanner() = banner.show(deps.tune().onAir, deps.fallbackChannel())

    /**
     * The app left the screen (onStop): hold the programme and everything timed against it. The
     * guide music is the guide's; see MainActivity.onStop.
     */
    fun appStopped() {
        syncCovered()
        deps.player()?.setPaused(true)
        skipper.stop()
        // Nobody is watching: no reads. Back on screen, the break is seen afresh.
        plutoBreak.appStopped()
        deps.extras.appStopped()
    }

    /** Back on screen: resume the picture, re-derive the volume, and watch for skips again. */
    fun appResumed() {
        val onAir = deps.tune().onAir
        if (plutoBreak.retuneOnResume(onAir) && !tuning.value && onAir != null) {
            // A Pluto channel under mpv, or a break's reel paused on the player: a fresh load - see
            // there. The blank before the un-pause, so a paused commercial is never heard.
            returnFromBreak(onAir.channel)
        } else {
            // Not under a card: the file there was paused on purpose - see [showCard].
            if (!upNext.showing) deps.player()?.setPaused(false)
            updateProgrammeVolume()
            if (!tuning.value) {
                skipper.start(onAir)
                plutoBreak.playing(onAir)
            }
        }
        upNext.resumeMusic()
    }

    /**
     * Tune [channel] afresh from a break's reel (or a resume): the blank, the player un-parked
     * (silent under the blank), the tune. Under an overlay no tune - its close re-tunes one unfinished
     * ([recoverIfAbandoned]), and a tune under the guide is what its supersede forbids.
     */
    private fun returnFromBreak(channel: Channel) {
        raiseBlank()
        // Stopped at the source like a surf (startBlank): the reel must not render or finish
        // loading under the blank while the tune resolves.
        deps.player()?.stop()
        if (!deps.stoppedNow()) deps.player()?.setPaused(false)
        if (!deps.overlayOpen()) deps.tune().tune(channel)
    }

    /**
     * Re-tune if an overlay closed over a tune that never finished.
     *
     * [tuning] is set by a channel change and only a first frame clears it, so it still being
     * up when an overlay closes means the picture never arrived - either the tune failed or
     * the overlay's generation bump abandoned it. Watching normally it is false and this does
     * nothing.
     */
    fun recoverIfAbandoned() {
        if (!tuning.value || deps.halted()) return
        val channel = deps.tune().onAir?.channel ?: deps.fallbackChannel() ?: return
        Log.i("fs42", "re-tuning ${channel.number} ${channel.name}: overlay closed over an unfinished tune")
        // A fresh start for the watch too: the watchdog armed before the overlay opened would
        // otherwise fire moments after this retune and retune again on top of it.
        watch.tuneStarted()
        deps.tune().tune(channel)
    }

    /**
     * The first tune after the dial loads, which does not go through [startBlank] - there is no
     * outgoing channel to stop and no banner to announce. Without this it ran with [tuning]
     * false and no watchdog: a first picture that never came was a black screen with no card,
     * and an overlay opened and closed over it found nothing to recover.
     */
    fun launchTuneStarted() {
        tuning.value = true
        updateProgrammeVolume()
        watch.tuneStarted()
    }
}
