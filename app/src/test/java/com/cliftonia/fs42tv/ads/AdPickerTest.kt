package com.cliftonia.fs42tv.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which reel and which commercial a break opens on. */
class AdPickerTest {

    private fun reel(id: String, vararg cuts: Double) =
        AdReel(id, era = "80s", url = "https://archive.org/download/$id/r.mp4", duration = 1200.0, cuts = cuts.toList())

    private val pool = listOf(
        reel("a", 0.0, 30.0, 60.0), reel("b", 10.0, 40.0), reel("c", 5.0), reel("d", 0.0, 45.0), reel("e", 15.0),
    )

    @Test
    fun `the same break and the same history pick the same commercial`() {
        val seed = AdPicker.seed(1_790_000_000_000L, 7)
        assertEquals(AdPicker.pick(pool, seed), AdPicker.pick(pool, seed))
        // Order of the catalog is not part of the answer.
        assertEquals(AdPicker.pick(pool, seed), AdPicker.pick(pool.reversed(), seed))
    }

    @Test
    fun `every pick starts at one of the reel's cuts`() {
        (0 until 200).forEach { i ->
            val pick = AdPicker.pick(pool, AdPicker.seed(i * 60_000L, 7))!!
            assertTrue(pick.cutSeconds in pick.reel.usableCuts())
        }
    }

    @Test
    fun `breaks walk the whole pool`() {
        val seen = (0 until 200).map { AdPicker.pick(pool, AdPicker.seed(it * 60_000L, 7))!!.reel.id }.toSet()
        assertEquals(pool.map { it.id }.toSet(), seen)
    }

    @Test
    fun `the last few reels are avoided while the pool allows`() {
        (0 until 100).forEach { i ->
            val pick = AdPicker.pick(pool, AdPicker.seed(i * 60_000L, 3), recentReels = listOf("x", "a", "b", "c"))!!
            assertTrue(pick.reel.id in setOf("d", "e"))
        }
    }

    @Test
    fun `only the last few count - an old reel comes back`() {
        val old = listOf("d", "e", "a", "b", "c")
        val picks = (0 until 100).map { AdPicker.pick(pool, AdPicker.seed(it * 60_000L, 3), recentReels = old)!!.reel.id }
        assertTrue(picks.all { it in setOf("d", "e") })
    }

    @Test
    fun `a pool too small to avoid repeats repeats rather than going without`() {
        val one = listOf(reel("a", 0.0, 30.0))
        val pick = AdPicker.pick(one, 42L, recentReels = listOf("a"), recentPicks = listOf("a@0.0"))
        assertEquals("a", pick!!.reel.id)
        // Within the one reel, the commercial not started at last time.
        assertEquals(30.0, pick.cutSeconds, 0.0)
        val both = AdPicker.pick(one, 42L, recentReels = listOf("a"), recentPicks = listOf("a@0.0", "a@30.0"))
        assertNotNull(both)
    }

    @Test
    fun `no usable reel is no pick - the card`() {
        assertNull(AdPicker.pick(emptyList(), 1L))
        val bad = listOf(
            reel("http", 0.0).copy(url = "http://archive.org/x.mp4"),
            reel("nocuts"),
            reel("late", 1190.0),
        )
        assertNull(AdPicker.pick(bad, 1L))
        assertFalse(bad.any { it.usable })
    }

    @Test
    fun `two channels breaking at the same instant need not open on the same commercial`() {
        val differ = (0 until 50).any { i ->
            val at = i * 60_000L
            AdPicker.pick(pool, AdPicker.seed(at, 7)) != AdPicker.pick(pool, AdPicker.seed(at, 8))
        }
        assertTrue(differ)
    }
}
