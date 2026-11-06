package com.limelight.shell

import com.limelight.shell.ui.Symbols
import com.limelight.utils.ToastKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun theKindComesFromTheCallerThenTheIconAndAnActionIsAlwaysUndo() {
        val state = ShellState(Screen.Start)
        state.showToast("Plain")
        assertEquals(ToastKind.INFO, state.toast!!.kind)
        state.showToast("Failed", Symbols.WIFI_OFF)
        assertEquals(ToastKind.ERROR, state.toast!!.kind)
        state.showToast("Reset", Symbols.RESTART_ALT, kind = ToastKind.SUCCESS)
        assertEquals(ToastKind.SUCCESS, state.toast!!.kind)
        state.showToast("Deleted", Symbols.DELETE, ToastAction("Undo") {})
        assertEquals(ToastKind.ACTION, state.toast!!.kind)
    }

    @Test
    fun runningTheActionClearsTheToastAndCountsAPress() {
        val state = ShellState(Screen.Start)
        var ran = 0
        state.showToast("Deleted", Symbols.DELETE, ToastAction("Undo") { ran++ })
        assertEquals(0, state.toastPress)
        assertTrue(state.runToastAction())
        assertEquals(1, ran)
        assertEquals(1, state.toastPress)
        assertNull(state.toast)
        assertFalse(state.runToastAction())
        assertEquals(1, state.toastPress)
    }

    @Test
    fun shellDefaultAndUndoDurationsRemainUnchanged() {
        val state = ShellState(Screen.Start)
        state.showToast("Default")
        assertEquals(4500L, state.toast!!.durationMs)
        state.showToast("Undo", action = ToastAction("Undo") {})
        assertEquals(10_000L, state.toast!!.durationMs)
    }
}
