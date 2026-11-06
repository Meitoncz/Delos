package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowGlideMotionTest {
    @Test
    fun glidesOnlyFromARingThatIsStillShown() {
        assertFalse("first focus into the list appears in place", RowGlideMotion.glides(0f, reduced = false))
        assertFalse("a ring that has mostly faded has no place to glide from", RowGlideMotion.glides(RowGlideMotion.VISIBLE_FROM - .01f, reduced = false))
        assertTrue(RowGlideMotion.glides(RowGlideMotion.VISIBLE_FROM, reduced = false))
        assertTrue(RowGlideMotion.glides(1f, reduced = false))
        assertFalse("reduced motion jumps", RowGlideMotion.glides(1f, reduced = true))
    }

    @Test
    fun ringStartsWhereItWasAndArrivesExactlyOnTheRow() {
        val from = 120f
        val row = 330f
        val carry = RowGlideMotion.carry(from, row)
        assertEquals(from, RowGlideMotion.edge(row, carry, 0f), 0f)
        assertEquals(row, RowGlideMotion.edge(row, carry, 1f), 0f)
        // Halfway the ring is between the old place and the row; an overshooting ease goes a little past the row
        assertEquals((from + row) / 2f, RowGlideMotion.edge(row, carry, .5f), .001f)
        assertTrue(RowGlideMotion.edge(row, carry, 1.04f) > row)
    }

    @Test
    fun scrollingMovesTheRingWithTheRowItHeadsFor() {
        val carry = RowGlideMotion.carry(from = 120f, edge = 330f)
        for (progress in listOf(0f, .3f, .8f, 1f)) {
            val before = RowGlideMotion.edge(330f, carry, progress)
            // The list scrolls 75 px up while the glide runs: the whole ring moves with it, so it never lags behind or drifts off
            val after = RowGlideMotion.edge(330f - 75f, carry, progress)
            assertEquals(before - 75f, after, .001f)
        }
        // At rest, however far the list has scrolled, the ring lies exactly on the row
        assertEquals(40f, RowGlideMotion.edge(40f, carry, 1f), 0f)
    }

    @Test
    fun ringIsGrownAroundItsCenter() {
        assertEquals(100f, RowGlideMotion.grownNear(100f, 300f, 1f), 0f)
        assertEquals(300f, RowGlideMotion.grownFar(100f, 300f, 1f), 0f)
        assertEquals(98f, RowGlideMotion.grownNear(100f, 300f, 1.02f), .001f)
        assertEquals(302f, RowGlideMotion.grownFar(100f, 300f, 1.02f), .001f)
    }
}
