package com.cliftonia.fs42tv.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a resolve response, and deciding a server is worth asking.
 *
 * Parsed by hand rather than with org.json, which Android STUBS in JVM tests - a parser written
 * with it passes its tests without ever having run, which has already happened once in this
 * codebase.
 */
class ServerTiersTest {

    private val body = """
        {"id":"abc12345678","warm":true,
         "uhd":{"video":"https://v/uhd","audio":"https://a/1","expires":9999},
         "hd":{"video":"https://v/hd","audio":"https://a/1","expires":9999},
         "sd":{"video":"https://v/sd","audio":"https://a/1","expires":9999}}
    """.trimIndent()

    @Test
    fun `the first rung of the ladder wins`() {
        val got = ServerTiers.parse(body, listOf("hd", "sd"), emptySet(), "abc12345678", 100)
        assertEquals("https://v/hd", got?.playable?.videoUrl)
        assertEquals("https://a/1", got?.playable?.audioUrl)
    }

    @Test
    fun `a rung the ladder does not name is never taken`() {
        // A 1080p panel must not be handed 4K just because the server published it.
        assertEquals("https://v/sd",
            ServerTiers.parse(body, listOf("sd"), emptySet(), "abc12345678", 100)
                ?.playable?.videoUrl)
    }

    @Test
    fun `a refused rung is skipped`() {
        // The server has no idea which urls THIS television has been turned away from, so the
        // skipping has to happen here or a 403 would be handed back the same url forever.
        val refused = setOf(StreamResolver.refusedKey("abc12345678", "hd"))
        assertEquals("https://v/sd",
            ServerTiers.parse(body, listOf("hd", "sd"), refused, "abc12345678", 100)
                ?.playable?.videoUrl)
    }

    @Test
    fun `an expired tier is refused, using the same margin the device applies`() {
        // 9999 expiry with a 300s margin is dead from 9699 onwards, and must be dead at exactly
        // the same moment whichever path produced the url.
        assertNull(ServerTiers.parse(body, listOf("hd"), emptySet(), "abc12345678", 9_800))
        assertTrue(ServerTiers.parse(body, listOf("hd"), emptySet(), "abc12345678", 9_000) != null)
    }

    @Test
    fun `a response with no usable tier is null rather than a guess`() {
        assertNull(ServerTiers.parse("""{"id":"x"}""", listOf("hd"), emptySet(), "x", 100))
        assertNull(ServerTiers.parse("not json at all", listOf("hd"), emptySet(), "x", 100))
        assertNull(ServerTiers.parse("", listOf("hd"), emptySet(), "x", 100))
    }

    @Test
    fun `health is usable only when the server says ok`() {
        assertTrue(Health.isUsable("""{"ok":true,"cached":180}"""))
        assertFalse(Health.isUsable("""{"ok":false,"extractor":{"ok":false}}"""))
        assertFalse(Health.isUsable(""))
        assertFalse(Health.isUsable("<html>502 Bad Gateway</html>"))
    }

    private val withCaption = """
        {"id":"abc12345678","warm":true,
         "caption":"https://www.youtube.com/api/timedtext?v=abc&fmt=vtt",
         "hd":{"video":"https://v/hd","audio":"https://a/1","expires":9999}}
    """.trimIndent()

    @Test
    fun `the caption url is carried through`() {
        // This field is why captions did nothing on the television at home: the accelerator
        // answers every resolve when reachable, so the on-device caption picking never ran and
        // the fast path had nowhere to carry a track.
        val got = ServerTiers.parse(withCaption, listOf("hd"), emptySet(), "abc12345678", 100)
        assertEquals("https://www.youtube.com/api/timedtext?v=abc&fmt=vtt",
                     got?.playable?.captionUrl)
    }

    @Test
    fun `the track is carried even though the viewer may have captions off`() {
        // The second half of the same fault, and the reason this is not conditional on the
        // toggle. Reading the field is free - it is already in the body - and a resolve that
        // dropped it left the clip on screen with no track at all, so turning captions ON could
        // not do anything until the viewer left the channel and came back. Whether to DRAW them
        // is the toggle's business; whether the url is available is not.
        val got = ServerTiers.parse(withCaption, listOf("hd"), emptySet(), "abc12345678", 100)
        assertEquals("https://www.youtube.com/api/timedtext?v=abc&fmt=vtt",
                     got?.playable?.captionUrl)
    }

    @Test
    fun `a response with no caption is not a failure`() {
        // Most clips have no English track, and that must play normally rather than be refused.
        val got = ServerTiers.parse(body, listOf("hd"), emptySet(), "abc12345678", 100)
        assertEquals("https://v/hd", got?.playable?.videoUrl)
        assertNull(got?.playable?.captionUrl)
    }

    @Test
    fun `a json null is an absent value, not the string null`() {
        // json.dumps renders None as a bare null and the field regex captures the word. Flowing
        // onwards it becomes the literal url "null" - handed to the player, or to sub-add.
        val nullCaption = """
            {"caption":null,
             "hd":{"video":"https://v/hd","audio":"https://a/1","expires":9999}}
        """.trimIndent()
        val got = ServerTiers.parse(nullCaption, listOf("hd"), emptySet(), "abc12345678", 100)
        assertEquals("https://v/hd", got?.playable?.videoUrl)
        assertNull(got?.playable?.captionUrl)
    }

    @Test
    fun `the tier the url came from rides along`() {
        val got = ServerTiers.parse(body, listOf("hd"), emptySet(), "abc12345678", 100)
        assertEquals("the 403 handler refuses the rung that actually played", "hd", got?.tier)
    }
}
