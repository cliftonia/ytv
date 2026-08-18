package com.cliftonia.fs42tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the proxy tells ffmpeg about the stream it is about to read.
 *
 * Untested until now because reaching it meant a real socket. It is not a formality: mpv's ability
 * to join a clip at a wall-clock offset - which is every tune on this dial - rests on ffmpeg
 * seeing a status and a Content-Range that say the stream is seekable.
 */
class ProxyProtocolTest {

    @Test
    fun `a range request is answered 206 even when it covers the whole resource`() {
        // 200 here is the failure that looks like success: the bytes are correct, ffmpeg decides
        // the stream is not seekable, and every channel starts its clip at 00:00.
        val head = ProxyProtocol.header(partial = true, asked = 0L..999L, total = 1000)
        assertTrue(head.startsWith("HTTP/1.1 206 Partial Content\r\n"))
        assertTrue("without the Content-Range the status alone does not make it seekable",
            head.contains("Content-Range: bytes 0-999/1000\r\n"))
    }

    @Test
    fun `a request with no range is answered 200 and carries no Content-Range`() {
        val head = ProxyProtocol.header(partial = false, asked = 0L..999L, total = 1000)
        assertTrue(head.startsWith("HTTP/1.1 200 OK\r\n"))
        assertFalse(head.contains("Content-Range"))
    }

    @Test
    fun `Content-Length counts both ends of the inclusive range`() {
        // HTTP ranges are inclusive at both ends. An off-by-one here is a body one byte shorter
        // than declared, which stalls the client waiting for a byte that never comes.
        assertTrue(ProxyProtocol.header(true, 500L..999L, 1000)
            .contains("Content-Length: 500\r\n"))
        assertTrue(ProxyProtocol.header(true, 0L..0L, 1000)
            .contains("Content-Length: 1\r\n"))
    }

    @Test
    fun `every response says it accepts ranges and closes afterwards`() {
        val head = ProxyProtocol.header(true, 0L..9L, 10)
        assertTrue(head.contains("Accept-Ranges: bytes\r\n"))
        assertTrue(head.contains("Content-Type: video/mp4\r\n"))
        assertTrue("the head must end with a blank line or the client waits for more headers",
            head.endsWith("Connection: close\r\n\r\n"))
    }

    @Test
    fun `the id is the path of the request line, with its leading slash removed`() {
        assertEquals("7", ProxyProtocol.idFrom("GET /7 HTTP/1.1"))
        assertEquals("7", ProxyProtocol.idFrom("HEAD /7 HTTP/1.1"))
    }

    @Test
    fun `a request line with no path yields no id rather than an empty one`() {
        // An empty id would miss the registry and answer 404, which is the same outcome, but a
        // malformed line is not a missing registration and should not be logged as one.
        assertNull(ProxyProtocol.idFrom("GET"))
        assertNull(ProxyProtocol.idFrom(""))
    }

    @Test
    fun `only HEAD is a head request`() {
        assertTrue(ProxyProtocol.isHead("HEAD /7 HTTP/1.1"))
        assertFalse(ProxyProtocol.isHead("GET /7 HTTP/1.1"))
        assertFalse("ffmpeg sends both, and answering a GET with headers alone is a black screen",
            ProxyProtocol.isHead("get /7 HTTP/1.1"))
    }
}
