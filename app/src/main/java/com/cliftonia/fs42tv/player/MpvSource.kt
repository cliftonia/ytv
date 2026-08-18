package com.cliftonia.fs42tv.player

import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.NeedsResolving
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.Unplayable

/**
 * The URL mpv is handed for a playable, or null when there is nothing to hand it.
 *
 * Separated from [MpvChannelPlayer] because that class cannot be loaded in a JVM test at all:
 * `MPVLib`'s static initialiser calls `System.loadLibrary("mpv")`, so every rule about which
 * tracks go through the proxy and which do not was unreachable. Behind a `proxied` lambda they
 * all are, and none of them needs a player.
 */
object MpvSource {

    fun urlFor(playable: Playable, proxied: (String) -> String): String? = when (playable) {
        is Progressive ->
            if (playable.audioUrl == null) proxied(playable.videoUrl)
            // YouTube serves video and audio apart above 360p. mpv's EDL plays them as one
            // stream, which is how the box has always played these same URLs. BOTH go through
            // the proxy - the audio track is small but it is fetched over the same throttled
            // connection, and a starved audio track stalls the video just as surely.
            else MpvEdl.of(proxied(playable.videoUrl), proxied(playable.audioUrl))

        // Live HLS is left alone: it is already a series of bounded segment requests, which
        // is why it was never throttled and never slow. Proxying it would add a hop for
        // nothing.
        is Hls -> playable.url

        is NeedsResolving, is Unplayable -> null
    }
}
