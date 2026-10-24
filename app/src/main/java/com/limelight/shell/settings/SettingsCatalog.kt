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
    COVERS("Covers", Symbols.PHOTO_LIBRARY, "Game artwork from your PC. SteamGridDB support comes in the next step."),
    DEVICES("Devices", Symbols.COMPUTER, "Paired PCs and what happens when Delos starts."),
    ABOUT("About", Symbols.INFO, "Delos is a fork of Artemis and Moonlight, licensed under the GPLv3."),
}

enum class SettingKind { TOGGLE, CHOICE, SLIDER, TEXT, ACTION }

data class SettingChoice(val label: String, val value: String)

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
        "list_resolution", "edit_diy_w_h" -> Symbols.ASPECT_RATIO
        "list_fps", "custom_refresh_rate" -> Symbols.SPEED
        "seekbar_bitrate_kbps", "seekbar_metered_bitrate_kbps", "edit_diy_bitrate" -> Symbols.NETWORK_CHECK
        "frame_pacing" -> Symbols.TIMER
        "video_format" -> Symbols.MOVIE
        "checkbox_enable_hdr" -> Symbols.HDR_ON
        "checkbox_ultra_low_latency", "pref_low_latency_frame_balance" -> Symbols.BOLT
        else -> when (category) {
            "category_gamepad_settings" -> if (key.contains("vibrat") || key.contains("rumble")) Symbols.VIBRATION else Symbols.SPORTS_ESPORTS
            "category_input_settings", "category_virtual_trackpad_settings" -> Symbols.MOUSE
            "category_special_key_layout" -> Symbols.KEYBOARD
            "category_onscreen_controls" -> Symbols.JOYSTICK
            "category_perf_monitor_settings" -> Symbols.MONITORING
            else -> tab.icon
        }
    }

    fun matches(query: String): Boolean = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
        .all { word -> "$label $description $section ${tab.label} $key".lowercase(Locale.ROOT).contains(word) }
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
                key, text(attrs, "title"), text(attrs, "summary").ifEmpty { text(attrs, "dialogMessage") },
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
                    "checkbox_auto_orientation", "checkbox_auto_invert_video_resolution", "checkbox_enable_view_top_center",
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
