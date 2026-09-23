package com.cliftonia.fs42tv.ui

import kotlin.random.Random

/**
 * Frames of TV snow, as ARGB pixels for a tiny bitmap that is scaled up to fill the screen.
 *
 * Tiny on purpose. The TCL is a 32-bit SoC driving a 4K panel, and per-pixel work at panel
 * resolution - or anything in mpv, whose `mediacodec_embed` output cannot carry a shader (see
 * HANDOVER: the app draws its own captions for the same reason) - is exactly what it cannot
 * afford. 160x90 is 14,400 pixels generated ONCE; the GPU does the scaling, nearest-neighbour, and
 * the chunky grain that gives is what analogue snow looks like from a sofa anyway.
 */
object SnowFrames {

    fun generate(width: Int, height: Int, random: Random): IntArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            // Each scanline gets its own gain, so the frame has the faint horizontal banding of a
            // tuner hunting for sync rather than the flat look of a random-number generator.
            val rowGain = 0.7f + random.nextFloat() * 0.3f
            for (x in 0 until width) {
                val level = (random.nextInt(256) * rowGain).toInt().coerceIn(0, 255)
                pixels[y * width + x] =
                    (0xFF shl 24) or (level shl 16) or (level shl 8) or level
            }
        }
        return pixels
    }
}

/** The hiss's waveform: uniform white noise, pre-attenuated so it can never be loud. */
object WhiteNoise {

    fun pcm(samples: Int, amplitude: Float, random: Random): ShortArray {
        val peak = amplitude * Short.MAX_VALUE
        return ShortArray(samples) { ((random.nextFloat() * 2f - 1f) * peak).toInt().toShort() }
    }
}

/** How the hiss dies away when the picture arrives: linearly, to nothing, over [fadeMillis]. */
object HissEnvelope {

    fun fadeGain(elapsedMillis: Long, fadeMillis: Long): Float =
        (1f - elapsedMillis.toFloat() / fadeMillis).coerceIn(0f, 1f)
}

/**
 * When the hiss starts and stops.
 *
 * Driven from the one place that already decides whether the programme may be heard -
 * `ScreenDirector.updateProgrammeVolume` - and deliberately its complement: the programme is
 * silenced exactly while a tune is in progress and nothing covers the screen, and that is the
 * only time the hiss is [wanted]. So it cannot play over a programme, over the guide's music, or
 * over the launcher: each of those is a state the volume rule already handles.
 *
 * Capped: a tune that never lands (a dead channel retrying) stays "tuning" for as long as the
 * outage lasts, and a hiss for all of it would be a noise complaint. After [timedOut] it stays
 * quiet until the tune ends and a new one begins.
 */
class HissGate {

    enum class Action { START, FADE, NONE }

    private var playing = false

    /** Already hissed during the current tune; reset only when the tune ends. */
    private var spent = false

    fun update(active: Boolean): Action = when {
        active && !playing && !spent -> {
            playing = true
            spent = true
            Action.START
        }
        !active -> {
            spent = false
            if (playing) {
                playing = false
                Action.FADE
            } else {
                Action.NONE
            }
        }
        else -> Action.NONE
    }

    fun timedOut(): Action = if (playing) {
        playing = false
        Action.FADE
    } else {
        Action.NONE
    }

    companion object {
        /**
         * [covered] is anything in front of the blank or taking the audio: the guide (it plays
         * music), settings, the app being in the background, the activity being torn down.
         */
        fun wanted(enabled: Boolean, tuning: Boolean, covered: Boolean): Boolean =
            enabled && tuning && !covered
    }
}
