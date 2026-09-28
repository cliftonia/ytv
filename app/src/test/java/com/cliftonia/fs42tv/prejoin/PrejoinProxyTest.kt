package com.cliftonia.fs42tv.prejoin

import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loopback copy mpv opens: the warmed playlist and join segment once, from memory; every
 * reload and every other segment from the network, as it came.
 */
class PrejoinProxyTest {

    private val media = "https://cdn.example/n/720p.m3u8?tok=T"
    private fun seg(n: Long) = "https://cdn.example/n/seg$n.ts?tok=T"
    private val net = FakeNet()
    private var now = 0L
    private val proxy = PrejoinProxy(net.open) { now }

    @After
    fun release() = proxy.release()

    private fun snapshot(first: Long = 100, wall: Long = 42L): PrejoinCache.Snapshot {
        net.text(media, FakeNet.live(first + 1, 6))
        (first until first + 8).forEach { net.bytes(seg(it), 2_000) }
        return PrejoinCache.Snapshot(media, FakeNet.live(first, 6, key = true), media, 0L, wall, 6_000L,
            mapOf(seg(first + 3) to ByteArray(2_000) { 7 }, seg(first + 4) to ByteArray(2_000) { 8 }))
    }

    private fun path(url: String) = url.substringAfter("127.0.0.1:").substringAfter('/').let { "/$it" }

    private fun text(reply: PrejoinProxy.Reply) = String(reply.body)

    @Test
    fun `the first playlist is the snapshot, its join served from memory - the next is the network's`() {
        val url = proxy.handOut(snapshot())
        val first = proxy.handle(path(url), null)
        assertEquals(200, first.status)
        val lines = text(first).lines()
        assertTrue(lines.contains("#EXT-X-MEDIA-SEQUENCE:100"))
        assertTrue("held: third from the end, and the next", lines.containsAll(listOf("s0.ts", "s1.ts")))
        assertTrue(lines.contains(seg(102)))
        assertTrue(lines.contains(seg(105)))
        assertTrue("the key stays on the network", lines.any { it.contains("URI=\"https://cdn.example/n/keys/k1?t=1\"") })
        assertTrue("no playlist read for the hit", net.asked.isEmpty())
        // The reload: read afresh, and still pointing at the held segments ffmpeg has not read yet.
        val reload = proxy.handle(path(url), null)
        assertTrue(text(reload).lines().contains("#EXT-X-MEDIA-SEQUENCE:101"))
        assertTrue(text(reload).lines().contains("s0.ts"))
        assertEquals(1, net.count(media))
    }

    @Test
    fun `a held segment is served once from memory, then from the network`() {
        val url = proxy.handOut(snapshot())
        val base = path(url).substringBeforeLast('/')
        proxy.handle(path(url), null)
        val hit = proxy.handle("$base/s0.ts", null)
        assertEquals(200, hit.status)
        assertArrayEquals(ByteArray(2_000) { 7 }, hit.body)
        assertTrue(net.asked.isEmpty())
        val again = proxy.handle("$base/s0.ts", null)
        assertEquals(200, again.status)
        assertEquals(listOf(seg(103) to null), net.asked)
        again.upstream!!.close()
    }

    @Test
    fun `a range of a held segment is a 206 slice - a miss passes the range upstream`() {
        val url = proxy.handOut(snapshot())
        val base = path(url).substringBeforeLast('/')
        proxy.handle(path(url), null)
        val slice = proxy.handle("$base/s1.ts", "bytes=1000-")
        assertEquals(206, slice.status)
        assertEquals(1_000, slice.body.size)
        assertTrue(slice.headers.contains("Content-Range" to "bytes 1000-1999/2000"))
        val miss = proxy.handle("$base/s1.ts", "bytes=500-")
        assertEquals(206, miss.status)
        assertEquals(listOf(seg(104) to "bytes=500-"), net.asked)
    }

    @Test
    fun `a new hand-out lets go of the old one's bytes, which then come from the network`() {
        val old = proxy.handOut(snapshot())
        val base = path(old).substringBeforeLast('/')
        proxy.handle(path(old), null)
        proxy.handOut(snapshot(200))
        val after = proxy.handle("$base/s0.ts", null)
        assertEquals(listOf(seg(103) to null), net.asked)
        after.upstream!!.close()
        assertNull("the old one's snapshot is gone too", proxy.windowAt(old))
    }

    @Test
    fun `the break card's anchor is the snapshot's read, for the first load only`() {
        val url = proxy.handOut(snapshot(wall = 1234L))
        assertEquals(1234L, proxy.windowAt(url))
        proxy.handle(path(url), null)
        assertEquals("mpv may read it before the card asks", 1234L, proxy.windowAt(url))
        now += PrejoinProxy.FIRST_LOAD_GRACE_MILLIS
        assertNull("a reload joins the network's own window", proxy.windowAt(url))
        assertNull(proxy.windowAt("https://cdn.example/n/720p.m3u8"))
    }

    @Test
    fun `an upstream refusal on reload is passed on as it came - unknown paths are 404`() {
        val url = proxy.handOut(snapshot())
        proxy.handle(path(url), null)
        net.text(media, "", code = 403)
        assertEquals(403, proxy.handle(path(url), null).status)
        assertEquals(404, proxy.handle("/pj/999/live.m3u8", null).status)
        assertEquals(404, proxy.handle(path(url).replace("live.m3u8", "s9.ts"), null).status)
        assertEquals(404, proxy.handle("/elsewhere", null).status)
    }

    @Test
    fun `segments keep their own extension, for ffmpeg's check`() {
        assertEquals("ts", PrejoinProxy.extensionOf("https://a/b/seg1.ts?x=1"))
        assertEquals("aac", PrejoinProxy.extensionOf("https://a/b/seg1.aac"))
        assertEquals("ts", PrejoinProxy.extensionOf("https://a/b/segment?x=a.b/c"))
    }

    @Test
    fun `over a real loopback socket - playlist, held segment, and a miss streamed through`() {
        val url = proxy.handOut(snapshot())
        assertTrue(url.startsWith("http://127.0.0.1:"))
        fun get(u: String): Pair<Int, ByteArray> {
            val c = URL(u).openConnection() as HttpURLConnection
            return try { c.responseCode to (if (c.responseCode < 400) c.inputStream.readBytes() else ByteArray(0)) } finally { c.disconnect() }
        }
        val (code, body) = get(url)
        assertEquals(200, code)
        assertTrue(String(body).contains("s0.ts"))
        val base = url.substringBeforeLast('/')
        assertArrayEquals(ByteArray(2_000) { 7 }, get("$base/s0.ts").second)
        val streamed = get("$base/s0.ts")
        assertEquals(200, streamed.first)
        assertEquals(2_000, streamed.second.size)
        // No upstream at all: a 502, as a CDN that would not answer.
        net.answers.remove(media)
        assertEquals(502, get(url).first)
    }
}
