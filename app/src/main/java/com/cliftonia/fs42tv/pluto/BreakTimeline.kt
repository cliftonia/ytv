package com.cliftonia.fs42tv.pluto

import java.time.OffsetDateTime
import java.util.TreeMap

/** One media segment: its sequence number, length, PROGRAM-DATE-TIME if tagged, and kind. */
data class HlsSegment(
    val seq: Long,
    val durationMillis: Long,
    val programDateTime: Long?,
    val bumper: Boolean,
)

/** A media playlist's window, as read. */
data class HlsWindow(val targetDurationMillis: Long, val segments: List<HlsSegment>) {

    companion object {
        private val TARGET = Regex("""#EXT-X-TARGETDURATION:(\d+)""")
        private val SEQUENCE = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""")

        /**
         * The window in [body], or null when it is not a media playlist with segments in it.
         * Hand-rolled like the other playlist readers here: only five tags matter, and a parser
         * that throws on an unknown one would lose a read to Pluto's own PLUTO-SESSION-ID.
         */
        fun parse(body: String?): HlsWindow? {
            if (body == null || !body.contains("#EXTINF") || body.contains("#EXT-X-STREAM-INF")) return null
            val target = TARGET.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            var seq = SEQUENCE.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            var duration: Long? = null
            var pdt: Long? = null
            val segments = mutableListOf<HlsSegment>()
            for (raw in body.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXTINF:") -> duration = line.removePrefix("#EXTINF:")
                        .substringBefore(',').toDoubleOrNull()?.let { (it * 1000).toLong() }
                    line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> pdt = instant(line.substringAfter(':'))
                    line.isEmpty() || line.startsWith("#") -> Unit
                    else -> {
                        val length = duration ?: return null
                        segments += HlsSegment(seq++, length, pdt, BreakDetector.isBumper(line))
                        duration = null
                        pdt = null
                    }
                }
            }
            return if (segments.isEmpty()) null else HlsWindow(target * 1000, segments)
        }

        private fun instant(text: String): Long? =
            runCatching { OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli() }.getOrNull()
    }
}

/**
 * What the playlists have said so far about the break, in the stream's own clock: epoch
 * milliseconds of PROGRAM-DATE-TIME, the instant a segment's first frame was stamped with.
 *
 * Immutable - built on the polling thread and handed to the UI thread whole.
 */
data class BreakView(
    /** Every segment of the latest window has an instant: [start] and [end] can be trusted. */
    val timed: Boolean,
    /** The latest break's first bumper segment - or the first segment seen, tuned mid-break. */
    val start: Long?,
    /** The first programme segment after [start], once it has appeared. */
    val end: Long?,
    /** Where the latest window ends: its last segment's instant plus its length. */
    val edgeEnd: Long?,
    val targetDurationMillis: Long,
    /** The instants of the first window read after the tune, in order - the mpv anchor. */
    val firstWindowStarts: List<Long>,
    /**
     * Wall-clock milliseconds the first read's playlist was ASKED for - not answered: the anchor
     * compares it with the load, and the answer's wait (TLS, a redirect) is not the window's age.
     */
    val firstReadAt: Long,
    /** Wall-clock milliseconds the latest read was answered - the edge estimate's clock. */
    val readAt: Long,
    /**
     * How far behind the edge a player that starts three segments back sits: those three
     * segments' own lengths. Pluto's target is a ceiling (6), its segments ~5.005s, so three
     * targets would be ~3s wrong all the time.
     */
    val liveOffsetMillis: Long = 3 * targetDurationMillis,
    /** Six reads in a row said nothing: whatever the timeline says, nobody can vouch for it. */
    val blind: Boolean = false,
    /** The two-read detector's verdict, for when there are no timestamps. */
    val fallbackInBreak: Boolean = false,
) {

    /** Whether the viewer, seeing instant [onScreen], is in the break - and when that changes. */
    data class Decision(val inBreak: Boolean, val nextChangeAt: Long?)

    /**
     * The break as it stands at [onScreen]. The rules:
     *  - Up from [start] to [end], never past [MAX_BREAK_MILLIS] after [start]. A break is known
     *    by its start instant, so a channel stuck on the bumper has no later start to raise the
     *    card again after the ceiling: no flapping, and nothing to remember.
     *  - Not before [MIN_BREAK_MILLIS] of bumper is known. A one-segment bumper at a programme
     *    change is not a break, and the old two-read rule was what kept it off screen; this is
     *    that rule on the stream's clock. The player runs ~15s behind the window's edge, so a
     *    real break is normally confirmed well before the viewer reaches its start.
     */
    fun at(onScreen: Long): Decision {
        if (blind) return Decision(false, null)
        val begins = start ?: return Decision(false, null)
        val confirmed = (end ?: edgeEnd ?: begins) - begins
        if (confirmed < MIN_BREAK_MILLIS) return Decision(false, null)
        val ceiling = begins + MAX_BREAK_MILLIS
        val stop = minOf(end ?: ceiling, ceiling)
        return when {
            onScreen < begins -> Decision(false, begins)
            onScreen < stop -> Decision(true, stop)
            else -> Decision(false, null)
        }
    }

    companion object {
        const val MIN_BREAK_MILLIS = 10_000L

        /** Past any real break (one to three minutes measured), with room. */
        const val MAX_BREAK_MILLIS = BreakDetector.MAX_BREAK_MILLIS
    }
}

/**
 * The segments of one tune's playlist, kept across reads so a break's start and end are known
 * however the window slides between them.
 *
 * Measured Sep 2026 on real breaks: there are no break-duration markers at all, but every
 * programme/bumper change is an EXT-X-DISCONTINUITY followed by an EXT-X-PROGRAM-DATE-TIME. So a
 * segment's instant is its own PDT, or counted on from the one before (start + EXTINF), or - for
 * the programme before the first PDT in a window - counted back from the one after. Sequence
 * numbers carry instants from one read to the next when a window has no PDT of its own.
 *
 * Polling thread only.
 */
class BreakTimeline {

    private class Known(var start: Long?, val durationMillis: Long, val bumper: Boolean)

    private val known = TreeMap<Long, Known>()
    private var target = 0L
    private var lastWindow: List<Long> = emptyList()
    /**
     * The first window read after the tune - the one mpv read too - by sequence number. During a
     * long programme a window can carry no PDT at all (Pluto stamps the changes), so its instants
     * may only be known later, counted back from the next PDT; until then nothing is pruned.
     */
    private var firstWindowSeqs: List<Long>? = null
    private var firstWindowStarts: List<Long>? = null
    private var firstReadAt = 0L
    private var readAt = 0L
    private var liveOffset = 0L

    fun feed(window: HlsWindow, readAt: Long, requestedAt: Long = readAt) {
        target = window.targetDurationMillis
        this.readAt = readAt
        liveOffset = window.segments.takeLast(3).sumOf { it.durationMillis }
        window.segments.forEach { s ->
            val start = s.programDateTime ?: known[s.seq]?.start
            known[s.seq] = Known(start, s.durationMillis, s.bumper)
        }
        propagate()
        lastWindow = window.segments.map { it.seq }
        if (firstWindowSeqs == null) {
            firstWindowSeqs = lastWindow
            firstReadAt = requestedAt
        }
        if (firstWindowStarts == null) {
            val starts = firstWindowSeqs.orEmpty().map { known[it]?.start }
            if (starts.all { it != null }) firstWindowStarts = starts.filterNotNull()
        }
        val keep = if (firstWindowStarts == null) KEEP_UNANCHORED else KEEP
        while (known.size > keep) known.pollFirstEntry()
    }

    /** Instants forward from each known one, then backward to the segments before it. */
    private fun propagate() {
        var previous: Map.Entry<Long, Known>? = null
        for (entry in known.entries) {
            val p = previous
            if (entry.value.start == null && p != null && p.key == entry.key - 1) {
                p.value.start?.let { entry.value.start = it + p.value.durationMillis }
            }
            previous = entry
        }
        var next: Map.Entry<Long, Known>? = null
        for (entry in known.descendingMap().entries) {
            val n = next
            if (entry.value.start == null && n != null && n.key == entry.key + 1) {
                n.value.start?.let { entry.value.start = it - entry.value.durationMillis }
            }
            next = entry
        }
    }

    fun view(): BreakView {
        val window = lastWindow.mapNotNull { seq -> known[seq]?.let { seq to it } }
        val timed = window.isNotEmpty() && window.all { it.second.start != null }
        // The latest programme-to-bumper change among the timed segments. Against the segment
        // known before, contiguous or not: bumper either side of a gap in the reads is the same
        // break, so a lost read can never mint a later start - and with it a second card.
        val ordered = known.entries.filter { it.value.start != null }
        var start: Long? = null
        var end: Long? = null
        ordered.forEachIndexed { i, entry ->
            val before = ordered.getOrNull(i - 1)
            if (entry.value.bumper && (before == null || !before.value.bumper)) {
                start = entry.value.start
                end = null
            } else if (!entry.value.bumper && before != null && before.value.bumper && start != null && end == null) {
                end = entry.value.start
            }
        }
        val last = window.lastOrNull()?.second
        val edge = last?.start?.let { it + last.durationMillis }
        return BreakView(
            timed = timed,
            start = start,
            end = end,
            edgeEnd = edge,
            targetDurationMillis = target,
            firstWindowStarts = firstWindowStarts.orEmpty(),
            firstReadAt = firstReadAt,
            readAt = readAt,
            liveOffsetMillis = liveOffset,
        )
    }

    private companion object {
        /** Far more than a window (five segments), a little more than a long break's worth. */
        const val KEEP = 96

        /** An hour and a half of 5s segments, held while the mpv anchor waits for a PDT. */
        const val KEEP_UNANCHORED = 1_080
    }
}
