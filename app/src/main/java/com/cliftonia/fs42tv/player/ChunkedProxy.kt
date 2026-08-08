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
                pool.execute { runCatching { serve(client) }; runCatching { client.close() } }
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

        val id = requestLine.split(' ').getOrNull(1)?.trimStart('/') ?: return
        val target = registry[id] ?: run {
            client.getOutputStream().write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
            return
        }
        val head = requestLine.startsWith("HEAD")

        val total = lengthOf(target) ?: run {
            client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            return
        }
        val asked = RangeWindows.parse(rangeHeader, total) ?: (0 until total).let { 0..(total - 1) }
        val out = client.getOutputStream()

        // Answer 206 whenever a Range was asked for, even when it covers the whole resource:
        // ffmpeg decides a stream is seekable from the status and the Content-Range, and a
        // stream it thinks is unseekable cannot be started at a wall-clock offset at all.
        val partial = rangeHeader != null
        val header = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: video/mp4\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: ${asked.last - asked.first + 1}\r\n")
            if (partial) append("Content-Range: bytes ${asked.first}-${asked.last}/$total\r\n")
            append("Connection: close\r\n\r\n")
        }
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
        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=${range.first}-${range.last}")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        return try {
            if (conn.responseCode !in 200..299) {
                Log.w("fs42", "proxy upstream ${conn.responseCode} for bytes=${range.first}-${range.last}")
                return false
            }
            conn.inputStream.use { it.copyTo(out, DEFAULT_BUFFER_SIZE) }
            true
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Total size of the resource, from a one-byte bounded probe.
     *
     * Deliberately not a HEAD: googlevideo answers HEAD inconsistently, while a `bytes=0-0`
     * request is the same shape as every other request this proxy makes and comes back with
     * `Content-Range: bytes 0-0/<total>`.
     */
    private fun lengthOf(target: String): Long? {
        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Range", "bytes=0-0")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        return try {
            conn.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn.inputStream.close() }
            conn.disconnect()
        }
    }
}
