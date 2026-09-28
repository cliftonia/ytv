package com.cliftonia.fs42tv.ui

import android.util.Log

/**
 * A Settings switch flipped: act on what is on screen now, so OFF is visible at once.
 * Exhaustive on purpose - a new flag must decide what switching it does here.
 *
 * Out of [ScreenDirector] to keep the director under the source-length ceiling, and because this
 * is a table: one arm per [Features.Flag], each handing off to a piece the director already owns
 * ([SponsorSkipper], [UpNextBreak], [PlutoBreak], the volume rule). UI thread only: Settings
 * calls it from the row's click.
 */
fun ScreenDirector.featureToggled(flag: Features.Flag, on: Boolean) {
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
            if (on && !tuning.value) plutoBreak.playing(deps.tune().onAir) else plutoBreak.switchedOff()
        // OFF with a reel on the player tunes the channel back; otherwise the next break reads it.
        Features.Flag.BREAK_ADS -> if (!on && plutoBreak.adsOnPlayer) plutoBreak.switchedOff()
    }
}
