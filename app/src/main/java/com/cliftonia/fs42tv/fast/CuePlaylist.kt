package com.cliftonia.fs42tv.fast

import java.time.OffsetDateTime

/** A break marker on a segment: the tags between the segment before it and its own EXTINF. */
sealed interface Cue {
    /** `#EXT-X-CUE-OUT`: the break starts with this segment, lasting [durationMillis] if said. */
    data class Out(val durationMillis: Long?) : Cue

    /** `#EXT-X-CUE-OUT-CONT`: this segment is [elapsedMillis] into a break of [durationMillis]. */
    data class Cont(val elapsedMillis: Long, val durationMillis: Long?) : Cue

    /** `#EXT-X-CUE-IN`: the programme is back with this segment. */
    object In : Cue
}

/** One media segment, as read: sequence number, EXTINF length, PROGRAM-DATE-TIME, and cues. */
data class CueSegment(val seq: Long, val durationMillis: Long, val programDateTime: Long?, val cues: List<Cue>)

/**
 * An `#EXT-X-DATERANGE`'s attributes, by name, unquoted. Ranges with one ID are one range whose
 * attributes arrive over several tags, so they are merged by [id] before being read.
 */
data class DateCue(val id: String, val attributes: Map<String, String>) {

    val out: Boolean get() = "SCTE35-OUT" in attributes
    val into: Boolean get() = "SCTE35-IN" in attributes
    val startMillis: Long? get() = attributes["START-DATE"]?.let(CuePlaylist::instant)
    val endMillis: Long? get() = attributes["END-DATE"]?.let(CuePlaylist::instant)

    /** The range's length: DURATION, else END-DATE less START-DATE, else PLANNED-DURATION. */
    val durationMillis: Long?
        get() = attributes["DURATION"]?.let(CuePlaylist::seconds)
            ?: endMillis?.let { end -> startMillis?.let { end - it } }
            ?: attributes["PLANNED-DURATION"]?.let(CuePlaylist::seconds)

    fun merged(other: DateCue): DateCue = DateCue(id, attributes + other.attributes)
}

/** A media playlist's window, as the cue reader sees it. */
data class CueWindow(val targetDurationMillis: Long, val segments: List<CueSegment>, val dates: List<DateCue>)

/**
 * The cue tags of a FAST channel's media playlist - Samsung TV Plus, Tubi, Xumo, Stirr, Rakuten
 * and the rest mark their ad breaks with SCTE-35 in one or more of these forms (Sep 2026):
 *
 *  - `#EXT-X-CUE-OUT:DURATION=120` (or `:120`, or bare) before the break's first segment;
 *  - `#EXT-X-CUE-OUT-CONT:ElapsedTime=30,Duration=120` (or `:30/120`) before each one after it;
 *  - `#EXT-X-CUE-IN` before the programme's first segment back;
 *  - `#EXT-OATCLS-SCTE35:` / `#EXT-X-SCTE35:CUE=` - the raw splice, read ([Scte35]) only where a
 *    segment has no CUE tag of its own, since they usually come as a pair;
 *  - `#EXT-X-DATERANGE` with SCTE35-OUT / SCTE35-IN, on the PROGRAM-DATE-TIME clock.
 *
 * Hand-rolled like the other playlist readers here: a handful of tags matter, and a parser that
 * threw on an unknown one would lose a read to some stitcher's own. Pure.
 */
object CuePlaylist {

    private val TARGET = Regex("""#EXT-X-TARGETDURATION:(\d+)""")
    private val SEQUENCE = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""")
    private val DURATION = Regex("""DURATION=([\d.]+)""", RegexOption.IGNORE_CASE)
    private val ELAPSED = Regex("""ELAPSEDTIME=([\d.]+)""", RegexOption.IGNORE_CASE)
    /** Duration as its own key, after a separator - never the tail of some other key. */
    private val CONT_DURATION = Regex("""(?:^|,)\s*DURATION=([\d.]+)""", RegexOption.IGNORE_CASE)
    private val SLASHED = Regex("""^([\d.]+)/([\d.]+)""")
    private val ATTRIBUTE = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
    private val CUE_ATTRIBUTE = Regex("""CUE="?([A-Za-z0-9+/=]+)""")

    /** The window in [body], or null when it is not a media playlist with segments in it. */
    fun parse(body: String?): CueWindow? {
        if (body == null || !body.contains("#EXTINF") || body.contains("#EXT-X-STREAM-INF")) return null
        val target = TARGET.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        var seq = SEQUENCE.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        var duration: Long? = null
        var pdt: Long? = null
        var cues = mutableListOf<Cue>()
        var splices = mutableListOf<Cue>()
        val segments = mutableListOf<CueSegment>()
        val dates = mutableListOf<DateCue>()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> duration = seconds(line.removePrefix("#EXTINF:").substringBefore(','))
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> pdt = instant(line.substringAfter(':'))
                line.startsWith("#EXT-X-CUE-OUT-CONT") -> cont(line.substringAfter(':', ""))?.let { cues += it }
                line.startsWith("#EXT-X-CUE-OUT") -> cues += Cue.Out(DURATION.find(line)?.let { seconds(it.groupValues[1]) }
                    ?: line.substringAfter(':', "").takeIf { it.isNotEmpty() }?.let(::seconds))
                line.startsWith("#EXT-X-CUE-IN") -> cues += Cue.In
                line.startsWith("#EXT-OATCLS-SCTE35:") -> splice(line.substringAfter(':'))?.let { splices += it }
                line.startsWith("#EXT-X-SCTE35:") ->
                    CUE_ATTRIBUTE.find(line)?.let { splice(it.groupValues[1]) }?.let { splices += it }
                line.startsWith("#EXT-X-DATERANGE:") -> dateRange(line.substringAfter(':'))?.let { dates += it }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val length = duration ?: return null
                    segments += CueSegment(seq++, length, pdt, cues.ifEmpty { splices })
                    duration = null
                    pdt = null
                    cues = mutableListOf()
                    splices = mutableListOf()
                }
            }
        }
        return if (segments.isEmpty()) null else CueWindow(target * 1000, segments, dates)
    }

    private fun cont(text: String): Cue? {
        val elapsed = ELAPSED.find(text)?.groupValues?.get(1)?.let(::seconds)
        if (elapsed != null) {
            val total = CONT_DURATION.find(text)?.groupValues?.get(1)?.let(::seconds)
            return Cue.Cont(elapsed, total)
        }
        val slashed = SLASHED.find(text.trim()) ?: return null
        val at = seconds(slashed.groupValues[1]) ?: return null
        return Cue.Cont(at, seconds(slashed.groupValues[2]))
    }

    private fun splice(base64: String): Cue? = when (val s = Scte35.decode(base64)) {
        is Scte35.Splice.Out -> Cue.Out(s.durationMillis)
        Scte35.Splice.In -> Cue.In
        null -> null
    }

    private fun dateRange(text: String): DateCue? {
        val attributes = ATTRIBUTE.findAll(text).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
        val id = attributes["ID"] ?: return null
        return DateCue(id, attributes)
    }

    internal fun seconds(text: String): Long? = text.trim().toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 1000).toLong() }

    internal fun instant(text: String): Long? =
        runCatching { OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli() }.getOrNull()
}
