package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.ads.AdCatalog
import com.cliftonia.fs42tv.ads.AdReel
import com.cliftonia.fs42tv.pluto.BreakPoller
import com.cliftonia.fs42tv.pluto.BreakPollerTest
import com.cliftonia.fs42tv.relay.FastRelay
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.tune.Tuned
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A FAST channel's break, end to end through the same controller as Pluto's: 5s segments,
 * segment `i` stamped t0 + 5s*i and at the edge from clock 5s*(i+1), a window of five. Segments
 * 20..31 are the break - CUE-OUT:DURATION=60 above 20, CUE-OUT-CONT above the rest, CUE-IN above
 * 32 - so t0+100s..t0+160s, the very instants of PlutoBreakAdsTest's bumper: the maths on screen
 * is the same, only how the break is found differs.
 */
class FastBreakTest {

    private val t0 = Instant.parse("2026-09-28T04:00:00Z").toEpochMilli()
    private val upstream = "https://cdn.samsungtvplus.example/v1/master/abc/playlist.m3u8"
    private val relay = FastRelay.url("http://192.168.4.58:4243", upstream)!!

    private fun channel(route: String? = null, breaks: String = Channel.BREAKS_CUE) = Channel(
        number = 301, name = "Retro Crime", kind = "live",
        streams = listOf(Stream(url = upstream, duration = 600, route = route)),
        breaks = breaks,
    )

    private fun tuned(channel: Channel = channel(), url: String = upstream) =
        Tuned(channel, 0, channel.streams.first(), Hls(url), 0.0)

    private class Stage {
        val clock = BreakPollerTest.Clock()
        var mpv = true
        var adsOn = true
        var pdt = true
        val fetched = mutableListOf<String>()
        val plays = mutableListOf<Double>()
        val retunes = mutableListOf<Long>()
    }

    private fun Stage.window(): String {
        val newest = clock.now / 5_000 - 1
        val first = maxOf(0L, newest - 4)
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:5")
            appendLine("#EXT-X-MEDIA-SEQUENCE:$first")
            (first..newest).forEach { i ->
                when (i) {
                    20L -> appendLine("#EXT-X-CUE-OUT:DURATION=60")
                    in 21L..31L -> appendLine("#EXT-X-CUE-OUT-CONT:ElapsedTime=${(i - 20) * 5},Duration=60")
                    32L -> appendLine("#EXT-X-CUE-IN")
                }
                if (i == 20L || i == 32L) appendLine("#EXT-X-DISCONTINUITY")
                if (pdt) appendLine("#EXT-X-PROGRAM-DATE-TIME:${Instant.ofEpochMilli(t0 + i * 5_000)}")
                appendLine("#EXTINF:5.0,")
                appendLine(if (i in 20L..31L) "slate/$i.ts" else "show/$i.ts")
            }
        }
    }

    /** The master, from wherever it is asked for; its variant a relay link when relayed. */
    private fun Stage.answer(url: String): BreakPoller.Fetched {
        fetched += url
        val body = when {
            url == upstream -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow.m3u8\n"
            url == relay -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n" +
                FastRelay.url("http://192.168.4.58:4243", "https://cdn.samsungtvplus.example/v1/master/abc/low.m3u8") + "\n"
            else -> window()
        }
        return BreakPoller.Fetched(url, body)
    }

    private val catalog = AdCatalog(1, listOf(
        AdReel("aus-ads-1986", era = "80s", url = "https://archive.org/download/aus-ads-1986/r.mp4",
            duration = 1800.0, cuts = listOf(0.0, 31.0, 62.0)),
    ))

    private fun Stage.subject(): PlutoBreak {
        var subject: PlutoBreak? = null
        subject = PlutoBreak(PlutoBreak.Deps(
            enabled = { true },
            poller = { read ->
                BreakPoller(fetch = { answer(it) }, schedule = clock.schedule, read = read,
                    nowMillis = { clock.now }, wallMillis = { clock.now })
            },
            later = clock.schedule,
            wallMillis = { clock.now },
            elapsedMillis = { clock.now },
            exactInstant = { null },
            joinsThirdFromLast = { mpv },
            runOnUi = { it() },
            halted = { false },
            guideOpen = { false },
            overlayOpen = { false },
            stoppedNow = { false },
            playMusic = { },
            releaseMusic = { },
            nowTitle = { _, _ -> null },
            volumeChanged = { },
            picture = { },
            ads = { changed ->
                BreakAds(BreakAds.Deps(
                    enabled = { adsOn },
                    catalog = { catalog },
                    warm = { },
                    play = { _, at -> plays += at },
                    park = { },
                    later = clock.schedule,
                    changed = changed,
                ))
            },
            retune = { retunes += clock.now; subject!!.leave() },
        ))
        return subject
    }

    private fun Stage.until(t: Long) = clock.advance(t - clock.now)

    private fun Stage.tuneIn(subject: PlutoBreak, t: Tuned = tuned(), at: Long = 60_000) {
        until(at)
        subject.loading(t)
        until(at + 1_500)
        subject.playing(t)
    }

    @Test
    fun `tune, the break reaches the screen, the reel takes the player, and the return re-tunes`() {
        val stage = Stage()
        val subject = stage.subject()
        stage.tuneIn(subject)
        // As PlutoBreakAdsTest: mpv's first frame is segment 9 (t0+45s) at 61.5s; t0+100s at 116.5s.
        stage.until(116_499)
        assertTrue(stage.plays.isEmpty())
        assertFalse(subject.inBreak)
        stage.until(116_500)
        assertEquals(1, stage.plays.size)
        assertTrue("black while the reel loads", subject.showing && subject.state.value!!.blank)
        subject.ads!!.firstFrame()
        assertTrue(subject.inBreak && subject.adsOnPlayer && !subject.showing)
        // CUE-OUT's DURATION says t0+160s before the CUE-IN is even read; a load joins there once
        // the edge is 175s, proven by the read at 176s.
        stage.until(175_999)
        assertTrue(stage.retunes.isEmpty())
        stage.until(176_000)
        assertEquals(listOf(176_000L), stage.retunes)
        assertFalse(subject.inBreak)
        stage.until(400_000)
        assertEquals("once", 1, stage.retunes.size)
    }

    @Test
    fun `surfed in mid-break gets commercials, never the card`() {
        val stage = Stage()
        val subject = stage.subject()
        stage.tuneIn(subject, at = 130_000)
        stage.until(135_000)
        assertEquals(1, stage.plays.size)
        assertTrue(subject.inBreak && subject.state.value!!.blank)
    }

    @Test
    fun `a stream with no PDT is counted from the first read, and lands the same`() {
        val stage = Stage().apply { pdt = false }
        val subject = stage.subject()
        stage.tuneIn(subject)
        stage.until(116_499)
        assertTrue(stage.plays.isEmpty())
        stage.until(116_500)
        assertEquals(1, stage.plays.size)
        subject.ads!!.firstFrame()
        stage.until(176_000)
        assertEquals(listOf(176_000L), stage.retunes)
    }

    @Test
    fun `BREAK ADS off is the card over the break, and no tune`() {
        val stage = Stage().apply { adsOn = false }
        val subject = stage.subject()
        stage.tuneIn(subject)
        stage.until(116_500)
        assertTrue(subject.showing && subject.muting)
        assertFalse(subject.state.value!!.blank)
        assertTrue(stage.plays.isEmpty())
        stage.until(176_500)
        assertFalse(subject.showing)
        assertTrue(stage.retunes.isEmpty())
    }

    @Test
    fun `a relayed channel is polled through the relay it plays from, never the upstream`() {
        val stage = Stage()
        val subject = stage.subject()
        val relayed = channel(route = FastRelay.US)
        stage.tuneIn(subject, tuned(relayed, relay))
        stage.until(116_500)
        assertEquals(1, stage.plays.size)
        assertTrue(stage.fetched.isNotEmpty())
        assertTrue(stage.fetched.toString(), stage.fetched.all { it.startsWith("http://192.168.4.58:4247/hls?u=") })
        assertTrue(stage.fetched.none { it.startsWith("https://cdn.samsungtvplus.example") })
    }

    @Test
    fun `a live channel the lineup does not mark is never polled, whatever its cues say`() {
        // A FAST channel left unmarked (a music one), an unknown mode, and a YouTube-dial live
        // feed as the lineup publishes it today - ABC's own cues are its local ad insertion.
        val abc = Channel(101, "ABC TV QLD", "live", streams = listOf(Stream(url = upstream, duration = 600)))
        listOf(channel(breaks = ""), channel(breaks = "bumper"), abc).forEach { unmarked ->
            val stage = Stage()
            val subject = stage.subject()
            stage.tuneIn(subject, tuned(unmarked))
            stage.until(200_000)
            assertTrue(unmarked.name, stage.fetched.isEmpty())
            assertFalse(subject.inBreak)
            assertNull(subject.state.value)
            assertTrue(stage.plays.isEmpty())
            // Nor does a return from the home screen re-tune it for a break's sake.
            subject.appStopped()
            assertFalse(subject.retuneOnResume(tuned(unmarked)))
        }
    }

    @Test
    fun `a Pluto feed off the Pluto dial, and a clip channel, are not FAST breaks even marked`() {
        val jmp2 = Channel(301, "CBS News", "live", breaks = Channel.BREAKS_CUE,
            streams = listOf(Stream(url = "https://jmp2.uk/plu-6350fdd266e9ea0007bedec5.m3u8", duration = 600)))
        assertNull(BreakChannels.of(Tuned(jmp2, 0, jmp2.streams.first(), Hls(jmp2.streams.first().url), 0.0)))
        val clips = channel().copy(kind = "youtube")
        assertNull(BreakChannels.of(Tuned(clips, 0, clips.streams.first(), Hls(upstream), 0.0)))
        assertEquals(BreakChannels.FAST, BreakChannels.of(tuned()))
    }
}
