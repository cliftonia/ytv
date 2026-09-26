package com.cliftonia.fs42tv.pluto

import com.cliftonia.fs42tv.resolver.TierBands

/**
 * One media playlist out of an HLS master - plus its separate audio, if it has one - for mpv.
 *
 * WHY: mpv's demuxer is ffmpeg's, and ffmpeg opens a master by fetching and probing EVERY variant
 * and rendition in it before the first frame. Measured Sep 2026 with the same mpv/ffmpeg on a Mac,
 * a five-variant Pluto master with a SUBTITLES group took 7-11s to open (`--hls-bitrate=min` did
 * not help, ~7.5s) and one of its media playlists opened directly took ~3s; the TCL logged 8.2-9.2s
 * to a first frame on every Pluto tune. Media3 reads a master lazily and is left on it.
 *
 * Policy: the highest BANDWIDTH whose RESOLUTION fits the QUALITY setting's height ceiling
 * ([heightCap]); among variants that do not state a resolution, the highest at or under
 * [DEFAULT_MAX_BANDWIDTH]; when nothing fits, the smallest. SUBTITLES are ignored - the app draws
 * its own captions. A variant naming an AUDIO group plays that group's DEFAULT rendition (else its
 * first) - chosen FIRST, then its URI read: a chosen rendition without a URI is audio muxed into
 * the variant, whatever an alternate in the group carries.
 *
 * SEPARATE AUDIO IS OFF ([SEPARATE_AUDIO]): a pick that needs its audio from a rendition playlist
 * is null, and the channel plays its master. The path (mpv `audio-file` beside the variant, see
 * MpvSource) is kept, tested, for later: an external audio-file on a LIVE HLS rendition can go
 * silent with no error at all, and a second demuxer has its own clock across Pluto's
 * discontinuities - a mute or drifting channel is worse than a slow start.
 *
 * Null - play the master exactly as before - whenever the pick might play silent: an AUDIO group
 * named but not declared, or a CODECS list naming only video with no audio rendition beside it.
 * A slow start is a better failure than a mute channel.
 *
 * Hand-rolled like the other playlist readers here (org.json-style stubs and throwing parsers
 * have both cost this codebase before); pure, so every rule above is a JVM test.
 */
object HlsMaster {

    /** A choice, as absolute urls. Carries the session's token - never log the urls. */
    data class Pick(val videoUrl: String, val audioUrl: String?, val bandwidth: Long?, val height: Int?)

    /**
     * Where no RESOLUTION says otherwise: about a 720p Pluto variant. Past this a variant's first
     * segments cost the start more than its picture gives back on a live channel.
     */
    const val DEFAULT_MAX_BANDWIDTH = 3_500_000L

    /** Whether a separate audio rendition may be played beside the variant. See the class comment. */
    const val SEPARATE_AUDIO = false

    private class Variant(val uri: String, val attrs: Map<String, String>) {
        val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull()
        val height = attrs["RESOLUTION"]?.substringAfter('x', "")?.toIntOrNull()
        val audioGroup = attrs["AUDIO"]
    }

    // A quoted value may hold commas (CODECS="avc1.4d401f,mp4a.40.2"); an unquoted one ends at one.
    private val ATTRIBUTE = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private val VIDEO_CODECS = listOf("avc1", "avc3", "hvc1", "hev1", "vp09", "vp8", "av01", "dvh1", "dvhe")

    fun isMaster(body: String): Boolean = body.contains("#EXT-X-STREAM-INF")

    /** A tag line's attribute list, quotes taken off. */
    fun attributes(line: String): Map<String, String> =
        ATTRIBUTE.findAll(line.substringAfter(':')).associate { m ->
            m.groupValues[1] to m.groupValues[2].removeSurrounding("\"")
        }

    /** The QUALITY setting's ceiling in pixels: its top rung's band ("hd" is 1080). */
    fun heightCap(ladder: List<String>): Int =
        ladder.firstOrNull()?.let(TierBands::bandFor)?.last ?: 1080

    /** What mpv should open for master [body], fetched from [url] (after redirects), or null. */
    fun choose(body: String, url: String, maxHeight: Int, separateAudio: Boolean = SEPARATE_AUDIO): Pick? {
        if (!isMaster(body)) return null
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val variants = lines.mapIndexedNotNull { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val uri = lines.getOrNull(i + 1)?.takeUnless { it.startsWith("#") } ?: return@mapIndexedNotNull null
            Variant(uri, attributes(line))
        }
        val chosen = pickVariant(variants) { it <= maxHeight } ?: return null
        val audio = chosen.audioGroup?.let { group ->
            val renditions = lines.filter { it.startsWith("#EXT-X-MEDIA:") }.map(::attributes)
                .filter { it["TYPE"] == "AUDIO" && it["GROUP-ID"] == group }
            if (renditions.isEmpty()) return null
            val rendition = renditions.firstOrNull { it["DEFAULT"] == "YES" } ?: renditions.first()
            rendition["URI"]
        }
        if (audio != null && !separateAudio) return null
        if (audio == null && videoOnly(chosen)) return null
        val videoUrl = absolute(url, chosen.uri) ?: return null
        val audioUrl = audio?.let { absolute(url, it) ?: return null }
        return Pick(videoUrl, audioUrl, chosen.bandwidth, chosen.height)
    }

    private fun pickVariant(variants: List<Variant>, fits: (Int) -> Boolean): Variant? {
        val eligible = variants.filter { v ->
            v.height?.let(fits) ?: ((v.bandwidth ?: 0L) <= DEFAULT_MAX_BANDWIDTH)
        }
        return eligible.maxByOrNull { it.bandwidth ?: 0L }
            ?: variants.minByOrNull { it.bandwidth ?: Long.MAX_VALUE }
    }

    /** A CODECS list that names only video: nothing audible in the variant itself. */
    private fun videoOnly(v: Variant): Boolean {
        val codecs = v.attrs["CODECS"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (codecs.isNullOrEmpty()) return false
        return codecs.all { codec -> VIDEO_CODECS.any { codec.startsWith(it) } }
    }

    // java.net.URL rather than URI: URL is lenient about characters a stitcher's query may carry
    // unescaped, where URI would throw. Same reasoning as HlsVariants.
    private fun absolute(base: String, uri: String): String? =
        runCatching { java.net.URL(java.net.URL(base), uri).toString() }.getOrNull()
}
