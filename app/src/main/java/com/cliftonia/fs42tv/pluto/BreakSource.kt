package com.cliftonia.fs42tv.pluto

/**
 * How one tune's playlist reads become a [BreakView] - the part of the break that differs from
 * one kind of channel to another. [BreakPoller] fetches; a source says what each body means.
 *
 *  - [BumperBreaks]: Pluto. No markers at all; a break is the stitcher's logo bumper, found by
 *    its segment names ([BreakDetector], [BreakTimeline]).
 *  - `fast/CueBreaks`: every other live HLS channel. SCTE-35 cue tags in the media playlist say
 *    where the break is and, often, how long.
 *
 * One per tune, like the run that owns it; the polling thread only.
 */
interface BreakSource {

    /** What the start and end log lines call this kind of break. */
    val label: String

    /**
     * One read: [body] is the media playlist, or null for a read that failed. [readAt] is the
     * wall clock when it was answered, [requestedAt] when it was asked for (see
     * [BreakView.firstReadAt]), [nowMillis] a monotonic clock. The view after it, [BreakView.blind]
     * included.
     */
    fun read(body: String?, readAt: Long, requestedAt: Long, nowMillis: Long): BreakView
}

/** Pluto's breaks: the logo bumper, on the stream's clock, with the two-read rule behind it. */
class BumperBreaks : BreakSource {

    override val label = "pluto"

    private val detector = BreakDetector()
    private val timeline = BreakTimeline()
    private var silentReads = 0

    override fun read(body: String?, readAt: Long, requestedAt: Long, nowMillis: Long): BreakView {
        val before = detector.state
        val window = HlsWindow.parse(body)
        val longEnough = window != null && window.segments.all { it.bumper } &&
            window.segments.sumOf { it.durationMillis } >= BreakView.MIN_BREAK_MILLIS
        val after = detector.feed(body, nowMillis, longEnough)
        if (window != null) {
            timeline.feed(window, readAt, requestedAt)
            silentReads = 0
        } else {
            silentReads++
        }
        val view = timeline.view().copy(
            blind = silentReads >= BreakDetector.MAX_UNKNOWN_READS,
            fallbackInBreak = after == BreakDetector.State.IN_BREAK,
        )
        if (!view.timed && after != before) android.util.Log.i("fs42", "pluto break (untimed): $after")
        return view
    }
}
