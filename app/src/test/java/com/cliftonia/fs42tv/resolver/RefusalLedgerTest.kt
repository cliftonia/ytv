package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refusal rules, pinned on the JVM for the first time.
 *
 * Every behaviour here previously lived as four separate fields in the activity, where the only
 * way to test the forget-on-refuse invariant was a 403 on the television - and it shipped
 * broken twice exactly because of that.
 */
class RefusalLedgerTest {

    private var clock = 1_000L
    private fun ledger() = RefusalLedger(nowElapsedSeconds = { clock })

    private fun resolved(tier: String, url: String = "https://v/$tier") =
        ClipResolver.Resolved(Progressive(url, null), expiresAtSeconds = 900_000, tier = tier)

    @Test
    fun `condemn refuses the tier that actually played`() {
        // The clip only offered sd, so sd is what played - but the ladder's first rung is hd.
        // Recomputing "first fresh rung" would condemn hd, a rung that never produced a byte,
        // while the guilty sd url is retried forever.
        val ledger = ledger()
        ledger.rememberPlayed("abcdefghijk", resolved("sd"))
        assertEquals("sd", ledger.condemn("abcdefghijk", listOf("hd", "sd")))
        assertTrue(StreamResolver.refusedKey("abcdefghijk", "sd") in ledger.refusedSnapshot())
    }

    @Test
    fun `condemn falls back to the first fresh rung when nothing was recorded`() {
        // An error can arrive before anything played - a bad url straight out of a prefetch.
        val ledger = ledger()
        assertEquals("hd", ledger.condemn("abcdefghijk", listOf("hd", "sd")))
        assertEquals("sd", ledger.condemn("abcdefghijk", listOf("hd", "sd")))
    }

    @Test
    fun `a clip with every rung refused is condemned outright`() {
        val ledger = ledger()
        ledger.condemn("abcdefghijk", listOf("hd", "sd"))
        ledger.condemn("abcdefghijk", listOf("hd", "sd"))
        assertNull(ledger.condemn("abcdefghijk", listOf("hd", "sd")))
        assertTrue(ledger.isDead("abcdefghijk"))
    }

    @Test
    fun `condemning forgets the cached resolve`() {
        // The cache still holds the very url the CDN just rejected; replaying it three or four
        // times over is why a 403 used to show as seconds of unexplained black.
        val ledger = ledger()
        ledger.rememberPlayed("abcdefghijk", resolved("hd"))
        ledger.condemn("abcdefghijk", listOf("hd", "sd"))
        assertNull(ledger.recall("abcdefghijk", nowSeconds = 0))
    }

    @Test
    fun `refusals expire after the life of the longest signed url`() {
        // Two network blips used to condemn a clip until the box was power-cycled. Six hours on,
        // every url the refusal could describe is dead anyway, so remembering it buys nothing
        // and costs a rung.
        val ledger = ledger()
        ledger.condemn("abcdefghijk", listOf("hd", "sd"))
        clock += 21_601
        assertTrue(ledger.refusedSnapshot().isEmpty())
    }

    @Test
    fun `a refusal one second inside the ttl still stands`() {
        // The boundary pair: the test above fails if the TTL is deleted, this one fails if it
        // is applied too eagerly.
        val ledger = ledger()
        ledger.condemn("abcdefghijk", listOf("hd", "sd"))
        clock += 21_599
        assertFalse(ledger.refusedSnapshot().isEmpty())
    }

    @Test
    fun `a successful resolve revives a condemned clip`() {
        // deadIds must self-heal: the server can succeed where the device failed, and a clip
        // condemned forever would sit as a hole in the rotation for the whole session.
        val ledger = ledger()
        ledger.markDead("abcdefghijk")
        ledger.rememberPlayed("abcdefghijk", resolved("hd"))
        assertFalse(ledger.isDead("abcdefghijk"))
    }

    @Test
    fun `recallToPlay records the tier for a later condemnation`() {
        val ledger = ledger()
        ledger.remember("abcdefghijk", resolved("sd"))
        ledger.recallToPlay("abcdefghijk", nowSeconds = 0)
        assertEquals("the cache hit is what played, so its tier is what a 403 must refuse",
            "sd", ledger.condemn("abcdefghijk", listOf("hd", "sd")))
    }

    @Test
    fun `a plain recall does not claim to be playing`() {
        // The prefetch recalls neighbours constantly; if that recorded a tier, a 403 on the
        // channel actually PLAYING could condemn the neighbour's rung instead.
        val ledger = ledger()
        ledger.remember("abcdefghijk", resolved("sd"))
        ledger.recall("abcdefghijk", nowSeconds = 0)
        assertEquals("hd", ledger.condemn("abcdefghijk", listOf("hd", "sd")))
    }

    @Test
    fun `a resolve landing after its tier was refused is not cached`() {
        val ledger = ledger()
        ledger.rememberPlayed("abcdefghijk", resolved("hd"))
        ledger.condemn("abcdefghijk", listOf("hd", "sd"))
        ledger.remember("abcdefghijk", resolved("hd"))
        assertNull("caching it would replay the refused url on the next tune",
            ledger.recall("abcdefghijk", nowSeconds = 0))
    }
}
