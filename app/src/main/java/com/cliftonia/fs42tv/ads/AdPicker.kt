package com.cliftonia.fs42tv.ads

import kotlin.random.Random

/**
 * Which reel, and which commercial in it, a break opens on.
 *
 * Deterministic on purpose: seeded from the break (its start instant and the channel), so the
 * same break on the same history picks the same commercial - the dial's rule that what is on is
 * a function of the clock, and a test that can say exactly what plays. Random enough on purpose:
 * a seed that changes with every break walks the whole pool.
 *
 * The last few reels are avoided while the pool allows it, and within a reel the last few
 * commercials started at, so a viewer sitting through three breaks in an hour does not see the
 * same reel open three times. A pool too small to avoid them repeats rather than going without.
 *
 * Pure.
 */
object AdPicker {

    data class Pick(val reel: AdReel, val cutSeconds: Double) {
        /** How [recentPicks] remembers it. */
        val key: String get() = key(reel.id, cutSeconds)
    }

    /** How many reels back to avoid, and how many picks. */
    const val RECENT_REELS = 3
    const val RECENT_PICKS = 8

    fun key(reelId: String, cutSeconds: Double) = "$reelId@$cutSeconds"

    /**
     * The pick for [seed] out of [reels], or null when none is usable. [recentReels] and
     * [recentPicks] are the history, newest last; only their last [RECENT_REELS] / [RECENT_PICKS]
     * count.
     */
    fun pick(
        reels: List<AdReel>,
        seed: Long,
        recentReels: List<String> = emptyList(),
        recentPicks: List<String> = emptyList(),
    ): Pick? {
        val usable = reels.filter { it.usable }.distinctBy { it.id }.sortedBy { it.id }
        if (usable.isEmpty()) return null
        val avoidReels = recentReels.takeLast(RECENT_REELS).toSet()
        val avoidPicks = recentPicks.takeLast(RECENT_PICKS).toSet()
        val random = Random(seed)
        val pool = usable.filter { it.id !in avoidReels }.ifEmpty { usable }
        val reel = pool[random.nextInt(pool.size)]
        val cuts = reel.usableCuts()
        val fresh = cuts.filter { key(reel.id, it) !in avoidPicks }.ifEmpty { cuts }
        return Pick(reel, fresh[random.nextInt(fresh.size)])
    }

    /** The seed for a break: its start on the stream's clock, and the channel it is on. */
    fun seed(breakStartMillis: Long, channel: Int, attempt: Int = 0): Long =
        breakStartMillis * 31 + channel * 1_000_003L + attempt
}
