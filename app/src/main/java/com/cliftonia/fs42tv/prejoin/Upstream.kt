package com.cliftonia.fs42tv.prejoin

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * One answer from the network, open: its status, the url it finally came from, the headers the
 * proxy passes on, and its body - read it, then [close]. Behind a function ([Open]) so the warmer
 * and the proxy are tested without a network.
 */
class Upstream(
    val code: Int,
    val finalUrl: String,
    val headers: Map<String, String>,
    val body: InputStream,
    val close: () -> Unit,
) {

    /** The body as text, [maxBytes] at most; closes. Throws past the limit. */
    fun text(maxBytes: Int = MAX_PLAYLIST_BYTES): String = String(bytes(maxBytes), Charsets.UTF_8)

    /** The body, [maxBytes] at most; closes. Throws past the limit: a segment that big is not held. */
    fun bytes(maxBytes: Int): ByteArray = try {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = body.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > maxBytes) throw java.io.IOException("body over $maxBytes bytes")
        }
        out.toByteArray()
    } finally {
        close()
    }

    companion object {
        const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024

        /** The headers of a segment that are passed on to ffmpeg as they came. */
        val PASSED = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges")

        /**
         * GET [url] - with [range] when given - redirects followed. Timeouts a little looser than
         * the master read's: this also carries the playing stream's playlist reloads and segments.
         * Throws when there is no answer at all; an HTTP error is an answer, with its code.
         */
        fun http(url: String, range: String?): Upstream {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_MILLIS
                readTimeout = READ_MILLIS
                instanceFollowRedirects = true
                if (range != null) setRequestProperty("Range", range)
            }
            val code = connection.responseCode
            val stream = (if (code >= 400) connection.errorStream else connection.inputStream)
                ?: java.io.ByteArrayInputStream(ByteArray(0))
            val headers = PASSED.mapNotNull { name -> connection.getHeaderField(name)?.let { name to it } }.toMap()
            // Closing the stream, not disconnect(): a body read to its end returns the connection
            // to the keep-alive pool - the next playlist read skips its TCP and TLS handshakes.
            return Upstream(code, connection.url.toString(), headers, stream) { runCatching { stream.close() } }
        }

        private const val CONNECT_MILLIS = 4_000
        private const val READ_MILLIS = 8_000
    }
}

/** GET a url, with an optional Range header. */
typealias Open = (url: String, range: String?) -> Upstream
