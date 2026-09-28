package com.cliftonia.fs42tv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * fast_guide.json's optional descriptions and pictures, for the picker's details pane: aligned
 * with the programme pairs, blank where the guide had none, and never trusted when misaligned.
 */
class FastGuideProgrammeTest {

    private val base = 1_790_553_600L

    private val text = """
        {"generated": $base, "base": $base,
         "titles": ["", "Terry and June", "Bare"],
         "channels": {"samsung:US1": [0, 1, 30, 0, 60, 2, 90, 0],
                      "samsung:US2": [0, 1, 30, 0],
                      "samsung:US3": [0, 1, 30, 0]},
         "descs": ["", "Terry is jealous."],
         "icons": ["", "https://img.example/a.jpg", "http://insecure.example/b.jpg"],
         "info": {"samsung:US1": [1, 1, 0, 0, 0, 2, 0, 0],
                  "samsung:US2": [1, 1]}}
    """.trimIndent()

    private val guide = FastGuide.parse(text)

    private fun channel(id: String) = Channel(number = 1, name = "T", kind = "live", guide = "samsung", guideId = id)

    private fun at(minutes: Long) = (base + minutes * 60) * 1000

    @Test
    fun `the programme on air carries its description and picture`() {
        assertEquals(FastGuide.Programme("Terry and June", "Terry is jealous.", "https://img.example/a.jpg"),
            guide.programmeAt(channel("US1"), at(10)))
    }

    @Test
    fun `a programme without them, or with a plain-http picture, gets blanks`() {
        assertEquals(FastGuide.Programme("Bare", "", null), guide.programmeAt(channel("US1"), at(70)))
    }

    @Test
    fun `a misaligned info list is ignored and a missing one is titles only`() {
        assertEquals(FastGuide.Programme("Terry and June"), guide.programmeAt(channel("US2"), at(10)))
        assertEquals(FastGuide.Programme("Terry and June"), guide.programmeAt(channel("US3"), at(10)))
    }

    @Test
    fun `holes still say nothing, and titleAt is unchanged`() {
        assertNull(guide.programmeAt(channel("US1"), at(45)))
        assertEquals("Terry and June", guide.titleAt(channel("US1"), at(10)))
    }

    @Test
    fun `a file from before the tables existed reads as before`() {
        val old = FastGuide.parse("""{"generated": $base, "base": $base, "titles": ["", "A"], "channels": {"samsung:US1": [0, 1, 30, 0]}}""")
        assertEquals(FastGuide.Programme("A"), old.programmeAt(channel("US1"), at(1)))
    }
}
