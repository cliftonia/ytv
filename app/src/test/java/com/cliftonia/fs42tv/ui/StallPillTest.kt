package com.cliftonia.fs42tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stall pill, and a break card standing in front of it. Posts run when [fire] says. */
class StallPillTest {

    private var pending = mutableListOf<() -> Unit>()
    private var covered = false
    private val pill = StallPill(
        post = { _, block -> pending += block },
        cancel = { pending.clear() },
        halted = { false },
        covered = { covered },
    )

    private fun fire() {
        val due = pending.toList()
        pending.clear()
        due.forEach { it() }
    }

    @Test
    fun `a stall shows the pill after the wait, and its end takes it down`() {
        pill.buffering(true)
        assertFalse(pill.showing.value)
        fire()
        assertTrue(pill.showing.value)
        pill.buffering(false)
        assertFalse(pill.showing.value)
    }

    @Test
    fun `a stall that clears before the wait is never shown`() {
        pill.buffering(true)
        pill.buffering(false)
        fire()
        assertFalse(pill.showing.value)
    }

    @Test
    fun `no pill over a break card, but a stall still going gets one when the card comes down`() {
        pill.buffering(true)
        covered = true
        pill.cover()
        fire()
        assertFalse(pill.showing.value)
        covered = false
        pill.uncover()
        fire()
        assertTrue(pill.showing.value)
    }

    @Test
    fun `a stall that began under the card gets its pill after the card`() {
        covered = true
        pill.cover()
        pill.buffering(true)
        fire()
        assertFalse(pill.showing.value)
        covered = false
        pill.uncover()
        fire()
        assertTrue(pill.showing.value)
    }

    @Test
    fun `a stall that ended under the card leaves nothing to show`() {
        pill.buffering(true)
        covered = true
        pill.cover()
        pill.buffering(false)
        covered = false
        pill.uncover()
        fire()
        assertFalse(pill.showing.value)
    }

    @Test
    fun `a new tune forgets the stall`() {
        pill.buffering(true)
        pill.clear()
        pill.uncover()
        fire()
        assertFalse(pill.showing.value)
    }
}
