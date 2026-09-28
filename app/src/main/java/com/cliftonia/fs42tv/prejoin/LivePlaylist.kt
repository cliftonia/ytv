package com.cliftonia.fs42tv.prejoin

/**
 * The little of a live HLS media playlist the pre-join needs: which segment ffmpeg will start
 * on, how often the playlist moves, and the same text with every uri made absolute - so it can be
 * served from 127.0.0.1 without a single relative uri resolving against the loopback address.
 *
 * WHERE FFMPEG STARTS: its hls demuxer joins a live playlist at `live_start_index` -3 - the third
 * segment from the end (see [com.cliftonia.fs42tv.pluto.OnScreen]) - and mpv leaves that alone.
 * So the segments worth holding are that one and the one after it ([joinSegments]), not the
 * newest: those are what the first frame waits on.
 *
 * NOT WARMED: a master (nothing to join), an ended playlist, and byte-range segments, whose
 * bytes are a slice of a shared resource this cache does not model. Those are simply played as
 * before, from the network.
 *
 * Pure: no network, no Android.
 */
object LivePlaylist {

    /** One media segment: its absolute url and its media sequence number. */
    data class Segment(val url: String, val sequence: Long)

    class Parsed(
        /** `#EXT-X-TARGETDURATION`, in milliseconds - how often a live playlist gains a segment. */
        val targetMillis: Long,
        val segments: List<Segment>,
        /** `#EXT-X-ENDLIST`: not live, or a Pluto session that has moved on. */
        val ended: Boolean,
        /** `#EXT-X-BYTERANGE` anywhere: not warmed. */
        val byteRanges: Boolean,
    ) {
        /** Whether a live join of this playlist can be served from memory at all. */
        val warmable: Boolean get() = !ended && !byteRanges && segments.size >= 1 && targetMillis > 0
    }

    /** Where ffmpeg starts a live playlist: this many segments from its end. */
    const val LIVE_START_FROM_END = 3

    /**
     * [body], fetched from [baseUrl] (after redirects), as a media playlist - or null when it is
     * not one: no `#EXTM3U`, or a master.
     */
    fun parse(body: String, baseUrl: String): Parsed? {
        if (!body.trimStart('﻿', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")) return null
        if (body.contains("#EXT-X-STREAM-INF")) return null
        var target = 0L
        var sequence = 0L
        var byteRanges = false
        val segments = mutableListOf<Segment>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") ->
                    target = ((line.substringAfter(':').trim().toDoubleOrNull() ?: 0.0) * 1000).toLong()
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-BYTERANGE") -> byteRanges = true
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val url = absolute(baseUrl, line) ?: return null
                    segments += Segment(url, sequence + segments.size)
                }
            }
        }
        return Parsed(target, segments, body.contains("#EXT-X-ENDLIST"), byteRanges)
    }

    /** The segment ffmpeg joins [parsed] on, and up to [count] - 1 after it. */
    fun joinSegments(parsed: Parsed, count: Int): List<Segment> =
        parsed.segments.drop((parsed.segments.size - LIVE_START_FROM_END).coerceAtLeast(0)).take(count)

    /**
     * [body] with every uri absolute against [baseUrl] - segments, and every `URI="..."` in a tag
     * (keys, maps) - except the segments [local] names: those become what it returns, a path
     * relative to the proxy's own playlist. Everything else - durations, sequence numbers,
     * discontinuities, dates, cue tags - is left exactly as it was, so ffmpeg and the break
     * detector read the same playlist they would have. Null when a uri cannot be resolved.
     */
    fun rewrite(body: String, baseUrl: String, local: (absoluteUrl: String) -> String?): String? {
        val out = StringBuilder(body.length + 256)
        for (raw in body.lineSequence()) {
            val line = raw.trimEnd('\r')
            val trimmed = line.trim()
            val rewritten = when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> rewriteAttributes(line, baseUrl) ?: return null
                else -> {
                    val url = absolute(baseUrl, trimmed) ?: return null
                    local(url) ?: url
                }
            }
            out.append(rewritten).append('\n')
        }
        // lineSequence yields a last, empty line for a body ending in a newline; keep one only.
        while (out.endsWith("\n\n")) out.setLength(out.length - 1)
        return out.toString()
    }

    private val URI_ATTRIBUTE = Regex("URI=\"([^\"]*)\"")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    private fun rewriteAttributes(line: String, baseUrl: String): String? {
        var failed = false
        val out = URI_ATTRIBUTE.replace(line) { match ->
            val uri = match.groupValues[1].trim()
            // skd://, data: and the like are the player's to handle, as they always were.
            val scheme = SCHEME.find(uri)?.groupValues?.get(1)?.lowercase()
            if (uri.isEmpty() || (scheme != null && scheme != "http" && scheme != "https")) {
                match.value
            } else {
                val url = absolute(baseUrl, uri)
                if (url == null) { failed = true; match.value } else "URI=\"$url\""
            }
        }
        return if (failed) null else out
    }

    /** [uri] resolved against [base]; null when either will not parse. */
    fun absolute(base: String, uri: String): String? =
        runCatching { java.net.URL(java.net.URL(base), uri).toString() }.getOrNull()
}
