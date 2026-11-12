package com.limelight.shell.settings

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.limelight.R
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.profiles.ProfilesManager
import com.limelight.shadows.ShadowGameManager
import com.limelight.shadows.ShadowMoonBridge
import com.limelight.shell.ui.Symbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The optional "Fix gyro handling and vibrations" row (docs/CONTROLLER_STATE.md). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en", shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
class GyroStateRefreshSettingTest {
    private lateinit var context: Context
    private lateinit var catalog: SettingsCatalog
    private val key = "checkbox_gyro_state_refresh"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        ProfilesManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ProfilesManager.getInstance().load(context)
        catalog = SettingsCatalog(context)
    }

    private fun spec() = catalog.all.first { it.key == key }

    @Test fun itIsAToggleOnTheControlsTabThatIsOffByDefaultRightAfterGyroSmoothing() {
        val s = spec()
        assertEquals(SettingKind.TOGGLE, s.kind)
        assertEquals(false, s.default)
        assertEquals(SettingsTab.CONTROLS, s.tab)
        assertEquals("Fix gyro handling and vibrations", s.label)
        val order = catalog.all.map { it.key }
        assertEquals(order.indexOf("seekbar_gyro_smoothing") + 1, order.indexOf(key))
        assertEquals(catalog.all.first { it.key == "seekbar_gyro_smoothing" }.section, s.section)
    }

    @Test fun itsHelpSaysHonestlyThatItIsAWorkaroundForAHostProblem() {
        val help = SettingsHelp.description(context.resources, key, "")
        assertEquals("Works around a host problem where, with gyro on, the game keeps vibrating and keeps aiming with gyro " +
            "after a button is released, until you move a stick. Turn it off when the host is fixed.", help)
        assertEquals(help, spec().description)
    }

    @Test fun theStoredValueIsReadAndDefaultsToOff() {
        assertFalse(PreferenceConfiguration.readPreferences(context).gyroStateRefresh)
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(key, true).commit()
        assertTrue(PreferenceConfiguration.readPreferences(context).gyroStateRefresh)
        assertEquals(key, PreferenceConfiguration.GYRO_STATE_REFRESH_PREF_STRING)
    }

    @Test fun itIsNotAPerGameSetting() {
        assertFalse(key in GameSettingsStore.KEYS)
    }

    @Test fun itKeepsTheRefreshGlyphAndIsFoundByWhatItFixes() {
        assertEquals(Symbols.REFRESH, spec().icon)
        val items = catalog.all.map {
            com.limelight.shell.screens.SettingsItem(it.key, it.tab, it.section, it.icon, it.label, it.description,
                com.limelight.shell.ui.RowValue.Info(""), searchTerms = com.limelight.shell.screens.settingSearchTerms(context.resources, it))
        }
        fun search(query: String) = com.limelight.shell.screens.searchSettings(context.resources, items, query).map { it.key }
        assertTrue(key in search("fix gyro"))
        assertTrue(key in search("vibrations"))
        assertTrue(key in search("stuck vibration"))
    }

    @Test fun theRowIsHiddenOnlyWhereThereIsNoMotionSourceAtAll() {
        assertTrue(key in SettingsCapabilities.motionHiddenKeys(30, hasDeviceSensors = false))
        assertFalse(key in SettingsCapabilities.motionHiddenKeys(30, hasDeviceSensors = true))
        assertFalse(key in SettingsCapabilities.motionHiddenKeys(33, hasDeviceSensors = false))
    }

    @Test fun everyLanguageHasTheTitleTheSummaryAndTheHelp() {
        // The base text of the three resources exists, the translation tests check the other nine languages against them
        assertTrue(context.getString(R.string.title_checkbox_gyro_state_refresh).isNotBlank())
        assertTrue(context.getString(R.string.summary_checkbox_gyro_state_refresh).isNotBlank())
        assertTrue(context.getString(R.string.help_checkbox_gyro_state_refresh).isNotBlank())
    }
}
