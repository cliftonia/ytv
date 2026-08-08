package com.cliftonia.fs42tv.resolver

/**
 * Which published tiers to try, best first, for a given panel.
 *
 * Two conditions, not one. The old `preferUhd: Boolean` could only say "try 4K" or "don't",
 * which conflates two separate questions: what is the best rendition available, and what can
 * this screen actually show? Handing 2160p to a 1080p panel spends bandwidth and decode on
 * pixels that are scaled away before they reach the glass - and on the 1.5 GB Chromecast that
 * is the difference between playing and stuttering.
 *
 * So the ladder is capped by the display and then descends: the best tier the panel can use,
 * followed by every smaller one as a fallback. A tier that is missing or whose signed URL has
 * expired simply drops through to the next rung, which is why the lower rungs matter more than
 * they look - without them a stale `hd` means the channel plays nothing at all.
 *
 * Heights come from the server's `TIER_HEIGHTS` in `fs42/yt_cache.py`: sd 720, hd 1080,
 * uhd 2160. Keep the two in step; a rung named here that the server never publishes is simply
 * skipped, but a rung the server publishes and this omits is quality thrown away.
 */
object TierLadder {

    /**
     * [displayHeight] is the panel's physical height in pixels - `Display.getMode().physicalHeight`,
     * NOT `DisplayMetrics`, which on Android TV reports the (often 1080p) UI layer rather than
     * the panel. A TCL 4K set renders its UI at 1920x1080 while the video surface runs at 2160p,
     * so reading the wrong one caps every 4K television at hd.
     */
    fun forDisplay(displayHeight: Int): List<String> = when {
        displayHeight >= 2160 -> listOf("uhd", "hd", "sd")
        displayHeight >= 1080 -> listOf("hd", "sd")
        // Below 1080 the panel cannot use hd either, but it is kept as a last resort: a
        // downscaled 1080p picture is better than the black screen an empty ladder produces.
        else -> listOf("sd", "hd")
    }
}
