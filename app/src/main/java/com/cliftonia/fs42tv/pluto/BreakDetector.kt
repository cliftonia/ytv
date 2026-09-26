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
 *  - A read that says nothing - a failed fetch, a master, an empty window - counts for nothing.
 *    A flaky network must not raise the card, or take it down mid-break for one bad read.
 *  - But a break has a ceiling, because while it is up the programme is silent and the music
 *    loops: it ends after [MAX_UNKNOWN_READS] reads in a row that said nothing (the network is
 *    gone, and the programme may well be back), or after [MAX_BREAK_MILLIS], whichever is first.
 *    A real break is one to three minutes; anything longer is a channel stuck on the bumper
 *    (a legacy url looping it) or a playlist that stopped saying what it is.
 *  - After the time ceiling, no new break until a programme read has been seen. A channel stuck
 *    on the bumper then shows the bumper, once the card has had its five minutes, rather than
 *    flapping the card up and down every ten seconds for as long as it is watched. A break ended
 *    by unknown reads needs only the usual fresh confirmation: the network came back mid-break.
 *
 * Pure and single-threaded; one per tune, owned by [BreakPoller]'s run.
 */
class BreakDetector {

    enum class State { PROGRAMME, IN_BREAK }

    /** What one playlist window says. */
    enum class Read { BUMPER, PROGRAMME }

    /** Why the last break ended - for the log. */
    enum class End { PROGRAMME, UNKNOWN_READS, TIME_CEILING }

    var state: State = State.PROGRAMME
        private set

    var lastEnd: End? = null
        private set

    /** All-bumper reads since the last programme read, toward a break. */
    private var bumperReads = 0

    /** Reads in a row that said nothing, during a break. */
    private var unknownReads = 0

    /** When the break on now began, in [feed]'s clock. */
    private var breakSince = 0L

    /** A break hit the time ceiling: none again until the programme is seen. */
    private var stuck = false

    /**
     * Feed the next media-playlist body (null: the read failed), read at [nowMillis] on any
     * monotonic clock, and get the state after it.
     */
    fun feed(body: String?, nowMillis: Long): State {
        when (classify(body)) {
            Read.BUMPER -> {
                unknownReads = 0
                if (state == State.IN_BREAK) {
                    if (nowMillis - breakSince >= MAX_BREAK_MILLIS) {
                        stuck = true
                        end(End.TIME_CEILING)
                    }
                } else if (!stuck && ++bumperReads >= CONFIRM_READS) {
                    state = State.IN_BREAK
                    breakSince = nowMillis
                }
            }
            Read.PROGRAMME -> {
                stuck = false
                if (state == State.IN_BREAK) end(End.PROGRAMME)
                bumperReads = 0
                unknownReads = 0
            }
            null -> if (state == State.IN_BREAK && ++unknownReads >= MAX_UNKNOWN_READS) {
                end(End.UNKNOWN_READS)
            }
        }
        return state
    }

    private fun end(why: End) {
        state = State.PROGRAMME
        lastEnd = why
        bumperReads = 0
        unknownReads = 0
    }

    companion object {
        /** Two reads five seconds apart - about ten seconds of nothing but bumper. */
        const val CONFIRM_READS = 2

        /** Six reads - about thirty seconds - of saying nothing ends a break. */
        const val MAX_UNKNOWN_READS = 6

        /** Past any real break (one to three minutes measured), with room. */
        const val MAX_BREAK_MILLIS = 5 * 60_000L

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
