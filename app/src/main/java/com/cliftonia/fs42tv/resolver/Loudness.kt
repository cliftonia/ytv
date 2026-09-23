package com.cliftonia.fs42tv.resolver

import kotlin.math.pow

/**
 * YouTube's loudness figure for a clip, and the gain that levels it.
 *
 * Every player response carries `playerConfig.audioConfig`: how many dB the clip sits above
 * YouTube's reference loudness (-14 LKFS). YouTube's own TV client, and SmartTube after it, turn
 * the volume down by exactly that much and never up. Surfing a dial of a hundred creators is
 * surfing a hundred mastering engineers, and without this a music channel after a talk channel
 * arrives several dB louder than the remote was set for.
 *
 * Read by a hand-rolled scan, like every other parser here (HANDOVER: org.json is stubbed in JVM
 * tests), over responses the extractor had ALREADY downloaded - no second request.
 *
 * Measured against NewPipeExtractor 0.26.5 on 23 Sep 2026: of the responses one resolve reads,
 * `reel/reel_item_watch` (the ANDROID client) carries `loudnessDb` itself, while the `player`
 * response (the iOS client) carries only `perceptualLoudnessDb` and `loudnessTargetLkfs`. The two
 * agree exactly - Big Buck Bunny: loudnessDb -4.71, perceptual -18.71, target -14 - so the second
 * is used, derived, when the first is missing.
 */
object Loudness {

    /**
     * The quietest this will ever make a clip: -20dB. Anything the formula puts below that is a
     * figure to distrust rather than a clip to silence.
     */
    const val MIN_GAIN = 0.1f

    /** YouTube's reference, for a response that states perceptual loudness but not its target. */
    private const val DEFAULT_TARGET_LKFS = -14.0

    fun parse(text: String): Double? {
        // indexOf first: a watch page is a megabyte, and a regex walking all of it from the top
        // for a key that appears once is work the scan does not need.
        val at = text.indexOf("\"audioConfig\"")
        if (at < 0) return null
        val open = text.indexOf('{', at)
        if (open < 0) return null
        // Only the object's own flat fields, up to its first nested object or its end. The
        // per-format `loudnessDb` on every adaptive audio stream is a DIFFERENT figure, and
        // stopping at the first brace is what keeps this from ever reaching one.
        var end = open + 1
        while (end < text.length && text[end] != '{' && text[end] != '}') end++
        val flat = text.substring(open + 1, end)
        number(flat, "loudnessDb")?.let { return it }
        val perceptual = number(flat, "perceptualLoudnessDb") ?: return null
        return perceptual - (number(flat, "loudnessTargetLkfs") ?: DEFAULT_TARGET_LKFS)
    }

    /** min(1, 10^(-loudnessDb/20)), floored at [MIN_GAIN]; unity when there is no usable figure. */
    fun gain(loudnessDb: Double?): Float {
        if (loudnessDb == null || loudnessDb.isNaN() || loudnessDb.isInfinite()) return 1f
        val linear = 10.0.pow(-loudnessDb / 20.0).toFloat()
        return linear.coerceIn(MIN_GAIN, 1f)
    }

    // The opening quote is part of the key, so "loudnessDb" can never match inside
    // "perceptualLoudnessDb".
    private fun number(flat: String, key: String): Double? =
        Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?)""")
            .find(flat)?.groupValues?.get(1)?.toDoubleOrNull()
}

/**
 * Carries the loudness figure out of the extractor's downloads to the resolve that caused them.
 *
 * NewPipeExtractor downloads the player responses through [NewPipeDownloader] and keeps the
 * parts it models; loudness is not one of them. But `StreamInfo.getInfo` runs synchronously on
 * the calling thread, so a thread-local opened around that call sees exactly the responses that
 * resolve fetched - and the tune and the neighbour prefetch, resolving at the same moment on two
 * threads, cannot see each other's.
 */
object LoudnessCapture {

    private val slot = ThreadLocal<Array<Double?>?>()

    /** Run [block] with a capture open; returns its result and the first figure seen, if any. */
    fun <T> capture(block: () -> T): Pair<T, Double?> {
        val previous = slot.get()
        val mine = arrayOf<Double?>(null)
        slot.set(mine)
        try {
            val result = block()
            return result to mine[0]
        } finally {
            slot.set(previous)
        }
    }

    /**
     * A response the downloader just read. Only the endpoints that return a player response are
     * scanned - `player`, the ANDROID client's `reel_item_watch`, and a watch page - and the FIRST
     * figure wins: the extractor asks several clients about the same clip, and they agree.
     */
    fun offer(url: String, body: String) {
        val mine = slot.get() ?: return
        if (mine[0] != null) return
        if (!url.contains("/player") && !url.contains("reel_item_watch") && !url.contains("/watch")) {
            return
        }
        mine[0] = Loudness.parse(body)
    }
}
