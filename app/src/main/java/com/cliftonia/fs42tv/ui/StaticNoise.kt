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
