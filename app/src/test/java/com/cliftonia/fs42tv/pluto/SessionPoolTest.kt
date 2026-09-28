package com.cliftonia.fs42tv.pluto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One channel per Pluto session, and the rotation that lets the neighbours' masters be read ahead
 * without ending the programme on screen - see [SessionPool]. Measured against live Pluto: a
 * second channel's master read on a session ends the first channel's media playlist at once.
 */
class SessionPoolTest {

    private class Fixture(var sameFromServer: Boolean = false) {
        var now = 0L
        private var serial = 0
        var serverUp = true
        val cache: VariantCache
        val sessions: PlutoSessions

        fun mint(region: String) = PlutoSession("https://s.pluto.tv", "sid=${serial}", "jwt${serial++}", region,
            now + 3 * 3_600_000L)

        private var mainSession: PlutoSession? = null

        init {
            sessions = PlutoSessions(
                boot = { mint("AU") },
                server = { region ->
                    if (!serverUp) throw PlutoBoot.Unreachable(java.net.SocketTimeoutException("connect"))
                    mint(region.uppercase()).also { mainSession = it }
                },
                nowMillis = { now },
                poolSize = SessionPool.SIZE,
                slotServer = { region, _, _ -> if (sameFromServer) mainSession else mint(region.uppercase()) },
            )
            cache = VariantCache({ now }, sessions::claimOf)
        }

        fun tune(id: String, region: String? = null, rotate: Boolean = true) =
            sessions.forChannel(region, id, rotate)!!.session

        /** Read [id]'s master ahead - a lease, released at once, as a finished read is. */
        fun ahead(id: String, keep: Set<String>, region: String? = null): PlutoSession? =
            sessions.lease(region, id, keep)?.also { it.release() }?.session

        fun remember(session: PlutoSession, id: String) {
            val url = session.masterUrl(id)
            cache.put(url, ENGINE, VariantCache.Choice("$url#720p", null), cache.claim(url))
        }

        fun remembered(session: PlutoSession, id: String) = cache.get(session.masterUrl(id), ENGINE)
    }

    @Test
    fun `reading X then Y on one session retires X's pick, even when X is read again`() {
        val f = Fixture()
        val s = f.tune("X", rotate = false)
        f.remember(s, "X")
        assertNotNull(f.remembered(s, "X"))
        assertSame(s, f.tune("Y", rotate = false))
        assertNull("Y's master ended X's playlist", f.remembered(s, "X"))
        assertSame(s, f.tune("X", rotate = false))
        assertNull("read before Y, so still dead", f.remembered(s, "X"))
    }

    @Test
    fun `the channel on screen and its neighbours are each on a session of their own`() {
        val f = Fixture()
        val keep = setOf("A", "U", "D")
        val a = f.tune("A")
        val u = f.ahead("U", keep)!!
        val d = f.ahead("D", keep)!!
        assertEquals(3, setOf(a.jwt, u.jwt, d.jwt).size)
        assertNull("three slots, all spoken for", f.ahead("Z", keep))
    }

    @Test
    fun `a surf onto a neighbour plays on its session, and the channel left stays read`() {
        val f = Fixture()
        val a = f.tune("A")
        val u = f.ahead("U", setOf("A", "U", "D"))!!
        val d = f.ahead("D", setOf("A", "U", "D"))!!
        f.remember(u, "U")
        f.remember(a, "A")
        assertSame(u, f.tune("U"))
        assertNotNull("the pick read ahead is the tune's", f.remembered(u, "U"))
        assertNotNull("A is the new channel's neighbour the other way", f.remembered(a, "A"))
        // The remaining slot - D's - is re-pointed at the new neighbour; A's is kept.
        f.now += SessionPool.QUIET_MILLIS
        assertSame(d, f.ahead("U+1", setOf("U", "U+1", "A")))
        assertNotNull(f.remembered(a, "A"))
        // Surf back down: A's own session, its pick still good.
        assertSame(a, f.tune("A"))
        assertNotNull(f.remembered(a, "A"))
    }

    @Test
    fun `a read ahead never takes the session on screen, nor one just left`() {
        val f = Fixture()
        f.tune("A")
        val u = f.ahead("U", setOf("A", "U"))!!
        f.ahead("D", setOf("A", "U", "D"))
        assertSame(u, f.tune("U"))
        // A's slot is quiet - the break poller may still be reading A there - and U is on screen.
        assertNotNull(f.ahead("X", setOf("U", "X")))
        assertNull(f.ahead("Y", setOf("U", "X", "Y")))
        f.now += SessionPool.QUIET_MILLIS
        assertNotNull(f.ahead("Y", setOf("U", "X", "Y")))
    }

    @Test
    fun `a tune never takes a slot whose read ahead is still in flight for another channel`() {
        val f = Fixture()
        f.tune("A")
        val lease = f.sessions.lease(null, "U", setOf("A", "U"))!!
        f.ahead("D", setOf("A", "U", "D"))
        val x = f.tune("X")
        assertNotEquals(lease.session.jwt, x.jwt)
        lease.release()
    }

    @Test
    fun `without rotation every tune is on the dial's one session, as before`() {
        val f = Fixture()
        val a = f.tune("A", rotate = false)
        f.ahead("U", setOf("A", "U"))
        assertSame(a, f.tune("U", rotate = false))
        assertSame(a, f.tune("X", rotate = false))
    }

    @Test
    fun `a server that hands every slot the same session stops the rotation`() {
        val f = Fixture(sameFromServer = true)
        val a = f.tune("A", region = "us")
        assertNull(f.ahead("U", setOf("A", "U"), region = "us"))
        assertSame(a, f.tune("U", region = "us"))
        assertNull(f.sessions.lease("us", "V", setOf("V")))
        // The local pool is unaffected: every boot is a new session.
        assertNotNull(f.ahead("L", setOf("L")))
    }

    @Test
    fun `a neighbour is read on a session from its own region`() {
        val f = Fixture()
        val a = f.tune("A", region = "us")
        val uk = f.ahead("B", setOf("A", "B", "C"), region = "uk")!!
        val us = f.ahead("C", setOf("A", "B", "C"), region = "us")!!
        assertEquals("UK", uk.region)
        assertEquals("US", us.region)
        assertNotEquals(a.jwt, us.jwt)
    }

    @Test
    fun `no read ahead for a region while the home server is away`() {
        val f = Fixture()
        f.serverUp = false
        f.tune("A", region = "us")
        assertNull(f.ahead("U", setOf("A", "U"), region = "us"))
    }

    @Test
    fun `a failed session takes its claim with it, and the re-tune is on another`() {
        val f = Fixture()
        val a = f.tune("A")
        f.ahead("U", setOf("A", "U"))
        f.remember(a, "A")
        f.sessions.invalidate(a)
        assertNull(f.remembered(a, "A"))
        assertNull(f.sessions.claimOf(a.masterUrl("A")))
        assertNotEquals(a.jwt, f.tune("A").jwt)
    }

    @Test
    fun `a pick is not kept when another channel was read on its session meanwhile`() {
        val f = Fixture()
        val s = f.tune("X", rotate = false)
        val url = s.masterUrl("X")
        val claim = f.cache.claim(url)
        f.tune("Y", rotate = false)
        f.cache.put(url, ENGINE, VariantCache.Choice("v", null), claim)
        assertEquals(0, f.cache.size)
        assertTrue(f.cache.claim(url) == null)
    }

    private companion object {
        const val ENGINE = "mpv@1080"
    }
}
