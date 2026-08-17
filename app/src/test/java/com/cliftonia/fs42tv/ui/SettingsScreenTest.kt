package com.cliftonia.fs42tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Moving the highlight between rows that OK can actually do something with.
 *
 * The readings - version, display modes, how old the lineup is - are not controls. Landing the
 * highlight on one and pressing OK does nothing, which on a television reads as a broken remote
 * rather than as a deliberate non-control, so the highlight steps over them entirely.
 */
class SettingsScreenTest {

    /** Rows 0 and 2 are controls; 1, 3 and 4 are readings. */
    private val actionable = listOf(0, 2)

    @Test
    fun `down skips the readings between two controls`() {
        assertEquals(2, nextActionable(actionable, current = 0, forward = true))
    }

    @Test
    fun `up skips them the other way`() {
        assertEquals(0, nextActionable(actionable, current = 2, forward = false))
    }

    @Test
    fun `it wraps at the bottom`() {
        // A list this short is faster to wrap than to stop at. Stopping would also need an
        // affordance saying why, and there is no room on a settings screen for that.
        assertEquals(0, nextActionable(actionable, current = 2, forward = true))
    }

    @Test
    fun `it wraps at the top`() {
        assertEquals(2, nextActionable(actionable, current = 0, forward = false))
    }

    @Test
    fun `a highlight sitting on a reading is pulled back to a control`() {
        // Possible if the rows are rebuilt while the screen is open - which they are, every time
        // the engine is toggled. Without this the highlight would be stranded on a row that does
        // nothing and neither direction would rescue it.
        assertEquals(0, nextActionable(actionable, current = 3, forward = true))
        assertEquals(0, nextActionable(actionable, current = 3, forward = false))
    }

    @Test
    fun `a list with no controls at all leaves the highlight alone`() {
        assertEquals(7, nextActionable(emptyList(), current = 7, forward = true))
    }

    @Test
    fun `a single control is its own neighbour in both directions`() {
        assertEquals(4, nextActionable(listOf(4), current = 4, forward = true))
        assertEquals(4, nextActionable(listOf(4), current = 4, forward = false))
    }
}
