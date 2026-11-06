package com.limelight.shell.settings

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.limelight.profiles.ProfilesManager
import com.limelight.shadows.ShadowGameManager
import com.limelight.shadows.ShadowMoonBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowMoonBridge::class, ShadowGameManager::class])
class PhoneOnlySettingsTest {
    private lateinit var catalog: SettingsCatalog

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        ProfilesManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        ProfilesManager.getInstance().load(context)
        catalog = SettingsCatalog(context)
    }

    @Test fun hiddenKeysAreAbsentFromTheVisibleItemsButStayInTheCatalog() {
        assertTrue(PhoneOnlySettings.HIDDEN)
        val all = catalog.all.map { it.key }.toSet()
        val visible = catalog.visible.map { it.key }.toSet()
        assertTrue("every hidden key must exist in the XML", all.containsAll(PhoneOnlySettings.KEYS))
        assertTrue("hidden keys leaked into the visible items: ${visible intersect PhoneOnlySettings.KEYS}",
            visible.none { it in PhoneOnlySettings.KEYS })
        assertEquals(all - PhoneOnlySettings.KEYS, visible)
    }

    @Test fun handheldRelevantItemsRemainVisible() {
        val visible = catalog.visible.map { it.key }.toSet()
        listOf(
            "checkbox_multi_controller", "seekbar_deadzone", "checkbox_enable_rumble", "checkbox_enable_device_rumble",
            "checkbox_multi_touch_gestures", "checkbox_mouse_local_cursor", "checkbox_enable_keyboard", "keyboard_axi_list",
            "option_reset_osc", "import_keyboard_file", "export_keyboard_file", "import_special_button_file",
            "checkbox_smart_clipboard_sync", "checkbox_enable_pip",
        ).forEach { assertTrue("$it must stay visible", it in visible) }
    }

    @Test fun noPhoneOnlySectionHeadingOrOrphanedDependencyIsLeft() {
        val categories = catalog.visible.map { it.category }.toSet()
        assertTrue("category_onscreen_controls" !in categories && "category_virtual_trackpad_settings" !in categories)
        val visible = catalog.visible.map { it.key }.toSet()
        assertTrue(catalog.visible.all { it.dependency == null || it.dependency in visible })
    }
}
