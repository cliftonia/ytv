package com.cliftonia.fs42tv.ui

import android.os.Handler
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.cliftonia.fs42tv.player.ChannelPlayback
import com.cliftonia.fs42tv.player.MpvChannelPlayer
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
class ScreenDirector(private val deps: Deps) {

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

    /**
     * A mid-clip stall, shown as a small pill over the FROZEN picture rather than the full
     * stand-by card. The card is for faults; a stall is weather. Covering the programme with
     * TECHNICAL DIFFICULTIES while ExoPlayer was quietly refilling its buffer made every slow
     * patch of Wi-Fi look like a breakdown.
     */
    val buffering = mutableStateOf(false)

    /** The tune banner's lines and the rules for what they say. See [Banner]. */
    val banner = Banner(deps.extras, deps.nowSeconds)

    /**
     * The error grace and the no-picture watchdog. Its timers run on [Deps.recoveryHandler] but
     * cancel only their own runnables: the dial loader's retry shares that handler, and a
     * blanket clear would take the retry with it.
     */
    private val watch = RecoveryWatch(
        schedule = { delay, block ->
            val runnable = Runnable(block)
            deps.recoveryHandler.postDelayed(runnable, delay)
            ({ deps.recoveryHandler.removeCallbacks(runnable) })
        },
        halted = deps.halted,
        stillTuning = { tuning.value },
        deferred = { deps.overlayOpen() || deps.stoppedNow() },
        cardUp = { standByReason.value.isNotEmpty() },
        showCard = { standByReason.value = it },
        retune = { reason ->
            // Where the viewer wants to be, not what last painted: a tune that never painted
            // leaves onAir on the channel before it.
            (deps.fallbackChannel() ?: deps.tune().onAir?.channel)?.let {
                Log.i("fs42", "re-tuning ${it.number} ${it.name}: $reason")
                deps.tune().tune(it)
            }
        },
        retuneAfterError = { reason -> deps.tune().retuneCurrent(reason) },
    )

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
     * card is a picture, like the up-next card: the watchdog and the stall pill stand down for it.
     */
    val plutoBreak = PlutoBreak.create(deps.extras, deps.music, deps.channels, deps.runOnUi,
        deps.halted, guideOpen = deps.pickerOpen, overlayOpen = deps.overlayOpen,
        stoppedNow = deps.stoppedNow, volumeChanged = ::updateProgrammeVolume, picture = {
            watch.firstFrame()
            deps.stallHandler.removeCallbacksAndMessages(null)
            buffering.value = false
        })

    /** SKIP SPONSORS during playback; a range reaching the end ends the clip the usual way. */
    private val skipper = SponsorSkipper(
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
        // A Pluto channel that fell back to its legacy url for want of a session - a television
        // just woken - plays Pluto's bumper without an error, so nothing else would ever re-tune
        // it. Once, when a session can be had, and only if the viewer is still there with
        // nothing open over it: a re-tune under the guide changes the channel under the list.
        deps.extras.onPlutoSessionReady { channel ->
            val still = deps.tune().onAir?.takeIf { it.card == null }?.channel?.number == channel.number
            if (still && !tuning.value && !deps.overlayOpen() && !deps.pickerOpen() &&
                !deps.stoppedNow()) {
                deps.tune().retuneCurrent("a pluto session is available")
            }
        }
    }

    /** The captions drawn over the programme, and the viewer's switch for them. */
    val captions = CaptionState(
        executor = deps.captionExecutor,
        runOnUi = deps.runOnUi,
        generationNow = { deps.tune().generationNow() },
        halted = deps.halted,
        // Never a card's: its stream is the programme it announces, not one on screen.
        onAirId = { deps.tune().onAir?.takeIf { it.card == null }?.stream?.id },
        recallResolved = { id -> deps.recallResolved(id, deps.nowSeconds()) },
        persistOn = deps.persistCaptionsOn,
    )

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
            plutoBreak.muting) 0f else deps.extras.programmeGain())
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
        plutoBreak.leave()
        upNext.stopCut()
        watch.firstFrame()
        deps.stallHandler.removeCallbacksAndMessages(null)
        standByReason.value = ""
        buffering.value = false
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
        plutoBreak.leave()
        tuning.value = true
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
        plutoBreak.leave()
        // A deliberate channel change supersedes any error still waiting to be announced: the
        // card would name a channel the viewer has already left. It also starts the watchdog on
        // the new channel's first frame.
        watch.tuneStarted()
        deps.stallHandler.removeCallbacksAndMessages(null)
        standByReason.value = ""
        buffering.value = false
        tuning.value = true
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

    /**
     * Give [player] the four callbacks that keep the dial honest.
     *
     * A method rather than wiring at construction so a REBUILT engine gets the same callbacks
     * the first one had: mpv shuts its core down on a fatal, and a replacement with nothing
     * listening reports no first frame - the stand-by card would then never come down again.
     */
    fun wirePlayer(player: ChannelPlayback) {
        player.onClipEnded = {
            skipper.stop()
            deps.tune().clipEnded()
        }
        player.onPlaybackError = { code ->
            skipper.stop()
            if (code.startsWith(MpvChannelPlayer.ENGINE_DIED) && !deps.halted()) {
                // The engine, not the clip. Rebuild first, then let the normal recovery below
                // re-tune into the new instance.
                deps.rebuildEngine()
            } else {
                // A Pluto stream on its own route that would not open or was refused: the route
                // rebuilds its session, or retires the channel to the legacy url, before the
                // re-tune below asks it again. A no-op for anything else.
                deps.extras.plutoFailed(deps.tune().onAir?.playable)
            }
            // A rejected url must be forgotten, or the re-tune resolves the same dead link.
            RefusedUrl.report(code, deps.tune().onAir?.stream?.id, deps.condemn)
            // The picture is gone; so is any break card over it.
            plutoBreak.leave()

            // Do NOT put the stand-by card up yet. A signed googlevideo URL can be refused
            // with 403 while still inside its stated expiry, and the recovery below - drop the
            // dead id, ask the server for a fresh one, tune again - puts a picture back in
            // about a second. Announcing that as a fault showed the viewer an error code for
            // something the app had already fixed.
            //
            // The card is only delayed, never skipped: if the retune has not produced a
            // picture by the time the grace period is up, this is a real fault and says so.
            // Armed once per streak of errors, and the retune itself is the watch's to time -
            // at once for the first few, backing off after; see RecoveryWatch.error.
            tuning.value = true
            updateProgrammeVolume()
            watch.error(code)
        }
        // The card comes down when a picture actually appears, not when a tune is merely
        // dispatched - a tune that fails again would otherwise clear it and leave black.
        player.onFirstFrame = {
            deps.tune().noteFirstFrame()
            watch.firstFrame()
            standByReason.value = ""
            buffering.value = false
            tuning.value = false
            updateProgrammeVolume()
            skipper.start(deps.tune().onAir)
            plutoBreak.playing(deps.tune().onAir)
        }

        // A stall is the third way this player goes quiet, and the only silent one - no error,
        // no end of media, just a stopped picture.
        //
        // The card is ALL that happens. Re-tuning on a stall was tried and made things far
        // worse: it discards whatever has buffered and restarts the deep seek, so on a
        // connection that cannot sustain the bitrate it produced a permanent cycle of six
        // seconds of picture and twelve of nothing. ExoPlayer keeps filling during a stall and
        // resumes by itself; interrupting that is the one thing that stops it recovering.
        player.onBuffering = { stalled ->
            deps.stallHandler.removeCallbacksAndMessages(null)
            if (stalled) {
                deps.stallHandler.postDelayed({
                    // Not over a break card: the card is the picture, whatever the bumper does.
                    if (!deps.halted() && !plutoBreak.showing) buffering.value = true
                }, STALL_CARD_MILLIS)
            } else {
                buffering.value = false
            }
        }
    }

    /** Put the channel banner back up, recomputed rather than replayed - see [Banner.show]. */
    fun showBanner() = banner.show(deps.tune().onAir, deps.fallbackChannel())

    /**
     * A Settings switch flipped: act on what is on screen now, so OFF is visible at once.
     * Exhaustive on purpose - a new flag must decide what switching it does here.
     */
    fun featureToggled(flag: Features.Flag, on: Boolean) {
        Log.i("fs42", "feature ${flag.label} ${if (on) "on" else "off"}")
        when (flag) {
            // Nothing to undo: the next banner and the next guide open read the flag.
            Features.Flag.PLUTO_GUIDE -> Unit
            // Re-derived now, so OFF restores full volume on the clip already playing.
            Features.Flag.LEVEL_VOLUME -> updateProgrammeVolume()
            // Applied to the clip already playing: OFF stops the watcher at once, ON starts it
            // for a clip that has ranges. The clock's arithmetic changes with the next tune.
            Features.Flag.SKIP_SPONSORS ->
                if (on && !tuning.value) skipper.start(deps.tune().onAir) else skipper.stop()
            // A card up now belongs to the schedule just left: end it, and let the tune decide.
            // A clip playing carries on; the next roll-over asks the new schedule.
            Features.Flag.SCHEDULE -> upNext.endNow()
            // Read per tune: the channel playing carries on, and the next tune - a surf, or
            // the re-tune after any error - takes the route now chosen.
            Features.Flag.PLUTO_ROUTE -> Unit
            // OFF takes a card up now down, restores the sound and stops reading; ON starts
            // reading the channel playing.
            Features.Flag.BREAK_CARD ->
                if (on && !tuning.value) plutoBreak.playing(deps.tune().onAir) else plutoBreak.leave()
        }
    }

    /**
     * The app left the screen (onStop): hold the programme and everything timed against it. The
     * guide music is the guide's; see MainActivity.onStop.
     */
    fun appStopped() {
        syncCovered()
        deps.player()?.setPaused(true)
        skipper.stop()
        // Nobody is watching: no reads. Back on screen, the break is seen afresh.
        plutoBreak.leave()
    }

    /** Back on screen: resume the picture, re-derive the volume, and watch for skips again. */
    fun appResumed() {
        // Not under a card: the file there was paused on purpose - see [showCard].
        if (!upNext.showing) deps.player()?.setPaused(false)
        updateProgrammeVolume()
        if (!tuning.value) {
            skipper.start(deps.tune().onAir)
            plutoBreak.playing(deps.tune().onAir)
        }
        upNext.resumeMusic()
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

    private companion object {
        /** Long enough not to flash on the brief stalls that clear themselves. */
        const val STALL_CARD_MILLIS = 2_500L
    }
}
