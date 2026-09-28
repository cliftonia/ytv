package com.cliftonia.fs42tv.relay

import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.resolver.Unplayable
import com.cliftonia.fs42tv.sync.Stream

/**
 * US-only live feeds, played through the home server's US tunnel.
 *
 * Some FAST channels (Samsung TV Plus US, Fire TV, Tubi, Plex...) are plain HLS that answers
 * only a US address. The home server relays them from inside its Los Angeles Mullvad tunnel
 * (tools/fast-relay) on [PORT]; the lineup marks such a stream `"route": "us"`.
 *
 * The same machine as the resolve accelerator, so "is it reachable" is the accelerator's last
 * health reading, exactly as [com.cliftonia.fs42tv.ads.AdMirror] asks it. Unlike the ad mirror
 * there is no fallback: the direct url does not play from Australia, so with no server the stream
 * is [Unplayable] and the dial shows the channel as unavailable - the car, away from home.
 *
 * Pure: no network, no Android.
 */
object FastRelay {

    /** fast_relay.py's front on the home server - beside the Pluto sessions' 4246. */
    const val PORT = 4247

    /** The one route the server relays today. */
    const val US = "us"

    /**
     * The relay's url for [upstream] on the machine at [resolveServer] (e.g.
     * `http://192.168.4.58:4243`), or null when there is no server.
     */
    fun url(resolveServer: String?, upstream: String): String? {
        if (resolveServer == null) return null
        val uri = runCatching { java.net.URI(resolveServer) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val scheme = uri.scheme ?: return null
        return "$scheme://$host:$PORT/hls?u=${java.net.URLEncoder.encode(upstream, "UTF-8")}"
    }

    /**
     * The url a tune of live [channel] hands on to be played - its first stream, as the tuner
     * picks it for a live channel, through the relay when routed - or null: no stream, or a US
     * route with no server. For reading a FAST neighbour's master ahead exactly as its tune would.
     */
    fun liveUrl(channel: com.cliftonia.fs42tv.sync.Channel, resolveServer: () -> String?): String? {
        if (channel.kind != "live") return null
        val stream = channel.streams.firstOrNull() ?: return null
        return (route(stream, Hls(stream.url), resolveServer) as? Hls)?.url
    }

    /**
     * What to play for live [stream], whose published playable is [playable]. A stream without a
     * route comes back as [playable] itself, untouched, and [resolveServer] is never asked.
     */
    fun route(stream: Stream, playable: Playable, resolveServer: () -> String?): Playable {
        val route = stream.route?.trim()?.lowercase() ?: return playable
        val hls = playable as? Hls ?: return playable
        if (route != US) return Unplayable("no relay for route \"$route\"")
        val relayed = url(resolveServer(), hls.url)
            ?: return Unplayable("a US-only stream, and no home server to relay it")
        return Hls(relayed)
    }
}
