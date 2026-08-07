package com.cliftonia.fs42tv.player

/**
 * Which channels to hold preloaded, in priority order, highest first.
 *
 * `DefaultPreloadManager` already ranks by distance from the current index, so this exists for
 * the one thing a distance metric cannot express: the channel *behind* must outrank the second
 * channel *ahead*, even though both are two steps from nowhere and one step from here.
 *
 * That asymmetry is not a preference. On the box this app replaces, priming purely forwards
 * made every reversal a cold open - 5,359 ms, against 350 ms once a reverse slot existed. The
 * reserve matters far more at a budget of 2, which is what the 1.5 GB Chromecast gets, than at
 * 8, because at 2 it is the difference between having a reverse slot and not having one.
 *
 * Order is: one ahead, one behind, then alternating outwards starting ahead. Surfing forwards
 * is the common case so it keeps the first slot, but it does not get the second as well.
 */
object PreloadPlan {

    /**
     * Channel indices to preload, best first, wrapping at both ends of the dial.
     *
     * Never includes [index] itself - a slot spent on what is already playing is a slot wasted -
     * and never repeats an index, which matters on a dial shorter than the budget where naive
     * wrapping would emit the same channel several times.
     */
    fun forPosition(size: Int, index: Int, budget: Int): List<Int> {
        if (size <= 1 || budget <= 0) return emptyList()

        val wanted = minOf(budget, size - 1)
        val picked = LinkedHashSet<Int>()

        var step = 1
        while (picked.size < wanted) {
            val ahead = Math.floorMod(index + step, size)
            if (ahead != index) picked.add(ahead)
            if (picked.size >= wanted) break

            val behind = Math.floorMod(index - step, size)
            if (behind != index) picked.add(behind)

            step += 1
            // On a short dial the walk wraps past every channel before the budget is filled;
            // `wanted` already caps at size - 1, so this is a guard against spinning rather
            // than a limit anyone should hit.
            if (step > size) break
        }
        return picked.toList()
    }
}
