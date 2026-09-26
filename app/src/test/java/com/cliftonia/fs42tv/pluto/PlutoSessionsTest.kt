package com.cliftonia.fs42tv.pluto

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which session a Pluto stream plays on, how long each is kept, and when a new one is asked for.
 * Every fetch is a counted fake and the clock is a variable, so three hours pass in a line.
 */
class PlutoSessionsTest {

    private val hour = 3_600_000L
    private val minute = 60_000L

    private class Fixture {
        var now = 0L
        var boots = 0
        val serverAsks = mutableListOf<String>()
        var serverAnswers: (String) -> PlutoSession? = { region -> session("R-$region", now + 4 * 3_600_000L) }
        var bootAnswer: () -> PlutoSession? = { session("AU", now + PlutoBoot.LOCAL_LIFETIME_MILLIS) }
        val sessions = PlutoSessions(
            boot = { boots++; bootAnswer() },
            server = { region -> serverAsks += region; serverAnswers(region) },
            nowMillis = { now },
        )

        companion object {
            private var serial = 0
            fun session(region: String, expires: Long) =
                PlutoSession("https://s.pluto.tv", "sid=${serial++}", "jwt", region, expires)
        }
    }

    @Test
    fun `a local session is reused until half an hour before it expires`() {
        val f = Fixture()
        val first = f.sessions.forDial(region = null)!!.session
        f.now = 2 * hour + 29 * minute
        assertSame(first, f.sessions.forDial(null)!!.session)
        assertEquals(1, f.boots)
        f.now = 2 * hour + 31 * minute
        assertNotSame("inside the last half hour a fresh one is fetched", first, f.sessions.forDial(null)!!.session)
        assertEquals(2, f.boots)
    }

    @Test
    fun `a channel with a region plays on the home server's session for that region`() {
        val f = Fixture()
        val uk = f.sessions.forDial("uk")!!
        assertTrue(uk.fromServer)
        assertEquals("R-uk", uk.session.region)
        assertSame(uk.session, f.sessions.forDial("uk")!!.session)
        assertEquals("R-us", f.sessions.forDial("us")!!.session.region)
        assertEquals("one ask per region, then the cache", listOf("uk", "us"), f.serverAsks)
        assertEquals("no local boot is needed while the server answers", 0, f.boots)
    }

    @Test
    fun `a region session is kept until half an hour before the server's own expiry`() {
        val f = Fixture()
        f.serverAnswers = { Fixture.session("GB", f.now + hour) }
        f.sessions.forDial("uk")
        f.now = 29 * minute
        f.sessions.forDial("uk")
        assertEquals(1, f.serverAsks.size)
        f.now = 31 * minute
        f.sessions.forDial("uk")
        assertEquals(2, f.serverAsks.size)
    }

    @Test
    fun `no server answer falls back to the local session, and the miss is remembered`() {
        // The car: neither address exists. Paying two connect timeouts on every Pluto tune would
        // make the dial slower than it was before any of this.
        val f = Fixture()
        f.serverAnswers = { null }
        val choice = f.sessions.forDial("uk")!!
        assertFalse(choice.fromServer)
        assertEquals("AU", choice.session.region)
        f.now = 9 * minute
        f.sessions.forDial("uk")
        assertEquals(listOf("uk"), f.serverAsks)
        f.now = 11 * minute
        f.sessions.forDial("uk")
        assertEquals("asked again once the miss has aged", listOf("uk", "uk"), f.serverAsks)
    }

    @Test
    fun `a server that throws is a miss, not a crash`() {
        val f = Fixture()
        f.serverAnswers = { throw java.io.IOException("refused") }
        assertEquals("AU", f.sessions.forDial("us")!!.session.region)
    }

    @Test
    fun `a channel without a region never asks the server`() {
        val f = Fixture()
        f.sessions.forDial(null)
        assertTrue(f.serverAsks.isEmpty())
    }

    @Test
    fun `a player beside the dial gets its own session, never the dial's`() {
        // Pluto allows one stream per session: the guide music on the dial's token would end
        // the programme the viewer is watching.
        val f = Fixture()
        val dial = f.sessions.forDial(null)!!.session
        val beside = f.sessions.beside()!!
        assertNotSame(dial, beside)
        assertEquals(2, f.boots)
        assertSame(beside, f.sessions.beside())
        assertSame(dial, f.sessions.forDial(null)!!.session)
        assertEquals(2, f.boots)
    }

    @Test
    fun `the player beside the dial never takes the home server's session`() {
        // The server hands one session per television per region - the dial's.
        val f = Fixture()
        f.sessions.forDial("uk")
        f.sessions.beside()
        assertEquals(listOf("uk"), f.serverAsks)
    }

    @Test
    fun `an invalidated session is rebuilt on the next ask`() {
        val f = Fixture()
        val first = f.sessions.forDial(null)!!.session
        f.sessions.invalidate(first)
        val second = f.sessions.forDial(null)!!.session
        assertNotSame(first, second)
        f.sessions.invalidate(first)
        assertSame("invalidating a session already replaced changes nothing", second,
            f.sessions.forDial(null)!!.session)
    }

    @Test
    fun `invalidating a region session asks the server again at once`() {
        val f = Fixture()
        val uk = f.sessions.forDial("uk")!!.session
        f.sessions.invalidate(uk)
        f.sessions.forDial("uk")
        assertEquals(listOf("uk", "uk"), f.serverAsks)
    }

    @Test
    fun `a failed refresh keeps a session that has not actually expired`() {
        val f = Fixture()
        val first = f.sessions.forDial(null)!!.session
        f.bootAnswer = { null }
        f.now = 2 * hour + 45 * minute
        assertSame(first, f.sessions.forDial(null)!!.session)
        f.now = 3 * hour + 1
        assertNull("past its real expiry it is not offered", f.sessions.forDial(null))
    }

    @Test
    fun `a failed boot is retried after a short pause, not on every tune`() {
        val f = Fixture()
        f.bootAnswer = { throw java.io.IOException("no network") }
        assertNull(f.sessions.forDial(null))
        f.sessions.forDial(null)
        assertEquals(1, f.boots)
        f.now = 31_000
        f.sessions.forDial(null)
        assertEquals(2, f.boots)
    }

    @Test
    fun `two callers at once share one fetch`() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.bootAnswer = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            Fixture.session("AU", PlutoBoot.LOCAL_LIFETIME_MILLIS)
        }
        var fromA: PlutoSession? = null
        val a = Thread { fromA = f.sessions.forDial(null)?.session }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        var fromB: PlutoSession? = null
        val b = Thread { fromB = f.sessions.forDial(null)?.session }.apply { start() }
        Thread.sleep(50)
        release.countDown()
        a.join(5_000)
        b.join(5_000)
        assertEquals(1, f.boots)
        assertSame(fromA, fromB)
    }
}
