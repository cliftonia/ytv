package com.cliftonia.fs42tv.prejoin

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps a neighbour's live join warm: every half target duration, its media playlist read again
 * and the segment ffmpeg would join on (and the one after) held in [cache] - so a surf onto it is
 * served from memory by [PrejoinProxy] instead of waiting on the network.
 *
 * FOR [windowMillis] AND NO LONGER - a minute after the picture that asked for it - and only while
 * [warm]'s `wanted` holds (the same tune on air, no commercial reel on the player, the app in front)
 * and its `valid` holds (the pick is still that playlist; on Pluto, still claimed on the
 * neighbour's own session, see [com.cliftonia.fs42tv.pluto.VariantCache]). Both are asked before
 * every read and again before anything read is kept. [stop] - the viewer tuned, the app stopped -
 * ends every warm at once, interrupting its sleep.
 *
 * ONE SEGMENT A TARGET DURATION: the join moves on by one segment each time the playlist gains
 * one, and the segment it moves onto is the one held after it last time, so each refresh fetches
 * one new segment - a neighbour's own bitrate, for the minute. Never more than [maxWarmers] at once.
 *
 * Never a master: only the media playlist the pick already names, and its segments.
 */
class PrejoinWarmer(
    private val open: Open,
    private val cache: PrejoinCache,
    private val elapsedMillis: () -> Long,
    private val wallMillis: () -> Long,
    /** Sleeps the warm's own thread; throws InterruptedException when [stop] interrupts it. */
    private val sleep: (Long) -> Unit,
    /** Runs a block off the UI thread, at low priority; returns what cancels (interrupts) it. */
    private val background: (block: () -> Unit) -> () -> Unit,
    private val windowMillis: Long = WINDOW_MILLIS,
    private val maxWarmers: Int = MAX_WARMERS,
) {

    private val epoch = AtomicInteger(0)
    private val running = ConcurrentHashMap<Any, () -> Unit>()

    /** How many warms are running - for the tests. */
    val warming: Int get() = running.size

    /**
     * Keep [mediaUrl]'s join warm for [windowMillis], while [wanted] and [valid]. False when it
     * is not started: [maxWarmers] already running, or it is being warmed already.
     */
    fun warm(mediaUrl: String, valid: () -> Boolean, wanted: () -> Boolean): Boolean {
        if (running.size >= maxWarmers || running.containsKey(mediaUrl)) return false
        val began = epoch.get()
        val deadline = elapsedMillis() + windowMillis
        val current = { epoch.get() == began }
        running[mediaUrl] = {}
        val cancel = background {
            try {
                run(mediaUrl, deadline) { current() && wanted() && valid() }
            } finally {
                running.remove(mediaUrl)
                cache.prune()
            }
        }
        running.replace(mediaUrl, cancel)
        return true
    }

    /** Every warm ends now; what they hold stays for the tune to take - or [PrejoinCache.clear]. */
    fun stop() {
        epoch.incrementAndGet()
        running.values.forEach { cancel -> runCatching { cancel() } }
        running.clear()
    }

    private class Tally(var reads: Int = 0, var segments: Int = 0, var bytes: Long = 0)

    private fun run(mediaUrl: String, deadline: Long, still: () -> Boolean) {
        val tally = Tally()
        val started = elapsedMillis()
        var failures = 0
        try {
            while (elapsedMillis() < deadline && still()) {
                val next = try {
                    once(mediaUrl, still, tally, first = tally.reads == 0)
                } catch (e: Stop) {
                    break
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    // The class only: a message can hold the url.
                    Log.i("fs42", "prejoin: read failed (${e.javaClass.simpleName})")
                    null
                }
                if (next == null) {
                    if (++failures >= MAX_FAILURES) break
                    sleep(RETRY_MILLIS)
                } else {
                    failures = 0
                    sleep(next)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        Log.i("fs42", "prejoin: warmed ${(elapsedMillis() - started) / 1000}s - ${tally.reads} playlist reads, " +
            "${tally.segments} segments, ${tally.bytes / 1024}KB")
    }

    /**
     * One refresh: the playlist, the join's segments, kept as one snapshot. The wait before the
     * next, or null when nothing could be kept - a failure, or a playlist that is not warmable.
     */
    private fun once(mediaUrl: String, still: () -> Boolean, tally: Tally, first: Boolean): Long? {
        val readAt = elapsedMillis()
        val readAtWall = wallMillis()
        val answer = open(mediaUrl, null)
        if (answer.code != 200) {
            answer.close()
            Log.i("fs42", "prejoin: playlist HTTP ${answer.code}")
            return null
        }
        val body = answer.text()
        tally.reads++
        val parsed = LivePlaylist.parse(body, answer.finalUrl)
        if (parsed == null || !parsed.warmable) {
            Log.i("fs42", "prejoin: not a live playlist it can join; left to the network")
            throw Stop()
        }
        val held = LinkedHashMap<String, ByteArray>()
        for (segment in LivePlaylist.joinSegments(parsed, SEGMENTS)) {
            if (!still()) return null
            val kept = cache.segment(mediaUrl, segment.url)
            val bytes = kept ?: fetchSegment(segment.url) ?: break
            if (kept == null) {
                tally.segments++
                tally.bytes += bytes.size
            }
            held[segment.url] = bytes
        }
        if (held.isEmpty() || !still()) return null
        val snapshot = PrejoinCache.Snapshot(mediaUrl, body, answer.finalUrl, readAt, readAtWall, parsed.targetMillis, held)
        if (!cache.put(snapshot)) {
            Log.i("fs42", "prejoin: ${snapshot.bytes / 1024}KB of segments is over the budget; not held")
            throw Stop()
        }
        if (first) {
            Log.i("fs42", "prejoin: warm - ${held.size} segments, ${snapshot.bytes / 1024}KB, in ${elapsedMillis() - readAt}ms")
        }
        return (parsed.targetMillis / 2).coerceIn(MIN_REFRESH_MILLIS, MAX_REFRESH_MILLIS)
    }

    /** A segment's bytes, or null on any failure - the join is then held without it. */
    private fun fetchSegment(url: String): ByteArray? {
        val answer = try {
            open(url, null)
        } catch (e: java.io.IOException) {
            return null
        }
        if (answer.code != 200) {
            answer.close()
            return null
        }
        return try {
            answer.bytes(MAX_SEGMENT_BYTES)
        } catch (e: java.io.IOException) {
            null
        }
    }

    /** Not worth another try: the playlist is not a live one this can join, or too big to hold. */
    private class Stop : RuntimeException()

    companion object {
        /** How long after a channel change the neighbours are kept warm. */
        const val WINDOW_MILLIS = 60_000L

        /** Neighbours warmed at once: one each side. */
        const val MAX_WARMERS = 2

        /** The segments held per neighbour: the join, and the one ffmpeg reads next. */
        const val SEGMENTS = 2

        /** A segment larger than this is not held. */
        const val MAX_SEGMENT_BYTES = 10 * 1024 * 1024

        const val MIN_REFRESH_MILLIS = 1_000L
        const val MAX_REFRESH_MILLIS = 5_000L
        const val RETRY_MILLIS = 2_000L
        const val MAX_FAILURES = 2
    }
}
