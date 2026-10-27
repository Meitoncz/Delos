package com.limelight.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMenuInputTest {
    @Test
    fun analogNavigationMovesOnceThenRepeatsAtTheConfiguredRate() {
        val input = DirectionalRepeater(initialDelayMs = 300, repeatIntervalMs = 100)

        assertTrue(input.shouldMove(MenuDirection.RIGHT, 1_000))
        assertEquals(0, input.repeatCount)
        assertFalse(input.shouldMove(MenuDirection.RIGHT, 1_299))
        assertTrue(input.shouldMove(MenuDirection.RIGHT, 1_300))
        assertEquals(1, input.repeatCount)
        assertFalse(input.shouldMove(MenuDirection.RIGHT, 1_399))
        assertTrue(input.shouldMove(MenuDirection.RIGHT, 1_400))
        assertEquals(2, input.repeatCount)
    }

    @Test
    fun neutralAndDirectionChangesResetAnalogRepeatState() {
        val input = DirectionalRepeater(initialDelayMs = 300, repeatIntervalMs = 100)

        assertTrue(input.shouldMove(MenuDirection.DOWN, 1_000))
        assertFalse(input.shouldMove(null, 1_050))
        assertTrue(input.shouldMove(MenuDirection.DOWN, 1_060))
        assertEquals(0, input.repeatCount)
        assertTrue(input.shouldMove(MenuDirection.LEFT, 1_070))
        assertEquals(0, input.repeatCount)
    }
}
