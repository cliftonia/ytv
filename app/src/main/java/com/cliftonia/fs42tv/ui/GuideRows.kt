package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.schedule.ClockRotation
import com.cliftonia.fs42tv.sync.Channel

/**
 * What the channel guide shows: every channel, and what is on it right now.
 *
 * Pure, and separated from the picker for that reason. Working out what is on air across the whole
 * dial means walking every channel's clip list against the clock - it is the only real computation
 * the guide does, it is the part that can be silently wrong, and inside an Activity it could not
 * be tested at all.
 *
 * A wrong answer here is not a crash. It is a guide that confidently lists the wrong programme,
 * which nobody would think to blame on a rounding error in a rotation.
 */
object GuideRows {

    /**
     * One row per channel: the label, and the title now playing.
     *
     * [nowSeconds] is passed in rather than read here so the whole dial is resolved against ONE
     * instant. Reading the clock per channel would let a slow walk of 100 channels straddle a
     * programme boundary and show two different moments in the same list.
     */
    fun forChannels(channels: List<Channel>, nowSeconds: Long): List<Pair<String, String>> =
        channels.map { channel -> ChannelLabels.listRow(channel, titleOn(channel, nowSeconds)) }

    /**
     * The clip on air on [channel] at [nowSeconds], or null when nothing can be.
     *
     * Null for a live feed as well as an empty channel: a broadcast stream has no clip list to
     * take a position in, and inventing one would put a stale title against a channel showing
     * whatever it is actually showing.
     */
    fun titleOn(channel: Channel, nowSeconds: Long): String? =
        ClockRotation.playPointFor(channel.streams.map { it.duration }, nowSeconds)
            ?.let { channel.streams.getOrNull(it.index)?.title }
}
