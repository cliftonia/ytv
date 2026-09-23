package com.cliftonia.fs42tv.ui

import androidx.compose.runtime.mutableStateOf
import com.cliftonia.fs42tv.schedule.ScheduleLines
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.tune.Tuned

/**
 * The tune banner's lines, and the three moments that write them: a channel change announced,
 * a tune that genuinely landed, and the viewer asking.
 *
 * Out of [ScreenDirector] so the director keeps its one idea - when each thing appears - while
 * what the banner SAYS lives here. Compose state, written on the UI thread only.
 */
class Banner(
    private val extras: ScreenExtras,
    private val nowSeconds: () -> Long,
) {

    // Written only on a genuine success or a deliberate change: a failed re-tune must not touch
    // these, since bumping [generation] would replay the LaunchedEffect in ChannelOsd and pop a
    // banner back up for a channel that never changed.
    val channelLine = mutableStateOf("")
    val titleLine = mutableStateOf("")

    // Separate from the tune generation on purpose: that counter is bumped once per keypress,
    // to coalesce a burst of presses, and can advance even when a tune ultimately fails. Using
    // it as the banner's LaunchedEffect key would replay the auto-hide timer on a failed
    // re-tune even though nothing on screen changed. This one only advances alongside onAir.
    val generation = mutableStateOf(0)

    /** Which channel the lines describe, so a late guide answer cannot land on another. */
    private var channelNumber = -1

    /**
     * A channel change has begun: name [target] and what is on it from the clock, right away.
     *
     * The title comes from the timetable here, not from the tune that follows. Waiting for the
     * tune meant the banner showed a bare channel name whenever the tune was superseded - which
     * is every press but the last when surfing quickly. What is on a channel is knowable without
     * tuning to it.
     */
    fun announce(target: Channel) {
        val (line, title) = ChannelLabels.bannerLinesFor(target, nowSeconds(), extras.timetable)
        channelLine.value = line
        titleLine.value = title
        applyProgrammeLines(target, playingIndex = null)
        generation.value += 1
    }

    /**
     * A tune landed. Reads the current onAir rather than the tune's own outcome - a failed tune
     * leaves onAir on whatever last actually played, exactly as the picture itself does.
     */
    fun painted(onAir: Tuned?) {
        onAir?.let { describe(it, keepTitleWhenBlank = false) }
        generation.value += 1
    }

    /**
     * Put the banner back up, recomputed rather than replayed.
     *
     * The channel on air is described from what is ACTUALLY playing, not recomputed from the
     * clock: after an early roll-over or a dead-clip substitution the rotation names a programme
     * the player is not showing, and an info button that answers with a guess when the truth is
     * in hand is worse than none. Only with nothing on air does the clock answer, for [fallback].
     */
    fun show(onAir: Tuned?, fallback: Channel?) {
        if (onAir != null) {
            describe(onAir, keepTitleWhenBlank = true)
        } else if (fallback != null) {
            val (line, title) =
                ChannelLabels.bannerLinesFor(fallback, nowSeconds(), extras.timetable)
            channelLine.value = line
            if (title.isNotEmpty()) titleLine.value = title
            applyProgrammeLines(fallback, playingIndex = null)
        }
        // The generation is what replays the auto-hide timer in ChannelOsd, so bumping it is
        // what actually shows the banner - exactly what pressing OK on the current channel does.
        generation.value += 1
    }

    private fun describe(onAir: Tuned, keepTitleWhenBlank: Boolean) {
        val (line, title) = ChannelLabels.bannerLines(onAir)
        channelLine.value = line
        if (title.isNotEmpty() || !keepTitleWhenBlank) titleLine.value = title
        // A card has no clip on air: its stream is the programme it announces.
        applyProgrammeLines(onAir.channel, onAir.streamIndex.takeIf { onAir.card == null })
    }

    /**
     * Swap the title for what is on NOW when there is a line for [channel]: the half-hour schedule's
     * real times for a clock channel, or Pluto's guide for a Pluto one. [playingIndex] is the
     * clip actually on air, when one is - see [ScheduleLines.banner].
     *
     * A Pluto cache miss leaves the lines as they are and asks; the answer re-enters here on the
     * UI thread, and is dropped if the banner has moved to another channel meanwhile. It does
     * NOT bump the generation - that would restart the auto-hide timer for a banner that merely
     * gained a line, or pop up one that had already gone.
     */
    private fun applyProgrammeLines(channel: Channel, playingIndex: Int?) {
        channelNumber = channel.number
        val scheduled = ScheduleLines.banner(channel, extras.timetable, nowSeconds(), playingIndex)
        val lines = scheduled ?: extras.bannerLines(channel) {
            if (channelNumber == channel.number) applyProgrammeLines(channel, playingIndex)
        }
        if (lines != null) titleLine.value = lines.first
    }
}
