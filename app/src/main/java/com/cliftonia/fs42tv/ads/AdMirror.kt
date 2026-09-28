package com.cliftonia.fs42tv.ads

/**
 * Where the home server keeps its copy of a reel, when the television can reach it.
 *
 * A reel from archive.org took 8.6-10s to show a frame on the TCL: from Brisbane its redirect
 * costs about a second and every range request another second and a half to its first byte, and
 * a join is several - the moov at the front, then a seek to the cut mid-file. The nightly job on
 * the home server keeps every published reel (tools/ads-pool/mirror_ads.py) and serves them on
 * [PORT] (serve_ads.py) - byte for byte the archive's file, so the pool's cuts hold.
 *
 * The same machine as the resolve accelerator, so "is it reachable" is the accelerator's last
 * health reading: [url] takes that server's base url (null when none is available - the car)
 * and swaps in the reel server's port. Never a dependency: a reel missing from the mirror, or
 * a mirror that is down, is the archive's copy of the same reel - see [com.cliftonia.fs42tv.ui.BreakAds].
 *
 * Pure: no network, no Android.
 */
object AdMirror {

    /** serve_ads.py's port on the home server - beside the accelerator's 4243. */
    const val PORT = 4245

    /** archive.org identifiers, as the server accepts them: never a path, never a dot first. */
    private val ID = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,199}$")

    /**
     * The mirror's url for reel [id] on the machine at [resolveServer] (e.g.
     * `http://192.168.4.58:4243`), or null when there is no server or the id is not one the
     * mirror could hold.
     */
    fun url(resolveServer: String?, id: String): String? {
        if (resolveServer == null || !ID.matches(id)) return null
        val uri = runCatching { java.net.URI(resolveServer) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val scheme = uri.scheme ?: return null
        return "$scheme://$host:$PORT/ads/$id.mp4"
    }
}
