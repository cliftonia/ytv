package com.cliftonia.fs42tv.player

import kotlin.math.cbrt
import kotlin.math.roundToInt

/**
 * The same loudness on both engines.
 *
 * [ChannelPlayback.setVolume] takes a LINEAR gain, which is what Media3's `player.volume` is. mpv's
 * `volume` property is not: its software volume applies `(volume / 100)^3`. For the only values
 * the app sent before matched volume - 0 and 1 - the two agree, which is why nobody noticed;
 * for a level-matching gain of 0.5 a naive `volume=50` would be 0.125, three times quieter than
 * the Chromecast playing the same clip.
 */
object VolumeScale {

    fun mpvPercent(linear: Float): Int {
        if (linear.isNaN()) return 0
        return (cbrt(linear.coerceIn(0f, 1f)) * 100f).roundToInt()
    }
}
