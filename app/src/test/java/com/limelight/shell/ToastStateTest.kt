package com.limelight.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ToastStateTest {
    @Test
    fun replacementWinsAndAnOldExpiryCannotDismissIt() {
        val state = ShellState(Screen.Start)
        state.showToast("First", durationMs = 2000L)
        val first = state.toast!!
        state.showToast("Second", durationMs = 3500L)
        val replacement = state.toast!!

        assertNotEquals(first.id, replacement.id)
        assertEquals("Second", replacement.text)
        assertEquals(3500L, replacement.durationMs)
        state.hideToast(first.id)
        assertSame(replacement, state.toast)
        state.hideToast(replacement.id)
        assertNull(state.toast)
    }

    @Test
    fun repeatingIdenticalMessageStillReplacesAndRestartsItsLifetime() {
        val state = ShellState(Screen.Start)
        state.showToast("Repeated", durationMs = 2000L)
        val firstId = state.toast!!.id
        state.showToast("Repeated", durationMs = 2000L)
        val replacement = state.toast!!

        assertNotEquals(firstId, replacement.id)
        state.hideToast(firstId)
        assertSame(replacement, state.toast)
        assertEquals(2000L, replacement.durationMs)
    }

    @Test
    fun shellDefaultAndUndoDurationsRemainUnchanged() {
        val state = ShellState(Screen.Start)
        state.showToast("Default")
        assertEquals(2600L, state.toast!!.durationMs)
        state.showToast("Undo", action = ToastAction("Undo") {})
        assertEquals(10_000L, state.toast!!.durationMs)
    }
}
