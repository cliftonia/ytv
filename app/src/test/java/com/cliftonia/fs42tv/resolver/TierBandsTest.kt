package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides which rendition a rung of the ladder actually gets.
 *
 * It had no tests, not because it is simple but because it was a private method of a class that
 * cannot be constructed without the extractor and the network. Getting it wrong is not a crash:
 * it is a 4K panel quietly playing 720p, or a 32-bit set handed a rendition its decoder refuses.
 */
class TierBandsTest {

    private data class Rendition(val label: String, val avc: Boolean)

    private fun best(vararg renditions: Rendition) = renditions.toList().maxWithOrNull(
        TierBands.preference<Rendition>(
            height = { TierBands.heightOf(it.label) },
            isAvc = { it.avc },
        )
    )

    @Test
    fun `the bands do not overlap, so no two rungs resolve to the same rendition`() {
        // If they overlapped, a clip with no 1080p rendition would resolve hd and sd to the same
        // 720p stream and the ladder would try the identical url twice before giving up.
        val bands = listOf("uhd", "hd", "sd").map { TierBands.bandFor(it)!! }
        for (height in 0..2160) {
            val matching = bands.count { height in it }
            assertTrue("height ${height}p falls in $matching bands, not exactly one",
                matching == 1)
        }
    }

    @Test
    fun `the top band stops at 2160 rather than running open-ended`() {
        // YouTube publishes 4320p on a growing number of uploads. An unbounded top band takes it:
        // four times the pixels of the panel's native resolution, on a set with 2.34GB in total.
        assertEquals(1081..2160, TierBands.bandFor("uhd"))
        assertTrue("4320p must not fall in any band",
            listOf("uhd", "hd", "sd").none { 4320 in TierBands.bandFor(it)!! })
    }

    @Test
    fun `a rung with no band is declined rather than defaulted`() {
        assertNull("an unknown rung silently matching everything would let a typo in a stored " +
            "quality preference hand a 4K rendition to a set that asked for sd",
            TierBands.bandFor("ultra"))
    }

    @Test
    fun `the height comes from the resolution label, with the frame rate suffix ignored`() {
        assertEquals(1080, TierBands.heightOf("1080p60"))
        assertEquals(2160, TierBands.heightOf("2160p"))
        assertEquals(720, TierBands.heightOf("720p50"))
    }

    @Test
    fun `an unparseable resolution sorts to the bottom instead of throwing`() {
        // The label is the accessor that has survived extractor version changes; the price of
        // that is that it is a string, and a string can be anything.
        assertEquals(0, TierBands.heightOf(null))
        assertEquals(0, TierBands.heightOf(""))
        assertEquals(0, TierBands.heightOf("hd"))
    }

    @Test
    fun `the tallest rendition inside the band wins`() {
        val winner = best(Rendition("720p", avc = true), Rendition("1080p", avc = false))
        assertEquals("1080p", winner!!.label)
    }

    @Test
    fun `H 264 wins a tie against an equal-height VP9`() {
        // Both usually play where both are offered, but H.264 is the one every device has decoded
        // in hardware for fifteen years, and the cost of preferring it is nothing.
        val winner = best(Rendition("1080p-vp9", avc = false), Rendition("1080p-avc", avc = true))
        assertEquals("1080p-avc", winner!!.label)
    }

    @Test
    fun `height beats codec, so a taller VP9 is still preferred to a shorter H 264`() {
        val winner = best(Rendition("1080p-vp9", avc = false), Rendition("720p-avc", avc = true))
        assertEquals("1080p-vp9", winner!!.label)
    }
}
