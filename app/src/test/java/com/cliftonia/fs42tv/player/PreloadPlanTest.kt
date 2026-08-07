package com.cliftonia.fs42tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreloadPlanTest {

    @Test
    fun `the channel behind is reserved even at the smallest useful budget`() {
        val plan = PreloadPlan.forPosition(size = 100, index = 50, budget = 2)
        assertTrue("priming purely forwards made every reversal a cold open on the box - " +
            "5359ms against 350ms once a reverse slot existed, and the reserve matters far " +
            "more at a budget of 2 than at 8",
            plan.contains(49))
    }

    @Test
    fun `the reverse slot outranks the second channel ahead`() {
        val plan = PreloadPlan.forPosition(size = 100, index = 50, budget = 3)
        assertTrue("a reverse slot that gets dropped first is not reserved at all",
            plan.indexOf(49) < plan.indexOf(52))
    }

    @Test
    fun `the channel ahead is still first`() {
        val plan = PreloadPlan.forPosition(size = 100, index = 50, budget = 4)
        assertEquals("surfing forwards is the common case and must stay the cheapest",
            51, plan.first())
    }

    @Test
    fun `the plan wraps at both ends of the dial`() {
        val atEnd = PreloadPlan.forPosition(size = 10, index = 9, budget = 3)
        assertTrue("the dial wraps when surfing, so the neighbours of the last channel " +
            "include the first", atEnd.contains(0))
        val atStart = PreloadPlan.forPosition(size = 10, index = 0, budget = 3)
        assertTrue(atStart.contains(9))
    }

    @Test
    fun `the plan never exceeds the budget`() {
        for (budget in 1..8) {
            assertEquals("the budget is a memory ceiling on a 1.5GB device, not a suggestion",
                budget, PreloadPlan.forPosition(size = 100, index = 50, budget = budget).size)
        }
    }

    @Test
    fun `the plan never includes the channel already playing`() {
        val plan = PreloadPlan.forPosition(size = 100, index = 50, budget = 6)
        assertTrue("a slot spent on what is already on screen is a slot wasted",
            !plan.contains(50))
    }

    @Test
    fun `a dial smaller than the budget yields no duplicates`() {
        // A wrap-around implementation that looks right on a 111-channel dial will happily
        // emit the same index three times on a dial of three, and no other test here would
        // catch it.
        val plan = PreloadPlan.forPosition(size = 3, index = 1, budget = 6)
        assertEquals("wrapping around a short dial must not preload the same channel twice",
            plan.size, plan.toSet().size)
        assertEquals("a dial of three has only two channels that are not the current one",
            2, plan.size)
    }

    @Test
    fun `a dial of one and a budget of zero both yield nothing`() {
        assertEquals(emptyList<Int>(), PreloadPlan.forPosition(size = 1, index = 0, budget = 4))
        assertEquals(emptyList<Int>(), PreloadPlan.forPosition(size = 100, index = 50, budget = 0))
    }

    @Test
    fun `the full order alternates outwards starting ahead`() {
        // Pins the whole sequence, not just its first element: ahead, behind, further ahead,
        // further behind. Any reordering that still happens to satisfy the individual tests
        // above fails here.
        assertEquals(listOf(51, 49, 52, 48, 53, 47),
            PreloadPlan.forPosition(size = 100, index = 50, budget = 6))
    }
}
