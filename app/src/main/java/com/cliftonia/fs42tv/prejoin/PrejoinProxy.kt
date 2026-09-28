package com.cliftonia.fs42tv.prejoin

import android.util.Log
import com.cliftonia.fs42tv.player.RangeWindows
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The loopback server a warmed surf is played through: mpv is handed
 * `http://127.0.0.1:<port>/pj/<token>/live.m3u8` in place of the media playlist, and gets the
 * playlist and join segment the warmer held - at once - and everything else from the network.
 *
 * A HAND-OUT per tune ([handOut]), holding one snapshot taken out of the cache:
 *  - the FIRST playlist request is the snapshot, every uri absolute, the held segments pointing
 *    back here. ffmpeg joins it three from the end - the held segment - exactly as it would have
 *    joined the network's playlist when the snapshot was read ([windowAt] tells the break card);
 *  - every LATER playlist request - ffmpeg's reloads, a stall's reload - is read from the network
 *    afresh and rewritten the same way: a live stream never sees a playlist older than its last;
 *  - a held segment is served from memory ONCE, and its bytes let go; asked again (a reconnect,
 *    a range), or never held, it streams from the network, headers and status as they came.
 * Keys, maps and every other uri are absolute upstream urls: ffmpeg fetches them itself, so an
 * encrypted stream decrypts exactly as before.
 *
 * A new hand-out lets go of every older one's bytes: only the tune on screen is served from
 * memory. The hand-outs themselves - a few urls each - are kept, the last [MAX_HANDOUTS], since
 * mpv reloads the playing one's playlist here for as long as it is watched.
 *
 * Bound to loopback only, like [com.cliftonia.fs42tv.player.ChunkedProxy]: its urls carry tokens.
 * One request per connection. Logs hits and misses - never a url.
 */
class PrejoinProxy(
    private val open: Open,
    /** Monotonic milliseconds, for [windowAt]'s grace. */
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    /** One tune's stream: its playlist, and the segments held for its join. */
    class Handout internal constructor(val token: String, val mediaUrl: String, snapshot: PrejoinCache.Snapshot) {
        @Volatile internal var unserved: PrejoinCache.Snapshot? = snapshot
        /** When the snapshot went to mpv; null before. */
        @Volatile internal var servedAt: Long? = null
        internal val windowAtWall = snapshot.readAtWall
        /** Held bytes by upstream url, until served once or let go. */
        internal val bytes = ConcurrentHashMap(snapshot.segments)
        /** The ids the rewritten playlists gave the held segments, and back. */
        internal val ids = ConcurrentHashMap<String, String>()
        internal val urls = ConcurrentHashMap<String, String>()
    }

    /** What to answer: a status, headers, and a body in memory or streamed from [upstream]. */
    class Reply(val status: Int, val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray = ByteArray(0), val upstream: Upstream? = null)

    private val handouts = object : LinkedHashMap<String, Handout>(MAX_HANDOUTS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Handout>?) = size > MAX_HANDOUTS
    }
    private val tokens = AtomicLong(0)
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "prejoin-proxy").apply { isDaemon = true }
    }

    /** The loopback url mpv opens for [snapshot]'s stream; the server starts on the first. */
    fun handOut(snapshot: PrejoinCache.Snapshot): String {
        val port = start()
        val token = tokens.incrementAndGet().toString()
        synchronized(handouts) {
            handouts.values.forEach { it.bytes.clear(); it.unserved = null; it.servedAt = null }
            handouts[token] = Handout(token, snapshot.mediaUrl, snapshot)
        }
        return "http://127.0.0.1:$port/pj/$token/$PLAYLIST"
    }

    /**
     * The wall-clock instant of the window mpv will join, when [url] is a hand-out whose snapshot
     * has not been served yet - or was served within [FIRST_LOAD_GRACE_MILLIS], since mpv may ask
     * for it before the break card does. What the card anchors on in place of the load's own time;
     * null for any later load of the same url (a reload), which joins the network's own window.
     */
    fun windowAt(url: String?): Long? {
        val token = url?.let(::tokenOf) ?: return null
        val handout = synchronized(handouts) { handouts[token] } ?: return null
        val served = handout.servedAt
        val first = handout.unserved != null || (served != null && elapsedMillis() - served < FIRST_LOAD_GRACE_MILLIS)
        return if (first) handout.windowAtWall else null
    }

    /** Stop listening, and let go of everything held. */
    @Synchronized
    fun release() {
        runCatching { server?.close() }
        server = null
        synchronized(handouts) { handouts.clear() }
        pool.shutdownNow()
    }

    /** The answer to GET [path] with [range] - the whole of the proxy but the socket. */
    fun handle(path: String, range: String?): Reply {
        val parts = path.substringBefore('?').trim('/').split('/')
        if (parts.size != 3 || parts[0] != "pj") return Reply(404)
        val handout = synchronized(handouts) { handouts[parts[1]] } ?: return Reply(404)
        return if (parts[2] == PLAYLIST) playlist(handout) else segment(handout, parts[2], range)
    }

    private fun playlist(handout: Handout): Reply {
        val snapshot = synchronized(handouts) {
            handout.unserved.also { if (it != null) { handout.unserved = null; handout.servedAt = elapsedMillis() } }
        }
        if (snapshot != null) {
            val body = LivePlaylist.rewrite(snapshot.body, snapshot.finalUrl) { local(handout, it) }
            if (body != null) {
                Log.i("fs42", "prejoin: hit - playlist from memory, ${handout.bytes.size} segments held")
                return playlistReply(body)
            }
        }
        val answer = open(handout.mediaUrl, null)
        if (answer.code != 200) {
            answer.close()
            return Reply(answer.code)
        }
        val body = LivePlaylist.rewrite(answer.text(), answer.finalUrl) { local(handout, it) } ?: return Reply(502)
        return playlistReply(body)
    }

    private fun playlistReply(body: String) = Reply(200, listOf("Content-Type" to "application/vnd.apple.mpegurl",
        "Cache-Control" to "no-cache"), body.toByteArray(Charsets.UTF_8))

    /** The relative path a held segment is served at, or null to leave it on the network. */
    private fun local(handout: Handout, url: String): String? {
        if (!handout.bytes.containsKey(url)) return null
        val id = handout.ids.getOrPut(url) { "s${handout.ids.size}.${extensionOf(url)}" }
        handout.urls[id] = url
        return id
    }

    private fun segment(handout: Handout, id: String, range: String?): Reply {
        val url = handout.urls[id] ?: return Reply(404)
        val held = handout.bytes.remove(url)
        val slice = held?.let { bytes -> if (range == null) 0L until bytes.size.toLong() else RangeWindows.parse(range, bytes.size.toLong()) }
        if (held != null && slice != null && !slice.isEmpty()) {
            Log.i("fs42", "prejoin: hit - segment from memory, ${held.size / 1024}KB")
            val body = held.copyOfRange(slice.first.toInt(), slice.last.toInt() + 1)
            val type = if (extensionOf(url) == "ts") "video/mp2t" else "application/octet-stream"
            val headers = mutableListOf("Content-Type" to type)
            if (range != null) headers += "Content-Range" to "bytes ${slice.first}-${slice.last}/${held.size}"
            return Reply(if (range != null) 206 else 200, headers, body)
        }
        Log.i("fs42", "prejoin: miss - segment from the network")
        val answer = open(url, range)
        return Reply(answer.code, answer.headers.filterKeys { it != "Content-Length" }.toList(), upstream = answer)
    }

    @Synchronized
    private fun start(): Int {
        server?.let { return it.localPort }
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = socket
        Log.i("fs42", "prejoin proxy on 127.0.0.1:${socket.localPort}")
        pool.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                // As ChunkedProxy: a connection accepted as the pool shuts down must not throw here.
                runCatching { pool.execute { runCatching { serve(client) }; runCatching { client.close() } } }
                    .onFailure { runCatching { client.close() } }
            }
        }
        return socket.localPort
    }

    private fun serve(client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = 15_000
        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = input.readLine() ?: return
        var range: String? = null
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        val out = client.getOutputStream()
        val words = request.split(' ')
        if (words.size < 2 || words[0] != "GET") {
            write(out, Reply(405), null)
            return
        }
        val reply = try {
            handle(words[1], range)
        } catch (e: Exception) {
            // The network would not answer at all: as a CDN that did not, so ffmpeg retries or fails.
            Log.i("fs42", "prejoin: upstream failed (${e.javaClass.simpleName})")
            Reply(502)
        }
        write(out, reply, reply.upstream)
    }

    private fun write(out: OutputStream, reply: Reply, upstream: Upstream?) {
        try {
            val head = StringBuilder("HTTP/1.1 ${reply.status} ${reason(reply.status)}\r\n")
            reply.headers.forEach { (name, value) -> head.append("$name: $value\r\n") }
            if (upstream == null) head.append("Content-Length: ${reply.body.size}\r\n")
            else upstream.headers["Content-Length"]?.let { head.append("Content-Length: $it\r\n") }
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (upstream == null) out.write(reply.body) else upstream.body.copyTo(out, 64 * 1024)
            out.flush()
        } finally {
            upstream?.close?.invoke()
        }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        502 -> "Bad Gateway"
        else -> "Status"
    }

    companion object {
        const val PLAYLIST = "live.m3u8"

        /** A first load's playlist read that beat the break card's own call to [windowAt]. */
        const val FIRST_LOAD_GRACE_MILLIS = 3_000L

        /** Hand-outs kept: the stream playing, and a few recently left that a retune may reload. */
        const val MAX_HANDOUTS = 16

        /** The token of a hand-out url, or null when [url] is not one of this proxy's. */
        fun tokenOf(url: String): String? {
            if (!url.startsWith("http://127.0.0.1:")) return null
            val parts = url.substringAfter("/pj/", "").split('/')
            return parts.getOrNull(0)?.takeIf { it.isNotEmpty() && parts.getOrNull(1) == PLAYLIST }
        }

        /**
         * The upstream segment's own extension, so ffmpeg's check on segment extensions (hls
         * `extension_picky`) sees what it would have seen; `ts` when there is none to be read.
         */
        fun extensionOf(url: String): String {
            val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext.takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: "ts"
        }
    }
}
