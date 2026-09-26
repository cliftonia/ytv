package com.cliftonia.fs42tv.pluto

/**
 * Whether a Pluto channel is in an ad break Pluto has nothing to fill, read from its playlist.
 *
 * Measured Sep 2026: every ~10-20 minutes a Pluto channel breaks for ~1-3 minutes, and with no
 * ads to insert for Australia the stitcher fills the break with Pluto's own logo bumper - the
 * media playlist then names ONLY clips like `..._ptv_7424adbumperanimationdotsinverted30_30fps/`
 * or `..._ptv_7424_ad_bumper_animation_dots_normal_30_1/`. Programme segments are
 * `/clip/<id>_<title>/...` or `/live/v1/prd/<Channel>/...`. The same on the jmp2 and direct
 * routes, so the detector does not care which one is playing.
 *
 * The rules, each against a way of being wrong on screen:
 *  - IN a break only after [CONFIRM_READS] all-bumper windows in a row. One bumper segment at a
 *    programme transition can fill a whole window for a single read, and a card flashing up for
 *    five seconds is worse than a bumper nobody minds.
 *  - OUT on the FIRST window with anything else in it: the programme is back, and every second
 *    of it spent behind a card is a second the viewer came for.
 *  - A read that says nothing - a failed fetch, a master, an empty window - changes nothing and
 *    counts for nothing. A flaky network must not raise the card, or take it down mid-break.
 *
 * Pure and single-threaded; one per tune, owned by [BreakPoller]'s run.
 */
class BreakDetector {

    enum class State { PROGRAMME, IN_BREAK }

    /** What one playlist window says. */
    enum class Read { BUMPER, PROGRAMME }

    var state: State = State.PROGRAMME
        private set

    /** All-bumper reads since the last programme read. */
    private var bumperReads = 0

    /** Feed the next media-playlist body (null: the read failed) and get the state after it. */
    fun feed(body: String?): State {
        when (classify(body)) {
            Read.BUMPER -> {
                bumperReads++
                if (bumperReads >= CONFIRM_READS) state = State.IN_BREAK
            }
            Read.PROGRAMME -> {
                bumperReads = 0
                state = State.PROGRAMME
            }
            null -> Unit
        }
        return state
    }

    companion object {
        /** Two reads five seconds apart - about ten seconds of nothing but bumper. */
        const val CONFIRM_READS = 2

        private val BUMPER = Regex("ad_?bumper", RegexOption.IGNORE_CASE)

        /** The segment urls of a media playlist - every non-blank line that is not a tag. */
        fun segments(body: String): List<String> =
            body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()

        /**
         * What [body] says, or null when it says nothing: not a media playlist (a master, an error
         * page) or a window with no segments in it.
         */
        fun classify(body: String?): Read? {
            if (body == null || !body.contains("#EXTINF")) return null
            val segments = segments(body)
            if (segments.isEmpty()) return null
            return if (segments.all { BUMPER.containsMatchIn(it) }) Read.BUMPER else Read.PROGRAMME
        }
    }
}

/** Finding the media playlist under a master - the one the break poller actually reads. */
object HlsVariants {

    private val BANDWIDTH = Regex("""[:,]BANDWIDTH=(\d+)""")

    /**
     * The media playlist to read for [body], fetched from [url] (after redirects - relative
     * variants resolve against where the master actually came from): the lowest-bandwidth
     * variant of a master, since any variant carries the same segment names and the smallest is
     * the cheapest to fetch; [url] itself when it already is a media playlist; else null.
     */
    fun mediaPlaylist(body: String, url: String): String? {
        if (!body.contains("#EXT-X-STREAM-INF")) return url.takeIf { body.contains("#EXTINF") }
        val lines = body.lineSequence().map { it.trim() }.toList()
        val variants = lines.mapIndexedNotNull { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() }
                ?.takeUnless { it.startsWith("#") } ?: return@mapIndexedNotNull null
            val bandwidth = BANDWIDTH.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE
            uri to bandwidth
        }
        val chosen = variants.minByOrNull { it.second }?.first ?: return null
        // java.net.URL rather than URI: URL is lenient about characters a stitcher's query may
        // carry unescaped, where URI would throw and leave the channel unreadable for good.
        return runCatching { java.net.URL(java.net.URL(url), chosen).toString() }.getOrNull()
    }
}
