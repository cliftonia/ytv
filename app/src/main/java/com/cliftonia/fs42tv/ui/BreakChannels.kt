package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.fast.CueBreaks
import com.cliftonia.fs42tv.pluto.BreakSource
import com.cliftonia.fs42tv.pluto.BumperBreaks
import com.cliftonia.fs42tv.pluto.PlutoIds
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.tune.Tuned

/**
 * Which channels have breaks [PlutoBreak] covers, and how each kind's break is found.
 *
 *  - PLUTO: a Pluto-dial channel. Its logo bumper, exactly as always ([BumperBreaks]).
 *  - FAST: any other live HLS channel - Samsung TV Plus, Tubi, Xumo, Stirr, Rakuten - by its cue
 *    tags ([CueBreaks]). Not one whose lineup entry says `"breaks": false` (a music channel), and
 *    not a Pluto feed outside the Pluto dial (a jmp2 news channel): Pluto marks no cues, and its
 *    bumper is not this rule's to find. A relayed (`route: us`) channel is polled through the
 *    relay url it plays from, which is the playable handed here - never the geoblocked upstream.
 *
 * Pure.
 */
enum class BreakChannels {
    PLUTO,
    FAST;

    /** A fresh source for one tune of this kind. */
    fun source(): BreakSource = when (this) {
        PLUTO -> BumperBreaks()
        FAST -> CueBreaks()
    }

    companion object {
        /** The kind of [tuned]'s breaks, or null when there are none to cover. */
        fun of(tuned: Tuned): BreakChannels? {
            if (tuned.card != null || tuned.playable !is Hls) return null
            val channel = tuned.channel
            if (channel.pluto != null) return PLUTO
            if (channel.kind != "live" || !channel.breaks || PlutoIds.of(channel) != null) return null
            return FAST
        }
    }
}
