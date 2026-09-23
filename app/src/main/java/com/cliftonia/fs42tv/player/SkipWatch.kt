package com.cliftonia.fs42tv.player

import com.cliftonia.fs42tv.schedule.SkipRange

/**
 * What the playback watcher does with one reading of the position, for one clip.
 *
 * The decisions live here, away from the handler loop that feeds them, because each one exists
 * to avoid fighting something else:
 *
 * - **One seek per range.** mpv runs `hr-seek=no` (HANDOVER: precise seeking measured 3.77s
 *   against 0.38s), so a seek lands on a keyframe - possibly still inside the range. Seeking
 *   again would find the same keyframe and loop. A range that was seeked once and is still
 *   under the playhead is played through: a second or two of the advert beats a stuck picture.
 * - **A range reaching the end ends the clip**, once - the same single report a natural end
 *   makes, so the dial's roll-over path runs exactly as it always has.
 *
 * Main thread only; not thread-safe, and does not need to be.
 */
class SkipWatch(
    private val ranges: List<SkipRange>,
    /** The clip's published length - where a trailing range counts as "the end". */
    private val clipEndSeconds: Double,
) {

    sealed class Action {
        object None : Action()
        data class SeekTo(val seconds: Double) : Action()
        object EndClip : Action()
    }

    private val seeked = BooleanArray(ranges.size)
    private var ended = false

    fun check(positionSeconds: Double?): Action {
        if (positionSeconds == null || ended) return Action.None
        val index = ranges.indexOfFirst { positionSeconds >= it.start && positionSeconds < it.end }
        if (index < 0) return Action.None
        val range = ranges[index]
        if (range.end >= clipEndSeconds - END_SLACK_SECONDS) {
            ended = true
            return Action.EndClip
        }
        if (seeked[index]) return Action.None
        seeked[index] = true
        return Action.SeekTo(range.end)
    }

    private companion object {
        /**
         * How close to the published end a range must reach to count as the clip's tail. The
         * published duration is metadata and the file is often a little shorter; a range that
         * stops a fraction short of it would otherwise seek to a point the file never reaches.
         */
        const val END_SLACK_SECONDS = 1.0
    }
}
