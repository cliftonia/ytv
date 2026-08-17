package com.cliftonia.fs42tv.tune

import com.cliftonia.fs42tv.sync.Channel

/**
 * Where the viewer is on the dial.
 *
 * Steps through the LIST rather than the numbers: the real dial is sparse and non-contiguous,
 * so stepping numerically would land on channels that do not exist. Both directions wrap,
 * because a dial that stops at the end is not a dial.
 *
 * Pure: persistence belongs to the caller.
 */
class DialNavigator(channels: List<Channel>, startNumber: Int? = null) {

    /** Read-only view of the dial for the phase 2b channel-list overlay. */
    val channels: List<Channel> = channels.toList()

    init {
        require(this.channels.isNotEmpty()) { "a dial with no channels cannot be navigated" }
    }

    // Mutated on the UI thread (key handling is the single writer); @Volatile so the overlay can read it safely from
    // the UI thread once it exists.
    @Volatile private var index: Int = this.channels.indexOfFirst { it.number == startNumber }
        .let { if (it >= 0) it else 0 }

    val current: Channel get() = channels[index]
    val currentNumber: Int get() = current.number
    val currentIndex: Int get() = index

    fun up(): Channel {
        index = (index + 1) % channels.size
        return current
    }

    fun down(): Channel {
        index = (index - 1 + channels.size) % channels.size
        return current
    }

    /**
     * The channel one step up from [from], without moving.
     *
     * For prefetching: the dial needs to know where a press WOULD go so it can resolve that
     * channel in advance, and doing it with [up] followed by [down] would move the navigator
     * under whatever else is reading it.
     *
     * Takes an explicit channel rather than reading [current], because the prefetch is queued
     * from a background thread and the viewer may have moved on by the time it runs - it should
     * prepare the neighbours of the channel that actually came on air.
     */
    fun peekUp(from: Channel): Channel? = neighbour(from, +1)

    /** The channel one step down from [from], without moving. */
    fun peekDown(from: Channel): Channel? = neighbour(from, -1)

    private fun neighbour(from: Channel, step: Int): Channel? {
        val at = channels.indexOfFirst { it.number == from.number }
        if (at < 0) return null
        return channels[(at + step + channels.size) % channels.size]
    }

    /** Move to a channel by number, or return null and stay put if it is not on the dial. */
    fun jumpTo(number: Int): Channel? {
        val found = channels.indexOfFirst { it.number == number }
        if (found < 0) return null
        index = found
        return current
    }
}
