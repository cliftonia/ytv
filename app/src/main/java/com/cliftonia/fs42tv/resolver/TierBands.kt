package com.cliftonia.fs42tv.resolver

/**
 * Which renditions belong to which rung of the ladder, and which of them wins.
 *
 * Extracted from [DeviceResolver] for the same reason [DecoderSupport] was: none of this touches
 * the network, the extractor or Android, so trapping it inside a class that needs all three meant
 * the policy that decides what every channel plays had no tests at all.
 */
object TierBands {

    /**
     * The height band [tier] accepts, or null for a rung with no band.
     *
     * Bands rather than "at or below", so the rungs are disjoint the way the server's published
     * tiers were. If they overlapped, a clip with no 1080p rendition would resolve `hd` and `sd`
     * to the same 720p stream and the ladder would try the identical url twice before giving up.
     */
    fun bandFor(tier: String): IntRange? = when (tier) {
        // Capped at 2160, NOT open-ended. YouTube publishes 4320p on a growing number of
        // uploads, and an unbounded top band takes it: four times the pixels of the panel's
        // native resolution, on a television with 2.34GB of memory in total. The server this
        // replaced capped its top tier at 2160 and this quietly did not.
        "uhd" -> 1081..2160
        "hd" -> 721..1080
        "sd" -> 0..720
        else -> null
    }

    /**
     * Height in pixels, read from the resolution label.
     *
     * The label is what the extractor has always exposed and it survives version changes that the
     * numeric accessors have not. It reads like "1080p60" or "2160p", so everything up to the `p`
     * is the height; anything unparseable sorts to the bottom rather than throwing.
     */
    fun heightOf(resolution: String?): Int = resolution?.substringBefore('p')?.toIntOrNull() ?: 0

    /**
     * The order renditions inside one band are ranked in, worst first.
     *
     * Highest inside the band, then H.264 ahead of an equal-height VP9. Both usually play where
     * both are offered, but H.264 is the one every device has decoded in hardware for fifteen
     * years, and the cost of preferring it is nothing.
     *
     * Generic over the stream type so this can be exercised without an extractor: the caller says
     * how to read a height and how to recognise H.264, which is the whole of what the ranking
     * depends on.
     */
    fun <T> preference(height: (T) -> Int, isAvc: (T) -> Boolean): Comparator<T> =
        compareBy<T> { height(it) }.thenBy { if (isAvc(it)) 1 else 0 }
}
