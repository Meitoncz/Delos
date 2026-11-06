package com.limelight.shell.settings

/**
 * Delos is handheld-first, so two groups of settings are hidden from the settings list and search:
 * settings that only matter on a phone's touchscreen (touch mouse modes, trackpad and touch sensitivity,
 * the on-screen gamepad; issue #65), and the settings that exist only for the key-layout overlay
 * (`KeyBoardController`, the custom special-key layout that the stream menu no longer offers). Only the UI
 * hides them: the XML entries, stored values, defaults and everything that reads them
 * (`PreferenceConfiguration`, `Game`, profiles, the stream menu) are untouched, so existing values keep
 * applying. [SettingsCatalog.all] still lists every key; [SettingsCatalog.visible] is what the settings
 * screens show.
 *
 * To bring the items back, set [HIDDEN] to `false` (or delete single keys from [KEYS]). Section headings
 * are derived from the visible items, so a heading disappears when all of its items are hidden.
 */
object PhoneOnlySettings {
    const val HIDDEN = true

    val KEYS: Set<String> = setOf(
        // Touch mouse
        "mouse_mode_list",
        "checkbox_remember_mouse_mode",
        "checkbox_remember_zoom_pan",
        "checkbox_absolute_mouse_mode",
        "seekbar_trackpad_sensitivity_x",
        "seekbar_trackpad_sensitivity_y",
        "checkbox_trackpad_drag_drop_vibration",
        "seekbar_trackpad_drag_drop_threshold",
        "checkbox_trackpad_swap_axis",
        "checkbox_enable_touch_sensitivity",
        "checkbox_enable_global_touch_sensitivity",
        "checkbox_enable_touch_sensitivity_rotation_auto",
        "seekbar_touch_sensitivity_opacity_x",
        "seekbar_touch_sensitivity_opacity_y",
        "seekbar_touchpad_sensitivity_opacity",
        "seekbar_touchpad_sensitivity_y_opacity",
        // On-screen gamepad. checkbox_vibrate_osc depends on checkbox_show_onscreen_controls, so it goes
        // with it.
        "checkbox_show_onscreen_controls",
        "checkbox_hide_osc_when_has_gamepad",
        "checkbox_vibrate_osc",
        "checkbox_only_show_L3R3",
        "checkbox_show_guide_button",
        "seekbar_osc_opacity",
        "checkbox_enable_analog_stick_new",
        "seekbar_osc_free_analog_stick_opacity",
        "option_reset_osc_preference",
        // Key-layout overlay (KeyBoardController) only. option_reset_osc resets that layout, not the
        // gamepad. Kept visible because other code reads them: checkbox_vibrate_keyboard (full keyboard),
        // seekbar_keyboard_axi_opacity (full keyboard), sticky modifiers, and import_special_button_file /
        // checkbox_enable_clear_default_special_button / option_help_custom_keys (stream menu Send keys).
        "checkbox_enable_keyboard",
        "keyboard_axi_list",
        "checkbox_enable_keyboard_square",
        "option_reset_osc",
        "import_keyboard_file",
        "export_keyboard_file",
    )

    fun isHidden(key: String): Boolean = HIDDEN && key in KEYS
}
