package com.limelight.shell.ui

import com.limelight.shell.Toast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToastStageTest {
    private fun toast(id: Int, ms: Long = 4000L) = Toast("Toast $id", null, id, durationMs = ms)

    @Test
    fun aSecondHostSyncingTheSameToastKeepsItsStartTimeAndSlot() {
        val stage = ToastStage()
        val shown = toast(1)
        stage.sync(shown, 1000L)
        val slot = stage.slots.single()
        assertEquals(1000L, slot.startMs)
        // The other window's host takes over 700 ms later and syncs the same toast: nothing restarts
        stage.sync(shown, 1700L)
        assertSame(slot, stage.slots.single())
        assertEquals(1000L, stage.slots.single().startMs)
        assertEquals(5000L, stage.slots.single().exitMs)
    }

    @Test
    fun aNewToastReplacesAndNoToastDismissesOnTheSharedStage() {
        val stage = ToastStage()
        stage.sync(toast(1), 0L)
        stage.sync(toast(2), 1000L)
        assertEquals(2, stage.slots.size)
        assertEquals(1000L, stage.slots[0].exitMs)
        assertEquals(1000L + 120L, stage.slots[1].startMs)
        stage.sync(null, 1500L)
        assertEquals(1500L, stage.slots[1].exitMs)
        // Syncing "none" again does nothing
        stage.sync(null, 1600L)
        assertEquals(1500L, stage.slots[1].exitMs)
    }

    @Test
    fun theClockAndExpiryAreSharedToo() {
        val stage = ToastStage()
        stage.sync(toast(1, 2000L), 0L)
        val expired = mutableListOf<Int>()
        assertFalse(stage.tick(1999L) { expired += it.payload.id })
        assertEquals(1999L, stage.clock.longValue)
        assertFalse(stage.tick(2000L) { expired += it.payload.id })
        assertEquals(listOf(1), expired)
        assertTrue(stage.tick(2000L + 240L) { expired += it.payload.id })
        assertEquals(listOf(1), expired)
    }

    @Test
    fun aPressCountsOnlyWhenItChangesAndTheFirstLookIsNotOne() {
        val stage = ToastStage()
        assertFalse(stage.pressed(3, 0L)) // the host starts with a state that already counted 3
        stage.sync(toast(1, 10_000L), 0L)
        assertFalse(stage.pressed(3, 100L))
        assertTrue(stage.pressed(4, 200L))
        assertEquals(200L, stage.slots.single().pressMs)
    }

    @Test
    fun theNewestCoveringWindowDrawsTheToastAndTheStreamWindowWhenThereIsNone() {
        val covers = ToastCovers()
        val menu = Any()
        val exit = Any()
        assertNull(covers.top)
        covers.ready(menu)
        assertSame(menu, covers.top)
        covers.ready(exit)
        assertSame(exit, covers.top)
        covers.gone(exit)
        assertSame(menu, covers.top)
        covers.gone(menu)
        assertNull(covers.top)
        covers.gone(menu) // twice is harmless
        assertNull(covers.top)
    }
}
