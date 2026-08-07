package com.cliftonia.fs42tv.player

/**
 * How many channels to hold preloaded, from how much RAM the device has.
 *
 * The two targets are a TCL television with room to spare and a Chromecast with Google TV HD
 * with 1.5 GB total, where the decoder, the app and the system already share a small pool.
 * Preloading buffers rather than decoders is cheap next to the box's mpv shadow pool - 300-500
 * MB per instance there - but it is not free, and the small device is the one that sets this
 * number.
 *
 * Kept free of Android imports so it tests on the JVM; the caller reads the real figure from
 * `ActivityManager.MemoryInfo.totalMem` and passes it in.
 */
object DeviceBudget {

    private const val GB = 1_024L * 1_024L * 1_024L

    /**
     * Thresholds are on TOTAL device RAM, not free RAM, which swings with whatever else is up.
     *
     * **The floor is 2, not 1, and that is the important part of this function.** A budget of 1
     * can only hold the channel ahead, which is precisely the forward-only priming that made
     * every reversal on the box a cold open - 5,359 ms against 350 ms once a reverse slot
     * existed. Reserving a slot for the channel behind is worth most exactly where memory is
     * tightest, so the small device is the last place to economise by dropping it. Two buffered
     * windows is a small price for not reproducing the worst bug this project has already fixed
     * once.
     */
    fun forDevice(totalRamBytes: Long): Int = when {
        totalRamBytes >= 3 * GB -> 4
        else -> 2
    }
}
