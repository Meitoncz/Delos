package com.limelight.shell.settings

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.profiles.ProfilesManager
import com.limelight.shadows.ShadowGameManager
import com.limelight.shadows.ShadowMoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Variable refresh rate row (`list_vrr`): catalog, icon, per-game key, capability rule and the stored value. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en", shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
class VrrSettingTest {
    private lateinit var context: Context
    private lateinit var catalog: SettingsCatalog

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        ProfilesManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ProfilesManager.getInstance().load(context)
        catalog = SettingsCatalog(context)
    }

    @Test fun theRowIsAThreeChoiceListOnTheDisplayTabDefaultingToAuto() {
        val spec = catalog.all.first { it.key == "list_vrr" }
        assertEquals(SettingsTab.DISPLAY, spec.tab)
        assertEquals("auto", spec.default)
        assertEquals(listOf("auto", "on", "off"), spec.choices.map { it.value })
        assertEquals(listOf("Auto", "On", "Off"), spec.choices.map { it.label })
        assertNotNull(settingIcon("list_vrr"))
    }

    @Test fun theRowHasHelpAndIsFoundBySearchWords() {
        val res = context.resources
        assertTrue(SettingsHelp.description(res, "list_vrr", "").isNotBlank())
        listOf("auto", "on", "off").forEach { assertTrue(SettingsHelp.choice(res, "list_vrr", it).isNotBlank()) }
        assertTrue(com.limelight.shell.screens.SearchAliases.terms(res, "list_vrr").contains("adaptive"))
    }

    @Test fun theGameCanOverrideIt() {
        assertTrue("list_vrr" in GameSettingsStore.DISPLAY_KEYS)
        assertTrue("list_vrr" in GameSettingsStore.KEYS)
    }

    @Test fun theRowIsHiddenBelowAndroid11() {
        assertTrue("list_vrr" in SettingsCapabilities.vrrHiddenKeys(29))
        assertFalse("list_vrr" in SettingsCapabilities.vrrHiddenKeys(30))
        assertFalse("list_vrr" in SettingsCapabilities.vrrHiddenKeys(36))
    }

    @Test fun theStoredValueReachesThePreferenceConfiguration() {
        assertEquals("auto", PreferenceConfiguration.readPreferences(context).vrrSetting)
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString("list_vrr", "on").commit()
        assertEquals("on", PreferenceConfiguration.readPreferences(context).vrrSetting)
    }
}
