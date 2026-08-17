package com.cliftonia.fs42tv.resolver

import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Resolves a YouTube id on the television, with no server involved.
 *
 * This replaced a `/resolve` endpoint that ran yt-dlp on a mini-pc at home. That machine is gone,
 * and it was the only thing standing between the app and working anywhere there is internet -
 * which matters most for the set in the car, where "anywhere" means a phone hotspot rather than
 * the house network.
 *
 * The trade is honesty about failure. yt-dlp is updated within hours of YouTube changing anything;
 * a library baked into an installed apk is not. So every failure here returns null, the caller
 * skips the clip, and [TierLadder] means a clip usually has another rung to fall to before the
 * viewer sees anything at all.
 */
class DeviceResolver(
    /**
     * What this device can decode. Injected so the policy can be tested without an Android
     * runtime, and defaulted so nothing else has to know it exists.
     */
    private val decoders: DecoderSupport = AndroidDecoders.support(),
) : ClipResolver {

    override fun resolveDetailed(
        videoId: String,
        nowSeconds: Long,
        ladder: List<String>,
        refused: Set<String>,
    ): ClipResolver.Resolved? {
        ensureInitialised()
        val info = runCatching {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        }.getOrElse {
            Log.w("fs42", "resolve failed for $videoId: $it")
            return null
        }

        val audio = bestAudio(info.audioStreams) ?: run {
            // Every YouTube video has an audio track, so no usable audio stream means the whole
            // extraction came back wrong rather than this clip being unusual. Falling through to
            // a silent video would be worse than skipping it.
            Log.w("fs42", "no audio stream for $videoId")
            return null
        }

        for (name in ladder) {
            // A rung the CDN already refused this session will be refused again; walking onto it
            // costs a playback attempt and several seconds of black for a known answer.
            if (StreamResolver.refusedKey(videoId, name) in refused) continue
            val video = bestVideoForTier(info.videoOnlyStreams, name) ?: continue
            val videoUrl = video.content ?: continue
            val audioUrl = audio.content ?: continue
            val expires = expiryOf(videoUrl, audioUrl, nowSeconds)
            PlaybackDiagnostics.record(name, video.resolution, video.codec)
            Log.i("fs42", "resolved $videoId at $name (${video.resolution} ${video.codec}), " +
                "expires in ${expires - nowSeconds}s")
            return ClipResolver.Resolved(Progressive(videoUrl, audioUrl), expires)
        }
        Log.w("fs42", "no tier of $ladder available for $videoId")
        return null
    }

    /**
     * The best video-only stream sitting inside [tier]'s height band.
     *
     * Bands rather than "at or below", so the rungs are disjoint the way the server's published
     * tiers were. If they overlapped, a clip with no 1080p rendition would resolve `hd` and `sd`
     * to the same 720p stream and the ladder would try the identical url twice before giving up.
     *
     * Video-only, never the muxed progressive streams: YouTube caps those at 360p, so accepting
     * one would silently hand a 4K panel a picture from 2009.
     */
    private fun bestVideoForTier(streams: List<VideoStream>?, tier: String): VideoStream? {
        val (low, high) = when (tier) {
            // Capped at 2160, NOT open-ended. YouTube publishes 4320p on a growing number of
            // uploads, and an unbounded top band takes it: four times the pixels of the panel's
            // native resolution, on a television with 2.34GB of memory in total. The server this
            // replaced capped its top tier at 2160 and this quietly did not.
            "uhd" -> 1081 to 2160
            "hd" -> 721 to 1080
            "sd" -> 0 to 720
            else -> return null
        }
        return streams.orEmpty()
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { heightOf(it) in low..high }
            // Only what this device's decoders say they can handle. Without this the 4K television
            // takes the 2160p rendition of everything - which above 1080p is always VP9, because
            // YouTube publishes no H.264 up there - and a 32-bit panel either draws nothing or
            // takes the app down with it inside mediacodec.
            .filter { decoders.canPlay(it.codec, heightOf(it)) }
            // Highest inside the band, then H.264 ahead of an equal-height VP9. Both usually play
            // where both are offered, but H.264 is the one every device has decoded in hardware
            // for fifteen years, and the cost of preferring it is nothing.
            .maxWithOrNull(
                compareBy<VideoStream> { heightOf(it) }
                    .thenBy { if (DecoderSupport.family(it.codec) == DecoderSupport.AVC) 1 else 0 }
            )
    }

    /**
     * Height in pixels, read from the resolution label.
     *
     * The label is what the extractor has always exposed and it survives version changes that the
     * numeric accessors have not. It reads like "1080p60" or "2160p", so everything up to the `p`
     * is the height; anything unparseable sorts to the bottom rather than throwing.
     */
    private fun heightOf(stream: VideoStream): Int =
        stream.resolution?.substringBefore('p')?.toIntOrNull() ?: 0

    /**
     * The loudest-quality audio track available.
     *
     * m4a is preferred over an equal-bitrate opus stream: the television in the lounge is a 32-bit
     * armeabi-v7a device whose hardware AAC decoder is a known quantity, while opus falls to
     * software. Bitrate still wins over container - a 160k opus beats a 48k m4a.
     */
    private fun bestAudio(streams: List<AudioStream>?): AudioStream? =
        streams.orEmpty()
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxWithOrNull(
                compareBy<AudioStream> { it.averageBitrate }
                    .thenBy { if (it.format?.suffix == "m4a") 1 else 0 }
            )

    /**
     * When the signed urls stop working.
     *
     * Read from the `expire` parameter googlevideo puts on every url, taking the EARLIER of the
     * two: the pair is useless the moment either half dies, and caching to the later one would
     * leave a clip that plays picture with no sound.
     *
     * A url with no readable expiry gets a deliberately short life rather than a generous guess.
     * Guessing long means the cache confidently serves a dead url and the viewer sees a stand-by
     * card; guessing short costs one extra resolve.
     */
    private fun expiryOf(videoUrl: String, audioUrl: String, nowSeconds: Long): Long {
        val stated = listOfNotNull(expireParam(videoUrl), expireParam(audioUrl)).minOrNull()
        return stated ?: (nowSeconds + FALLBACK_LIFETIME_SECONDS)
    }

    private fun expireParam(url: String): Long? =
        EXPIRE.find(url)?.groupValues?.get(1)?.toLongOrNull()

    private companion object {
        /** `expire=1754812345`, in seconds since the epoch, as googlevideo writes it. */
        val EXPIRE = Regex("[?&]expire=(\\d+)")
        const val FALLBACK_LIFETIME_SECONDS = 3600L

        @Volatile
        private var initialised = false

        /**
         * Hand NewPipeExtractor its downloader, once per process.
         *
         * Localised to Australia deliberately. YouTube varies what it serves by region, and the
         * dial is watched in Brisbane - resolving as though the viewer were in the United States
         * returns renditions and availability that do not match what the lineup was built from.
         */
        @Synchronized
        fun ensureInitialised() {
            if (initialised) return
            NewPipe.init(
                NewPipeDownloader(),
                Localization("en", "AU"),
                ContentCountry("AU"),
            )
            initialised = true
        }
    }
}
