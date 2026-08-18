package com.cliftonia.fs42tv.player

/**
 * The HTTP that [ChunkedProxy] speaks to mpv, separated from the socket loop that speaks it.
 *
 * Pure string work with no I/O, which is the only reason it can be tested at all: reaching any of
 * it through [ChunkedProxy] means a real `ServerSocket`, a real client and a real googlevideo URL.
 * What it decides is not cosmetic - ffmpeg works out whether a stream is seekable from the status
 * line and the `Content-Range`, and a stream it believes is unseekable cannot be started at a
 * wall-clock offset at all, which is every tune this dial makes.
 */
object ProxyProtocol {

    /** The registered id a request line asks for, or null when the line is not usable. */
    fun idFrom(requestLine: String): String? =
        requestLine.split(' ').getOrNull(1)?.trimStart('/')

    /** Whether the client asked for headers only. */
    fun isHead(requestLine: String): Boolean = requestLine.startsWith("HEAD")

    /**
     * The response head for a request that resolved to [asked] out of [total] bytes.
     *
     * [partial] is true whenever the client sent a Range header at all, and the answer is then 206
     * even when the range covers the whole resource. That is deliberate: ffmpeg decides a stream
     * is seekable from the status and the Content-Range, and a stream it thinks is unseekable
     * cannot be started at a wall-clock offset at all.
     */
    fun header(partial: Boolean, asked: LongRange, total: Long): String = buildString {
        append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        append("Content-Type: video/mp4\r\n")
        append("Accept-Ranges: bytes\r\n")
        append("Content-Length: ${asked.last - asked.first + 1}\r\n")
        if (partial) append("Content-Range: bytes ${asked.first}-${asked.last}/$total\r\n")
        append("Connection: close\r\n\r\n")
    }
}
