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

    @Test
    fun onlyAGainRightAfterALossIsAMoveBetweenButtons() {
        val ms = 1_000_000L
        val lost = 5_000L * ms
        assertTrue(TransientPillMotion.isMove(lost, lost))
        assertTrue(TransientPillMotion.isMove(lost, lost + 30 * ms))
        // Focus that comes in from elsewhere, long after the last loss, or with no loss at all, shows no pill
        assertFalse(TransientPillMotion.isMove(lost, lost + 500 * ms))
        assertFalse(TransientPillMotion.isMove(TransientPillMotion.NONE, lost))
        // A gain before the loss cannot be its follow-up
        assertFalse(TransientPillMotion.isMove(lost, lost - ms))
    }

    @Test
    fun thePillTakesTheFocusedButtonsBoundsScaledAboutItsCentre() {
        val rest = androidx.compose.ui.geometry.Rect(100f, 20f, 164f, 84f)
        val focused = TransientPillMotion.scaled(rest, 1.12f)
        assertEquals(64f * 1.12f, focused.width, .001f)
        assertEquals(64f * 1.12f, focused.height, .001f)
        assertEquals(rest.center.x, focused.center.x, .001f)
        assertEquals(rest.center.y, focused.center.y, .001f)
    }
}
