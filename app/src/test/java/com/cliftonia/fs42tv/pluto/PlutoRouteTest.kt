package com.cliftonia.fs42tv.pluto

import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.Playable
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.PlutoRef
import com.cliftonia.fs42tv.sync.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a Pluto channel is played through: Pluto's own route on the right session, or the
 * published jmp2 url exactly as before - and how a failing stream walks from one to the other.
 */
class PlutoRouteTest {

    private val hallmark = "628e685ba3811100070551a8"
    private val homeful = "66df8aa7abec540008ca8cb6"

    private class Fixture {
        var now = 0L
        var direct = true
        var boots = 0
        var serverUp = true
        var bootUp = true
        /** Blocks [PlutoRoute] asked to run later, with their delays; [runLater] runs them. */
        val pending = ArrayDeque<Pair<Long, () -> Unit>>()
        val ready = mutableListOf<Int>()
        val serverAsks = mutableListOf<String>()
        val reports = mutableListOf<String>()
        private var serial = 0

        private fun session(region: String) = PlutoSession(
            "https://s.pluto.tv", "sid=${serial++}", "jwt", region, now + 3 * 3_600_000L)

        val sessions = PlutoSessions(
            boot = { boots++; if (bootUp) session("AU") else null },
            server = { region -> serverAsks += region; if (serverUp) session(region.uppercase()) else null },
            nowMillis = { now },
        )
        val route = PlutoRoute(sessions, direct = { direct }, nowMillis = { now }, report = { reports += it },
            later = { delay, block -> pending.addLast(delay to block) },
        ).also { it.onSessionReady = { channel -> ready += channel.number } }

        fun runLater() {
            val (delay, block) = pending.removeFirst()
            now += delay
            block()
        }
    }

    private fun channel(id: String, region: String? = null, published: Boolean = true) = Channel(
        number = 11, name = "Pluto $id", kind = "live",
        streams = listOf(Stream(url = "https://jmp2.uk/plu-$id.m3u8", duration = 600)),
        pluto = if (published) PlutoRef(id, region) else null,
    )

    private fun legacy(channel: Channel): Playable = Hls(channel.streams.single().url)

    private fun url(p: Playable) = (p as Hls).url

    @Test
    fun `a channel with a region plays Pluto's own route on that region's session`() {
        val f = Fixture()
        val ch = channel(hallmark, "us")
        val played = url(f.route.forDial(ch, legacy(ch)))
        assertTrue(played, played.startsWith("https://s.pluto.tv/v2/stitch/hls/channel/$hallmark/master.m3u8?"))
        assertEquals(listOf("us"), f.serverAsks)
        assertEquals("PLUTO US - HOME SERVER", f.reports.last())
    }

    @Test
    fun `a published channel without a region plays on this TV's session`() {
        val f = Fixture()
        val ch = channel(homeful)
        assertTrue(url(f.route.forDial(ch, legacy(ch))).contains("/channel/$homeful/master.m3u8"))
        assertTrue("no region, no server", f.serverAsks.isEmpty())
        assertEquals("PLUTO AU - THIS TV", f.reports.last())
    }

    @Test
    fun `a jmp2 channel without the published field keeps its url exactly as before`() {
        // Euronews and CBS News on the YouTube dial are Pluto streams by their urls, but nobody
        // has watched them on this route; a silent bumper loop there would look healthy.
        val f = Fixture()
        val ch = channel(homeful, published = false)
        val before = legacy(ch)
        assertSame(before, f.route.forDial(ch, before))
        assertSame(before, f.route.forBeside(ch, before))
        assertEquals(0, f.boots)
        assertTrue(f.reports.isEmpty())
    }

    @Test
    fun `a server that does not answer leaves the channel on this TV's session`() {
        val f = Fixture()
        f.serverUp = false
        val ch = channel(hallmark, "uk")
        assertTrue(url(f.route.forDial(ch, legacy(ch))).contains("/channel/$hallmark/"))
        assertEquals("PLUTO AU - THIS TV", f.reports.last())
    }

    @Test
    fun `LEGACY plays the published url untouched and asks nobody`() {
        val f = Fixture()
        f.direct = false
        val ch = channel(hallmark, "us")
        val before = legacy(ch)
        assertSame(before, f.route.forDial(ch, before))
        assertSame(before, f.route.forBeside(ch, before))
        assertEquals(0, f.boots)
        assertEquals("PLUTO LEGACY - SETTING", f.reports.last())
        assertTrue(f.serverAsks.isEmpty())
    }

    @Test
    fun `a live channel that is not Pluto is left alone`() {
        val f = Fixture()
        val news = Channel(1, "News", "live", streams = listOf(Stream(url = "https://news.example/live.m3u8", duration = 600)))
        val before = legacy(news)
        assertSame(before, f.route.forDial(news, before))
        assertEquals(0, f.boots)
    }

    @Test
    fun `no session at all falls back to the published url`() {
        val sessions = PlutoSessions(boot = { null }, server = { null }, nowMillis = { 0L })
        val reports = mutableListOf<String>()
        val route = PlutoRoute(sessions, direct = { true }, nowMillis = { 0L }, report = { reports += it })
        val ch = channel(hallmark, "us")
        val before = legacy(ch)
        assertSame(before, route.forDial(ch, before))
        assertEquals("PLUTO LEGACY - NO SESSION", reports.last())
    }

    @Test
    fun `the player beside the dial is on a session of its own`() {
        val f = Fixture()
        val ch = channel(hallmark, "us")
        val dial = url(f.route.forDial(ch, legacy(ch)))
        val beside = url(f.route.forBeside(ch, legacy(ch)))
        assertNotEquals("one stream per session: never the same token", sid(dial), sid(beside))
        assertEquals("the beside player never takes the server's one session", listOf("us"), f.serverAsks)
        val reports = f.reports.size
        f.route.forBeside(ch, legacy(ch))
        assertEquals("the diagnostics row is about the dial", reports, f.reports.size)
    }

    private fun sid(url: String) = url.substringAfter("sid=").substringBefore('&')

    @Test
    fun `a failed stream rebuilds its session once`() {
        val f = Fixture()
        val ch = channel(homeful)
        val first = f.route.forDial(ch, legacy(ch))
        f.route.playbackFailed(first)
        val second = f.route.forDial(ch, legacy(ch))
        assertEquals(2, f.boots)
        assertNotEquals(sid(url(first)), sid(url(second)))
    }

    @Test
    fun `a failure on the playlist mpv was given instead of the master is still the session's`() {
        // MasterPicker hands mpv one media playlist out of the master; the master url stays the
        // playable's identity, so the dial's onAir still traces an error to this session.
        val f = Fixture()
        val ch = channel(homeful)
        val first = f.route.forDial(ch, legacy(ch)) as Hls
        f.route.playbackFailed(first.copy(mediaUrl = "https://s.pluto.tv/720p.m3u8", audioUrl = "https://s.pluto.tv/a.m3u8"))
        val second = f.route.forDial(ch, legacy(ch))
        assertEquals(2, f.boots)
        assertNotEquals(sid(url(first)), sid(url(second)))
    }

    @Test
    fun `failing again on the rebuilt session puts that channel on the legacy url for a while`() {
        val f = Fixture()
        val ch = channel(homeful)
        f.route.playbackFailed(f.route.forDial(ch, legacy(ch)))
        f.route.playbackFailed(f.route.forDial(ch, legacy(ch)))
        val before = legacy(ch)
        assertSame(before, f.route.forDial(ch, before))
        assertEquals("PLUTO LEGACY - DIRECT FAILED", f.reports.last())
        assertEquals("no endless rebuilding", 2, f.boots)

        val other = channel(hallmark)
        assertTrue("other channels keep Pluto's route", url(f.route.forDial(other, legacy(other))).contains(hallmark))

        f.now += PlutoRoute.FALLBACK_MILLIS + 1
        assertTrue("and it is tried again later", url(f.route.forDial(ch, legacy(ch))).contains("/channel/$homeful/"))
    }

    @Test
    fun `a failure long after the last rebuild counts as a first failure again`() {
        val f = Fixture()
        val ch = channel(homeful)
        f.route.playbackFailed(f.route.forDial(ch, legacy(ch)))
        f.now += PlutoRoute.REBUILD_WINDOW_MILLIS + 1
        f.route.playbackFailed(f.route.forDial(ch, legacy(ch)))
        assertTrue(url(f.route.forDial(ch, legacy(ch))).contains("/channel/$homeful/"))
    }

    @Test
    fun `a failure of anything but the last direct stream is ignored`() {
        val f = Fixture()
        val ch = channel(homeful)
        val played = f.route.forDial(ch, legacy(ch))
        f.route.playbackFailed(legacy(ch))
        f.route.playbackFailed(null)
        assertEquals(played, f.route.forDial(ch, legacy(ch)))
        assertEquals(1, f.boots)
    }

    @Test
    fun `a channel that fell back for want of a session is re-tuned once one can be had`() {
        // A television just woken has no network for its first seconds. The legacy url then
        // plays Pluto's bumper without any error, so nothing else would ever re-tune it.
        val f = Fixture()
        f.bootUp = false
        val ch = channel(homeful)
        assertEquals("no session: the legacy url", legacy(ch), f.route.forDial(ch, legacy(ch)))
        f.runLater()
        assertTrue("still no network: nothing to say yet", f.ready.isEmpty())
        f.bootUp = true
        f.runLater()
        assertEquals(listOf(11), f.ready)
        assertTrue("said once, and the looking stops", f.pending.isEmpty())
        assertTrue(url(f.route.forDial(ch, legacy(ch))).contains("/channel/$homeful/"))
    }

    @Test
    fun `the looking stops after a while, and when the channel tunes direct anyway`() {
        val f = Fixture()
        f.bootUp = false
        val ch = channel(homeful)
        f.route.forDial(ch, legacy(ch))
        repeat(PlutoRoute.SESSION_CHECKS) { f.runLater() }
        assertTrue(f.pending.isEmpty())
        assertTrue(f.ready.isEmpty())

        f.route.forDial(ch, legacy(ch))
        f.now += PlutoRoute.SESSION_CHECK_MILLIS
        f.bootUp = true
        f.route.forDial(ch, legacy(ch))
        f.runLater()
        assertTrue("a direct tune in the meantime ends the wait", f.ready.isEmpty())
    }
}
