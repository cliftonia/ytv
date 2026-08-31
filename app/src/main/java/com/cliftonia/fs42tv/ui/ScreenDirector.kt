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
        /** The ledger's verdict on a rejected url: the tier condemned, or null for the whole clip. */
        val condemn: (String) -> String?,
        /** Tears the engine down and builds a fresh one; only mpv ever needs it. */
        val rebuildEngine: () -> Unit,
        /** The cached resolve for a clip, so the caption toggle can find the current track. */
        val recallResolved: (String, Long) -> Progressive?,
        val persistCaptionsOn: (Boolean) -> Unit,
        val captionExecutor: Executor,
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

    // Compose state backing the tune banner. Written only on the UI thread, and only on a
    // genuine success: a failed re-tune must not touch these, since bumping bannerGeneration
    // would replay the LaunchedEffect in ChannelOsd and pop a banner back up for a channel
    // that never changed.
    val bannerChannelLine = mutableStateOf("")
    val bannerTitleLine = mutableStateOf("")

    // Separate from the tune generation on purpose: that counter is bumped once per keypress,
    // to coalesce a burst of presses, and can advance even when a tune ultimately fails. Using
    // it as the banner's LaunchedEffect key would replay the auto-hide timer on a failed
    // re-tune even though nothing on screen changed. This one only advances alongside onAir.
    val bannerGeneration = mutableStateOf(0)

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
        deps.player()?.setVolume(if (tuning.value || deps.pickerOpen()) 0f else 1f)
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
        // A deliberate channel change supersedes any error still waiting to be announced: the
        // card would name a channel the viewer has already left.
        deps.recoveryHandler.removeCallbacksAndMessages(null)
        deps.stallHandler.removeCallbacksAndMessages(null)
        standByReason.value = ""
        buffering.value = false
        tuning.value = true
        updateProgrammeVolume()
        // The title comes from the clock rotation right here, not from the tune that follows.
        // Waiting for the tune meant the banner showed a bare channel name whenever the tune
        // was superseded - which is every press but the last when surfing quickly. What is on
        // a channel is knowable without tuning to it.
        val (line, title) = ChannelLabels.bannerLinesFor(target, deps.nowSeconds())
        bannerChannelLine.value = line
        bannerTitleLine.value = title
        bannerGeneration.value += 1
    }

    private fun paint(
        tuned: Tuned,
        playable: com.cliftonia.fs42tv.resolver.Playable,
        requestedAtMillis: Long,
        played: Boolean,
        generation: Int,
    ) {
        deps.player()?.play(playable, tuned.offsetSeconds, requestedAtMillis)
        // A tune that lands while the app is in the background must not leave the player
        // running: onStop already paused whatever was playing, and this tune would otherwise
        // stream and decode to a screen nobody is watching.
        if (deps.stoppedNow()) deps.player()?.setPaused(true)
        // Cleared on the same thread that starts the clip, so the outgoing programme's
        // dialogue cannot be left sitting over the incoming one.
        captionCues.value = emptyList()
        if (captionsOn) captions.load(playable, generation)
        // Only a genuine success touches the banner, and it reads the current onAir rather
        // than this tune's outcome directly - a failed tune leaves onAir on whatever last
        // actually played, exactly as the picture itself does.
        if (played) {
            deps.tune().onAir?.let { nowOnAir ->
                val (channelLine, titleLine) = ChannelLabels.bannerLines(nowOnAir)
                bannerChannelLine.value = channelLine
                bannerTitleLine.value = titleLine
            }
            bannerGeneration.value += 1
        }
    }

    /**
     * Give [player] the four callbacks that keep the dial honest.
     *
     * A method rather than wiring at construction so a REBUILT engine gets the same callbacks
     * the first one had: mpv shuts its core down on a fatal, and a replacement with nothing
     * listening reports no first frame - the stand-by card would then never come down again.
     */
    fun wirePlayer(player: ChannelPlayback) {
        player.onClipEnded = { deps.tune().clipEnded() }
        player.onPlaybackError = { code ->
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
                        Log.w("fs42", "all tiers refused for $id; asking the server")
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
            tuning.value = true
            updateProgrammeVolume()
            deps.recoveryHandler.removeCallbacksAndMessages(null)
            deps.recoveryHandler.postDelayed(
                { if (!deps.halted()) standByReason.value = code }, RECOVERY_GRACE_MILLIS)
            deps.tune().retuneCurrent("playback error $code")
        }
        // The card comes down when a picture actually appears, not when a tune is merely
        // dispatched - a tune that fails again would otherwise clear it and leave black.
        player.onFirstFrame = {
            deps.tune().noteFirstFrame()
            deps.recoveryHandler.removeCallbacksAndMessages(null)
            standByReason.value = ""
            buffering.value = false
            tuning.value = false
            updateProgrammeVolume()
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

    /**
     * Put the channel banner back up, recomputed rather than replayed.
     *
     * The stored lines were written when the channel was tuned, and a clip that has rolled
     * over since would name the programme before this one - which is worse than no banner,
     * because it is confidently wrong. Falls back to the stored lines only when nothing is on
     * air, which is a channel between clips rather than a mistake.
     */
    fun showBanner() {
        // The channel on air is described from what is ACTUALLY playing, not recomputed from
        // the clock: after an early roll-over or a dead-clip substitution the rotation names a
        // programme the player is not showing, and an info button that answers with a guess
        // when the truth is in hand is worse than none.
        val onAir = deps.tune().onAir
        if (onAir != null) {
            val (line, title) = ChannelLabels.bannerLines(onAir)
            bannerChannelLine.value = line
            if (title.isNotEmpty()) bannerTitleLine.value = title
        } else {
            val channel = deps.fallbackChannel()
            if (channel != null) {
                val (line, title) = ChannelLabels.bannerLinesFor(channel, deps.nowSeconds())
                bannerChannelLine.value = line
                if (title.isNotEmpty()) bannerTitleLine.value = title
            }
        }
        // The generation is what replays the auto-hide timer in ChannelOsd, so bumping it is
        // what actually shows the banner - exactly what pressing OK on the current channel does.
        bannerGeneration.value += 1
    }

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
        deps.tune().tune(channel)
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

        /**
         * How long a playback error is given to fix itself before the stand-by card appears.
         *
         * A 403 on a signed URL recovers by dropping the dead id, asking the server for a
         * fresh one and tuning again. Measured end to end that is about 1.5s; 4s covers the
         * slow end with room, while still being short enough that a channel which is genuinely
         * dead says so rather than sitting blank.
         */
        const val RECOVERY_GRACE_MILLIS = 4_000L
    }
}
