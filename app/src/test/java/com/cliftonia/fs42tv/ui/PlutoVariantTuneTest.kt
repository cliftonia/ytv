package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.pluto.BreakPoller
import com.cliftonia.fs42tv.pluto.MasterPicker
import com.cliftonia.fs42tv.pluto.PlutoGuide
import com.cliftonia.fs42tv.pluto.PlutoRoute
import com.cliftonia.fs42tv.pluto.PlutoSession
import com.cliftonia.fs42tv.pluto.PlutoSessions
import com.cliftonia.fs42tv.pluto.VariantCache
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.schedule.Timetable
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.PlutoRef
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.tune.Tuned
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The remembered master pick at the tune itself - [ScreenExtras.livePlayable] over a real route
 * and picker: a hit on the direct route skips the read, the legacy route never uses one, a
 * superseded tune reads and remembers nothing, and a failure or a rebuilt session misses.
 */
class PlutoVariantTuneTest {

    private val muxed = "#EXTM3U\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=3321000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f,mp4a.40.2\"\n" +
        "720p.m3u8\n"

    private class Fixture(val muxed: String) {
        var now = 0L
        var direct = true
        private var serial = 0
        val reads = mutableListOf<String>()
        val features = Features(read = { _, default -> default }, write = { _, _ -> })
        val sessions = PlutoSessions(
            boot = { PlutoSession("https://s.pluto.tv", "sid=${serial++}", "jwt${serial}", "AU", now + 3 * 3_600_000L) },
            server = { null },
            nowMillis = { now },
        )
        val route = PlutoRoute(sessions, direct = { direct }, nowMillis = { now }, report = {})
        val cache = VariantCache({ now })
        val picker = MasterPicker(
            fetch = { url -> reads += url; now += 700; BreakPoller.Fetched(url, muxed) },
            mpvLadder = { listOf("hd") },
            elapsedMillis = { now },
            cache = cache,
        )
        val extras = ScreenExtras(ScreenExtras.Deps(
            features = features,
            plutoGuide = PlutoGuide(fetch = { "" }, executor = {}, nowMillis = { now }, enabled = { false }),
            runOnUi = { it() },
            halted = { false },
            nowMillis = { now },
            timetable = Timetable.PLAIN,
            plutoRoute = route,
            masterPicker = picker,
        ))
    }

    private val channel = Channel(
        number = 11, name = "Pluto", kind = "live",
        streams = listOf(Stream(url = "https://jmp2.uk/plu-abc.m3u8", duration = 600)),
        pluto = PlutoRef("abc"),
    )

    private val tuned = Tuned(channel, 0, channel.streams[0], Hls(channel.streams[0].url), 0.0)

    @Test
    fun `a surf back to a channel on the same session skips the master read`() {
        val f = Fixture(muxed)
        val first = f.extras.livePlayable(tuned) { true } as Hls
        val second = f.extras.livePlayable(tuned) { true } as Hls
        assertEquals(1, f.reads.size)
        assertEquals(first, second)
        assertNotNull(second.mediaUrl)
    }

    @Test
    fun `the legacy route is read every time, as before`() {
        val f = Fixture(muxed)
        f.direct = false
        f.extras.livePlayable(tuned) { true }
        f.extras.livePlayable(tuned) { true }
        assertEquals(listOf(channel.streams[0].url, channel.streams[0].url), f.reads)
        assertEquals(0, f.cache.size)
    }

    @Test
    fun `a superseded tune abandons before the read, and remembers nothing`() {
        val f = Fixture(muxed)
        assertNull(f.extras.livePlayable(tuned) { false })
        assertEquals(0, f.reads.size)
        assertEquals(0, f.cache.size)
    }

    @Test
    fun `a failure drops the pick, and the rebuilt session reads its own`() {
        val f = Fixture(muxed)
        val played = f.extras.livePlayable(tuned) { true } as Hls
        f.extras.plutoFailed(played)
        assertEquals(0, f.cache.size)
        val again = f.extras.livePlayable(tuned) { true } as Hls
        assertEquals(2, f.reads.size)
        // A new session, so a new master url: nothing from the old one could have answered.
        assertNotEquals(played.url, again.url)
    }

    @Test
    fun `no picture drops the pick too`() {
        val f = Fixture(muxed)
        val played = f.extras.livePlayable(tuned) { true } as Hls
        f.extras.plutoNoPicture(played)
        assertEquals(0, f.cache.size)
    }

    @Test
    fun `a stall drops the pick but does not judge the session`() {
        val f = Fixture(muxed)
        val played = f.extras.livePlayable(tuned) { true } as Hls
        f.extras.plutoFailed(played, session = false)
        val again = f.extras.livePlayable(tuned) { true } as Hls
        assertEquals(2, f.reads.size)
        assertEquals(played.url, again.url)
    }

    @Test
    fun `a read ahead names the master the tune will play, and never fetches a session for it`() {
        val f = Fixture(muxed)
        var boots = 0
        val sessions = PlutoSessions(
            boot = { boots++; PlutoSession("https://s.pluto.tv", "sid=1", "J", "AU", f.now + 3_600_000L) },
            server = { null },
            nowMillis = { f.now },
        )
        val route = PlutoRoute(sessions, direct = { true }, nowMillis = { f.now }, report = {})
        assertNull(route.masterAhead(channel))
        assertEquals(0, boots)
        val played = route.forDial(channel, tuned.playable) as Hls
        assertEquals(played.url, route.masterAhead(channel))
        assertNull(route.masterAhead(channel.copy(pluto = null)))
        assertEquals(1, boots)
    }
}
