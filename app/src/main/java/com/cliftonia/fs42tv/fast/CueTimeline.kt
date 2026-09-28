package com.cliftonia.fs42tv.fast

import com.cliftonia.fs42tv.pluto.BreakView
import java.util.TreeMap

/**
 * The segments and cues of one tune's FAST playlist, kept across reads so a break's start and
 * end are known however the window slides between them - the cue-tag twin of pluto/BreakTimeline,
 * and like it the maker of a [BreakView] on the stream's own clock.
 *
 * THE CLOCK. When the stream stamps EXT-X-PROGRAM-DATE-TIME, a segment's instant is its PDT, or
 * counted on (start + EXTINF) or back from a neighbour's, sequence numbers carrying them from one
 * read to the next - exactly as BreakTimeline does. Some FAST streams stamp nothing: then the
 * tune's clock is COUNTED - the first window read is taken to end at the moment it was asked for
 * (a live window's edge is "now", to within a segment), and every instant is counted from there by
 * EXTINF. That is the clock [com.cliftonia.fs42tv.pluto.OnScreen]'s edge estimate and mpv anchor
 * already use, so the break lands on screen as a stamped one would; only an engine's own PDT
 * cannot be mixed with it ([BreakView.counted]). A tune decides which at its first window.
 * DATERANGE cues are absolute dates, so a counted clock cannot place them and leaves them out.
 *
 * Polling thread only.
 */
class CueTimeline {

    private class Known(var start: Long?, val durationMillis: Long, val cues: List<Cue>)

    private val known = TreeMap<Long, Known>()
    private val dates = LinkedHashMap<String, DateCue>()
    private var counted: Boolean? = null
    private var target = 0L
    private var lastWindow: List<Long> = emptyList()
    private var firstWindowSeqs: List<Long>? = null
    private var firstWindowStarts: List<Long>? = null
    private var firstReadAt = 0L
    private var readAt = 0L
    private var liveOffset = 0L

    fun feed(window: CueWindow, readAt: Long, requestedAt: Long = readAt) {
        val counted = counted ?: window.segments.none { it.programDateTime != null }.also { counted = it }
        target = window.targetDurationMillis
        this.readAt = readAt
        liveOffset = window.segments.takeLast(3).sumOf { it.durationMillis }
        window.segments.forEach { s ->
            val old = known[s.seq]
            val start = s.programDateTime.takeUnless { counted } ?: old?.start
            // A cue read once stays: the tag above a segment is the same on every read of it.
            known[s.seq] = Known(start, s.durationMillis, s.cues.ifEmpty { old?.cues.orEmpty() })
        }
        propagate()
        if (counted) {
            // No overlap with anything known - the first read, or reads lost for a whole window:
            // this window's edge is the moment it was asked for.
            val last = known.getValue(window.segments.last().seq)
            if (last.start == null) {
                last.start = requestedAt - last.durationMillis
                propagate()
            }
        } else {
            window.dates.forEach { d -> dates[d.id] = dates[d.id]?.merged(d) ?: d }
        }
        lastWindow = window.segments.map { it.seq }
        if (firstWindowSeqs == null) {
            firstWindowSeqs = lastWindow
            firstReadAt = requestedAt
        }
        if (firstWindowStarts == null) {
            val starts = firstWindowSeqs.orEmpty().map { known[it]?.start }
            if (starts.all { it != null }) firstWindowStarts = starts.filterNotNull()
        }
        prune()
    }

    private fun prune() {
        val keep = if (firstWindowStarts == null) KEEP_UNANCHORED else KEEP
        while (known.size > keep) known.pollFirstEntry()
        val oldest = known.values.firstNotNullOfOrNull { it.start } ?: return
        dates.values.removeAll { (it.endMillis ?: it.startMillis ?: Long.MIN_VALUE) < oldest - BreakView.MAX_BREAK_MILLIS }
        while (dates.size > MAX_DATES) dates.remove(dates.keys.first())
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
        val window = lastWindow.mapNotNull { known[it] }
        val timed = window.isNotEmpty() && window.all { it.start != null }
        val found = CueBreak.latest(events())
        val last = window.lastOrNull()
        return BreakView(
            timed = timed,
            start = found?.start,
            end = found?.end,
            edgeEnd = last?.start?.let { it + last.durationMillis },
            targetDurationMillis = target,
            firstWindowStarts = firstWindowStarts.orEmpty(),
            firstReadAt = firstReadAt,
            readAt = readAt,
            liveOffsetMillis = liveOffset,
            counted = counted == true,
        )
    }

    /** Every cue known, placed on the clock. */
    private fun events(): List<CueBreak.Event> {
        val events = mutableListOf<CueBreak.Event>()
        known.values.forEach { k ->
            val at = k.start ?: return@forEach
            k.cues.forEach { cue ->
                events += when (cue) {
                    is Cue.Out -> CueBreak.Event.Out(at, cue.durationMillis)
                    // Joined mid-break: the break began ElapsedTime before this segment.
                    is Cue.Cont -> CueBreak.Event.Out(at - cue.elapsedMillis, cue.durationMillis)
                    Cue.In -> CueBreak.Event.In(at)
                }
            }
        }
        dates.values.forEach { d ->
            val start = d.startMillis ?: return@forEach
            if (d.out) {
                events += CueBreak.Event.Out(start, d.durationMillis)
                d.endMillis?.let { events += CueBreak.Event.In(it) }
            } else if (d.into) {
                events += CueBreak.Event.In(d.endMillis ?: start)
            }
        }
        return events
    }

    private companion object {
        /** Six minutes of 1s segments, or far more of the usual 2-6s: past any break's start. */
        const val KEEP = 400

        /** An hour and a half of 5s segments, held while the mpv anchor waits for a PDT. */
        const val KEEP_UNANCHORED = 1_080

        const val MAX_DATES = 32
    }
}

/**
 * Where the latest break is, from cues placed on the clock. The rules:
 *  - It starts at an out: CUE-OUT's segment, a DATERANGE's START-DATE, or - joined mid-break -
 *    CUE-OUT-CONT's segment less its ElapsedTime. Outs while a break is open are the same break
 *    said again (a CONT per segment, a DATERANGE beside a CUE-OUT); the first duration any of them
 *    gives is its length.
 *  - It ends at the first in after it, or at its duration if no in comes first. With a duration
 *    the end is known ahead - the countdown and the return can use it before it is on screen.
 *  - A duration under [MIN_DURATION_MILLIS] is junk (a splice marker, not a break): ignored. One
 *    over [MAX_DURATION_MILLIS] is capped - and [BreakView]'s own five-minute ceiling still holds.
 *
 * Pure.
 */
object CueBreak {

    const val MIN_DURATION_MILLIS = BreakView.MIN_BREAK_MILLIS
    const val MAX_DURATION_MILLIS = 6 * 60_000L

    sealed interface Event {
        val at: Long

        data class Out(override val at: Long, val durationMillis: Long?) : Event
        data class In(override val at: Long) : Event
    }

    data class Found(val start: Long, val end: Long?)

    fun latest(events: List<Event>): Found? {
        var found: Found? = null
        var open: Found? = null
        // At one instant an in goes first: a break ending exactly where the next one starts.
        for (e in events.sortedWith(compareBy<Event> { it.at }.thenBy { it is Event.Out })) {
            // An open break with no length is over at the ceiling, so a stream that never says
            // CUE-IN still gets its next break.
            val limit = open?.let { it.end ?: (it.start + BreakView.MAX_BREAK_MILLIS) }
            if (limit != null && e.at >= limit) open = null
            when (e) {
                is Event.Out -> {
                    val d = e.durationMillis
                    if (d != null && d < MIN_DURATION_MILLIS) continue
                    val length = d?.coerceAtMost(MAX_DURATION_MILLIS)
                    val current = open
                    if (current == null) {
                        open = Found(e.at, length?.let { e.at + it })
                    } else if (current.end == null && length != null) {
                        open = Found(current.start, current.start + length)
                    }
                    found = open
                }
                is Event.In -> {
                    val current = open
                    if (current != null && e.at > current.start) {
                        found = Found(current.start, e.at)
                        open = null
                    }
                }
            }
        }
        return found
    }
}
