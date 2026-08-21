package com.cliftonia.fs42tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Moving the highlight through the settings list.
 *
 * It visits every row except a separator, INCLUDING the read-only readings. It used to visit only
 * rows OK could act on, on the reasoning that a highlight which does nothing reads as a broken
 * remote - and that was the wrong way round. The list scrolls to follow the highlight, so rows it
 * never visited could never be scrolled to: the diagnostics were on the screen and unreachable.
 */
class SettingsScreenTest {

    /** Every row but the separator. Readings are selectable; only separators are stepped over. */
    private val selectable = listOf(0, 2)

    @Test
    fun `down moves to the next selectable row`() {
        assertEquals(2, nextSelectable(selectable, current = 0, forward = true))
    }

    @Test
    fun `up moves the other way`() {
        assertEquals(0, nextSelectable(selectable, current = 2, forward = false))
    }

    @Test
    fun `it wraps at the bottom`() {
        // A list this short is faster to wrap than to stop at. Stopping would also need an
        // affordance saying why, and there is no room on a settings screen for that.
        assertEquals(0, nextSelectable(selectable, current = 2, forward = true))
    }

    @Test
    fun `it wraps at the top`() {
        assertEquals(2, nextSelectable(selectable, current = 0, forward = false))
    }

    @Test
    fun `a highlight sitting outside the list is pulled back into it`() {
        // Possible if the rows are rebuilt while the screen is open - which they are, every time
        // the engine is toggled. Without this the highlight would be stranded on a row that does
        // nothing and neither direction would rescue it.
        assertEquals(0, nextSelectable(selectable, current = 3, forward = true))
        assertEquals(0, nextSelectable(selectable, current = 3, forward = false))
    }

    @Test
    fun `a list with no controls at all leaves the highlight alone`() {
        assertEquals(7, nextSelectable(emptyList(), current = 7, forward = true))
    }

    @Test
    fun `a single control is its own neighbour in both directions`() {
        assertEquals(4, nextSelectable(listOf(4), current = 4, forward = true))
        assertEquals(4, nextSelectable(listOf(4), current = 4, forward = false))
    }

    @Test
    fun `a reading is selectable, because the list scrolls to follow the highlight`() {
        // The bug this replaced: the highlight visited only rows OK could act on, and since the
        // list scrolls to whatever is selected, every read-only row below them was unreachable.
        // The diagnostics were on the screen and could not be got to.
        val rows = listOf(
            SettingRow("CONTROL", "on") {},
            SettingRow("--- DIAGNOSTICS ---", ""),
            SettingRow("A READING", "42"),
        )
        val selectable = rows.indices.filter { !rows[it].label.startsWith("---") }
        assertEquals("the reading must be reachable", listOf(0, 2), selectable)
        assertEquals(2, nextSelectable(selectable, 0, forward = true))
    }
}
