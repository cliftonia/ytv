package com.cliftonia.fs42tv.fast

import com.cliftonia.fs42tv.pluto.BreakDetector
import com.cliftonia.fs42tv.pluto.BreakSource
import com.cliftonia.fs42tv.pluto.BreakView

/**
 * A FAST channel's breaks, from the SCTE-35 cue tags in its media playlist ([CuePlaylist],
 * [CueTimeline]) - Samsung TV Plus, Tubi, Xumo, Stirr, Rakuten and the like. What fills their
 * breaks for an Australian viewer is black, a logo slate or promos: the ad servers fill only for
 * their own apps. So the break is found by its markers, never by its picture.
 *
 * The same ceilings as Pluto's: reads gone blind for [BreakDetector.MAX_UNKNOWN_READS] in a row
 * vouch for nothing, and [BreakView.at] caps any break at five minutes. A read that cannot be
 * timed (a stamped stream's window with no PDT and nothing known to count from) is no break:
 * unlike Pluto's bumper, a cue says where the break is only on a clock.
 */
class CueBreaks : BreakSource {

    override val label = "fast"

    private val timeline = CueTimeline()
    private var silentReads = 0

    override fun read(body: String?, readAt: Long, requestedAt: Long, nowMillis: Long): BreakView {
        val window = CuePlaylist.parse(body)
        if (window != null) {
            timeline.feed(window, readAt, requestedAt)
            silentReads = 0
        } else {
            silentReads++
        }
        return timeline.view().copy(blind = silentReads >= BreakDetector.MAX_UNKNOWN_READS)
    }
}
