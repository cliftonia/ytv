package com.cliftonia.fs42tv.resolver

/**
 * What this device can actually decode, as a rule the resolver can apply.
 *
 * Pure, and separated from the Android query for that reason. The policy is the part that was
 * wrong - the query is trivial - and it could not be tested while it lived inside a resolver that
 * needs the network.
 *
 * The fault it exists to prevent: YouTube publishes nothing above 1080p in H.264. Ask for 2160p
 * and the only thing on offer is VP9 or AV1, so a 4K panel with a 32-bit decoder is handed a
 * rendition it will accept a URL for and then fail to draw. That is a black screen with a banner
 * over it - the channel names a programme and never shows one - and it happens per CLIP, because
 * it depends on whether that upload has a 4K rendition at all. Which is why it looked like
 * "some channels don't work".
 */
data class DecoderSupport(
    /** Highest height this device can decode, per codec family. Zero means "cannot at all". */
    private val maxHeightByCodec: Map<String, Int>,
) {

    /** Whether [codec] at [height] is worth handing to the player. */
    fun canPlay(codec: String?, height: Int): Boolean =
        height <= maxHeightByCodec.getOrElse(family(codec)) { 0 }

    companion object {

        /**
         * The codec family a YouTube codec string belongs to.
         *
         * YouTube writes full profile strings - `avc1.640028`, `vp09.00.51.08` - and the profile
         * digits vary per rendition, so matching on the prefix is what stays correct as YouTube
         * changes them.
         */
        fun family(codec: String?): String {
            val lowered = codec.orEmpty().lowercase()
            return when {
                lowered.startsWith("avc") || lowered.startsWith("h264") -> AVC
                lowered.startsWith("vp9") || lowered.startsWith("vp09") -> VP9
                lowered.startsWith("av01") || lowered.startsWith("av1") -> AV1
                lowered.startsWith("hev") || lowered.startsWith("hvc") -> HEVC
                else -> UNKNOWN
            }
        }

        const val AVC = "avc"
        const val VP9 = "vp9"
        const val AV1 = "av1"
        const val HEVC = "hevc"
        const val UNKNOWN = "unknown"

        /**
         * What to assume when the device cannot be asked.
         *
         * H.264 to 1080p and nothing else. Deliberately the most conservative thing that still
         * plays: every Android device made this century decodes 1080p H.264, and a picture at
         * 1080p is infinitely better than no picture at 2160p.
         */
        val CONSERVATIVE = DecoderSupport(mapOf(AVC to 1080))

        /** Everything, for tests and for devices that report full support. */
        val EVERYTHING = DecoderSupport(
            mapOf(AVC to 4320, VP9 to 4320, AV1 to 4320, HEVC to 4320))

        fun of(vararg pairs: Pair<String, Int>) = DecoderSupport(pairs.toMap())
    }
}
