package com.limelight.shell.settings

import android.content.Context
import android.content.res.Resources
import android.os.LocaleList
import android.util.AttributeSet
import android.util.Xml
import androidx.annotation.StringRes
import com.limelight.R
import com.limelight.shell.ui.Symbols
import org.xmlpull.v1.XmlPullParser

/** The prototype's eight tabs. The classic categories remain as section headings within them. */
enum class SettingsTab(@StringRes val labelRes: Int, val icon: String, @StringRes val descriptionRes: Int) {
    STREAM(R.string.settings_tab_stream, Symbols.CAST, R.string.settings_tab_stream_description),
    DISPLAY(R.string.settings_tab_display, Symbols.MONITOR, R.string.settings_tab_display_description),
    AUDIO(R.string.settings_tab_audio, Symbols.GRAPHIC_EQ, R.string.settings_tab_audio_description),
    CONTROLS(R.string.settings_tab_controls, Symbols.SPORTS_ESPORTS, R.string.settings_tab_controls_description),
    APPEARANCE(R.string.settings_tab_appearance, Symbols.APPEARANCE, R.string.settings_tab_appearance_description),
    COVERS(R.string.settings_tab_covers, Symbols.PHOTO_LIBRARY, R.string.settings_tab_covers_description),
    DEVICES(R.string.settings_tab_devices, Symbols.DESKTOP_WINDOWS, R.string.settings_tab_devices_description),
    ABOUT(R.string.settings_tab_about, Symbols.INFO, R.string.settings_tab_about_description);

    fun label(res: Resources): String = res.getString(labelRes)

    fun description(res: Resources): String = res.getString(descriptionRes)
}

enum class SettingKind { TOGGLE, CHOICE, SLIDER, TEXT, ACTION }

data class SettingChoice(val label: String, val value: String)

/** Keep an unavailable stored choice visible rather than displaying a different value as selected. */
fun SettingSpec.choiceLabel(res: Resources, value: Any?, available: List<SettingChoice>): String =
    available.firstOrNull { it.value == value }?.label
        ?: res.getString(R.string.settings_choice_unavailable, choices.firstOrNull { it.value == value }?.label ?: value.toString())

fun nextSettingChoice(value: Any?, available: List<SettingChoice>, direction: Int): SettingChoice? {
    if (available.isEmpty()) return null
    val current = available.indexOfFirst { it.value == value }
    val next = if (current < 0) { if (direction >= 0) 0 else available.lastIndex }
        else Math.floorMod(current + direction, available.size)
    return available[next]
}

data class SettingSpec(
    val key: String,
    val label: String,
    val description: String,
    val category: String,
    val section: String,
    val tab: SettingsTab,
    val kind: SettingKind,
    val default: Any?,
    val choices: List<SettingChoice> = emptyList(),
    val min: Int = 0,
    val max: Int = 100,
    val step: Int = 1,
    val divisor: Int = 1,
    val unit: String = "",
    val dependency: String? = null,
    val url: String? = null,
) {
    val icon: String get() = settingIcon(key) ?: tab.icon
}

/**
 * The icon of a settings row. Every row of the XML catalogue has its own entry here (the rule is tested by
 * `SettingIconsTest`); a glyph is shared only by rows with the same meaning. See docs/ICON_AUDIT.md.
 */
fun settingIcon(key: String): String? = when (key) {
    "list_resolution" -> Symbols.RESOLUTION
    "edit_diy_w_h" -> Symbols.CUSTOM_RESOLUTION
    "list_fps" -> Symbols.FRAME_RATE
    "checkbox_unlock_fps" -> Symbols.UNLOCK_FPS
    "custom_refresh_rate" -> Symbols.CUSTOM_REFRESH
    "seekbar_bitrate_kbps" -> Symbols.NETWORK_CHECK
    "edit_diy_bitrate" -> Symbols.MANUAL_BITRATE
    "seekbar_metered_bitrate_kbps" -> Symbols.METERED_NETWORK
    "frame_pacing" -> Symbols.FRAME_PACING
    "checkbox_async_decode" -> Symbols.ASYNC_DECODE
    "checkbox_sgsr" -> Symbols.UPSCALER
    "list_sgsr_quality" -> Symbols.UPSCALER_MODE
    "seekbar_sgsr_sharpness" -> Symbols.SHARPNESS
    "list_sgsr_supersampling_level" -> Symbols.HIGH_QUALITY
    "video_format" -> Symbols.CODEC
    "list_pyrowave_chroma" -> Symbols.CHROMA
    "checkbox_enable_hdr" -> Symbols.HDR_ON
    "checkbox_ultra_low_latency" -> Symbols.TIMER
    "pref_low_latency_frame_balance" -> Symbols.FRAME_BALANCE
    "checkbox_use_virtual_display" -> Symbols.SCREEN_SHARE
    "checkbox_auto_orientation" -> Symbols.SCREEN_ROTATION
    "checkbox_auto_invert_video_resolution", "checkbox_trackpad_swap_axis" -> Symbols.AXIS_SWAP
    "checkbox_enable_view_top_center" -> Symbols.DISPLAY_TOP
    "seekbar_resolution_scale_factor" -> Symbols.SCALE
    "list_video_scale_mode" -> Symbols.SCALE_MODE
    "list_vrr" -> Symbols.CUSTOM_REFRESH
    "checkbox_enforce_display_mode" -> Symbols.DISPLAY_MODE
    "checkbox_enable_fullexdisplay" -> Symbols.EXTERNAL_DISPLAY
    "checkbox_reduce_refresh_rate" -> Symbols.REDUCE_REFRESH
    "checkbox_full_range" -> Symbols.FULL_RANGE
    "checkbox_prevent_packet_loss" -> Symbols.WIFI_KEEPALIVE
    "list_audio_config" -> Symbols.SURROUND_SOUND
    "checkbox_enable_audiofx" -> Symbols.EQUALIZER
    "checkbox_host_audio" -> Symbols.SPEAKER
    "seekbar_deadzone" -> Symbols.STICK_DEADZONE
    "checkbox_multi_controller" -> Symbols.GAMEPAD_DETECT
    "checkbox_usb_driver" -> Symbols.USB
    "checkbox_usb_bind_all" -> Symbols.USB_OVERRIDE
    "checkbox_mouse_emulation" -> Symbols.MOUSE
    "analog_scrolling" -> Symbols.ANALOG_SCROLL
    "checkbox_flip_face_buttons" -> Symbols.GAMEPAD_BUTTONS
    "checkbox_gamepad_touchpad_as_mouse" -> Symbols.TOUCHPAD
    "checkbox_gamepad_motion_sensors" -> Symbols.MOTION_SENSORS
    "checkbox_gamepad_motion_fallback" -> Symbols.MOTION_FALLBACK
    "checkbox_force_device_motion" -> Symbols.DEVICE_MOTION
    "seekbar_gyro_smoothing" -> Symbols.SMOOTHING
    "checkbox_enable_joyconfix" -> Symbols.JOYCON_FIX
    "checkbox_gamepad_enable_battery_report" -> Symbols.BATTERY_FULL
    "checkbox_enable_rumble" -> Symbols.VIBRATION
    "list_rumble_feel" -> Symbols.PRESET
    "checkbox_vibrate_fallback" -> Symbols.DEVICE_VIBRATION
    "checkbox_vibrate_osc" -> Symbols.OSC_HAPTICS
    "checkbox_vibrate_keyboard" -> Symbols.KEYBOARD_HAPTICS
    "checkbox_enable_device_rumble" -> Symbols.DEVICE_FORCE
    "seekbar_vibrate_fallback_strength" -> Symbols.STRENGTH
    "mouse_mode_list" -> Symbols.TOUCH_APP
    "checkbox_enable_touch_sensitivity" -> Symbols.TOUCH_SENSITIVITY
    "checkbox_remember_mouse_mode", "checkbox_remember_zoom_pan" -> Symbols.REMEMBER
    "checkbox_mouse_local_cursor" -> Symbols.CURSOR
    "checkbox_absolute_mouse_mode" -> Symbols.ABSOLUTE_MOUSE
    "checkbox_multi_touch_gestures" -> Symbols.TOUCH_GESTURES
    "checkbox_mouse_nav_buttons" -> Symbols.MOUSE_NAV_BUTTONS
    "seekbar_trackpad_sensitivity_x" -> Symbols.AXIS_X
    "seekbar_touch_sensitivity_opacity_x" -> Symbols.TOUCH_AXIS_X
    "seekbar_touchpad_sensitivity_opacity" -> Symbols.TOUCHPAD_AXIS_X
    "seekbar_trackpad_sensitivity_y" -> Symbols.AXIS_Y
    "seekbar_touch_sensitivity_opacity_y" -> Symbols.TOUCH_AXIS_Y
    "seekbar_touchpad_sensitivity_y_opacity" -> Symbols.TOUCHPAD_AXIS_Y
    "checkbox_trackpad_drag_drop_vibration" -> Symbols.DRAG_DROP
    "seekbar_trackpad_drag_drop_threshold" -> Symbols.HOLD_DURATION
    "checkbox_force_qwerty" -> Symbols.FORCE_QWERTY
    "keyboard_axi_list" -> Symbols.KEY_LAYOUT
    "checkbox_right_alt_as_meta" -> Symbols.KEYBOARD_COMMAND_KEY
    "checkbox_ignore_synth_events" -> Symbols.SYNTHETIC_INPUT
    "checkbox_enable_commit_text" -> Symbols.TEXT_INPUT
    "checkbox_smart_clipboard_sync" -> Symbols.CLIPBOARD_SYNC
    "checkbox_smart_clipboard_sync_toast" -> Symbols.NOTIFICATIONS
    "checkbox_hide_clipboard_content" -> Symbols.CLIPBOARD_PRIVACY
    "seekbar_keyboard_axi_opacity", "seekbar_osc_opacity", "seekbar_osc_free_analog_stick_opacity" -> Symbols.OPACITY
    "onscreen_keyboard_autofit" -> Symbols.FIT_SCREEN
    "seekbar_onscreen_keyboard_height" -> Symbols.HEIGHT
    "seekbar_onscreen_keyboard_width" -> Symbols.WIDTH
    "list_onscreen_keyboard_align_mode" -> Symbols.ALIGNMENT
    "checkbox_enable_sticky_modifier_key_virtual_keyboard" -> Symbols.STICKY_KEYS
    "checkbox_disable_warnings" -> Symbols.WARNING
    "checkbox_enable_pip" -> Symbols.PICTURE_IN_PICTURE
    "checkbox_show_onscreen_controls" -> Symbols.JOYSTICK
    "checkbox_hide_osc_when_has_gamepad" -> Symbols.VISIBILITY_OFF
    "checkbox_only_show_L3R3" -> Symbols.BUTTON_L3R3
    "checkbox_show_guide_button" -> Symbols.GUIDE_BUTTON
    "checkbox_enable_analog_stick_new" -> Symbols.FREE_STICK
    "option_reset_osc_preference", "option_reset_osc" -> Symbols.RESET
    "checkbox_enable_keyboard" -> Symbols.KEYBOARD
    "checkbox_enable_clear_default_special_button" -> Symbols.CLEAR_DEFAULTS
    "checkbox_enable_keyboard_square" -> Symbols.SQUARE_BUTTONS
    "import_keyboard_file", "import_special_button_file" -> Symbols.IMPORT
    "export_keyboard_file" -> Symbols.EXPORT
    "checkbox_enable_global_touch_sensitivity" -> Symbols.PUBLIC
    "checkbox_enable_touch_sensitivity_rotation_auto" -> Symbols.AUTO_SENSITIVITY
    "checkbox_enable_perf_overlay" -> Symbols.OVERLAY
    "checkbox_enable_perf_overlay_lite" -> Symbols.OVERLAY_COMPACT
    "list_languages" -> Symbols.LANGUAGE
    "option_help_custom_keys" -> Symbols.HELP
    "pref_debug_info" -> Symbols.DEBUG
    else -> null
}

/**
 * Reads the existing XML rather than maintaining another list of keys, types, ranges and choices. Titles, summaries,
 * entries and dialog messages come from the legacy string resources in the language of the resources the rows were built
 * from. The language can change while the app runs: a screen that shows labels calls [refresh] with its resources
 * (`textResources()`) before it reads [all] or [visible], and the rows are rebuilt when the locale differs. Keys, kinds,
 * defaults and ranges do not depend on the language, so stores may keep using the catalog without refreshing it.
 * No Preference views are inflated.
 */
class SettingsCatalog(context: Context) {
    private class Rows(val locales: LocaleList, val all: List<SettingSpec>, val visible: List<SettingSpec>)

    @Volatile private var rows = build(context.resources)

    /** Every row of the XML catalogue, in the language of the last build. */
    val all: List<SettingSpec> get() = rows.all

    /** What the settings list and search show: [all] without the [PhoneOnlySettings] items. */
    val visible: List<SettingSpec> get() = rows.visible

    /** Rebuilds the rows for the language of [resources] when it differs from the one they were built for. */
    fun refresh(resources: Resources) {
        if (rows.locales != resources.configuration.locales) rows = build(resources)
    }

    private fun build(resources: Resources): Rows {
        val all = read(resources)
        return Rows(resources.configuration.locales, all, all.filterNot { PhoneOnlySettings.isHidden(it.key) })
    }

    private fun read(resources: Resources): List<SettingSpec> {
        fun text(attrs: AttributeSet, name: String): String {
            val id = attrs.getAttributeResourceValue(ANDROID, name, 0)
            return if (id != 0) resources.getText(id).toString() else attrs.getAttributeValue(ANDROID, name) ?: ""
        }

        fun array(attrs: AttributeSet, name: String): List<String> {
            val id = attrs.getAttributeResourceValue(ANDROID, name, 0)
            return if (id != 0) resources.getStringArray(id).toList() else emptyList()
        }

        return resources.getXml(R.xml.preferences).use { parser ->
            val specs = mutableListOf<SettingSpec>()
            var category = ""
            var section = ""
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                val attrs = Xml.asAttributeSet(parser)
                if (parser.name == "PreferenceCategory") {
                    category = attrs.getAttributeValue(ANDROID, "key") ?: attrs.getAttributeResourceValue(ANDROID, "title", 0)
                        .takeIf { it != 0 }?.let(resources::getResourceEntryName) ?: ""
                    section = text(attrs, "title")
                    continue
                }
                if (parser.name == "PreferenceScreen") continue
                val key = attrs.getAttributeValue(ANDROID, "key") ?: continue
                val kind = when {
                    parser.name.endsWith("CheckBoxPreference", ignoreCase = true) -> SettingKind.TOGGLE
                    parser.name.endsWith("ListPreference") || parser.name.endsWith("LanguagePreference") -> SettingKind.CHOICE
                    parser.name.endsWith("SeekBarPreference") -> SettingKind.SLIDER
                    parser.name.endsWith("EditTextPreference") -> SettingKind.TEXT
                    else -> SettingKind.ACTION
                }
                val defaultText = attrs.getAttributeValue(ANDROID, "defaultValue")
                val default: Any? = when (kind) {
                    SettingKind.TOGGLE -> defaultText?.toBoolean() ?: false
                    SettingKind.SLIDER -> defaultText?.toIntOrNull()
                    SettingKind.CHOICE, SettingKind.TEXT -> defaultText ?: ""
                    SettingKind.ACTION -> null
                }
                val labels = array(attrs, "entries")
                val values = array(attrs, "entryValues")
                val tab = tabFor(category, key)
                specs += SettingSpec(
                    key, text(attrs, "title"), SettingsHelp.description(resources, key, text(attrs, "summary").ifEmpty { text(attrs, "dialogMessage") }),
                    category, section, tab, kind, default,
                    values.mapIndexed { i, value -> SettingChoice(labels.getOrElse(i) { value }, value) },
                    attrs.getAttributeIntValue(SEEKBAR, "min", 1), attrs.getAttributeIntValue(ANDROID, "max", 100),
                    attrs.getAttributeIntValue(SEEKBAR, "step", 1),
                    attrs.getAttributeIntValue(SEEKBAR, "divisor", 1), text(attrs, "text").let { if (it.length > 1 && !it.startsWith(" ")) " $it" else it },
                    attrs.getAttributeValue(ANDROID, "dependency"),
                    attrs.getAttributeValue(null, "url")?.let { raw ->
                        val id = attrs.getAttributeResourceValue(null, "url", 0)
                        if (id != 0) resources.getString(id) else raw
                    },
                )
            }
            specs.toList()
        }
    }

    companion object {
        private const val ANDROID = "http://schemas.android.com/apk/res/android"
        private const val SEEKBAR = "http://schemas.moonlight-stream.com/apk/res/seekbar"

        fun tabFor(category: String, key: String): SettingsTab = when {
            key == "checkbox_host_audio" || category == "category_audio_settings" -> SettingsTab.AUDIO
            key == "checkbox_enable_hdr" || key == "checkbox_use_virtual_display" -> SettingsTab.DISPLAY
            category == "category_video_settings" -> if (key in setOf(
                    "checkbox_sgsr", "list_sgsr_quality", "seekbar_sgsr_sharpness", "list_sgsr_supersampling_level", "checkbox_auto_orientation", "checkbox_auto_invert_video_resolution", "checkbox_enable_view_top_center",
                    "seekbar_resolution_scale_factor", "list_video_scale_mode", "list_vrr", "checkbox_enforce_display_mode", "checkbox_enable_fullexdisplay",
                )) SettingsTab.DISPLAY else SettingsTab.STREAM
            category == "category_advanced_settings" -> if (key in setOf("checkbox_full_range", "checkbox_reduce_refresh_rate")) SettingsTab.DISPLAY else SettingsTab.STREAM
            category == "category_perf_monitor_settings" -> SettingsTab.DISPLAY
            category in setOf("category_gamepad_settings", "category_input_settings", "category_general_settings",
                "category_onscreen_controls", "category_special_key_layout", "category_virtual_trackpad_settings") -> SettingsTab.CONTROLS
            key.contains("keyboard") || key == "list_onscreen_keyboard_align_mode" || key == "import_special_button_file" || key == "option_help_custom_keys" -> SettingsTab.CONTROLS
            category == "category_ui_settings" -> SettingsTab.APPEARANCE
            else -> SettingsTab.ABOUT
        }
    }
}
