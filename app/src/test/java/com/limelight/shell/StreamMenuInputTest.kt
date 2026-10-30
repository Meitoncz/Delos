package com.limelight.shell

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun escapeBacksOutOfTheMenuOnReleaseLikeTheControllerBackButtons() {
        for (key in intArrayOf(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B)) {
            assertNull(externalMenuKeyAction(key, KeyEvent.ACTION_DOWN, 0, false))
            assertEquals(ExternalMenuAction.Back, externalMenuKeyAction(key, KeyEvent.ACTION_UP, 0, false))
        }
    }

    @Test
    fun aCancelledReleaseAndKeysTheMenuDoesNotUseTriggerNothing() {
        assertNull(externalMenuKeyAction(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP, 0, true))
        assertNull(externalMenuKeyAction(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, 0, false))
        assertNull(externalMenuKeyAction(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP, 0, false))
    }

    @Test
    fun movesActOnPressAndCarryTheRepeatCount() {
        assertEquals(
            ExternalMenuAction.Move(MenuDirection.LEFT, 3),
            externalMenuKeyAction(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, 3, false),
        )
        assertNull(externalMenuKeyAction(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP, 0, false))
    }
}
