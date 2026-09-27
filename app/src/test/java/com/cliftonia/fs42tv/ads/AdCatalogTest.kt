package com.cliftonia.fs42tv.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ads.json` as the server job publishes it, and what the app will and will not play of it. */
class AdCatalogTest {

    private val body = """
        {"generated": 1790000000, "extra": "ignored",
         "reels": [
          {"id": "aus-ads-1983", "title": "Aussie ads 1983", "era": "80s",
           "url": "https://archive.org/download/aus-ads-1983/reel.mp4", "duration": 1800.5,
           "cuts": [0, 31.2, 62.0, 1790], "new_field": [1, 2]},
          {"id": "aus-ads-1976", "era": "70s",
           "url": "https://archive.org/download/aus-ads-1976/reel.mp4", "duration": 900, "cuts": []}
         ]}
    """.trimIndent()

    @Test
    fun `parses the contract, ignoring fields it does not know`() {
        val catalog = AdCatalog.parse(body)!!
        assertEquals(1_790_000_000L, catalog.generated)
        assertEquals(2, catalog.reels.size)
        val reel = catalog.reels.first()
        assertEquals("aus-ads-1983", reel.id)
        assertEquals("80s", reel.era)
        assertEquals(1800.5, reel.duration, 0.0)
        assertEquals(listOf(0.0, 31.2, 62.0, 1790.0), reel.cuts)
    }

    @Test
    fun `anything that is not the contract is no catalog, never a throw`() {
        assertNull(AdCatalog.parse(null))
        assertNull(AdCatalog.parse(""))
        assertNull(AdCatalog.parse("404: Not Found"))
    }

    @Test
    fun `a reel missing a field is dropped on its own - the rest of the file still plays`() {
        val catalog = AdCatalog.parse("""{"reels": [{"id": "x"},
            {"id": "a", "url": "https://archive.org/download/a/a.mp4", "duration": 600, "cuts": [5]}]}""")!!
        assertEquals(2, catalog.reels.size)
        assertEquals(listOf("a"), catalog.usableReels.map { it.id })
    }

    @Test
    fun `an empty reel list is a valid answer - no ads`() {
        assertTrue(AdCatalog.parse("""{"generated": 1, "reels": []}""")!!.usableReels.isEmpty())
    }

    @Test
    fun `a reel is usable with an https url, a length and a cut a minute from its end`() {
        val catalog = AdCatalog.parse(body)!!
        assertEquals(listOf("aus-ads-1983"), catalog.usableReels.map { it.id })
        // 1790 is inside the last minute of an 1800s reel: a break would roll straight off it.
        assertEquals(listOf(0.0, 31.2, 62.0), catalog.reels.first().usableCuts())
    }

    @Test
    fun `cleartext, blank ids, no length and nonsense cuts are refused`() {
        val good = AdReel("a", url = "https://archive.org/download/a/a.mp4", duration = 600.0, cuts = listOf(10.0))
        assertTrue(good.usable)
        assertFalse(good.copy(url = "http://archive.org/download/a/a.mp4").usable)
        assertFalse(good.copy(id = " ").usable)
        assertFalse(good.copy(duration = 0.0).usable)
        assertFalse(good.copy(cuts = listOf(-1.0, Double.NaN, 700.0)).usable)
        assertEquals(listOf(5.0, 10.0), good.copy(cuts = listOf(10.0, 5.0, 10.0)).usableCuts())
    }
}
