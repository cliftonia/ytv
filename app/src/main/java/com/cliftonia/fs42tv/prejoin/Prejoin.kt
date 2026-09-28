package com.cliftonia.fs42tv.prejoin

import android.util.Log
import com.cliftonia.fs42tv.pluto.VariantCache
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.sync.Channel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * PRE-JOIN: the channels either side of the one on screen, kept a moment from playing - their
 * media playlist and the segment ffmpeg would start on held in memory for a minute after each
 * channel change - so a surf onto one plays at once, at the quality already picked.
 *
 * WHY: with the master read gone (MasterPrefetch), a warm Pluto surf on the TCL was still 2.3s -
 * 0.4s for mpv's playlist read, 1.1s to probe the first segment, 0.3s to decode. The first two
 * are network waits on bytes that can be fetched before the button is pressed. The relayed US
 * feeds pay a fresh TLS handshake over a 160ms path for each, which is most of their 7-9s.
 *
 * HOW, in three steps:
 *  1. [neighbourReady] - a neighbour's pick is remembered (MasterPrefetch, a few seconds after the
 *     picture): [PrejoinWarmer] keeps its join warm in [PrejoinCache] for a minute.
 *  2. [tuning] - a tune has begun: every warm stops, so nothing more is read for any channel.
 *  3. [handOff] - the tune has its playlist: if that very playlist is warm and young, mpv is
 *     handed [PrejoinProxy]'s loopback url for it instead ([Hls.mpvUrl]); everything else held
 *     is dropped. A miss changes nothing - mpv opens the playlist from the network, as before.
 *
 * PLUTO: only on the neighbour's own session - the one its master was read ahead on, where the
 * pick was remembered. A Pluto session carries one channel; this reads that channel's media
 * playlist and segments on it, which is what playing it there would do, and never a master. The
 * warm stops the moment the pick stops standing - its session re-pointed, rebuilt or expired -
 * and at the tune, before any session is chosen for it.
 *
 * NOT WARMED: Media3 (no pick - it opens masters itself), a pick with a separate audio track,
 * and any playlist the warmer cannot join - see [LivePlaylist]. Behind the PRE-JOIN row.
 *
 * Nothing here touches the player, the break poller or the first-frame guard: mpv is handed a url,
 * and all it can tell is that the first two reads came back quickly.
 */
class Prejoin(
    /** The pick remembered for a master - [com.cliftonia.fs42tv.pluto.MasterPicker.remembered]. */
    private val pickOf: (masterUrl: String, fast: Boolean) -> VariantCache.Choice?,
    /** A FAST feed's master url as its tune would open it, or null. */
    private val fastMaster: (Channel) -> String?,
    private val warmer: PrejoinWarmer,
    private val cache: PrejoinCache,
    private val proxy: PrejoinProxy,
    /** The PRE-JOIN row. */
    private val enabled: () -> Boolean,
) {

    /**
     * [channel], a neighbour of the one on screen, has its pick remembered: under [masterUrl] -
     * its own session's, for Pluto - or, when null, its FAST master. Warm it while [stillWanted] -
     * the same tune on air, no reel on the player, the app in front. Prefetch thread.
     */
    fun neighbourReady(channel: Channel, masterUrl: String?, stillWanted: () -> Boolean) {
        if (!enabled() || !stillWanted()) return
        val fast = masterUrl == null
        val master = masterUrl ?: fastMaster(channel) ?: return
        val pick = pickOf(master, fast) ?: return
        if (pick.audioUrl != null) return
        val media = pick.mediaUrl
        // Still the pick for that master - on Pluto, still claimed on that session.
        warmer.warm(media, valid = { pickOf(master, fast)?.mediaUrl == media }) { enabled() && stillWanted() }
    }

    /** A tune has begun: nothing more is read for any neighbour. Tune thread. */
    fun tuning() {
        warmer.stop()
    }

    /**
     * What the tune hands mpv for [playable]: itself, or - when its playlist is warm - itself with
     * the loopback copy to open. Everything else held is let go either way. Tune thread.
     */
    fun handOff(playable: Playable?): Playable? {
        val hls = playable as? Hls
        val media = hls?.mediaUrl
        val out = if (hls == null || media == null || hls.audioUrl != null || !enabled()) {
            playable
        } else {
            val snapshot = cache.take(media) { why -> Log.i("fs42", "prejoin: miss - $why") }
            // A loopback server that will not start is a miss, never a failed tune.
            val url = snapshot?.let { runCatching { proxy.handOut(it) }.getOrNull() }
            url?.let { hls.copy(mpvUrl = it) } ?: playable
        }
        cache.clear()
        return out
    }

    /** The window a hand-out not yet opened will join - see [PrejoinProxy.windowAt]. */
    fun windowAt(playable: Playable?): Long? = proxy.windowAt((playable as? Hls)?.mpvUrl)

    /** Nothing is warmed and nothing held: the app left the screen, or the dial left LIVE TV. */
    fun stop() {
        warmer.stop()
        cache.clear()
    }

    /** The app is going away: the loopback server too. */
    fun release() {
        stop()
        proxy.release()
    }

    companion object {
        /** On the television: two warm threads at minimum priority, lapsing when idle. */
        fun onDevice(
            pickOf: (String, Boolean) -> VariantCache.Choice?,
            fastMaster: (Channel) -> String?,
            enabled: () -> Boolean,
        ): Prejoin {
            val pool = ThreadPoolExecutor(PrejoinWarmer.MAX_WARMERS, PrejoinWarmer.MAX_WARMERS, 30, TimeUnit.SECONDS,
                LinkedBlockingQueue()) { runnable ->
                Thread(runnable, "prejoin-warm").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }.apply { allowCoreThreadTimeOut(true) }
            val elapsed = android.os.SystemClock::elapsedRealtime
            val cache = PrejoinCache(elapsed)
            val warmer = PrejoinWarmer(
                open = Upstream::http,
                cache = cache,
                elapsedMillis = elapsed,
                wallMillis = System::currentTimeMillis,
                sleep = { Thread.sleep(it) },
                background = { block ->
                    val future = pool.submit {
                        runCatching(block).onFailure { Log.w("fs42", "prejoin: warm failed: ${it.javaClass.simpleName}") }
                    }
                    ({ future.cancel(true) })
                },
            )
            return Prejoin(pickOf, fastMaster, warmer, cache, PrejoinProxy(Upstream::http), enabled)
        }
    }
}
