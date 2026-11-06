package com.limelight.shell.settings

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.util.Xml
import com.limelight.R
import com.limelight.shell.ui.Symbols
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/** The prototype's eight tabs. The classic categories remain as section headings within them. */
enum class SettingsTab(val label: String, val icon: String, val description: String) {
    STREAM("Stream", Symbols.CAST, "Quality and latency of the video coming from your PC."),
    DISPLAY("Display", Symbols.MONITOR, "How the stream fills the screen and which information is shown while you play."),
    AUDIO("Audio", Symbols.GRAPHIC_EQ, "Sound from the PC and the sounds of the Delos interface."),
    CONTROLS("Controls", Symbols.SPORTS_ESPORTS, "Gamepads, mouse, touch, keyboard and on-screen controls."),
    APPEARANCE("Appearance", Symbols.PALETTE, "Theme, colors and the animated background. Changes apply immediately."),
    COVERS("Covers", Symbols.PHOTO_LIBRARY, "Covers, wide covers, backgrounds and logos from your PC or SteamGridDB."),
    DEVICES("Devices", Symbols.COMPUTER, "Paired PCs and what happens when Delos starts."),
    ABOUT("About", Symbols.INFO, "Delos is a fork of Artemis and Moonlight, licensed under the GPLv3."),
}

enum class SettingKind { TOGGLE, CHOICE, SLIDER, TEXT, ACTION }

data class SettingChoice(val label: String, val value: String)

/** Keep an unavailable stored choice visible rather than displaying a different value as selected. */
fun SettingSpec.choiceLabel(value: Any?, available: List<SettingChoice>): String =
    available.firstOrNull { it.value == value }?.label
        ?: "${choices.firstOrNull { it.value == value }?.label ?: value.toString()} (unavailable)"

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
    val icon: String get() = when (key) {
        "list_resolution" -> Symbols.ASPECT_RATIO
        "edit_diy_w_h" -> Symbols.CUSTOM_RESOLUTION
        "list_fps", "checkbox_unlock_fps" -> Symbols.SPEED
        "custom_refresh_rate" -> Symbols.CUSTOM_REFRESH
        "seekbar_bitrate_kbps", "edit_diy_bitrate" -> Symbols.NETWORK_CHECK
        "seekbar_metered_bitrate_kbps" -> Symbols.METERED_NETWORK
        "frame_pacing" -> Symbols.TIMER
        "checkbox_async_decode" -> Symbols.ASYNC_DECODE
        "checkbox_sgsr" -> Symbols.UPSCALER
        "list_sgsr_quality" -> Symbols.UPSCALER_MODE
        "seekbar_sgsr_sharpness" -> Symbols.SHARPNESS
        "list_sgsr_supersampling_level" -> Symbols.HIGH_QUALITY
        "video_format" -> Symbols.MOVIE
        "list_pyrowave_chroma" -> Symbols.PALETTE
        "checkbox_enable_hdr" -> Symbols.HDR_ON
        "checkbox_ultra_low_latency" -> Symbols.BOLT
        "pref_low_latency_frame_balance" -> Symbols.TUNE
        "checkbox_use_virtual_display" -> Symbols.SCREEN_SHARE
        "checkbox_auto_orientation" -> Symbols.SCREEN_ROTATION
        "checkbox_auto_invert_video_resolution", "checkbox_trackpad_swap_axis" -> Symbols.AXIS_SWAP
        "checkbox_enable_view_top_center" -> Symbols.DISPLAY_TOP
        "seekbar_resolution_scale_factor" -> Symbols.SCALE
        "list_video_scale_mode" -> Symbols.SCALE_MODE
        "checkbox_enforce_display_mode" -> Symbols.DISPLAY_MODE
        "checkbox_enable_fullexdisplay" -> Symbols.EXTERNAL_DISPLAY
        "checkbox_reduce_refresh_rate" -> Symbols.SCHEDULE
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
        "checkbox_enable_joyconfix" -> Symbols.SPORTS_ESPORTS
        "checkbox_gamepad_enable_battery_report" -> Symbols.BATTERY_FULL
        "checkbox_enable_rumble", "list_rumble_feel", "checkbox_vibrate_fallback", "checkbox_vibrate_osc", "checkbox_vibrate_keyboard" -> Symbols.VIBRATION
        "checkbox_enable_device_rumble" -> Symbols.MOBILE_VIBRATE
        "seekbar_vibrate_fallback_strength" -> Symbols.GRAPHIC_EQ
        "mouse_mode_list", "checkbox_enable_touch_sensitivity" -> Symbols.TOUCH_APP
        "checkbox_remember_mouse_mode", "checkbox_remember_zoom_pan" -> Symbols.REMEMBER
        "checkbox_mouse_local_cursor", "checkbox_absolute_mouse_mode" -> Symbols.CURSOR
        "checkbox_multi_touch_gestures" -> Symbols.TOUCH_GESTURES
        "checkbox_mouse_nav_buttons" -> Symbols.SWAP_HORIZ
        "seekbar_trackpad_sensitivity_x", "seekbar_touch_sensitivity_opacity_x", "seekbar_touchpad_sensitivity_opacity" -> Symbols.AXIS_X
        "seekbar_trackpad_sensitivity_y", "seekbar_touch_sensitivity_opacity_y", "seekbar_touchpad_sensitivity_y_opacity" -> Symbols.AXIS_Y
        "checkbox_trackpad_drag_drop_vibration" -> Symbols.DRAG_DROP
        "seekbar_trackpad_drag_drop_threshold" -> Symbols.HOLD_DURATION
        "checkbox_force_qwerty", "keyboard_axi_list" -> Symbols.KEY_LAYOUT
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
        "checkbox_only_show_L3R3" -> Symbols.STICK_DEADZONE
        "checkbox_show_guide_button" -> Symbols.HOME
        "checkbox_enable_analog_stick_new" -> Symbols.FREE_STICK
        "option_reset_osc_preference", "option_reset_osc" -> Symbols.RESET
        "checkbox_enable_keyboard" -> Symbols.KEYBOARD
        "checkbox_enable_clear_default_special_button" -> Symbols.KEY
        "checkbox_enable_keyboard_square" -> Symbols.SQUARE
        "import_keyboard_file", "import_special_button_file" -> Symbols.IMPORT
        "export_keyboard_file" -> Symbols.EXPORT
        "checkbox_enable_global_touch_sensitivity" -> Symbols.PUBLIC
        "checkbox_enable_touch_sensitivity_rotation_auto" -> Symbols.STICK_DEADZONE
        "checkbox_enable_perf_overlay" -> Symbols.OVERLAY
        "checkbox_enable_perf_overlay_lite" -> Symbols.OVERLAY_COMPACT
        "list_languages" -> Symbols.LANGUAGE
        "option_help_custom_keys" -> Symbols.HELP
        "pref_debug_info" -> Symbols.DEBUG
        else -> when (category) {
            "category_gamepad_settings" -> if (key.contains("vibrat") || key.contains("rumble")) Symbols.VIBRATION else Symbols.SPORTS_ESPORTS
            "category_input_settings", "category_virtual_trackpad_settings" -> Symbols.MOUSE
            "category_special_key_layout" -> Symbols.KEYBOARD
            "category_onscreen_controls" -> Symbols.JOYSTICK
            "category_perf_monitor_settings" -> Symbols.MONITORING
            else -> tab.icon
        }
    }
}

/**
 * Reads the existing XML rather than maintaining another list of keys, types, ranges and choices.
 * English resources are used while the new shell is English-only. No Preference views are inflated.
 */
class SettingsCatalog(context: Context) {
    private val resources = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocale(Locale.ENGLISH)
    }).resources

    val all: List<SettingSpec> = resources.getXml(R.xml.preferences).use { parser ->
        val rows = mutableListOf<SettingSpec>()
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
            rows += SettingSpec(
                key, text(attrs, "title"), SettingsHelp.description(key, text(attrs, "summary").ifEmpty { text(attrs, "dialogMessage") }),
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
        rows.toList()
    }

    /** What the settings list and search show: [all] without the [PhoneOnlySettings] items. */
    val visible: List<SettingSpec> = all.filterNot { PhoneOnlySettings.isHidden(it.key) }

    private fun text(attrs: AttributeSet, name: String): String {
        val id = attrs.getAttributeResourceValue(ANDROID, name, 0)
        return if (id != 0) resources.getText(id).toString() else attrs.getAttributeValue(ANDROID, name) ?: ""
    }

    private fun array(attrs: AttributeSet, name: String): List<String> {
        val id = attrs.getAttributeResourceValue(ANDROID, name, 0)
        return if (id != 0) resources.getStringArray(id).toList() else emptyList()
    }

    companion object {
        private const val ANDROID = "http://schemas.android.com/apk/res/android"
        private const val SEEKBAR = "http://schemas.moonlight-stream.com/apk/res/seekbar"

        fun tabFor(category: String, key: String): SettingsTab = when {
            key == "checkbox_host_audio" || category == "category_audio_settings" -> SettingsTab.AUDIO
            key == "checkbox_enable_hdr" || key == "checkbox_use_virtual_display" -> SettingsTab.DISPLAY
            category == "category_video_settings" -> if (key in setOf(
                    "checkbox_sgsr", "list_sgsr_quality", "seekbar_sgsr_sharpness", "list_sgsr_supersampling_level", "checkbox_auto_orientation", "checkbox_auto_invert_video_resolution", "checkbox_enable_view_top_center",
                    "seekbar_resolution_scale_factor", "list_video_scale_mode", "checkbox_enforce_display_mode", "checkbox_enable_fullexdisplay",
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
