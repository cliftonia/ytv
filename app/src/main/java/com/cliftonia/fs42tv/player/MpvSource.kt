package com.cliftonia.fs42tv.player

import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.Unplayable

/** What mpv is asked to open: one file, and optionally a separate audio track alongside it. */
data class MpvLoad(val url: String, val audioFile: String? = null)

/**
 * What mpv is handed for a playable, or null when there is nothing to hand it.
 *
 * Separated from [MpvChannelPlayer] because that class cannot be loaded in a JVM test at all:
 * `MPVLib`'s static initialiser calls `System.loadLibrary("mpv")`, so every rule about which
 * tracks go through the proxy and which do not was unreachable. Behind a `proxied` lambda they
 * all are, and none of them needs a player.
 */
object MpvSource {

    fun loadFor(playable: Playable, proxied: (String) -> String): MpvLoad? = when (playable) {
        is Progressive ->
            if (playable.audioUrl == null) MpvLoad(proxied(playable.videoUrl))
            // YouTube serves video and audio apart above 360p, and mpv offers two ways to put
            // them back together. An EDL welds them into one synthetic stream; `audio-file`
            // opens the video normally and attaches the audio as an external track. This takes
            // the second, and the difference is entirely in how they FAIL.
            //
            // A signed googlevideo url can be refused while still inside its stated expiry, and
            // when that happens to an EDL the result is a stream list with nothing usable in it -
            // which mpv treats as fatal and answers by shutting its core down. That is upstream
            // behaviour, documented in mpv issues 11404 and 11426 against exactly this
            // arrangement of separate YouTube tracks, and it is what the comment in this file
            // used to record as "EDL with no streams is FATAL". A dial cannot live with a fatal:
            // it has to survive a dead clip and tune the next one.
            //
            // With an external audio track there is no synthetic stream to degenerate. A refused
            // audio url leaves a silent picture, a refused video url is an ordinary file-open
            // error, and `idle=yes` keeps the core alive through either - which is the whole
            // point of setting it.
            //
            // BOTH still go through the proxy. The audio track is small but it is fetched over
            // the same throttled connection, and a starved audio track stalls the video just as
            // surely.
            else MpvLoad(proxied(playable.videoUrl), proxied(playable.audioUrl))

        // Live HLS is left alone: it is already a series of bounded segment requests, which
        // is why it was never throttled and never slow. Proxying it would add a hop for
        // nothing.
        is Hls -> MpvLoad(playable.url)

        is NeedsResolving, is Unplayable -> null
    }
}
