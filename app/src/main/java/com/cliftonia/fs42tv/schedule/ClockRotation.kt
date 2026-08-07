package com.cliftonia.fs42tv.schedule

/** Where a channel is in its rotation: which clip, and how far into it. */
data class PlayPoint(val index: Int, val offsetSeconds: Double)

/**
 * Deterministic position in a channel's clip list, derived from the wall clock.
 *
 * Every device computes the same answer for the same instant with no coordination,
 * which is why the app needs no server at play time. Ported from the Python player's
 * `_build_stream_point`.
 */
object ClockRotation {

    /**
     * @param durations clip lengths in seconds, in playlist order
     * @param nowSeconds wall clock as a Unix timestamp
     * @return the clip and offset now on air, or null if the channel can never be on air
     */
    fun playPointFor(durations: List<Int>, nowSeconds: Long): PlayPoint? {
        val cycle = durations.sumOf { maxOf(it, 0).toLong() }
        if (cycle <= 0L) return null

        var elapsed = Math.floorMod(nowSeconds, cycle)
        for ((index, duration) in durations.withIndex()) {
            if (duration <= 0) continue
            if (elapsed < duration) return PlayPoint(index, elapsed.toDouble())
            elapsed -= duration
        }
        // Unreachable while cycle > 0, but a total rather than a crash if it ever is.
        return null
    }
}
