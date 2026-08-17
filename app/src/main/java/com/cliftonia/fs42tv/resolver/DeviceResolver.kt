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
class DeviceResolver : ClipResolver {

    override fun resolveDetailed(
        videoId: String,
        nowSeconds: Long,
        ladder: List<String>,
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
            val video = bestVideoForTier(info.videoOnlyStreams, name) ?: continue
            val videoUrl = video.content ?: continue
            val audioUrl = audio.content ?: continue
            val expires = expiryOf(videoUrl, audioUrl, nowSeconds)
            Log.i("fs42", "resolved $videoId at $name (${video.resolution}), expires in " +
                "${expires - nowSeconds}s")
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
            "uhd" -> 1081 to Int.MAX_VALUE
            "hd" -> 721 to 1080
            "sd" -> 0 to 720
            else -> return null
        }
        return streams.orEmpty()
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { heightOf(it) in low..high }
            // Highest inside the band, so `hd` on a clip offering both 720p and 1080p takes 1080p.
            .maxByOrNull { heightOf(it) }
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
