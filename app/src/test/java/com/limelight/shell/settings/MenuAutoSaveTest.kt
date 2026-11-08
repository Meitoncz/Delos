package com.limelight.shell.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuAutoSaveTest {
    private var now = 1000L
    private var sharpness = 50
    private val writes = mutableListOf<Map<String, Any>>()
    private val save = MenuAutoSave(500, { now }, { upscalerSaveValues(true, 0, sharpness, 1) }, { writes += it })

    @Test fun openingTheMenuWritesNothing() {
        assertNull(save.nextDelay())
        save.tick(); save.flush()
        assertTrue(writes.isEmpty())
    }

    @Test fun aSettledChangeIsWrittenOnceWithTheLiveValueAfterTheDelay() {
        save.changed(MenuSavedSetting.UPSCALER)
        assertEquals(500L, save.nextDelay())
        now += 499; save.tick()
        assertTrue(writes.isEmpty())
        now += 1; save.tick()
        assertEquals(listOf(upscalerSaveValues(true, 0, 50, 1)), writes)
        assertNull(save.nextDelay())
        now += 5000; save.tick(); save.flush()
        assertEquals(1, writes.size)
    }

    @Test fun aDragKeepsPostponingAndWritesTheLastValueOnce() {
        repeat(10) {
            save.changed(MenuSavedSetting.UPSCALER)
            sharpness = 50 + it * 5
            now += 100; save.tick()
        }
        assertTrue(writes.isEmpty())
        now += 400; save.tick()
        assertEquals(listOf(upscalerSaveValues(true, 0, 95, 1)), writes)
    }

    @Test fun flushWritesPendingChangesAtOnceOnlyWithKeysOfTheStore() {
        save.changed(MenuSavedSetting.UPSCALER)
        save.flush()
        assertEquals(1, writes.size)
        assertEquals(UPSCALER_KEYS.toSet(), writes[0].keys)
        assertTrue(writes[0].keys.all { it in GameSettingsStore.KEYS })
        assertNull(save.nextDelay())
    }
}
