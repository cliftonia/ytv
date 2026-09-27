package com.cliftonia.fs42tv.ui

import com.cliftonia.fs42tv.ads.AdCatalog
import com.cliftonia.fs42tv.ads.AdReel
import com.cliftonia.fs42tv.player.MpvChannelPlayer
import com.cliftonia.fs42tv.pluto.BreakPollerTest
import com.cliftonia.fs42tv.resolver.Progressive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A break's reel on the dial's player, and every way it falls back to the card. */
class BreakAdsTest {

    private class World {
        val clock = BreakPollerTest.Clock()
        var enabled = true
        var catalog: AdCatalog? = AdCatalog(1, listOf(
            AdReel("aus-80s", era = "80s", url = "https://archive.org/download/aus-80s/r.mp4", duration = 1800.0,
                cuts = listOf(0.0, 30.0, 61.0)),
            AdReel("aus-70s", era = "70s", url = "https://archive.org/download/aus-70s/r.mp4", duration = 900.0,
                cuts = listOf(12.0)),
        ))
        val played = mutableListOf<Pair<Progressive, Double>>()
        var parks = 0
        var changes = 0
        var warms = 0

        fun subject() = BreakAds(BreakAds.Deps(
            enabled = { enabled },
            catalog = { catalog },
            warm = { warms++ },
            play = { reel, at -> played += reel to at },
            park = { parks++ },
            later = clock.schedule,
            changed = { changes++ },
        ))
    }

    @Test
    fun `a break hands the player a reel at one of its cuts, loading until its first frame`() {
        val world = World()
        val ads = world.subject()
        assertTrue(ads.start(7, 1_000L))
        val (reel, at) = world.played.single()
        assertTrue(reel.videoUrl.startsWith("https://archive.org/download/"))
        assertEquals(null, reel.audioUrl)
        assertTrue(at in listOf(0.0, 30.0, 61.0, 12.0))
        assertTrue(ads.loading && ads.onPlayer && !ads.picture)
        assertTrue(ads.firstFrame())
        assertTrue(ads.picture)
        assertEquals(1, world.changes)
    }

    @Test
    fun `the same break picks the same reel and cut`() {
        val a = World().also { it.subject().start(7, 5_000L) }.played.single()
        val b = World().also { it.subject().start(7, 5_000L) }.played.single()
        assertEquals(a, b)
    }

    @Test
    fun `the row off, no catalog, or nothing usable leaves the break to the card and the player alone`() {
        assertFalse(World().apply { enabled = false }.subject().start(7, 1L))
        assertFalse(World().apply { catalog = null }.subject().start(7, 1L))
        val none = World().apply { catalog = AdCatalog(1, emptyList()) }
        val ads = none.subject()
        assertFalse(ads.start(7, 1L))
        assertTrue(none.played.isEmpty())
        assertFalse(ads.onPlayer)
        // And nothing of the player's is claimed.
        assertFalse(ads.firstFrame() || ads.failed("x") || ads.ended() || ads.buffering(true))
    }

    @Test
    fun `a reel that errors is the card for the rest of the break - handled, not a fault`() {
        val world = World()
        val ads = world.subject()
        ads.start(7, 1L)
        assertTrue(ads.failed("SOURCE_HTTP_404"))
        assertEquals(BreakAds.Stage.FAILED, ads.stage)
        assertEquals(1, world.parks)
        assertTrue("still off Pluto: the return is a tune", ads.onPlayer)
        // Later noise from the parked file changes nothing.
        assertTrue(ads.ended() && ads.failed("again") && ads.firstFrame())
        assertEquals(BreakAds.Stage.FAILED, ads.stage)
        assertEquals(1, world.parks)
    }

    @Test
    fun `a dead engine is not the reel's to swallow`() {
        val world = World()
        val ads = world.subject()
        ads.start(7, 1L)
        assertFalse(ads.failed(MpvChannelPlayer.ENGINE_DIED + ": core shut down"))
        assertFalse(ads.onPlayer)
    }

    @Test
    fun `no frame within the load time is the card`() {
        val world = World()
        val ads = world.subject()
        ads.start(7, 1L)
        world.clock.advance(BreakAds.LOAD_MILLIS - 1)
        assertTrue(ads.loading)
        world.clock.advance(1)
        assertEquals(BreakAds.Stage.FAILED, ads.stage)
    }

    @Test
    fun `a long stall mid-reel is the card, a short one is not`() {
        val world = World()
        val ads = world.subject()
        ads.start(7, 1L)
        ads.firstFrame()
        assertTrue(ads.buffering(true))
        world.clock.advance(2_000)
        assertTrue(ads.buffering(false))
        world.clock.advance(BreakAds.STALL_MILLIS * 2)
        assertTrue(ads.picture)
        ads.buffering(true)
        world.clock.advance(BreakAds.STALL_MILLIS)
        assertEquals(BreakAds.Stage.FAILED, ads.stage)
    }

    @Test
    fun `a reel that runs out rolls on to another, but never loops for ever`() {
        val world = World()
        val ads = world.subject()
        ads.start(7, 1L)
        ads.firstFrame()
        assertTrue(ads.ended())
        assertEquals(2, world.played.size)
        assertTrue(ads.loading)
        ads.firstFrame()
        ads.ended()
        assertEquals(BreakAds.MAX_REELS, world.played.size)
        ads.firstFrame()
        ads.ended()
        assertEquals(BreakAds.Stage.FAILED, ads.stage)
    }

    @Test
    fun `consecutive breaks do not open on the same reel while another is available`() {
        val world = World()
        val ads = world.subject()
        ads.start(7, 1L)
        ads.stop()
        ads.start(7, 2L)
        assertTrue(world.played[0].first != world.played[1].first)
    }

    @Test
    fun `retired, the reel's events are swallowed and no timer fires, until a load stops it`() {
        val world = World()
        val ads = world.subject()
        ads.start(7, 1L)
        ads.retire()
        assertEquals(BreakAds.Stage.RETIRING, ads.stage)
        assertFalse(ads.picture || ads.loading)
        world.clock.advance(BreakAds.LOAD_MILLIS * 2)
        assertEquals(BreakAds.Stage.RETIRING, ads.stage)
        assertTrue(ads.firstFrame() && ads.failed("x") && ads.ended() && ads.buffering(true))
        assertEquals(BreakAds.Stage.RETIRING, ads.stage)
        assertEquals("never parked, never a second reel", 0, world.parks)
        assertEquals(1, world.played.size)
        assertFalse("a new break waits for the load", ads.start(7, 2L))
        ads.stop()
        assertFalse(ads.onPlayer || ads.firstFrame())
    }

    @Test
    fun `retiring from nothing stays nothing`() {
        val ads = World().subject()
        ads.retire()
        assertEquals(BreakAds.Stage.IDLE, ads.stage)
    }

    @Test
    fun `stopped, nothing of the player is claimed and no timer fires`() {
        val world = World()
        val ads = world.subject()
        ads.start(7, 1L)
        ads.stop()
        world.clock.advance(BreakAds.LOAD_MILLIS * 2)
        assertEquals(BreakAds.Stage.IDLE, ads.stage)
        assertEquals(0, world.parks)
    }
}
