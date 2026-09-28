package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.pluto.BreakPoller
import com.cliftonia.fs42tv.pluto.MasterPicker
import com.cliftonia.fs42tv.pluto.PlutoGuide
import com.cliftonia.fs42tv.pluto.PlutoRoute
import com.cliftonia.fs42tv.pluto.PlutoSession
import com.cliftonia.fs42tv.pluto.PlutoSessions
import com.cliftonia.fs42tv.pluto.SessionPool
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
import org.junit.Assert.assertTrue
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
            poolSize = SessionPool.SIZE,
        )
        val route = PlutoRoute(sessions, direct = { direct }, nowMillis = { now }, report = {}, rotate = { true })
        val cache = VariantCache({ now }, sessions::claimOf)
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

    private val up = channel.copy(number = 12, name = "Pluto up", pluto = PlutoRef("def"),
        streams = listOf(Stream(url = "https://jmp2.uk/plu-def.m3u8", duration = 600)))

    private val upTuned = Tuned(up, 0, up.streams[0], Hls(up.streams[0].url), 0.0)

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
    fun `a surf onto a neighbour read ahead plays with no read, on a session of its own`() {
        val f = Fixture(muxed)
        val onScreen = f.extras.livePlayable(tuned) { true } as Hls
        val lease = f.route.readAhead(up, setOf("abc", "def"))!!
        assertTrue(f.picker.prefetch(lease.masterUrl))
        lease.release()
        assertNotEquals("never the screen's session", onScreen.url.substringAfter("jwt="), lease.session.jwt)
        val surfed = f.extras.livePlayable(upTuned) { true } as Hls
        assertEquals(2, f.reads.size)
        assertEquals(lease.masterUrl, surfed.url)
        assertNotNull(surfed.mediaUrl)
        // And back down: the channel left is still read on its own session.
        assertEquals(onScreen, f.extras.livePlayable(tuned) { true })
        assertEquals(2, f.reads.size)
    }

    @Test
    fun `nothing is read ahead for a channel sitting out on the legacy route`() {
        val f = Fixture(muxed)
        f.direct = false
        assertNull(f.route.readAhead(up, setOf("def")))
        assertNull(f.route.readAhead(up.copy(pluto = null), emptySet()))
    }
}
