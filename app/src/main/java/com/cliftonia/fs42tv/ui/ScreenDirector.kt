package com.cliftonia.fs42tv.ui

import android.os.Handler
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.cliftonia.fs42tv.player.ChannelPlayback
import com.cliftonia.fs42tv.player.MpvChannelPlayer
import com.cliftonia.fs42tv.resolver.PlaybackDiagnostics
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.VttCues
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
     * The cues of the clip currently playing, as the overlay draws them.
     *
     * The app parses and draws subtitles itself rather than handing the track to the player -
     * see [CaptionLine] for why. Written only on the UI thread: cleared where the clip starts,
     * filled by the loader once the file has come down.
     */
    val captionCues = mutableStateOf<List<VttCues.Cue>>(emptyList())

    /**
     * Whether the viewer wants English subtitles.
     *
     * Off by default. Most of the dial is in English and captions on a channel nobody needed
     * them for is a worse default than absence. `@Volatile` because the toggle is flipped on
     * the UI thread and read wherever a clip is painted.
     */
    @Volatile var captionsOn: Boolean = false

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

    /** SKIP SPONSORS during playback; a range reaching the end ends the clip the usual way. */
    private val skipper = SponsorSkipper(
        handler = Handler(android.os.Looper.getMainLooper()),
        player = deps.player,
        timetable = deps.extras.timetable,
        ended = {
            // Silenced first, as a natural end is: the tail being skipped must not play on under
            // the resolve of whatever is next.
            deps.player()?.stop()
            deps.tune().clipEnded()
        },
    )

    private val captions = CaptionLoader(
        executor = deps.captionExecutor,
        runOnUi = deps.runOnUi,
        generationNow = { deps.tune().generationNow() },
        halted = deps.halted,
        show = { captionCues.value = it },
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
        deps.player()?.setVolume(
            if (tuning.value || deps.pickerOpen()) 0f else deps.extras.programmeGain())
        syncHiss()
    }

    /** The switchable extras, for the overlay stack. */
    val extras: ScreenExtras get() = deps.extras

    /**
     * Re-derive the channel-change hiss from the same facts as the volume, plus the overlays and
     * the app being out of sight - public because opening settings and leaving the app change
     * those without changing the volume.
     */
    fun syncHiss() {
        deps.extras.syncHiss(
            tuning = tuning.value,
            covered = deps.pickerOpen() || deps.overlayOpen() || deps.stoppedNow() || deps.halted(),
        )
    }

    /** The screen's half of every tune, handed to [TuneController]. */
    fun screen() = TuneController.Screen(
        startBlank = ::startBlank,
        paint = ::paint,
        channelUnavailable = { channel ->
            standByReason.value = "CHANNEL ${channel.number} UNAVAILABLE"
        },
    )

    private fun startBlank(target: Channel) {
        // Stop the old channel at the SOURCE rather than covering it. A Compose overlay needs
        // a recomposition and a frame to appear, and the previous channel keeps rendering
        // underneath in the meantime - which showed up as an intermittent flash of the old
        // picture right after choosing a new one. stop() ends that render immediately, and the
        // blank covers the gap between the shutter and the first frame of the new channel.
        deps.player()?.stop()
        skipper.stop()
        // A deliberate channel change supersedes any error still waiting to be announced: the
        // card would name a channel the viewer has already left. It also starts the watchdog on
        // the new channel's first frame.
        watch.tuneStarted()
        deps.stallHandler.removeCallbacksAndMessages(null)
        standByReason.value = ""
        buffering.value = false
        tuning.value = true
        deps.extras.tuneStarted()
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
        deps.player()?.play(playable, tuned.offsetSeconds, requestedAtMillis)
        // Only when the level gain actually changed - with LEVEL VOLUME off it never does, and
        // this call is not made at all.
        if (deps.extras.clipPainted(playable)) updateProgrammeVolume()
        // A tune that lands while the app is in the background must not leave the player
        // running: onStop already paused whatever was playing, and this tune would otherwise
        // stream and decode to a screen nobody is watching.
        if (deps.stoppedNow()) deps.player()?.setPaused(true)
        // Cleared on the same thread that starts the clip, so the outgoing programme's
        // dialogue cannot be left sitting over the incoming one.
        captionCues.value = emptyList()
        if (captionsOn) captions.load(playable, generation)
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
            }
            // A rejected URL is the one error worth reacting to specifically: re-tuning
            // without forgetting it would resolve to the same dead link and fail the same way.
            // Engine-agnostic on purpose. Media3 names the fault precisely; mpv reports only
            // that the file ended in error, and its commonest cause by far is exactly this - a
            // signed URL the CDN refused. Matching only Media3's spellings meant an mpv 403
            // re-tuned to the very same dead URL, forever. Being wrong in the other direction
            // costs one server resolve.
            if (code.contains("BAD_HTTP_STATUS") || code.contains("FILE_NOT_FOUND") ||
                code.startsWith("MPV_")) {
                deps.tune().onAir?.stream?.id?.let { id ->
                    // Refuse the TIER, not the clip - condemning the whole id forces a
                    // /resolve, which runs yt-dlp at seven to twelve measured seconds, and
                    // nearly every clip carries a lower rung in a file the app already holds.
                    // Which rung, and what to forget, is the ledger's decision.
                    val tier = deps.condemn(id)
                    if (tier != null) {
                        Log.w("fs42", "tier $tier refused for $id; falling to the next rung")
                    } else {
                        Log.w("fs42", "all tiers refused for $id; skipping the clip")
                    }
                }
            }

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
            deps.extras.firstFrame(deps.tune().onAir)
            skipper.start(deps.tune().onAir)
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
                    if (!deps.halted()) buffering.value = true
                }, STALL_CARD_MILLIS)
            } else {
                buffering.value = false
            }
        }
    }

    /** Put the channel banner back up, recomputed rather than replayed - see [Banner.show]. */
    fun showBanner() = banner.show(deps.tune().onAir, deps.fallbackChannel())

    /**
     * The captions flag, applied to the clip already playing.
     *
     * Every resolve carries its caption url whether or not captions are on, so turning them on
     * is a fetch of the current clip's track out of the cache - no re-resolve. Off clears the
     * overlay immediately.
     */
    fun toggleCaptions() {
        captionsOn = !captionsOn
        deps.persistCaptionsOn(captionsOn)
        if (captionsOn) loadCaptionsForCurrentClip() else captionCues.value = emptyList()
        Log.i("fs42", "captions ${if (captionsOn) "on" else "off"}")
    }

    /**
     * A Settings switch flipped: act on what is on screen now, so OFF is visible at once.
     * Exhaustive on purpose - a new flag must decide what switching it does here.
     */
    fun featureToggled(flag: Features.Flag, on: Boolean) {
        Log.i("fs42", "feature ${flag.label} ${if (on) "on" else "off"}")
        when (flag) {
            // Nothing to undo: the next banner and the next guide open read the flag.
            Features.Flag.PLUTO_GUIDE -> Unit
            // Off silences a hiss at once; the snow gives way to black on the next tune.
            Features.Flag.STATIC -> syncHiss()
            // Re-derived now, so OFF restores full volume on the clip already playing.
            Features.Flag.LEVEL_VOLUME -> updateProgrammeVolume()
            Features.Flag.LOGO -> if (!on) deps.extras.hideBug()
            // Applied to the clip already playing: OFF stops the watcher at once, ON starts it
            // for a clip that has ranges. The clock's arithmetic changes with the next tune.
            Features.Flag.SKIP_SPONSORS ->
                if (on && !tuning.value) skipper.start(deps.tune().onAir) else skipper.stop()
        }
    }

    /**
     * The app left the screen (onStop): hold the programme and everything timed against it. The
     * guide music is the guide's; see MainActivity.onStop.
     */
    fun appStopped() {
        syncHiss()
        deps.player()?.setPaused(true)
        skipper.stop()
    }

    /** Back on screen: resume the picture, re-derive the volume, and watch for skips again. */
    fun appResumed() {
        deps.player()?.setPaused(false)
        updateProgrammeVolume()
        if (!tuning.value) skipper.start(deps.tune().onAir)
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
        deps.extras.tuneStarted()
        updateProgrammeVolume()
        watch.tuneStarted()
    }

    private fun loadCaptionsForCurrentClip() {
        val id = deps.tune().onAir?.stream?.id ?: run {
            Log.i("fs42", "captions: nothing on air to caption")
            return
        }
        val playable = deps.recallResolved(id, deps.nowSeconds()) ?: run {
            // Only reachable if the clip's urls expired while it was still playing, which the
            // tune path handles by re-resolving anyway.
            Log.i("fs42", "captions: $id is not in the resolved cache")
            PlaybackDiagnostics.recordCaptions("NOT CACHED")
            return
        }
        captions.load(playable, deps.tune().generationNow())
    }

    private companion object {
        /** Long enough not to flash on the brief stalls that clear themselves. */
        const val STALL_CARD_MILLIS = 2_500L
    }
}
