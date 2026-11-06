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
            "checkbox_multi_touch_gestures", "checkbox_mouse_local_cursor", "import_special_button_file",
            "checkbox_smart_clipboard_sync", "checkbox_enable_pip",
        ).forEach { assertTrue("$it must stay visible", it in visible) }
    }

    @Test fun keyLayoutOnlySettingsAreHiddenAndFullKeyboardSettingsStay() {
        val visible = catalog.visible.map { it.key }.toSet()
        listOf(
            "checkbox_enable_keyboard", "keyboard_axi_list", "checkbox_enable_keyboard_square", "option_reset_osc",
            "import_keyboard_file", "export_keyboard_file",
        ).forEach { assertTrue("$it must be hidden", it !in visible) }
        // Read by KeyBoardLayoutController (the full keyboard), by the stream menu's Send keys, or by both
        listOf(
            "onscreen_keyboard_autofit", "seekbar_onscreen_keyboard_height", "seekbar_onscreen_keyboard_width",
            "list_onscreen_keyboard_align_mode", "seekbar_keyboard_axi_opacity", "checkbox_vibrate_keyboard",
            "checkbox_enable_sticky_modifier_key_virtual_keyboard", "checkbox_enable_clear_default_special_button",
            "option_help_custom_keys",
        ).forEach { assertTrue("$it must stay visible", it in visible) }
    }

    @Test fun noPhoneOnlySectionHeadingOrOrphanedDependencyIsLeft() {
        val categories = catalog.visible.map { it.category }.toSet()
        assertTrue("category_onscreen_controls" !in categories && "category_virtual_trackpad_settings" !in categories)
        val visible = catalog.visible.map { it.key }.toSet()
        assertTrue(catalog.visible.all { it.dependency == null || it.dependency in visible })
    }
}
