package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.pluto.BreakPoller
import com.cliftonia.fs42tv.pluto.BreakPollerTest
import com.cliftonia.fs42tv.resolver.Hls
import com.cliftonia.fs42tv.resolver.Progressive
import com.cliftonia.fs42tv.sync.Channel
import com.cliftonia.fs42tv.sync.PlutoRef
import com.cliftonia.fs42tv.sync.Stream
import com.cliftonia.fs42tv.tune.Tuned
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The break card's life against everything else that happens to the screen: surfs, the guide,
 * settings, the app leaving, the row switched off. A real [BreakPoller] on a hand-cranked clock,
 * with a playlist the test flips between programme and bumper.
 */
class PlutoBreakTest {

    private val master = "https://stitcher.example/channel/abc/master.m3u8?sid=1"
    private val masterBody = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n360p.m3u8\n"
    private fun media(segment: String) = "#EXTM3U\n#EXTINF:5.0,\n$segment\n"
    private val bumper = media("/clip/x_ptv_7424adbumperanimationdotsinverted30_30fps/1080pDRM/a.ts")
    private val show = media("/clip/6123_Enter_the_Dragon/720p/b.ts")

    private val flicks = Channel(
        number = 7, name = "Flicks of Fury", kind = "live",
        streams = listOf(Stream(url = "https://jmp2.uk/plu-5f0000000000000000000000.m3u8", duration = 600)),
        pluto = PlutoRef("5f0000000000000000000000"),
    )
    private val news = Channel(
        number = 110, name = "Euronews", kind = "live",
        streams = listOf(Stream(url = "https://jmp2.uk/plu-6f0000000000000000000000.m3u8", duration = 600)),
    )

    private fun tuned(channel: Channel = flicks, url: String = master) =
        Tuned(channel, 0, channel.streams.first(), Hls(url), 0.0)

    private class World {
        val clock = BreakPollerTest.Clock()
        var playlist: String = ""
        var enabled = true
        var guideOpen = false
        var settingsOpen = false
        var stopped = false
        var halted = false
        var title: String? = "Enter the Dragon"
        val fetched = mutableListOf<String>()
        var musicPlays = 0
        var musicReleases = 0
        var musicWanted: (() -> Boolean)? = null
        var volumeChanges = 0
        var pictures = 0
    }

    private fun subject(world: World) = PlutoBreak(PlutoBreak.Deps(
        enabled = { world.enabled },
        poller = { changed ->
            BreakPoller(
                fetch = { url ->
                    world.fetched += url
                    BreakPoller.Fetched(url, if (url == master) masterBody else world.playlist)
                },
                schedule = world.clock.schedule,
                changed = changed,
            )
        },
        runOnUi = { it() },
        halted = { world.halted },
        guideOpen = { world.guideOpen },
        overlayOpen = { world.guideOpen || world.settingsOpen },
        stoppedNow = { world.stopped },
        playMusic = { wanted -> world.musicPlays++; world.musicWanted = wanted },
        releaseMusic = { world.musicReleases++ },
        nowTitle = { _, _ -> world.title },
        volumeChanged = { world.volumeChanges++ },
        picture = { world.pictures++ },
    ))

    /** Play [t] and let the poller see [reads] bumper windows. */
    private fun World.intoBreak(subject: PlutoBreak, t: Tuned = tuned(), reads: Int = 2) {
        playlist = bumper
        subject.playing(t)
        clock.advance(BreakPoller.FIRST_READ_MILLIS)
        repeat(reads - 1) { clock.advance(BreakPoller.POLL_MILLIS) }
    }

    @Test
    fun `two bumper reads put the card up with the channel and what it comes back to`() {
        val world = World()
        val subject = subject(world)
        world.intoBreak(subject)
        assertEquals(BreakCardState("07 FLICKS OF FURY", "BACK TO: Enter the Dragon"), subject.state.value)
        assertTrue(subject.inBreak && subject.muting && subject.showing)
        assertEquals(1, world.pictures)
        assertEquals(1, world.volumeChanges)
        assertEquals(1, world.musicPlays)
        assertTrue(world.musicWanted!!())
    }

    @Test
    fun `one bumper read is not a card`() {
        val world = World()
        val subject = subject(world)
        world.intoBreak(subject, reads = 1)
        assertNull(subject.state.value)
        assertFalse(subject.muting)
        assertEquals(0, world.musicPlays)
    }

    @Test
    fun `without a cached guide the card still goes up, without a BACK TO line`() {
        val world = World().apply { title = null }
        val subject = subject(world)
        world.intoBreak(subject)
        assertEquals("", subject.state.value?.backTo)
    }

    @Test
    fun `the programme's first read takes the card, the silence and the music down`() {
        val world = World()
        val subject = subject(world)
        world.intoBreak(subject)
        world.playlist = show
        world.clock.advance(BreakPoller.POLL_MILLIS)
        assertNull(subject.state.value)
        assertFalse(subject.muting)
        assertEquals(1, world.musicReleases)
        assertEquals(2, world.volumeChanges)
        assertFalse(world.musicWanted!!())
    }

    @Test
    fun `a surf during a break takes it down and stops the reads`() {
        val world = World()
        val subject = subject(world)
        world.intoBreak(subject)
        val before = world.fetched.size
        subject.leave()
        assertNull(subject.state.value)
        assertFalse(subject.muting)
        assertEquals(1, world.musicReleases)
        world.clock.advance(BreakPoller.POLL_MILLIS * 4)
        assertEquals(before, world.fetched.size)
    }

    @Test
    fun `the guide hides the card, keeps its music, and the card is back when it closes`() {
        val world = World()
        val subject = subject(world)
        world.intoBreak(subject)
        world.guideOpen = true
        subject.refresh()
        assertNull(subject.state.value)
        // Still a break: the programme stays silent under the guide.
        assertTrue(subject.muting)
        world.guideOpen = false
        subject.refresh()
        subject.resumeMusic()
        assertEquals("07 FLICKS OF FURY", subject.state.value?.channelLine)
        assertEquals(2, world.musicPlays)
    }

    @Test
    fun `a break that ends under the guide leaves the guide's music alone and no card to restore`() {
        val world = World()
        val subject = subject(world)
        world.intoBreak(subject)
        world.guideOpen = true
        subject.refresh()
        world.playlist = show
        world.clock.advance(BreakPoller.POLL_MILLIS)
        assertEquals(0, world.musicReleases)
        world.guideOpen = false
        subject.refresh()
        subject.resumeMusic()
        assertNull(subject.state.value)
        assertEquals(1, world.musicPlays)
    }

    @Test
    fun `settings hides the card and closing it restores the card`() {
        val world = World()
        val subject = subject(world)
        world.intoBreak(subject)
        world.settingsOpen = true
        subject.refresh()
        assertNull(subject.state.value)
        world.settingsOpen = false
        subject.refresh()
        assertTrue(subject.showing)
    }

    @Test
    fun `a break that starts under the guide shows once it closes, and plays no music over it`() {
        val world = World().apply { guideOpen = true }
        val subject = subject(world)
        world.intoBreak(subject)
        assertNull(subject.state.value)
        assertTrue(subject.muting)
        assertEquals(0, world.musicPlays)
        world.guideOpen = false
        subject.refresh()
        assertTrue(subject.showing)
    }

    @Test
    fun `only a Pluto-dial channel, with the row on and the app in view, is read at all`() {
        val world = World()
        val subject = subject(world)
        subject.playing(tuned(news))
        world.enabled = false
        subject.playing(tuned())
        world.enabled = true
        world.stopped = true
        subject.playing(tuned())
        world.stopped = false
        subject.playing(tuned().copy(playable = Progressive("https://x/y.mp4", null)))
        subject.playing(null)
        world.clock.advance(BreakPoller.POLL_MILLIS * 3)
        assertTrue(world.fetched.isEmpty())
    }

    @Test
    fun `a second first frame on the same stream keeps the break already seen`() {
        val world = World()
        val subject = subject(world)
        world.intoBreak(subject)
        subject.playing(tuned())
        assertTrue(subject.showing)
        assertEquals(1, world.fetched.count { it == master })
    }

    @Test
    fun `a new stream on the channel is a new tune, read from scratch`() {
        val world = World()
        val subject = subject(world)
        world.intoBreak(subject)
        subject.playing(tuned(url = "$master&again"))
        assertFalse(subject.showing)
        assertFalse(subject.muting)
    }

    @Test
    fun `switching the row off mid-break is the same as leaving`() {
        val world = World()
        val subject = subject(world)
        world.intoBreak(subject)
        world.enabled = false
        subject.playing(tuned())
        assertFalse(subject.showing)
        assertFalse(subject.muting)
    }

    @Test
    fun `a verdict landing after destroy changes nothing`() {
        val world = World()
        val subject = subject(world)
        world.playlist = bumper
        subject.playing(tuned())
        world.clock.advance(BreakPoller.FIRST_READ_MILLIS)
        world.halted = true
        world.clock.advance(BreakPoller.POLL_MILLIS)
        assertFalse(subject.inBreak)
    }
}
