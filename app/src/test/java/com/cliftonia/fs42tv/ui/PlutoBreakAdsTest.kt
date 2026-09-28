package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.ads.AdCatalog
import com.cliftonia.fs42tv.ads.AdReel
import com.cliftonia.fs42tv.pluto.BreakPoller
import com.cliftonia.fs42tv.pluto.BreakPollerTest
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.PlutoRef
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.tune.Tuned
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A break spent on archive commercials, against the simulated channel of [PlutoBreakTimingTest]:
 * 5s segments, segment `i` stamped t0 + 5s*i and at the edge from clock 5s*(i+1), a window of
 * five, a PDT where programme and bumper meet. Segments 20..31 are the bumper: t0+100s..t0+160s.
 *
 * Tuned at 60s under mpv with a first frame at 61.5s, the break reaches the screen at 116.5s
 * (see there) - which is when the reel must take the player.
 */
class PlutoBreakAdsTest {

    private val t0 = Instant.parse("2026-09-26T04:00:00Z").toEpochMilli()
    private val master = "https://stitcher.example/master.m3u8"
    private val masterBody = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nv.m3u8\n"

    private val channel = Channel(
        number = 7, name = "Flicks of Fury", kind = "live",
        streams = listOf(Stream(url = "https://jmp2.uk/plu-5f0000000000000000000000.m3u8", duration = 600)),
        pluto = PlutoRef("5f0000000000000000000000"),
    )
    private val tuned = Tuned(channel, 0, channel.streams.first(), Hls(master), 0.0)

    private class Stage(val bumper: (Long) -> Boolean) {
        val clock = BreakPollerTest.Clock()
        var mpv = true
        var exact: () -> Long? = { null }
        var adsOn = true
        var overlay = false
        var failFetches = false
        val plays = mutableListOf<Double>()
        val retunes = mutableListOf<Long>()
        var parks = 0
        var musicPlays = 0
    }

    private fun Stage.window(): String {
        if (failFetches) throw java.io.IOException("offline")
        val newest = clock.now / 5_000 - 1
        val first = maxOf(0L, newest - 4)
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:5")
            appendLine("#EXT-X-MEDIA-SEQUENCE:$first")
            (first..newest).forEach { i ->
                if (i > 0 && bumper(i) != bumper(i - 1)) {
                    appendLine("#EXT-X-DISCONTINUITY")
                    appendLine("#EXT-X-PROGRAM-DATE-TIME:${Instant.ofEpochMilli(t0 + i * 5_000)}")
                }
                appendLine("#EXTINF:5.0,")
                appendLine(if (bumper(i)) "/clip/x_ptv_7424_ad_bumper_dots/720p/$i.ts" else "/clip/5f1_King_Kong/720p/$i.ts")
            }
        }
    }

    private val catalog = AdCatalog(1, listOf(
        AdReel("aus-ads-1983", era = "80s", url = "https://archive.org/download/aus-ads-1983/r.mp4",
            duration = 1800.0, cuts = listOf(0.0, 31.0, 62.0)),
    ))

    private fun Stage.subject(): PlutoBreak {
        var subject: PlutoBreak? = null
        subject = PlutoBreak(PlutoBreak.Deps(
            enabled = { true },
            poller = { read ->
                BreakPoller(
                    fetch = { url -> BreakPoller.Fetched(url, if (url == master) masterBody else window()) },
                    schedule = clock.schedule,
                    read = read,
                    nowMillis = { clock.now },
                    wallMillis = { clock.now },
                )
            },
            later = clock.schedule,
            wallMillis = { clock.now },
            elapsedMillis = { clock.now },
            exactInstant = { exact() },
            joinsThirdFromLast = { mpv },
            runOnUi = { it() },
            halted = { false },
            guideOpen = { false },
            overlayOpen = { overlay },
            stoppedNow = { false },
            playMusic = { musicPlays++ },
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
                    park = { parks++ },
                    later = clock.schedule,
                    changed = changed,
                ))
            },
            // As the director does: the blank leaves the break, then the channel is tuned.
            retune = { retunes += clock.now; subject!!.leave() },
        ))
        return subject
    }

    private fun Stage.until(t: Long) = clock.advance(t - clock.now)

    private val minuteBreak: (Long) -> Boolean = { it in 20L..31L }

    private fun Stage.tunedIn(subject: PlutoBreak) {
        until(60_000)
        subject.loading(tuned)
        until(61_500)
        subject.playing(tuned)
    }

    @Test
    fun `mpv - the reel takes the player as the break reaches the screen, black until its frame`() {
        val stage = Stage(minuteBreak)
        val subject = stage.subject()
        stage.tunedIn(subject)
        stage.until(116_499)
        assertTrue(stage.plays.isEmpty())
        stage.until(116_500)
        assertEquals(1, stage.plays.size)
        assertTrue("black while the reel loads", subject.showing && subject.muting)
        assertTrue("no WE'LL BE RIGHT BACK before commercials", subject.state.value!!.blank)
        assertEquals("no card music for a reel on its way", 0, stage.musicPlays)
        subject.ads!!.firstFrame()
        assertFalse("the commercials, heard", subject.showing || subject.muting)
        assertTrue(subject.inBreak && subject.adsOnPlayer)
    }

    @Test
    fun `mpv - the return tunes the moment a fresh load joins at the programme's first frame`() {
        val stage = Stage(minuteBreak)
        val subject = stage.subject()
        stage.tunedIn(subject)
        stage.until(116_500)
        subject.ads!!.firstFrame()
        // Segment 34 reaches the edge at 175s: from then a load joins at segment 32, t0 + 160s -
        // the return. The read at 176s proves it (the one at 172s was a segment short).
        stage.until(175_999)
        assertTrue(stage.retunes.isEmpty())
        stage.until(176_000)
        assertEquals(listOf(176_000L), stage.retunes)
        assertFalse(subject.inBreak)
        // The reel is still the player's file until the tune's load replaces it.
        assertTrue(subject.adsOnPlayer)
        subject.loading(tuned)
        assertFalse(subject.adsOnPlayer)
        stage.until(400_000)
        assertEquals("once", 1, stage.retunes.size)
    }

    @Test
    fun `Media3 - its deeper join waits a segment more`() {
        val stage = Stage(minuteBreak)
        stage.mpv = false
        stage.exact = { t0 + stage.clock.now - 15_000 }
        val subject = stage.subject()
        stage.until(60_000)
        subject.loading(tuned)
        stage.until(61_000)
        subject.playing(tuned)
        stage.until(115_000)
        assertEquals(1, stage.plays.size)
        subject.ads!!.firstFrame()
        // Four targets (20s) back: a load joins at the return once the edge is 180s - read at 180s.
        stage.until(179_999)
        assertTrue(stage.retunes.isEmpty())
        stage.until(180_000)
        assertEquals(listOf(180_000L), stage.retunes)
    }

    @Test
    fun `the break's clock runs on while the reel plays - its stalls do not hold it`() {
        val stage = Stage(minuteBreak)
        val subject = stage.subject()
        stage.tunedIn(subject)
        stage.until(116_500)
        subject.ads!!.firstFrame()
        // A stall reported to the break while the reel has the player is not the stream's.
        subject.buffering(true)
        stage.until(150_000)
        subject.buffering(false)
        stage.until(176_000)
        assertEquals(listOf(176_000L), stage.retunes)
    }

    @Test
    fun `a reel that fails is the card, with its countdown, until the same return`() {
        val stage = Stage(minuteBreak)
        val subject = stage.subject()
        stage.tunedIn(subject)
        stage.until(116_500)
        subject.ads!!.failed("SOURCE_HTTP_404")
        assertEquals(1, stage.parks)
        assertTrue(subject.showing && subject.muting)
        assertFalse("the card's words once there are no commercials", subject.state.value!!.blank)
        assertEquals(1, stage.musicPlays)
        stage.until(170_000)
        assertTrue(subject.state.value?.border is BreakBorder.Countdown)
        stage.until(176_000)
        assertEquals(listOf(176_000L), stage.retunes)
        assertNull(subject.state.value)
    }

    @Test
    fun `BREAK ADS off is the card exactly as before - no reel, no tune`() {
        val stage = Stage(minuteBreak).apply { adsOn = false }
        val subject = stage.subject()
        stage.tunedIn(subject)
        stage.until(116_500)
        assertTrue(subject.showing)
        assertFalse(subject.state.value!!.blank)
        assertTrue(stage.plays.isEmpty())
        stage.until(176_500)
        assertFalse(subject.showing)
        assertTrue(stage.retunes.isEmpty())
    }

    @Test
    fun `a break that never ends is ended at its ceiling, and the channel tuned`() {
        val stage = Stage { it >= 20L }
        val subject = stage.subject()
        stage.tunedIn(subject)
        stage.until(116_500)
        subject.ads!!.firstFrame()
        stage.until(116_500 + 5 * 60_000 - 1)
        assertTrue(stage.retunes.isEmpty())
        stage.until(116_500 + 5 * 60_000)
        assertEquals(1, stage.retunes.size)
    }

    @Test
    fun `reads gone blind end the commercials`() {
        val stage = Stage(minuteBreak)
        val subject = stage.subject()
        stage.tunedIn(subject)
        stage.until(116_500)
        subject.ads!!.firstFrame()
        stage.failFetches = true
        stage.until(116_500 + 6 * BreakPoller.POLL_MILLIS + 1)
        assertEquals(1, stage.retunes.size)
    }

    @Test
    fun `a surf takes the reel off without a tune of its own`() {
        val stage = Stage(minuteBreak)
        val subject = stage.subject()
        stage.tunedIn(subject)
        stage.until(116_500)
        subject.ads!!.firstFrame()
        subject.leave()
        stage.until(400_000)
        assertTrue(stage.retunes.isEmpty())
    }

    @Test
    fun `returned under an overlay with no tune, the paused reel's events stay the reel's until a load`() {
        val stage = Stage(minuteBreak)
        val subject = stage.subject()
        stage.tunedIn(subject)
        stage.until(116_500)
        val ads = subject.ads!!
        ads.firstFrame()
        stage.overlay = true
        stage.until(176_000)
        assertEquals("the return: the blank, no tune under the overlay", 1, stage.retunes.size)
        // The director reads these as handled: no playbackFailed (no Pluto blame, no error card),
        // no channel roll-over, no blank dropped, no Pluto stall reload.
        assertTrue(ads.failed("SOURCE_HTTP_404"))
        assertTrue(ads.ended())
        assertTrue(ads.firstFrame())
        assertTrue(ads.buffering(true))
        assertTrue(subject.adsOnPlayer)
        assertFalse(subject.showing || subject.muting)
        assertEquals("nothing parked or replayed", 0, stage.parks)
        assertEquals(1, stage.plays.size)
        // The overlay closes and the channel is tuned: its load takes the player back.
        subject.loading(tuned)
        assertFalse(ads.failed("SOURCE_HTTP_404") || ads.firstFrame() || ads.ended())
    }

    @Test
    fun `home while a reel loads - back, its late first frame does not drop the blank or play it aloud`() {
        val stage = Stage(minuteBreak)
        val subject = stage.subject()
        stage.tunedIn(subject)
        stage.until(116_500)
        assertTrue(subject.ads!!.loading)
        subject.appStopped()
        assertTrue(subject.retuneOnResume(tuned))
        // The resume's tune is in flight; the reel's frame lands first. Swallowed, not a picture.
        assertTrue(subject.ads!!.firstFrame())
        assertFalse(subject.ads!!.picture)
        subject.loading(tuned)
        assertFalse(subject.adsOnPlayer)
    }

    @Test
    fun `a reel error in the gap before the load is the reel's, and the engine dying is not`() {
        val stage = Stage(minuteBreak)
        val subject = stage.subject()
        stage.tunedIn(subject)
        stage.until(116_500)
        subject.ads!!.firstFrame()
        stage.until(176_000)
        assertTrue(subject.ads!!.failed("SOURCE_HTTP_403"))
        assertFalse(subject.ads!!.failed(com.cliftonia.fs42tv.player.MpvChannelPlayer.ENGINE_DIED))
    }

    @Test
    fun `home during the reel - back, the channel is tuned on any engine`() {
        // Media3: a resume there never re-tunes for the card's sake - only for a reel's.
        val stage = Stage(minuteBreak).apply { mpv = false }
        stage.exact = { t0 + stage.clock.now - 15_000 }
        val subject = stage.subject()
        stage.until(60_000)
        subject.loading(tuned)
        stage.until(61_000)
        subject.playing(tuned)
        stage.until(115_000)
        assertTrue(subject.adsOnPlayer)
        subject.appStopped()
        assertTrue(subject.retuneOnResume(tuned))
        assertFalse("once", subject.retuneOnResume(tuned))
    }

    @Test
    fun `the row switched off under a reel tunes the channel back`() {
        val stage = Stage(minuteBreak)
        val subject = stage.subject()
        stage.tunedIn(subject)
        stage.until(116_500)
        subject.ads!!.firstFrame()
        subject.switchedOff()
        assertEquals(listOf(116_500L), stage.retunes)
    }
}
