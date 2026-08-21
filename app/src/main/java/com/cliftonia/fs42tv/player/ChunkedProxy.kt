package com.cliftonia.fs42tv.player

import android.util.Log
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * A loopback HTTP server that fetches googlevideo in BOUNDED ranges on mpv's behalf.
 *
 * mpv asks ffmpeg for a whole file, and googlevideo answers an open-ended request at roughly the
 * video's own bitrate: 3.61 Mbps measured, against content needing 2.2 Mbps. That thin margin is
 * why mpv took seven to ten seconds to show a picture where Media3 took one and a half. Media3 is
 * fast because [ChunkedDataSource] splits every read into bounded windows, which the same CDN
 * serves at ~400 Mbps.
 *
 * mpv cannot be taught that trick directly, so it is done for it: mpv is handed a
 * `http://127.0.0.1:<port>/<id>` URL, and this proxy fetches the real one in 8 MB windows and
 * streams the bytes back. The engine needs no knowledge of any of it.
 *
 * Bound to loopback only. The URLs it holds are signed, IP-locked and expire in hours, but they
 * are still credentials of a sort, and a server listening on the LAN that will fetch any
 * registered URL for anyone is not something this app has any reason to offer.
 */
class ChunkedProxy(private val window: Long = RangeWindows.DEFAULT_WINDOW) {

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val registry = ConcurrentHashMap<String, String>()
    private val ids = AtomicLong(0)

    val port: Int get() = server?.localPort ?: -1

    /** Begin listening on an ephemeral loopback port. Idempotent. */
    @Synchronized
    fun start() {
        if (server != null) return
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = socket
        Log.i("fs42", "chunked proxy on 127.0.0.1:${socket.localPort}")
        pool.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                // runCatching, because release() shuts the pool down while accept() may
                // have one last connection in hand: dispatching it then throws
                // RejectedExecutionException on the accept thread, and an uncaught exception
                // there takes the whole process down over a socket nobody wanted.
                runCatching {
                    pool.execute { runCatching { serve(client) }; runCatching { client.close() } }
                }.onFailure { runCatching { client.close() } }
            }
        }
    }

    /**
     * Register [url] and return the loopback URL to hand mpv instead.
     *
     * Registrations are never evicted while the proxy lives. A session tunes a few hundred times
     * at most and each entry is a string; a cache that forgets the URL mpv is midway through
     * reading would be a stall, which costs far more than the memory.
     */
    fun proxied(url: String): String {
        start()
        val id = ids.incrementAndGet().toString()
        registry[id] = url
        return "http://127.0.0.1:$port/$id"
    }

    fun release() {
        runCatching { server?.close() }
        server = null
        registry.clear()
        pool.shutdownNow()
    }

    private fun serve(client: Socket) {
        client.tcpNoDelay = true
        val input = client.getInputStream().bufferedReader()
        val requestLine = input.readLine() ?: return
        var rangeHeader: String? = null
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) {
                rangeHeader = line.substringAfter(':').trim()
            }
        }

        val id = ProxyProtocol.idFrom(requestLine) ?: return
        val target = registry[id] ?: run {
            client.getOutputStream().write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
            return
        }
        val head = ProxyProtocol.isHead(requestLine)

        // The first window doubles as the length probe. Every request here comes back with
        // `Content-Range: bytes a-b/total`, so asking separately was a whole extra connection -
        // TLS handshake included - before a single byte of video could move.
        val first = open(target, 0, 0) ?: run {
            client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            return
        }
        // One casing is enough here: HttpURLConnection.getHeaderField is case-insensitive,
        // unlike the raw header map Media3 hands ChunkedDataSource.
        val total = RangeWindows.totalLength(first.getHeaderField("Content-Range"))
        runCatching { first.inputStream.close() }
        if (total == null) {
            client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            return
        }
        val asked = RangeWindows.parse(rangeHeader, total) ?: 0..(total - 1)
        val out = client.getOutputStream()

        // 206 whenever a Range was asked for, even when it covers the whole resource - see
        // ProxyProtocol.header, which holds the reason and can be tested without a socket.
        val header = ProxyProtocol.header(partial = rangeHeader != null, asked = asked, total = total)
        out.write(header.toByteArray())
        if (head) { out.flush(); return }

        for (chunk in RangeWindows.of(asked.first, asked.last, window)) {
            // A client that has gone away is normal, not exceptional: mpv closes the connection
            // on every channel change, mid-window. Stopping quietly is the correct response.
            if (!pump(target, chunk, out)) return
        }
        runCatching { out.flush() }
    }

    /** Copy one bounded window upstream-to-client. False when the client or the CDN gave up. */
    private fun pump(target: String, range: LongRange, out: OutputStream): Boolean {
        val conn = open(target, range.first, range.last) ?: return false
        return try {
            conn.inputStream.use { it.copyTo(out, DEFAULT_BUFFER_SIZE) }
            true
        } catch (e: Exception) {
            false
        }
        // NOT disconnect(). That closes the socket and forces the next window to pay a fresh TCP
        // handshake and TLS negotiation to the same host - measured at hundreds of milliseconds
        // against roughly twenty for a reused one, on every 8MB. Fully reading the body, which
        // `use` does, returns the connection to HttpURLConnection's pool instead.
    }

    /** One bounded request, or null if the CDN refused it. */
    private fun open(target: String, from: Long, to: Long): HttpURLConnection? = try {
        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=$from-$to")
            // Explicit, because the whole design depends on it and a default is not a promise.
            setRequestProperty("Connection", "keep-alive")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        if (conn.responseCode !in 200..299) {
            Log.w("fs42", "proxy upstream ${conn.responseCode} for bytes=$from-$to")
            conn.disconnect()
            null
        } else {
            conn
        }
    } catch (e: Exception) {
        null
    }
}
