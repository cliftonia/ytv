package com.cliftonia.fs42tv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * fast_guide.json read back: the title on air on a FAST channel, found by `<guide>:<guide_id>`
 * and the minute. The answer must be the programme actually on, or nothing - a hole in the
 * listings or the end of them is never the last title again.
 */
class FastGuideTest {

    /** 2026-09-28 00:00 UTC. */
    private val base = 1_790_553_600L

    private val text = """
        {"generated": $base, "base": $base,
         "titles": ["", "Morning Film", "Afternoon Film", "Late Show"],
         "channels": {
           "samsung:US1": [-30, 1, 60, 2, 150, 0, 180, 3, 240, 0],
           "xumo:300#9": [0, 3, 30, 0],
           "roku:bad": [0, 1, 30],
           "plex:backwards": [60, 1, 0, 2, 90, 0]
         }}
    """.trimIndent()

    private val guide = FastGuide.parse(text)

    private fun channel(guide: String?, id: String?) =
        Channel(number = 100, name = "Test", kind = "live", guide = guide, guideId = id)

    private fun at(minutes: Long) = (base + minutes * 60) * 1000

    @Test
    fun `the title on air is found at any minute of it`() {
        val us1 = channel("samsung", "US1")
        assertEquals("Morning Film", guide.titleAt(us1, at(0)))
        assertEquals("Morning Film", guide.titleAt(us1, at(59)))
        assertEquals("Afternoon Film", guide.titleAt(us1, at(60)))
        assertEquals("Late Show", guide.titleAt(us1, at(200)))
    }

    @Test
    fun `before the listings, in a hole and after them there is no title`() {
        val us1 = channel("samsung", "US1")
        assertNull(guide.titleAt(us1, at(-31)))
        assertNull(guide.titleAt(us1, at(160)))
        assertNull(guide.titleAt(us1, at(240)))
        assertNull(guide.titleAt(us1, at(10_000)))
    }

    @Test
    fun `a seconds-precise now inside the first minute still counts`() {
        assertEquals("Late Show", guide.titleAt(channel("xumo", "300#9"), (base + 59) * 1000))
    }

    @Test
    fun `the key is the guide and its id, for the services the file carries`() {
        assertEquals("xumo:300#9", FastGuide.keyOf(channel("xumo", "300#9")))
        assertEquals("tubi:400000012", FastGuide.keyOf(channel("tubi", "400000012")))
        assertEquals("rakuten:sci-fi-rakuten-tv", FastGuide.keyOf(channel("rakuten", "sci-fi-rakuten-tv")))
        assertEquals("stirr:5294", FastGuide.keyOf(channel("stirr", "5294")))
        assertNull(FastGuide.keyOf(channel("none", "x")))
        assertNull(FastGuide.keyOf(channel("pluto", "abc")))
        assertNull(FastGuide.keyOf(channel("samsung", null)))
        assertNull(FastGuide.keyOf(channel(null, null)))
    }

    @Test
    fun `a channel with no entry, a malformed list or one running backwards answers nothing`() {
        assertNull(guide.titleAt(channel("samsung", "NOPE"), at(0)))
        assertNull(guide.titleAt(channel("roku", "bad"), at(0)))
        assertNull(guide.titleAt(channel("plex", "backwards"), at(70)))
        assertEquals(2, guide.size)
    }

    @Test(expected = Exception::class)
    fun `something that is not a guide is refused, so it is never cached`() {
        FastGuide.parse("<html>captive portal</html>")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty object is refused too`() {
        FastGuide.parse("{}")
    }
}
