package com.cliftonia.fs42tv.resolver

/**
 * WebVTT, reduced to "what words are on screen at this second".
 *
 * The app parses subtitles itself rather than handing the file to a player, because mpv on this
 * television will not draw one. Measured: the track downloads, decodes and is selected -
 * `sub-text` holds the line, `sid=1`, `sub-visibility=yes`, `sub-pos=100` inside a full
 * `osd-width=1920 osd-height=1080` - and the panel shows nothing. See MpvChannelPlayer's
 * subtitle probe for the reading and what it points at.
 *
 * Drawing the cue in the Compose overlay instead - the same layer as the channel banner, which
 * demonstrably reaches the panel - is engine-independent and cannot be defeated by anything in
 * the video pipeline. That needs the cues as data, which is this.
 *
 * Pure and hand-rolled on purpose. `org.json` is stubbed in this project's JVM tests and a parser
 * written against a library that does nothing under test passes without ever having run; the same
 * trap applies to anything that would need a real Android or Media3 class here.
 */
object VttCues {

    /** One cue: when it appears, when it goes, and the text with all markup removed. */
    data class Cue(val startSeconds: Double, val endSeconds: Double, val text: String)

    /** `00:00:32.590 --> 00:00:34.840 align:start position:0%` - trailing settings ignored. */
    private val ARROW = Regex("^(\\S+)\\s+-->\\s+(\\S+)(?:\\s+.*)?$")

    /**
     * Any angle-bracket span.
     *
     * YouTube's automatic captions are riddled with these: every word carries its own
     * `<00:00:09.040><c> was</c>` karaoke timing so the line fills in as it is spoken. Hand
     * authored tracks use `<v Speaker>` and `<i>`. None of it survives to the screen here - the
     * overlay draws one flat string - and leaving any of it in shows raw markup to the viewer.
     */
    private val TAG = Regex("<[^>]*>")

    /**
     * Every cue in [body], in file order.
     *
     * Timestamps are relative to the start of the media file, which is also what both engines
     * report as their position, so no offset arithmetic is needed even though every clip on this
     * dial is joined partway through.
     */
    fun parse(body: String): List<Cue> {
        val cues = ArrayList<Cue>()
        // Split on any line ending: googlevideo serves LF, but a file that has been near a
        // Windows tool arrives with CRLF and a stray \r on the end of a timestamp makes every
        // cue in the file fail to parse - silently, since a caption that shows nothing is
        // indistinguishable from a clip that had none.
        val lines = body.split('\n').map { it.trimEnd('\r') }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            // NOTE, STYLE and REGION blocks run to the next blank line and contain no cues.
            // Skipping only the keyword line would leave their contents to be read as cue text.
            if (isBlockHeader(line)) {
                i++
                while (i < lines.size && lines[i].isNotEmpty()) i++
                continue
            }
            val match = ARROW.find(line)
            if (match == null) {
                // Either a blank line, the WEBVTT header and its metadata, or a cue identifier -
                // which is the line BEFORE the timestamps, so it is skipped and the next
                // iteration finds the arrow.
                i++
                continue
            }
            val start = seconds(match.groupValues[1])
            val end = seconds(match.groupValues[2])
            i++
            val text = StringBuilder()
            // EMPTY, not blank. A cue ends at a line of zero characters, and YouTube's automatic
            // captions put a line holding a single SPACE between the timestamps and the words -
            // every single cue. Terminating on `isBlank` ends every cue before its own text, so
            // an automatically captioned clip parses to nothing at all while a hand-authored one
            // parses perfectly. Confirmed against a real 2517-cue track off the timedtext
            // endpoint; this is not a hypothetical.
            while (i < lines.size && lines[i].isNotEmpty()) {
                val cleaned = strip(lines[i])
                if (cleaned.isNotEmpty()) {
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(cleaned)
                }
                i++
            }
            // A cue with no start, no end or nothing to say is dropped rather than kept as an
            // empty box on screen. Automatic captions are full of these: a one-hundredth-of-a-
            // second bridging cue whose only content is a single space appears between every
            // pair of real ones.
            if (start != null && end != null && text.isNotEmpty()) {
                cues.add(Cue(start, end, text.toString()))
            }
        }
        return cues
    }

    private fun isBlockHeader(line: String): Boolean {
        val word = line.trim().substringBefore(' ')
        return word == "NOTE" || word == "STYLE" || word == "REGION"
    }

    /**
     * `HH:MM:SS.mmm` or `MM:SS.mmm` as seconds, or null when it is not a timestamp at all.
     *
     * The hours field is genuinely optional in WebVTT and YouTube omits it on short clips, so a
     * parser that insisted on three fields would return nothing for exactly the material this
     * feature was built for. A comma decimal separator is accepted too - that is SRT's spelling,
     * and [Captions] lets SRT tracks through.
     */
    fun seconds(stamp: String): Double? {
        val parts = stamp.replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val hours = if (parts.size == 3) parts[0].toIntOrNull() ?: return null else 0
        val minutes = parts[parts.size - 2].toIntOrNull() ?: return null
        val secs = parts[parts.size - 1].toDoubleOrNull() ?: return null
        return hours * 3600.0 + minutes * 60.0 + secs
    }

    /** One line of cue payload with markup and entities resolved, or "" if nothing is left. */
    fun strip(line: String): String {
        val withoutTags = TAG.replace(line, "")
        val decoded = withoutTags
            // Ampersand LAST, so a doubly-escaped `&amp;lt;` does not decode into a `<` that the
            // tag stripper has already run past and would then reach the screen as markup.
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
        // Non-breaking space is what YouTube pads its automatic cues with, and it is not
        // whitespace as far as trim() is concerned - a cue holding only one would survive the
        // empty check above and draw a blank caption box over the picture.
        return decoded.replace(' ', ' ').trim()
    }

    /**
     * What should be on screen at [seconds], or null.
     *
     * The LAST cue that contains the position wins. Automatic captions overlap constantly - a
     * cue holding the previous line stays open while the next one starts - and taking the first
     * match would leave the display a line behind the audio for the whole clip.
     *
     * [cues] must be in start order, which is how [parse] returns them. Linear, because a clip's
     * caption file is a few thousand cues at most and this is called a handful of times a second
     * on the UI thread; a binary search over overlapping ranges would need the same backward walk
     * anyway to find the last match.
     */
    fun activeAt(cues: List<Cue>, seconds: Double): String? {
        var found: Cue? = null
        for (cue in cues) {
            // Sorted by start, so once a cue begins after the position nothing later can match.
            if (cue.startSeconds > seconds) break
            if (seconds < cue.endSeconds) found = cue
        }
        return found?.text
    }
}
