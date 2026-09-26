package com.cliftonia.fs42tv.ui

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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card on the stream's clock, against a simulated Pluto channel: 5s segments, segment `i`
 * stamped [T0] + 5s*i and appearing at the playlist's edge at clock time 5s*(i+1), a window of
 * five, a PROGRAM-DATE-TIME only where programme and bumper meet (as captured, Sep 2026).
 *
 * The owner's complaint was the logo showing before the card and again after it; so every
 * assertion here is to the millisecond of the instant on screen.
 */
class PlutoBreakTimingTest {

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
        var exact: (() -> Long?) = { null }
        var mpv = true
    }

    private fun Stage.window(): String {
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

    private fun Stage.subject() = PlutoBreak(PlutoBreak.Deps(
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
        overlayOpen = { false },
        stoppedNow = { false },
        playMusic = { },
        releaseMusic = { },
        nowTitle = { _, _ -> null },
        volumeChanged = { },
        picture = { },
    ))

    /** Advance to clock time [t]. */
    private fun Stage.until(t: Long) = clock.advance(t - clock.now)

    /** Segments 20..31 are the bumper: a minute's break from t0+100s to t0+160s. */
    private val minuteBreak: (Long) -> Boolean = { it in 20L..31L }

    @Test
    fun `mpv - the card goes up exactly as the bumper reaches the screen, and down as it leaves`() {
        val stage = Stage(minuteBreak)
        val subject = stage.subject()
        // Tuned at 60s: the window is 7..11, so mpv starts at segment 9 (t0 + 45s), whose first
        // frame shows at 61.5s. On screen from then: t0 + 45s + (clock - 61.5s).
        stage.until(60_000)
        subject.loading(tuned)
        stage.until(61_500)
        subject.playing(tuned)
        stage.until(116_499)
        assertFalse(subject.showing)
        stage.until(116_500)
        assertTrue(subject.showing)
        stage.until(176_499)
        assertTrue(subject.showing)
        stage.until(176_500)
        assertFalse(subject.showing)
        assertFalse(subject.muting)
    }

    @Test
    fun `mpv - a stall holds the clock, so the card waits for the picture`() {
        val stage = Stage(minuteBreak)
        val subject = stage.subject()
        stage.until(60_000)
        subject.loading(tuned)
        stage.until(61_500)
        subject.playing(tuned)
        stage.until(90_000)
        subject.buffering(true)
        stage.until(92_000)
        subject.buffering(false)
        stage.until(118_499)
        assertFalse(subject.showing)
        stage.until(118_500)
        assertTrue(subject.showing)
    }

    @Test
    fun `Media3 - the engine's own instant times the card`() {
        val stage = Stage(minuteBreak)
        // Fifteen seconds behind the stamps' clock, as Media3 sits three targets back.
        stage.mpv = false
        stage.exact = { t0 + stage.clock.now - 15_000 }
        val subject = stage.subject()
        stage.until(60_000)
        subject.loading(tuned)
        stage.until(61_000)
        subject.playing(tuned)
        stage.until(114_999)
        assertFalse(subject.showing)
        stage.until(115_000)
        assertTrue(subject.showing)
        stage.until(174_999)
        assertTrue(subject.showing)
        stage.until(175_000)
        assertFalse(subject.showing)
    }

    @Test
    fun `the border pulses until the end is known, then counts down to it exactly`() {
        val stage = Stage(minuteBreak)
        val subject = stage.subject()
        stage.until(60_000)
        subject.loading(tuned)
        stage.until(61_500)
        subject.playing(tuned)
        stage.until(116_500)
        assertEquals(BreakBorder.Pulse, subject.state.value?.border)
        // Segment 32, the programme's return, reaches the edge at 165s and is read at 168s.
        stage.until(167_999)
        assertEquals(BreakBorder.Pulse, subject.state.value?.border)
        stage.until(168_000)
        val countdown = subject.state.value?.border as BreakBorder.Countdown
        assertEquals(168_000L, countdown.fromMillis)
        assertEquals(176_500L, countdown.untilMillis)
        assertEquals(1f, countdown.remaining(168_000L))
        assertEquals(0.5f, countdown.remaining(172_250L))
        assertEquals(0f, countdown.remaining(176_500L))
    }

    @Test
    fun `a one-segment bumper at a programme change never shows`() {
        val stage = Stage { it == 20L }
        val subject = stage.subject()
        stage.until(60_000)
        subject.loading(tuned)
        stage.until(61_500)
        subject.playing(tuned)
        var seen = false
        while (stage.clock.now < 200_000) {
            stage.until(stage.clock.now + 250)
            seen = seen || subject.showing
        }
        assertFalse(seen)
    }

    @Test
    fun `tuned in mid-break, the card is up with the first picture`() {
        val stage = Stage(minuteBreak)
        val subject = stage.subject()
        stage.until(130_000)
        subject.loading(tuned)
        stage.until(131_500)
        subject.playing(tuned)
        assertTrue(subject.showing)
    }

    @Test
    fun `a channel stuck on the bumper gets five minutes of card and then no more`() {
        val stage = Stage { it >= 20L }
        val subject = stage.subject()
        stage.until(60_000)
        subject.loading(tuned)
        stage.until(61_500)
        subject.playing(tuned)
        stage.until(116_500)
        assertTrue(subject.showing)
        stage.until(116_500 + 5 * 60_000 - 1)
        assertTrue(subject.showing)
        stage.until(116_500 + 5 * 60_000)
        assertFalse(subject.showing)
        stage.until(116_500 + 15 * 60_000)
        assertFalse(subject.showing)
    }

    @Test
    fun `mpv resumed from the home screen, not reloaded, falls back to three targets behind the edge`() {
        val stage = Stage(minuteBreak)
        val subject = stage.subject()
        stage.until(60_000)
        // No load: the poll starts with the picture, as appResumed does.
        subject.playing(tuned)
        // Edge estimate: the window's edge less 15s, moving with the clock. The edge moves in 5s
        // steps, so this is right to within a segment: the break's start, t0 + 100s, is on
        // screen at clock 115s, and the card lands inside the segment after it.
        var upAt: Long? = null
        while (upAt == null && stage.clock.now < 130_000) {
            stage.until(stage.clock.now + 100)
            if (subject.showing) upAt = stage.clock.now
        }
        assertTrue("card at $upAt", upAt != null && upAt in 115_000L..120_000L)
    }
}
