package com.cliftonia.fs42tv.relay

import com.cliftonia.fs42tv.pluto.PlutoGuide
import com.cliftonia.fs42tv.pluto.PlutoRoute
import com.cliftonia.fs42tv.pluto.PlutoSessions
import com.cliftonia.fs42tv.pluto.SessionPool
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.resolver.Unplayable
import com.cliftonia.fs42tv.schedule.Timetable
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.DialContract
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.tune.Tuned
import com.cliftonia.fs42tv.ui.Features
import com.cliftonia.fs42tv.ui.ScreenExtras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A US-only feed plays through the home server's relay on 4247, found the way the ad mirror finds
 * it; with no server it is unavailable, and every stream without a route is left exactly alone.
 */
class FastRelayTest {

    private val upstream = "https://cdn.example/live/master.m3u8?token=a&b=c d"
    private val lan = "http://192.168.4.58:4243"

    private fun decodedU(url: String): String =
        java.net.URLDecoder.decode(url.substringAfter("?u="), "UTF-8")

    @Test
    fun `the relay url is the resolve server's host on the relay port, the upstream encoded whole`() {
        val url = FastRelay.url(lan, upstream)!!
        assertTrue(url, url.startsWith("http://192.168.4.58:4247/hls?u="))
        assertEquals(upstream, decodedU(url))
        assertTrue("nothing of the upstream's query may leak into the relay's own", '&' !in url)
    }

    @Test
    fun `the tailnet address works as the LAN one does`() {
        assertTrue(FastRelay.url("http://100.74.3.68:4243", upstream)!!
            .startsWith("http://100.74.3.68:4247/hls?u="))
    }

    @Test
    fun `no server, no relay url`() {
        assertNull(FastRelay.url(null, upstream))
        assertNull(FastRelay.url("not a url", upstream))
    }

    @Test
    fun `a us stream plays through the relay`() {
        val routed = FastRelay.route(Stream(url = upstream, duration = 600, route = "us"), Hls(upstream)) { lan }
        assertEquals(upstream, decodedU((routed as Hls).url))
    }

    @Test
    fun `a us stream with no server is unavailable, not played direct`() {
        val routed = FastRelay.route(Stream(url = upstream, duration = 600, route = "US"), Hls(upstream)) { null }
        assertTrue(routed is Unplayable)
    }

    @Test
    fun `an unknown route is unavailable`() {
        val routed = FastRelay.route(Stream(url = upstream, duration = 600, route = "mars"), Hls(upstream)) { lan }
        assertTrue(routed is Unplayable)
    }

    @Test
    fun `a direct stream is untouched and never asks for a server`() {
        val playable = Hls(upstream)
        val routed = FastRelay.route(Stream(url = upstream, duration = 600), playable) {
            error("a direct stream must not look for a server")
        }
        assertSame(playable, routed)
    }

    @Test
    fun `only live feeds are relayed`() {
        val file = Progressive("http://192.168.4.58:4244/film.mp4", null)
        assertSame(file, FastRelay.route(Stream(url = file.videoUrl, duration = 600, route = "us"), file) { lan })
    }

    @Test
    fun `the lineup's route parses, and a stream without one or with unknown keys still does`() {
        val dial = DialContract.parseDial("""
            {"generated": 1, "channels": [{"number": 5, "name": "FAST", "kind": "live", "streams": [
              {"url": "https://a.example/1.m3u8", "duration": 600, "route": "us", "future": {"x": 1}},
              {"url": "https://a.example/2.m3u8", "duration": 600}
            ]}]}
        """.trimIndent())
        val streams = dial.channels.single().streams
        assertEquals("us", streams[0].route)
        assertNull(streams[1].route)
    }

    @Test
    fun `the dial's live tune goes through the relay`() {
        val stream = Stream(url = upstream, duration = 600, route = "us")
        val channel = Channel(number = 5, name = "FAST", kind = "live", streams = listOf(stream))
        val tuned = Tuned(channel, 0, stream, Hls(upstream), 0.0)
        val relayed = extras { lan }.livePlayable(tuned) { true } as Hls
        assertEquals(upstream, decodedU(relayed.url))
        assertTrue(extras { null }.livePlayable(tuned) { true } is Unplayable)
        assertEquals(upstream, decodedU((extras { lan }.besideTuned(tuned).playable as Hls).url))
    }

    private fun extras(server: () -> String?): ScreenExtras {
        val sessions = PlutoSessions(boot = { null }, server = { null }, nowMillis = { 0L },
            poolSize = SessionPool.SIZE)
        return ScreenExtras(ScreenExtras.Deps(
            features = Features(read = { _, default -> default }, write = { _, _ -> }),
            plutoGuide = PlutoGuide(fetch = { "" }, executor = {}, nowMillis = { 0L }, enabled = { false }),
            runOnUi = { it() },
            halted = { false },
            nowMillis = { 0L },
            timetable = Timetable.PLAIN,
            plutoRoute = PlutoRoute(sessions, direct = { true }, nowMillis = { 0L }, report = {}, rotate = { true }),
            relayServer = server,
        ))
    }
}
