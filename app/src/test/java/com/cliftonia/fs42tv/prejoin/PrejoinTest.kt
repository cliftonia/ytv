package com.cliftonia.fs42tv.prejoin

import com.cliftonia.fs42tv.player.MpvSource
import com.cliftonia.fs42tv.pluto.PlutoSession
import com.cliftonia.fs42tv.pluto.PlutoSessions
import com.cliftonia.fs42tv.pluto.SessionPool
import com.cliftonia.fs42tv.pluto.VariantCache
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.PlutoRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-join end to end, on real Pluto sessions and claims: a neighbour warmed only on its own
 * session and only while its pick stands, a tune that stops every warm before it chooses a
 * session, and a surf onto a warmed neighbour handed its loopback copy - or, on any miss, the
 * playlist exactly as before.
 */
class PrejoinTest {

    private var now = 0L
    private var serial = 0
    private val net = FakeNet()
    private val sessions = PlutoSessions(
        boot = { PlutoSession("https://s.pluto.tv", "sid=$serial", "jwt${serial++}", "AU", now + 3 * 3_600_000L) },
        server = { null },
        nowMillis = { now },
        poolSize = SessionPool.SIZE,
    )
    private val picks = VariantCache({ now }, sessions::claimOf)
    private val cache = PrejoinCache({ now })
    private val queued = ArrayDeque<() -> Unit>()
    /** Each warm runs [steps] refreshes when [runWarms] is called. */
    private val warmer = PrejoinWarmer(net.open, cache, { now }, { 5_000L + now }, sleep = { now += it },
        background = { block -> queued.addLast(block); ({}) }, windowMillis = 2_000L)
    private val proxy = PrejoinProxy(net.open)
    private var enabled = true
    private val prejoin = Prejoin(
        pickOf = { master, _ -> picks.get(master, ENGINE) },
        fastMaster = { "https://fast.example/${it.number}/master.m3u8" },
        warmer = warmer, cache = cache, proxy = proxy, enabled = { enabled },
    )

    @After
    fun release() = prejoin.release()

    private fun pluto(id: String) = Channel(number = id.hashCode(), name = id, kind = "live", pluto = PlutoRef(id))

    /** A neighbour's master read ahead on its own leased session, its pick remembered there. */
    private fun readAhead(id: String, keep: Set<String>): String {
        val lease = sessions.lease(null, id, keep)!!
        val media = lease.masterUrl.replace("master.m3u8", "720p.m3u8")
        picks.put(lease.masterUrl, ENGINE, VariantCache.Choice(media, null), picks.claim(lease.masterUrl))
        lease.release()
        net.text(media, FakeNet.live(10, 6))
        (10L until 16L).forEach { net.bytes(LivePlaylist.absolute(media, "seg$it.ts?tok=T")!!, 500) }
        return lease.masterUrl
    }

    private fun runWarms() {
        while (queued.isNotEmpty()) queued.removeFirst()()
    }

    @Test
    fun `a surf onto a warmed Pluto neighbour opens the loopback copy, on the session read ahead`() {
        sessions.forChannel(null, "A", rotate = true)
        val master = readAhead("U", setOf("A", "U"))
        prejoin.neighbourReady(pluto("U"), master) { true }
        runWarms()
        val media = picks.get(master, ENGINE)!!.mediaUrl
        // The tune: warms stop, the route takes U's own slot, the pick is remembered there.
        prejoin.tuning()
        val onScreen = sessions.forChannel(null, "U", rotate = true)!!.session
        assertEquals("the pre-join read on the session the surf now plays", onScreen.masterUrl("U"), master)
        val out = prejoin.handOff(Hls(master, mediaUrl = media)) as Hls
        assertNotNull(out.mpvUrl)
        assertEquals("the playlist everything else reads is unchanged", media, out.mediaUrl)
        assertEquals(master, out.url)
        assertEquals(out.mpvUrl, MpvSource.loadFor(out) { it }!!.url)
        assertNotNull("the break card anchors on the snapshot's read", prejoin.windowAt(out))
        assertEquals(0, cache.size)
        assertTrue("U's playlist and join were read", net.count(media) == 1 && net.asked.size == 3)
        assertTrue("never a master", net.asked.none { it.first.contains("master.m3u8") })
    }

    @Test
    fun `once a neighbour's session carries another channel, not one more read is made for it`() {
        sessions.forChannel(null, "A", rotate = true)
        val master = readAhead("U", setOf("A", "U"))
        prejoin.neighbourReady(pluto("U"), master) { true }
        val media = picks.get(master, ENGINE)!!.mediaUrl
        // Before the warm runs, U's slot is re-pointed at X (a jump's neighbour, U no longer kept).
        now += SessionPool.QUIET_MILLIS + 1
        sessions.lease(null, "X", setOf("A", "X"))?.release()
        sessions.lease(null, "X2", setOf("A", "X", "X2"))?.release()
        assertNull("U's pick no longer stands", picks.get(master, ENGINE))
        runWarms()
        assertEquals(0, net.count(media))
        assertEquals(Hls(master, media), prejoin.handOff(Hls(master, media)))
    }

    @Test
    fun `the tune stops every warm before a session is chosen`() {
        sessions.forChannel(null, "A", rotate = true)
        val master = readAhead("U", setOf("A", "U"))
        prejoin.neighbourReady(pluto("U"), master) { true }
        prejoin.tuning()
        runWarms()
        assertTrue(net.asked.isEmpty())
    }

    @Test
    fun `a miss, a separate audio track, a stale snapshot or PRE-JOIN off - mpv opens the playlist as before`() {
        val plain = Hls("https://fast.example/1/master.m3u8", mediaUrl = "https://fast.example/1/720p.m3u8")
        assertSame(plain, prejoin.handOff(plain))
        val audio = plain.copy(audioUrl = "https://fast.example/1/en.m3u8")
        assertSame(audio, prejoin.handOff(audio))
        cache.put(PrejoinCache.Snapshot(plain.mediaUrl!!, FakeNet.live(1, 6), plain.mediaUrl!!, now, 0L, 6_000L,
            mapOf("https://fast.example/1/seg4.ts" to ByteArray(10))))
        now += 20_000
        assertSame("stale", plain, prejoin.handOff(plain))
        cache.put(PrejoinCache.Snapshot(plain.mediaUrl!!, FakeNet.live(1, 6), plain.mediaUrl!!, now, 0L, 6_000L,
            mapOf("https://fast.example/1/seg4.ts" to ByteArray(10))))
        enabled = false
        assertSame(plain, prejoin.handOff(plain))
        assertEquals("let go of all the same", 0, cache.size)
        assertNull(prejoin.handOff(null))
    }

    @Test
    fun `a FAST neighbour is warmed by its master's pick, and nothing under Media3`() {
        val fast = Channel(number = 7, name = "F", kind = "live", block = "Movies")
        val fastPicks = mutableMapOf<String, VariantCache.Choice>()
        val p = Prejoin({ master, isFast -> if (isFast) fastPicks[master] else null }, { "https://fast.example/7/master.m3u8" },
            warmer, cache, proxy, { true })
        p.neighbourReady(fast, null) { true }
        assertTrue("no pick - Media3, or a read that failed - nothing to warm", queued.isEmpty())
        fastPicks["https://fast.example/7/master.m3u8"] = VariantCache.Choice("https://fast.example/7/720p.m3u8", null)
        p.neighbourReady(fast, null) { true }
        assertEquals(1, queued.size)
        p.neighbourReady(fast, null) { false }
        assertEquals("not once the picture has moved on", 1, queued.size)
    }

    companion object {
        private const val ENGINE = "mpv@1080"
    }
}
