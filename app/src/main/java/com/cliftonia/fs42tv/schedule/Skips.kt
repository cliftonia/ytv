package com.cliftonia.fs42tv.schedule

import com.cliftonia.fs42tv.sync.Stream

/** One stretch of a clip to jump over, in seconds from the start of the FILE. */
data class SkipRange(val start: Double, val end: Double)

/**
 * Sponsor skips as the clock sees them.
 *
 * The rotation walks WATCHED time - a clip is as long as the part of it anyone sees - because
 * the alternative desynchronises the dial: a television that skips a 40s sponsor read and then
 * rolls over to the next clip 40s early is 40s ahead of one that did not, and every channel
 * drifts further with every sponsored clip. So the clock counts watched seconds, and a position
 * in watched time is turned into a position in the file here, at the one moment the player
 * needs it.
 *
 * Pure, and forgiving of its input: the curation side promises sorted, disjoint, clamped ranges,
 * but a lineup is sideloaded onto televisions nobody can attach a debugger to, and a malformed
 * range must degrade to "play it" rather than to an exception on the tune path.
 */
object Skips {

    /** The ranges of [stream], sanitised: clamped to the clip, sorted, merged, empties dropped. */
    fun ranges(stream: Stream): List<SkipRange> {
        if (stream.skip.isEmpty()) return emptyList()
        val length = stream.duration.toDouble()
        val clean = stream.skip.mapNotNull { pair ->
            if (pair.size < 2) return@mapNotNull null
            val start = pair[0].coerceIn(0.0, length)
            val end = pair[1].coerceIn(0.0, length)
            if (start.isNaN() || end.isNaN() || end <= start) null else SkipRange(start, end)
        }.sortedBy { it.start }
        val merged = ArrayList<SkipRange>(clean.size)
        for (range in clean) {
            val last = merged.lastOrNull()
            if (last != null && range.start <= last.end) {
                merged[merged.size - 1] = SkipRange(last.start, maxOf(last.end, range.end))
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    /**
     * How long [stream] is on screen with its ranges skipped, in whole seconds.
     *
     * Floored, like every other duration the clock counts: the rotation is integer arithmetic,
     * and both televisions must round the same way or they disagree by a second per clip.
     */
    fun watchDuration(stream: Stream): Int {
        val ranges = ranges(stream)
        if (ranges.isEmpty()) return stream.duration
        val skipped = ranges.sumOf { it.end - it.start }
        return maxOf(0.0, stream.duration - skipped).toInt()
    }

    /**
     * The file position for [watchSeconds] of watched time: step over each range that starts at
     * or before the position reached so far.
     *
     * "At or before" is the whole rule. A position exactly at a range's start is that range's
     * END - a join that opened on the first frame of a sponsor read would show it, however
     * briefly, before the watcher could seek past.
     */
    fun mediaTime(ranges: List<SkipRange>, watchSeconds: Double): Double {
        var media = watchSeconds
        for (range in ranges) {
            if (range.start <= media) media += range.end - range.start else break
        }
        return media
    }
}
