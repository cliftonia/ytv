package com.cliftonia.fs42tv.ui

import android.util.Log
import com.cliftonia.fs42tv.player.ChannelPlayback
import com.cliftonia.fs42tv.player.MpvChannelPlayer
import com.cliftonia.fs42tv.player.MpvLog

/**
 * [ScreenDirector]'s side of the player: the four callbacks it hangs on every engine, and what a
 * playback error does to the screen.
 *
 * Out of the director for the reason [recoveryWatch] is: to keep the director under the
 * source-length ceiling. This is where the player's events are routed - to a break's reel, or to
 * the director's own pieces ([StallPill], [StallRecovery], [SponsorSkipper], [RecoveryWatch]) -
 * rather than a rule of its own. Extensions, so the routing reads the director's state exactly
 * as it did as members; the pieces it reaches are `internal` for that alone. UI thread only, as
 * every player callback is.
 */

/**
 * Give [player] the four callbacks that keep the dial honest.
 *
 * A method rather than wiring at construction so a REBUILT engine gets the same callbacks
 * the first one had: mpv shuts its core down on a fatal, and a replacement with nothing
 * listening reports no first frame - the stand-by card would then never come down again.
 */
fun ScreenDirector.wirePlayer(player: ChannelPlayback) {
    // A break's reel (BreakAds) owns all four while it is on the player: its end, error, frame
    // and stalls are the commercials', never the channel's - no error card, no Pluto blame.
    player.onClipEnded = {
        if (plutoBreak.ads?.ended() != true) {
            skipper.stop()
            deps.tune().clipEnded()
        }
    }
    player.onPlaybackError = { code -> if (plutoBreak.ads?.failed(code) != true) playbackFailed(code) }
    // The card comes down when a picture actually appears, not when a tune is merely
    // dispatched - a tune that fails again would otherwise clear it and leave black.
    player.onFirstFrame = first@{
        if (plutoBreak.ads?.firstFrame() == true) return@first stall.firstFrame()
        deps.tune().noteFirstFrame()
        watch.firstFrame()
        standByReason.value = ""
        stall.firstFrame()
        stallRecovery.buffering(false)
        tuning.value = false
        updateProgrammeVolume()
        skipper.start(deps.tune().onAir)
        plutoBreak.playing(deps.tune().onAir)
        // The neighbours' masters, Pluto and FAST, read ahead of a surf - never under a reel. See MasterPrefetch.
        deps.extras.plutoPictureUp(deps.tune(), deps.channels()) {
            tuning.value || plutoBreak.adsOnPlayer || deps.stoppedNow() }
    }

    // A stall is the third way this player goes quiet, and the only silent one - no error,
    // no end of media, just a stopped picture. The pill is ALL that happens - see [StallPill] -
    // but for mpv on a live Pluto stream, which [StallRecovery] reloads.
    // A stall also holds the break card's clock: the picture does not move on during one.
    player.onBuffering = stalled@{ stalled ->
        stall.buffering(stalled)
        if (plutoBreak.ads?.buffering(stalled) == true) return@stalled
        stallRecovery.buffering(stalled)
        plutoBreak.buffering(stalled)
    }
}

/** The player failed on what it was given - or mpv stalled past [StallRecovery]'s reloads. */
internal fun ScreenDirector.playbackFailed(code: String) {
    stallRecovery.clear()
    skipper.stop()
    if (code.startsWith(MpvChannelPlayer.ENGINE_DIED) && !deps.halted()) {
        // The engine, not the clip. Rebuild first, then let the normal recovery below
        // re-tune into the new instance.
        deps.rebuildEngine()
    } else {
        // A Pluto stream on its own route that would not open or was refused: the route
        // rebuilds its session, or retires the channel to the legacy url, before the
        // re-tune below asks it again. A no-op for anything else.
        // A demuxer stall drops only the remembered pick: that is ffmpeg, not a refused token.
        // So does a picture that would not start - the sound played, so the session is good.
        val session = code != StallRecovery.STALLED && code != MpvLog.NO_PICTURE
        deps.extras.plutoFailed(deps.tune().onAir?.playable, session = session)
    }
    // A rejected url must be forgotten, or the re-tune resolves the same dead link.
    RefusedUrl.report(code, deps.tune().onAir?.stream?.id, deps.condemn)

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
    // The picture is gone; so is any break card over it - once the blank is up.
    plutoBreak.leave()
    updateProgrammeVolume()
    watch.error(code)
}
