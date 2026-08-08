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

    /** Move to a channel by number, or return null and stay put if it is not on the dial. */
    fun jumpTo(number: Int): Channel? {
        val found = channels.indexOfFirst { it.number == number }
        if (found < 0) return null
        index = found
        return current
    }
}
