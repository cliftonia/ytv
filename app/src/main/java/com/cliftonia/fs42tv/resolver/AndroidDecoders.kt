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

    private val MIME_TO_FAMILY = mapOf(
        "video/avc" to DecoderSupport.AVC,
        "video/x-vnd.on2.vp9" to DecoderSupport.VP9,
        "video/av01" to DecoderSupport.AV1,
        "video/hevc" to DecoderSupport.HEVC,
    )

    @Volatile
    private var cached: DecoderSupport? = null

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
                    val heights = runCatching {
                        info.getCapabilitiesForType(type).videoCapabilities?.supportedHeights?.upper
                    }.getOrNull() ?: continue
                    if (heights > best.getOrElse(family) { 0 }) best[family] = heights
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
        Log.i("fs42", "decoders: " + best.entries.sortedBy { it.key }
            .joinToString { "${it.key} to ${it.value}p" })
        return DecoderSupport(best)
    }
}
