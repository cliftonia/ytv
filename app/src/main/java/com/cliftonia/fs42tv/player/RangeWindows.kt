package com.cliftonia.fs42tv.player

/**
 * Splits a byte span into BOUNDED windows, which is the whole trick.
 *
 * googlevideo throttles an open-ended request to roughly the video's own bitrate and serves a
 * bounded one at line speed - measured on one URL within a second: 2.57 Mbps with no Range header
 * and 2.57 Mbps with `Range: bytes=0-`, against 398.85 Mbps for `Range: bytes=0-8388607`. It is
 * the BOUNDEDNESS that matters, not the presence of the header.
 *
 * Media3 gets this from [ChunkedDataSource]. mpv has no equivalent - it asks ffmpeg for the whole
 * file and is throttled to 3.61 Mbps, barely above the 2.2 Mbps the content needs, which is why
 * it takes seven seconds to show a picture. [ChunkedProxy] uses these windows to fetch on mpv's
 * behalf, so the same discovery serves both engines.
 *
 * Pure arithmetic, no I/O: this is the part worth testing, and the part that is wrong in every
 * off-by-one way if it is written inline in a socket loop.
 */
object RangeWindows {

    /**
     * The windows covering [start] until [endInclusive], none larger than [size].
     *
     * Both ends are INCLUSIVE, matching HTTP's `Range: bytes=a-b`, because converting between
     * that and an exclusive end is exactly where this goes wrong: an off-by-one here is a stream
     * that plays with one byte missing per window, which decodes as corruption rather than as an
     * error anyone can find.
     */
    fun of(start: Long, endInclusive: Long, size: Long): List<LongRange> {
        require(size > 0) { "window size must be positive, was $size" }
        if (endInclusive < start) return emptyList()
        val out = ArrayList<LongRange>()
        var at = start
        // The first window is deliberately smaller, then it ramps.
        //
        // mpv opens a file, reads enough header to know its shape, and then seeks straight to the
        // clock offset - abandoning the connection. Committing to a full window up front means
        // fetching megabytes that are discarded before the seek can even start. Beginning small
        // gets those first bytes moving sooner and wastes far less when the client walks away,
        // while later windows still get the full bounded size that defeats the throttle.
        var next = minOf(FIRST_WINDOW, size)
        while (at <= endInclusive) {
            val last = minOf(at + next - 1, endInclusive)
            out.add(at..last)
            at = last + 1
            next = minOf(next * 4, size)
        }
        return out
    }

    /**
     * The byte range an HTTP `Range` header asks for, or null when there is no usable one.
     *
     * Only `bytes=a-` and `bytes=a-b` are handled. A suffix range (`bytes=-500`) is deliberately
     * NOT: mpv never sends one for progressive media, and quietly mishandling a form we do not
     * support would be worse than declining it and serving the whole resource.
     */
    fun parse(header: String?, totalLength: Long): LongRange? {
        val spec = header?.trim()?.removePrefix("bytes=")?.takeIf { header.startsWith("bytes=") }
            ?: return null
        if (spec.startsWith("-")) return null
        val parts = spec.split("-", limit = 2)
        val from = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: return null
        if (from < 0) return null
        val to = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()
            ?: (totalLength - 1)
        if (to < from) return null
        return from..minOf(to, totalLength - 1)
    }

    /**
     * 8 MB, measured rather than picked: 2 MB already reached 154 Mbps and 8 MB reached 399, so
     * the win is mostly there by 2 and comfortably saturated by 8. Larger windows mean fewer
     * requests but a longer stall whenever one has to be retried, and 8 MB is roughly four seconds
     * of a 1080p stream - short enough to recover from, long enough that the per-request overhead
     * disappears.
     *
     * Used by both engines: [ChunkedProxy] fetches mpv's bytes in windows of this size, and it is
     * also the chunk [ChunkedDataSource] opens for Media3. Written once because it is one measured
     * number, not two that happen to agree.
     */
    const val DEFAULT_WINDOW = 8L * 1024 * 1024

    /**
     * 512 KB, enough for an mp4 header without committing to a window that will be abandoned.
     * Still bounded, so it is served at line speed like every other request here.
     */
    const val FIRST_WINDOW = 512L * 1024

    /**
     * The total resource size stated by a `Content-Range: bytes a-b/total` header, or null.
     *
     * The total appears nowhere else. Without it, Media3 treats the stream as unbounded, and the
     * proxy has no length to clamp a client's range against - so the same three-line parse was
     * written twice, in a data source and in a socket loop, and only one of them was reachable
     * from a test.
     *
     * The header LOOKUP stays at each call site rather than moving here, because the two are
     * genuinely different: Media3 hands back a raw `Map<String, List<String>>` needing both
     * casings tried, while `HttpURLConnection.getHeaderField` is already case-insensitive.
     */
    fun totalLength(header: String?): Long? =
        header?.substringAfter('/', "")?.trim()?.toLongOrNull()
}
