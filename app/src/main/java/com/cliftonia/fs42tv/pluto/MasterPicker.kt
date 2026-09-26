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
 * Blocking; the tune thread only. Logs times, bandwidth and whether audio was separate - never a
 * url, which carries the session's token.
 */
class MasterPicker(
    /** GET a playlist: its body and the url it finally came from. Blocking; throws on failure. */
    private val fetch: (String) -> BreakPoller.Fetched,
    /** The QUALITY ladder while mpv plays the dial; null for Media3, which reads a master lazily. */
    private val mpvLadder: () -> List<String>?,
    /** Monotonic milliseconds, for the time spent. */
    private val elapsedMillis: () -> Long,
) {

    /** What mpv should be handed for [playable] - itself, or itself with a playlist chosen. */
    fun forMpv(playable: Playable): Playable {
        val hls = playable as? Hls ?: return playable
        val ladder = mpvLadder() ?: return playable
        val started = elapsedMillis()
        val fetched = try {
            fetch(hls.url)
        } catch (e: Exception) {
            // The class only: an exception's message can hold the url.
            say("unread (${e.javaClass.simpleName}) after ${elapsedMillis() - started}ms; mpv opens the master")
            return playable
        }
        val took = elapsedMillis() - started
        if (!HlsMaster.isMaster(fetched.body)) {
            say("not a master (${took}ms); mpv opens the url as it is")
            return playable
        }
        val pick = HlsMaster.choose(fetched.body, fetched.url, HlsMaster.heightCap(ladder)) ?: run {
            say("no safe variant (${took}ms); mpv opens the master")
            return playable
        }
        val kbps = pick.bandwidth?.let { "${it / 1000}kbps" } ?: "unstated bandwidth"
        val height = pick.height?.let { " ${it}p" }.orEmpty()
        val audio = if (pick.audioUrl != null) "separate audio" else "muxed audio"
        say("variant $kbps$height, $audio, resolved in ${took}ms")
        return hls.copy(mediaUrl = pick.videoUrl, audioUrl = pick.audioUrl)
    }

    private fun say(what: String) = Log.i("fs42", "pluto master: $what")
}
