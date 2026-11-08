package com.limelight.shell.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuAutoSaveTest {
    private var now = 1000L
    private var live = mapOf<MenuSavedSetting, Map<String, Any>>(
        MenuSavedSetting.UPSCALER to upscalerSaveValues(true, 0, 50, 1),
        MenuSavedSetting.GYRO_SMOOTHING to mapOf("seekbar_gyro_smoothing" to 30),
    )
    private val writes = mutableListOf<Map<String, Any>>()
    private val save = MenuAutoSave(500, { now }, { live.getValue(it) }, { writes += it })

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
            save.changed(MenuSavedSetting.GYRO_SMOOTHING)
            live = live + (MenuSavedSetting.GYRO_SMOOTHING to mapOf("seekbar_gyro_smoothing" to 30 + it * 5))
            now += 100; save.tick()
        }
        assertTrue(writes.isEmpty())
        now += 400; save.tick()
        assertEquals(listOf(mapOf<String, Any>("seekbar_gyro_smoothing" to 75)), writes)
    }

    @Test fun flushWritesPendingChangesAtOnceAndMergesSettings() {
        save.changed(MenuSavedSetting.UPSCALER)
        now += 100
        save.changed(MenuSavedSetting.GYRO_SMOOTHING)
        save.flush()
        assertEquals(1, writes.size)
        assertEquals(UPSCALER_KEYS.toSet() + "seekbar_gyro_smoothing", writes[0].keys)
        assertTrue(writes[0].keys.all { it in GameSettingsStore.KEYS })
        assertNull(save.nextDelay())
    }

    @Test fun onlyTheSettingThatSettledIsWritten() {
        save.changed(MenuSavedSetting.UPSCALER)
        now += 300
        save.changed(MenuSavedSetting.GYRO_SMOOTHING)
        now += 200; save.tick()
        assertEquals(listOf(upscalerSaveValues(true, 0, 50, 1)), writes)
        assertEquals(300L, save.nextDelay())
    }
}
