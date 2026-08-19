package com.cliftonia.fs42tv.resolver

import android.media.MediaCodecList
import android.util.Log

/**
 * Ask this device's decoders what they can actually do.
 *
 * Every alternative to asking is a guess. Screen height says what the panel can SHOW, not what the
 * silicon can decode, and those differ on exactly the device that matters here: a 4K TCL with a
 * 32-bit userspace will happily report 2160 and then crash on a 2160p VP9 stream, because YouTube
 * publishes nothing above 1080p in H.264 and VP9 is all that is left up there.
 *
 * Queried once and cached. MediaCodecList walks every codec on the device, which is tens of
 * milliseconds - nothing on its own, and far too much to repeat per clip.
 */
object AndroidDecoders {

    /**
     * The frame rate a resolution has to sustain before it counts as usable.
     *
     * Thirty rather than sixty. Most of the dial is 24 to 30fps and demanding 60 would refuse
     * resolutions that play perfectly; but a decoder that cannot reach thirty at a size will not
     * play anything at that size smoothly, which is exactly the fault this exists to prevent.
     */
    private const val MIN_SUSTAINABLE_FPS = 30.0

    /** Heights worth asking about, largest first. */
    private val CANDIDATE_HEIGHTS = listOf(2160 to 3840, 1440 to 2560, 1080 to 1920, 720 to 1280)

    private val MIME_TO_FAMILY = mapOf(
        "video/avc" to DecoderSupport.AVC,
        "video/x-vnd.on2.vp9" to DecoderSupport.VP9,
        "video/av01" to DecoderSupport.AV1,
        "video/hevc" to DecoderSupport.HEVC,
    )

    @Volatile
    private var cached: DecoderSupport? = null

    /**
     * The largest height this decoder can hold a real frame rate at.
     *
     * NOT `supportedHeights.upper`, which is what the decoder will ACCEPT. A decoder routinely
     * advertises 2160 and cannot sustain it: it takes the stream, decodes too slowly, and the
     * picture stutters. That is the reported symptom of 4K on this television - janky on both
     * engines, which rules the player out and leaves the decoder.
     *
     * `areSizeAndRateSupported` asks the question that actually matters, and
     * `getAchievableFrameRatesFor` is better still where a device provides it: the first is the
     * vendor's claim, the second is measured. Where both exist the measured one wins.
     */
    private fun sustainableHeight(caps: android.media.MediaCodecInfo.VideoCapabilities): Int? {
        for ((height, width) in CANDIDATE_HEIGHTS) {
            val ok = runCatching {
                val achievable = caps.getAchievableFrameRatesFor(width, height)
                if (achievable != null) achievable.upper.toDouble() >= MIN_SUSTAINABLE_FPS
                else caps.areSizeAndRateSupported(width, height, MIN_SUSTAINABLE_FPS)
            }.getOrDefault(false)
            if (ok) return height
        }
        return null
    }

    fun support(): DecoderSupport = cached ?: synchronized(this) {
        cached ?: detect().also { cached = it }
    }

    private fun detect(): DecoderSupport {
        val best = mutableMapOf<String, Int>()
        try {
            // REGULAR_CODECS rather than ALL_CODECS: the extra entries ALL_CODECS returns are
            // ones the platform will not actually hand out to an ordinary app, so counting them
            // would be another way of believing something the device cannot do.
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    val family = MIME_TO_FAMILY[type.lowercase()] ?: continue
                    val height = runCatching {
                        val caps = info.getCapabilitiesForType(type).videoCapabilities
                            ?: return@runCatching null
                        sustainableHeight(caps)
                    }.getOrNull() ?: continue
                    if (height > best.getOrElse(family) { 0 }) best[family] = height
                }
            }
        } catch (e: Exception) {
            // A device that will not answer gets the conservative answer, not an optimistic one.
            Log.w("fs42", "could not read decoder capabilities: $e")
            return DecoderSupport.CONSERVATIVE
        }
        if (best.isEmpty()) {
            Log.w("fs42", "no video decoders reported; assuming h264 to 1080p")
            return DecoderSupport.CONSERVATIVE
        }
        val summary = best.entries.sortedBy { it.key }
            .joinToString { "${it.key} to ${it.value}p" }
        Log.i("fs42", "decoders sustaining ${MIN_SUSTAINABLE_FPS.toInt()}fps: $summary")
        PlaybackDiagnostics.recordDecoders(summary)
        return DecoderSupport(best)
    }
}
