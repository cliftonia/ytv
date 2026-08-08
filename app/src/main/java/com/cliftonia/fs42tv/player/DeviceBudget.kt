package com.cliftonia.fs42tv.player

/**
 * How many channels to hold preloaded.
 *
 * **Currently zero: preloading is off.** It measured as a clear win and was observed to be a
 * clear loss, and both are true of different things.
 *
 * On the TCL it improved channel changes substantially - 72% of presses put a picture up within
 * eight seconds against 27% with it off, median 1,779 ms against 2,472 ms. But watching one
 * channel for a minute showed the picture stopping and starting repeatedly, because
 * `DefaultPreloadManager` fetches in parallel with playback rather than yielding to it: every
 * time it topped up a neighbour's buffer it took bandwidth from the stream on screen.
 *
 * The measurement rig could not see that. Every figure in this project is time-to-first-frame;
 * nothing measures whether the picture then keeps playing. A faster switch into a stuttering
 * channel is not a better television, and the fault was found by watching rather than measuring.
 *
 * The machinery is kept rather than deleted because the fix is known and reuses it. Media3's
 * playlist preloading (`ExoPlayer.setPreloadConfiguration`) "is only started when no media is
 * being loaded that is required for the ongoing playback" - it defers to playback by design,
 * which is exactly the property missing here. [PreloadPlan] and its reverse-slot ordering carry
 * straight over. Until then this returns zero, and `--ei fs42.budget N` re-enables it for
 * experiments.
 *
 * Kept free of Android imports so it tests on the JVM; the caller reads the real figure from
 * `ActivityManager.MemoryInfo.totalMem` and passes it in.
 */
object DeviceBudget {

    private const val GB = 1_024L * 1_024L * 1_024L

    /** Preloading is disabled; see the class note. Override with `--ei fs42.budget N`. */
    @Suppress("UNUSED_PARAMETER")
    fun forDevice(totalRamBytes: Long): Int = 0

    /**
     * What the budget would be if preloading were enabled, kept for when it is.
     *
     * Both real targets sit below 3 GB - the TCL reports 2.34 GB and the Chromecast has 1.5 - so
     * the four-slot branch was never reachable in production. The floor is two rather than one
     * because a single slot can only hold the channel ahead, which is the forward-only priming
     * that made every reversal on the box a cold open: 5,359 ms against 350 ms once a reverse
     * slot existed. Thresholds are on TOTAL RAM, not free RAM, which swings with whatever else
     * happens to be running.
     */
    fun budgetForRam(totalRamBytes: Long): Int = when {
        totalRamBytes >= 3 * GB -> 4
        else -> 2
    }
}
