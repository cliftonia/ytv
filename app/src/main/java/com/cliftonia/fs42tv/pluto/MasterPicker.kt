package com.cliftonia.fs42tv.pluto

import android.util.Log
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.Playable

/**
 * Reads a Pluto channel's master once, at the tune, and names the one media playlist mpv should
 * open - see [HlsMaster] for why (8-9s to a first frame on the TCL through the master, ~3s for one
 * playlist) and for the choice itself.
 *
 * Both routes: DIRECT's master and LEGACY's jmp2 url, which redirects to one ([fetch] follows it
 * and says where it landed, and relative variants resolve against that).
 *
 * Any failure - no answer inside the fetch's tight timeouts (2s connect, 3s read), an HTTP error,
 * a body that is not a master, a pick that might play silent - hands back the playable untouched,
 * so mpv opens the master exactly as it did before this existed. The master url stays the
 * playable's identity ([Hls.url]): a failure on the chosen playlist is traced to the session, and
 * the session rebuilt, exactly as a failure on the master was.
 *
 * With a [cache], a direct-route pick is remembered for its session and engine - see
 * [VariantCache] - and a tune that finds one skips the read altogether (0.7-1s on the TCL). The
 * same read runs ahead of the viewer through [prefetch], for [MasterPrefetch]. Only a pick is
 * remembered: a failed, refused or unsafe read is never cached, so the next tune asks again.
 *
 * Blocking; the tune thread, or a prefetch thread for [prefetch]. Logs times, bandwidth and
 * whether audio was separate - never a url, which carries the session's token.
 */
class MasterPicker(
    /** GET a playlist: its body and the url it finally came from. Blocking; throws on failure. */
    private val fetch: (String) -> BreakPoller.Fetched,
    /** The QUALITY ladder while mpv plays the dial; null for Media3, which reads a master lazily. */
    private val mpvLadder: () -> List<String>?,
    /** Monotonic milliseconds, for the time spent. */
    private val elapsedMillis: () -> Long,
    /** Picks remembered per session and engine; null remembers nothing, as before it existed. */
    private val cache: VariantCache? = null,
) {

    /**
     * What mpv should be handed for [playable] - itself, or itself with a playlist chosen.
     * [cacheable] is true only for the direct route, whose master url names its session: a
     * remembered pick is used, and a fresh one remembered. False reads and remembers nothing.
     */
    fun forMpv(playable: Playable, cacheable: Boolean = false, maxHeight: Int? = null): Playable {
        val hls = playable as? Hls ?: return playable
        val ladder = mpvLadder() ?: return playable
        val engine = engineOf(ladder)
        val remembered = if (cacheable) cache?.get(hls.url, engine) else null
        if (remembered != null) {
            say("variant remembered for this session; no read")
            return hls.copy(mediaUrl = remembered.mediaUrl, audioUrl = remembered.audioUrl)
        }
        // The claim the tune took on the session, from before the read - see VariantCache.put.
        val claim = if (cacheable) cache?.claim(hls.url) else null
        val pick = read(hls.url, ladder, ahead = false, maxHeight) ?: return playable
        cache?.put(hls.url, engine, VariantCache.Choice(pick.videoUrl, pick.audioUrl), claim)
        return hls.copy(mediaUrl = pick.videoUrl, audioUrl = pick.audioUrl)
    }

    /** Whether a read ahead would be of any use: mpv is the engine, and there is a cache to fill. */
    fun prefetching(): Boolean = cache != null && mpvLadder() != null

    /**
     * Read the direct-route master at [masterUrl] ahead of any tune, and remember its pick. False
     * when it is remembered already, mpv is not the engine, or the read found nothing to keep.
     * [stillWanted] is asked once the read is back: a pick read across an app stop is dropped.
     */
    fun prefetch(masterUrl: String, stillWanted: () -> Boolean = { true }): Boolean {
        val cache = cache ?: return false
        val ladder = mpvLadder() ?: return false
        val engine = engineOf(ladder)
        if (cache.has(masterUrl, engine)) return false
        // Claimed by the lease before this; nothing claimed is nothing to keep, so no read.
        val claim = cache.claim(masterUrl) ?: return false
        val pick = read(masterUrl, ladder, ahead = true) ?: return false
        if (!stillWanted()) return false
        cache.put(masterUrl, engine, VariantCache.Choice(pick.videoUrl, pick.audioUrl), claim)
        return true
    }

    /**
     * The dial's player failed on [playable], or showed no picture: forget its pick, so the
     * re-tune reads the master afresh rather than handing mpv the same playlist again.
     */
    fun forget(playable: Playable?) {
        val hls = playable as? Hls ?: return
        cache?.evict(hls.url)
    }

    /** The master's pick under [ladder]'s ceiling, or null when mpv should open the master. */
    private fun read(url: String, ladder: List<String>, ahead: Boolean, maxHeight: Int? = null): HlsMaster.Pick? {
        val tail = if (ahead) "; nothing kept" else "; mpv opens the master"
        val started = elapsedMillis()
        val fetched = try {
            fetch(url)
        } catch (e: Exception) {
            // The class only: an exception's message can hold the url.
            say("unread (${e.javaClass.simpleName}) after ${elapsedMillis() - started}ms$tail")
            return null
        }
        val took = elapsedMillis() - started
        if (!HlsMaster.isMaster(fetched.body)) {
            say("not a master (${took}ms)${if (ahead) tail else "; mpv opens the url as it is"}")
            return null
        }
        val ceiling = HlsMaster.heightCap(ladder).let { cap -> maxHeight?.let { minOf(cap, it) } ?: cap }
        val pick = HlsMaster.choose(fetched.body, fetched.url, ceiling) ?: run {
            say("no safe variant (${took}ms)$tail")
            return null
        }
        val kbps = pick.bandwidth?.let { "${it / 1000}kbps" } ?: "unstated bandwidth"
        val height = pick.height?.let { " ${it}p" }.orEmpty()
        val audio = if (pick.audioUrl != null) "separate audio" else "muxed audio"
        say("${if (ahead) "ahead: " else ""}variant $kbps$height, $audio, resolved in ${took}ms")
        return pick
    }

    private fun say(what: String) = Log.i("fs42", "pluto master: $what")

    companion object {
        /** What a pick was chosen for: mpv, under the QUALITY ceiling in force. See [VariantCache]. */
        fun engineOf(ladder: List<String>): String = "mpv@${HlsMaster.heightCap(ladder)}"
    }
}
