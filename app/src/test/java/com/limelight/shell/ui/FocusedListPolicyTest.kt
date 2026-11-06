package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusedListPolicyTest {
    @Test
    fun aHeldUpSequenceReachesTheFirstRowAndNeverExits() {
        var index = 27
        repeat(40) { repeatCount ->
            val move = focusedListMove(index, 40, -1, repeatCount)
            assertEquals(if (index > 0) FocusedListMove.PREVIOUS else FocusedListMove.STOP, move)
            if (move == FocusedListMove.PREVIOUS) index--
        }
        assertEquals(0, index)
    }

    @Test
    fun onlyANewUpAlreadyAtTheTrueFirstRowMayExit() {
        assertEquals(FocusedListMove.EXIT_UP, focusedListMove(0, 40, -1, 0))
        assertEquals(FocusedListMove.STOP, focusedListMove(0, 40, -1, 1))
        assertEquals(FocusedListMove.PREVIOUS, focusedListMove(1, 40, -1, 0))
        assertEquals(FocusedListMove.PREVIOUS, focusedListMove(1, 40, -1, 80))
        assertEquals(FocusedListMove.EXIT_UP, focusedListMove(0, 1, -1, 0))
    }

    @Test
    fun closedMenusKeepEvenFreshInputAtBothEndpoints() {
        for (repeat in listOf(0, 1, 40)) {
            assertEquals(FocusedListMove.STOP, focusedListMove(0, 3, -1, repeat, allowUpExit = false))
            assertEquals(FocusedListMove.STOP, focusedListMove(2, 3, 1, repeat, allowUpExit = false))
            assertEquals(FocusedListMove.NEXT, focusedListMove(0, 3, 1, repeat, allowUpExit = false))
            assertEquals(FocusedListMove.PREVIOUS, focusedListMove(2, 3, -1, repeat, allowUpExit = false))
        }
    }

    @Test
    fun downStopsAtTheLastRowForBothInitialAndRepeatedInput() {
        assertEquals(FocusedListMove.NEXT, focusedListMove(38, 40, 1, 0))
        assertEquals(FocusedListMove.NEXT, focusedListMove(38, 40, 1, 50))
        assertEquals(FocusedListMove.STOP, focusedListMove(39, 40, 1, 0))
        assertEquals(FocusedListMove.STOP, focusedListMove(39, 40, 1, 50))
        assertEquals(FocusedListMove.STOP, focusedListMove(0, 1, 1, 0))
    }

    @Test
    fun anUnknownOrRemovedRowCannotExitTheList() {
        assertEquals(FocusedListMove.STOP, focusedListMove(-1, 40, -1, 0))
        assertEquals(FocusedListMove.STOP, focusedListMove(40, 40, -1, 0))
        assertEquals(FocusedListMove.STOP, focusedListMove(0, 0, -1, 0))
        assertEquals(FocusedListMove.STOP, focusedListMove(0, 40, 0, 0))
    }

    @Test
    fun collapsingAdvancedRowsReturnsToTheSurvivingAdvancedToggle() {
        val expanded = listOf("device", "advanced", "gain", "curve", "controller")
        val collapsed = listOf("device", "advanced", "controller")
        assertEquals("advanced", retainedFocusedListKey(expanded, collapsed, "curve"))
        assertEquals("controller", retainedFocusedListKey(expanded, collapsed, "controller"))
        assertEquals("controller", retainedFocusedListKey(expanded, listOf("controller"), "gain"))
        assertNull(retainedFocusedListKey(expanded, emptyList(), "gain"))
        assertNull(retainedFocusedListKey(expanded, collapsed, null))
    }
}
