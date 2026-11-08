package com.limelight.shell.settings

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.profiles.ProfilesManager
import com.limelight.shadows.ShadowGameManager
import com.limelight.shadows.ShadowMoonBridge
import com.limelight.shell.screens.SettingsItem
import com.limelight.shell.screens.searchSettings
import com.limelight.shell.screens.settingSearchTerms
import com.limelight.shell.ui.RowValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
class GyroSmoothingSettingTest {
    private lateinit var context: Context
    private lateinit var catalog: SettingsCatalog
    private val key = "seekbar_gyro_smoothing"
    private val motionKeys = listOf("checkbox_gamepad_motion_sensors", "checkbox_gamepad_motion_fallback", "checkbox_force_device_motion", key)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        ProfilesManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ProfilesManager.getInstance().load(context)
        catalog = SettingsCatalog(context)
    }

    private fun spec(k: String) = catalog.all.first { it.key == k }

    private fun search(query: String) = searchSettings(catalog.all.map {
        SettingsItem(it.key, it.tab, it.section, it.icon, it.label, it.description, RowValue.Info(""), searchTerms = settingSearchTerms(it))
    }, query).map { it.key }

    @Test fun theSliderExistsWithDefaultOffNextToTheOtherMotionRows() {
        val s = spec(key)
        assertEquals(SettingKind.SLIDER, s.kind)
        assertEquals(0, s.default)
        assertEquals(0, s.min)
        assertEquals(100, s.max)
        assertEquals("Gyro smoothing", s.label)
        assertEquals(SettingsTab.CONTROLS, s.tab)
        assertEquals(spec("checkbox_force_device_motion").section, s.section)
        val order = catalog.all.map { it.key }
        assertEquals(motionKeys, order.filter { it in motionKeys })
        assertEquals(order.indexOf("checkbox_force_device_motion") + 1, order.indexOf(key))
    }

    @Test fun theStoredValueIsReadAndClampedAndDefaultsToOff() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertEquals(0, PreferenceConfiguration.readPreferences(context).gyroSmoothing)
        prefs.edit().putInt(key, 60).commit()
        assertEquals(60, PreferenceConfiguration.readPreferences(context).gyroSmoothing)
        prefs.edit().putInt(key, 400).commit()
        assertEquals(100, PreferenceConfiguration.readPreferences(context).gyroSmoothing)
    }

    @Test fun itFollowsTheMotionRowsWhichAreNotPerGameSettings() {
        assertTrue(motionKeys.none { it in GameSettingsStore.KEYS })
        assertNull(impact(key, mapOf(key to 50)))
    }

    @Test fun theRowIsHiddenOnlyWhenNeitherMotionSourceExists() {
        assertTrue(key in SettingsCapabilities.motionHiddenKeys(30, hasDeviceSensors = false))
        assertFalse(key in SettingsCapabilities.motionHiddenKeys(30, hasDeviceSensors = true))
        assertFalse(key in SettingsCapabilities.motionHiddenKeys(33, hasDeviceSensors = false))
        assertEquals(motionKeys.toSet(), SettingsCapabilities.motionHiddenKeys(30, hasDeviceSensors = false))
        assertTrue("checkbox_gamepad_motion_fallback" !in SettingsCapabilities.motionHiddenKeys(33, hasDeviceSensors = true))
    }

    @Test fun theRowIsDisabledWhileNoMotionSourceIsOn() {
        assertTrue(gyroSourceEnabled(33, emptyMap<String, Any>()))
        assertFalse(gyroSourceEnabled(33, mapOf("checkbox_gamepad_motion_sensors" to false)))
        assertTrue(gyroSourceEnabled(33, mapOf("checkbox_gamepad_motion_sensors" to false, "checkbox_gamepad_motion_fallback" to true)))
        assertFalse(gyroSourceEnabled(30, mapOf("checkbox_gamepad_motion_sensors" to true)))
        assertTrue(gyroSourceEnabled(30, mapOf("checkbox_gamepad_motion_fallback" to true)))
        assertFalse(gyroSourceEnabled(31, emptyMap<String, Any>()))
    }

    @Test fun theMotionRowsHaveTheirUnderstandableNamesAndTheOldNamesStillFindThem() {
        assertEquals("External gamepad gyroscope", spec("checkbox_gamepad_motion_sensors").label)
        assertEquals("Built-in device gyroscope", spec("checkbox_gamepad_motion_fallback").label)
        assertEquals("Always use built-in device gyroscope", spec("checkbox_force_device_motion").label)
        assertTrue("checkbox_gamepad_motion_sensors" in search("allow use of gamepad motion sensors"))
        assertTrue("checkbox_gamepad_motion_fallback" in search("emulate gamepad motion"))
        assertTrue("checkbox_force_device_motion" in search("always use device's motion sensors"))
        assertTrue(listOf("checkbox_gamepad_motion_sensors", "checkbox_gamepad_motion_fallback", "checkbox_force_device_motion", key)
            .all { it in search("motion sensors") })
        assertTrue(key in search("gyro smoothing"))
    }
}
