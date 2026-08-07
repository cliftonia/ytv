package com.cliftonia.fs42tv.player

/**
 * Turns raw first-frame samples into a line worth pasting into a report.
 *
 * The median rather than the mean: one 12-second outlier from a CDN hiccup should not be
 * able to move the headline number that decides whether a change to the preloader helped.
 */
object SwitchTiming {

    fun summarise(samplesMillis: List<Long>): String {
        if (samplesMillis.isEmpty()) return "no samples"
        val sorted = samplesMillis.sorted()
        val median = sorted[sorted.size / 2]
        return "n=${sorted.size} min=${sorted.first()} median=$median max=${sorted.last()}"
    }
}
