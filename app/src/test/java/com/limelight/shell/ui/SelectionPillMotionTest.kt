package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionPillMotionTest {
    @Test
    fun directionFollowsTheCentre() {
        assertTrue(SelectionPillMotion.travelsRight(0f, 100f, 120f, 260f))
        assertFalse(SelectionPillMotion.travelsRight(120f, 260f, 0f, 100f))
        // A wider pill whose left edge moves left but whose centre moves right still travels right
        assertTrue(SelectionPillMotion.travelsRight(100f, 150f, 90f, 300f))
    }

    @Test
    fun theLeadingEdgeIsStifferThanTheGeometrySpringAndTheTrailingOneSofter() {
        assertEquals(Motion.GEO_STIFFNESS * 1.5f, SelectionPillMotion.stiffness(true), 0f)
        assertEquals(Motion.GEO_STIFFNESS * .75f, SelectionPillMotion.stiffness(false), 0f)
    }
}
