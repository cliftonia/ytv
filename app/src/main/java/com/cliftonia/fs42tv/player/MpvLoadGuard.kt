package com.cliftonia.fs42tv.player

/**
 * Which of mpv's file events belong to the clip the dial actually asked for, and what each one
 * means.
 *
 * Separated from [MpvChannelPlayer] for the reason [MpvSource] was: that class cannot be loaded
 * in a JVM test, because `MPVLib` loads a native library the moment it is touched - and these
 * guards are exactly the rules that have each been wrong on the television at least once. Here
 * every one of them is reachable with nothing but a clock.
 *
 * mpv delivers events on its own native thread while loads are asked for from the UI thread, so
 * every method is synchronized: the decisions read several fields together, and a torn read of
 * two of them is precisely the kind of race these guards exist to close.
 */
class MpvLoadGuard(
    /**
     * How long after the newest load an event that cannot be matched to it is still treated as
     * belonging to a superseded one. Longer than any burst of surfing, far shorter than a viewer
     * would stare at black.
     */
    private val resyncMillis: Long = RESYNC_MILLIS,
) {

    /** What an end-file event should do to the dial. */
    enum class End {
        /** Not the current clip's business - a replaced file, a duplicate, a ghost. */
        IGNORE,

        /** The current clip failed; report it so the dead url is dropped and the channel re-tuned. */
        FAILED,

        /** The current clip finished (or rolled over in "error" at its tail); play what is next. */
        FINISHED,
    }

    // How many loads have been ASKED for versus how many mpv has finished opening, plus how many
    // failed before they ever opened. When the accelerator paints two surfs sixteen milliseconds
    // apart, mpv still briefly opens the superseded file - and its events must not be mistaken
    // for the current one's. Settled < asked means the event in hand may belong to a file that
    // has already been replaced.
    private var asked = 0
    private var seen = 0
    private var failedUnopened = 0

    /**
     * When the newest load was asked for. The counters above can skew - mpv coalesces two loads
     * asked for before it started either, and the first then produces no events at all - and
     * unrepaired skew would swallow every future first frame, which is a permanently black dial.
     * The repair is time: mpv processes loads serially and quickly, so an event arriving with no
     * NEW ask in the last couple of seconds belongs to the newest file no matter what the
     * counters say.
     */
    private var lastAskMillis = 0L

    /**
     * mpv's playlist entry id for the newest load, when it could be read. The exact answer to
     * "is this end-file the current clip's", where the counters above are only a heuristic. Null
     * when unknown, and then the heuristic decides.
     */
    private var currentEntryId: Long? = null

    /**
     * Guards against reporting the same clip's end twice while the next tune is in flight.
     *
     * Stays TRUE across a load. `loadfile ... replace` makes mpv end the outgoing file, and that
     * end-file is indistinguishable from the new clip finishing - clearing the guard at the ask
     * meant every channel change was immediately read as "clip ended" and re-tuned, six times in
     * fifty milliseconds, measured. [loaded] clears it once the new file is really the current
     * one.
     */
    private var ended = false

    /**
     * Whether the current load has already been reported as failed (or deliberately stopped).
     *
     * The latch above cannot do this job on its own: it is up for the whole of a load, which is
     * exactly when a load that dies before opening reports its end. This is what stops one dead
     * url being reported twice, and a stopped clip's late error re-tuning a channel the viewer
     * has already left.
     */
    private var currentSettled = false

    /**
     * Set once a picture is up, so a load in progress is never reported as a stall, and a
     * non-error end from a file that never showed a frame is recognised as a ghost.
     */
    @get:Synchronized
    var hasPicture = false
        private set

    /** A new load was just handed to mpv. */
    @Synchronized
    fun asked(nowMillis: Long) {
        asked++
        lastAskMillis = nowMillis
        hasPicture = false
        ended = true
        currentEntryId = null
        currentSettled = false
    }

    /** mpv's playlist entry id for the load just asked for, when it could be read. */
    @Synchronized
    fun entryIdIs(id: Long?) {
        currentEntryId = id
    }

    /** mpv finished opening a file. */
    @Synchronized
    fun loaded() {
        seen++
        // Only now do end-file events refer to the clip the dial actually asked for. Anything
        // before this belongs to the outgoing file that `loadfile ... replace` displaced.
        ended = false
    }

    /**
     * The dial stopped the clip deliberately (a channel change is starting).
     *
     * Settles the current load too: an error it reports from here on describes a channel the
     * viewer has already left, and re-tuning in answer would steal the screen back from the
     * channel they asked for.
     */
    @Synchronized
    fun stopped() {
        ended = true
        currentSettled = true
    }

    /**
     * Whether a first frame belongs to the newest load and may drop the blank.
     *
     * The first frame of a REPLACED file must not: with tunes painted milliseconds apart, mpv
     * shows a beat of the superseded channel before the wanted one loads, and clearing the cover
     * then put another channel's picture on screen until the right file took over.
     */
    @Synchronized
    fun firstFrame(nowMillis: Long): Boolean {
        if (hasPicture) return false
        if (seen + failedUnopened < asked) {
            if (nowMillis - lastAskMillis < resyncMillis) return false
            // Nothing newer was asked for in seconds: the pipeline has drained and this frame IS
            // the newest file - the counters skewed on a load that never reported.
            resync()
        }
        hasPicture = true
        return true
    }

    /**
     * What an end-file event means.
     *
     * [reason] is "error" or "eof" (see [parseEndFile]); [entryId] is the playlist entry that
     * ended, when mpv said. [nearEnd] is asked only when it matters - it reads mpv's position.
     */
    @Synchronized
    fun endFile(reason: String, entryId: Long?, nowMillis: Long, nearEnd: () -> Boolean): End {
        val current = currentEntryId
        // The exact answer, when both ids are known: an end of some other entry is the ghost of
        // a replaced file, whatever the latch says.
        if (entryId != null && current != null && entryId != current) {
            // An older load that died before opening settles its ask, which keeps the counters
            // honest for the first-frame guard. A "stop" is not counted: that is also how an
            // OPENED file ends when replaced, and it was already counted when it opened.
            if (reason == "error" && ended) failedUnopened++
            return End.IGNORE
        }
        if (ended) return endWhileLoading(reason, entryId, current, nowMillis)
        ended = true
        currentSettled = true
        return when {
            // An "error" end in the last seconds of a clip is a ROLL-OVER, not a fault. These
            // files are separately-muxed video and audio, and whichever track is shorter ends the
            // file "in error" moments before the published duration - routing it to the error
            // path flashed TECHNICAL DIFFICULTIES on every programme boundary whenever the
            // re-tune outran the four-second grace. A genuine dead url fails at the START.
            reason == "error" && hasPicture && !nearEnd() -> End.FAILED
            // Never showed a frame: a dead url or an unplayable stream.
            reason == "error" && !hasPicture -> End.FAILED
            // A non-error end from a file that never showed a frame is the ghost of a REPLACED
            // file, not a clip finishing: file A's end can arrive after the guard was already
            // cleared for file B, and mistaking it for B ending skipped to the next clip. A
            // genuine roll-over has always shown a picture first.
            !hasPicture -> End.IGNORE
            else -> End.FINISHED
        }
    }

    /**
     * An end-file arriving while the newest load has not opened yet.
     *
     * This used to be swallowed whole, and that was a silent black screen: a load that dies
     * BEFORE mpv opens it - a dead HLS link, a googlevideo 403 at open - reports its only event
     * here, so nothing ever reported the failure, no card went up and nothing retried. Only an
     * error can be the current load's (a replaced file ends with "stop", read as "eof"), and
     * only once.
     */
    private fun endWhileLoading(reason: String, entryId: Long?, current: Long?, now: Long): End {
        if (reason != "error" || currentSettled) return End.IGNORE
        failedUnopened++
        val mine = if (entryId != null && current != null) {
            entryId == current
        } else {
            // No ids to compare: fall back on the counters, then on time - the same repair the
            // first-frame guard uses. Settled == asked means every older load is accounted for
            // and this error can only be the newest's.
            seen + failedUnopened >= asked || now - lastAskMillis >= resyncMillis
        }
        if (!mine) return End.IGNORE
        if (seen + failedUnopened < asked) resync()
        currentSettled = true
        return End.FAILED
    }

    private fun resync() {
        seen = asked
        failedUnopened = 0
    }

    companion object {
        const val RESYNC_MILLIS = 2_500L

        private val ENTRY_ID = Regex("\"playlist_entry_id\"\\s*:\\s*(\\d+)")

        /**
         * The reason and playlist entry id out of an END_FILE event's node, as mpv renders it
         * to JSON. When the reason cannot be read, "eof" is the safer default - re-tuning is
         * harmless, while wrongly declaring an error discards a url that was never dead.
         */
        fun parseEndFile(json: String): Pair<String, Long?> {
            val reason = if (json.contains("error")) "error" else "eof"
            val id = ENTRY_ID.find(json)?.groupValues?.get(1)?.toLongOrNull()
            return reason to id
        }
    }
}
