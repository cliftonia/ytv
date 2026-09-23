package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.sync.Channel
import java.util.concurrent.Executor

/** The corner logo's words. */
object StationBug {

    /**
     * "47  PLUTO TV ACTION". The number unpadded and two spaces before the name - the box's own
     * spacing (`field_player.py:153`), which the banner dropped for its 27.5sp heading but which
     * reads right at a corner bug's small size.
     */
    fun label(channel: Channel): String = "${channel.number}  ${channel.name.uppercase()}"
}

/**
 * When the corner logo comes up: on the first frame of a deliberate tune, and again whenever a
 * clock channel moves on to a new programme - the moment a real station puts its bug back.
 *
 * Not on a first frame that merely recovers the same clip (a refused url, an engine rebuilt): the
 * viewer has not changed anything, and a logo popping up then reads as a glitch rather than as
 * the station. A live feed re-tuning itself is the same clip for this purpose.
 */
class BugTrigger {

    private var tunePending = false
    private var lastChannel: Int? = null
    private var lastClip: Int? = null

    /** A channel change or the launch tune has begun. */
    fun tuneStarted() {
        tunePending = true
    }

    /** A picture arrived for [clip] on [channel]; true when the logo should show. */
    fun firstFrame(channel: Int, clip: Int, clock: Boolean): Boolean {
        val show = tunePending || channel != lastChannel || (clock && clip != lastClip)
        tunePending = false
        lastChannel = channel
        lastClip = clip
        return show
    }
}

/**
 * Small images fetched once and held: the Pluto channel logos for the corner bug.
 *
 * A handful, least recently used out - every logo on a 219-channel dial held at once would be
 * megabytes of bitmaps on a 2.3GB television for pictures shown ten seconds at a time. A url
 * that failed is not retried this session; the bug simply keeps its text. Callbacks run on
 * whichever thread had the answer, so callers hop to the UI thread themselves.
 */
class ImageCache<T : Any>(
    private val load: (String) -> T?,
    private val executor: Executor,
    private val capacity: Int = 12,
) {
    private val held = object : LinkedHashMap<String, T>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) =
            size > capacity
    }
    private val failed = HashSet<String>()
    private val waiting = HashMap<String, MutableList<(T) -> Unit>>()

    fun get(url: String, onReady: (T) -> Unit) {
        synchronized(this) {
            held[url]?.let { hit -> return onReady(hit) }
            if (url in failed) return
            waiting[url]?.let { it += onReady; return }
            waiting[url] = mutableListOf(onReady)
        }
        executor.execute {
            val loaded = runCatching { load(url) }.getOrNull()
            val waiters = synchronized(this) {
                if (loaded != null) held[url] = loaded else if (failed.size < MAX_FAILED) failed += url
                waiting.remove(url).orEmpty()
            }
            if (loaded != null) waiters.forEach { it(loaded) }
        }
    }

    private companion object {
        const val MAX_FAILED = 64
    }
}
