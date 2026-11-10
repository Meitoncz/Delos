package com.limelight.shell.settings

import android.content.res.Resources
import com.limelight.R
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.preferences.UpscalerMode
import java.util.Locale

/** Reads the same effective values as the settings rows, without depending on the chosen bitrate. */
internal fun pyroWaveRecommendedBitrateKbps(values: Map<String, *>): Int? {
    if (values["video_format"] != "pyrowave") return null
    return PreferenceConfiguration.getDefaultBitrate(
        values["list_resolution"] as? String ?: "1280x720",
        values["list_fps"] as? String ?: "60",
        "pyrowave",
        values["checkbox_enable_hdr"] as? Boolean ?: false,
        values["list_pyrowave_chroma"] == "444",
    )
}

/**
 * Help for the shell. Keys and stored values still come from the preference catalog; the texts are string resources
 * (`strings_settings_help.xml`): `help_<setting key>` describes a setting and `help_<setting key>_<value>` explains one
 * choice, found by name through [lookup] (`res/raw/keep.xml` keeps them from lint). A setting without a text of its own
 * falls back to the summary of the XML catalog. See docs/LOCALIZATION.md.
 *
 * Texts are factual and short: what the setting does, what the selected value means, and the one
 * limit that matters. Settings apply on the next stream unless a text says otherwise.
 *
 * The settings screen's right box is 432 stage px wide and about 302 px tall at the largest sensible
 * interface scale (125%: 27.5 px text, 1.45 line height). The box does not grow with the scale, the
 * text does: about 31 characters by 7 lines. A typical English text uses 4-5 of them, and the dynamic notes
 * are included in the budget. [LINE_CHARS] and [MAX_LINES] describe the English text and are checked by the tests
 * against the base resources only; translations are not held to them and the box scrolls.
 * The per-game screen's smaller box also scrolls for the longest texts.
 */
object SettingsHelp {
    const val LINE_CHARS = 31
    const val MAX_LINES = 7

    fun description(res: Resources, key: String, fallback: String): String = lookup(res, "help_$key") ?: fallback

    /**
     * The selected value, its notes and the dynamic hints form the first paragraph; the setting's own
     * description follows after a blank line.
     */
    fun details(res: Resources, spec: SettingSpec, values: Map<String, *>, choices: List<SettingChoice> = spec.choices): String {
        val value = values[spec.key] ?: spec.default
        val selected = choices.firstOrNull { it.value == value } ?: spec.choices.firstOrNull { it.value == value }
        val explanation = selected?.let { choice(res, spec.key, it.value) }.orEmpty()
        val state = listOfNotNull(
            selected?.takeIf { explanation.isNotBlank() }?.let { "${it.label}\n$explanation" },
            res.getString(R.string.help_note_unavailable).takeIf { selected != null && selected !in choices },
            context(res, spec.key, values).takeIf { it.isNotBlank() },
        ).joinToString("\n")
        return listOf(state, spec.description).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /** Explanations follow the selected value, including resolutions and rates added by the device. */
    fun choice(res: Resources, key: String, value: String): String = lookup(res, "help_${key}_${slug(value)}") ?: when (key) {
        "list_resolution" -> res.getString(R.string.help_dynamic_resolution, value.replace("x", " × "))
        "list_fps" -> res.getString(R.string.help_dynamic_fps, value)
        "list_languages" -> res.getString(R.string.help_dynamic_language)
        "keyboard_axi_list" -> res.getString(R.string.help_dynamic_keyboard_layout)
        else -> ""
    }

    private fun context(res: Resources, key: String, values: Map<String, *>): String {
        val pyro = values["video_format"] == "pyrowave"
        return when {
            key == "seekbar_bitrate_kbps" -> pyroWaveRecommendedBitrateKbps(values)?.let {
                res.getString(R.string.help_note_pyrowave_recommends, it / 1000)
            }.orEmpty()
            key == "frame_pacing" && values["pref_low_latency_frame_balance"] == true && values[key] != "fast-vsync" ->
                res.getString(R.string.help_note_lfr_balanced)
            key == "frame_pacing" && values[key] == "fast-vsync" -> res.getString(R.string.help_note_fast_vsync_lfr)
            key in setOf("checkbox_async_decode", "checkbox_ultra_low_latency", "pref_low_latency_frame_balance") && pyro ->
                res.getString(R.string.help_note_not_with_pyrowave)
            key == "list_pyrowave_chroma" && !pyro -> res.getString(R.string.help_note_needs_pyrowave)
            key == "list_pyrowave_chroma" && values["checkbox_enable_hdr"] == true -> res.getString(R.string.help_note_hdr_420)
            key == "list_sgsr_quality" && pyro -> res.getString(R.string.help_note_pyrowave_modes)
            key == "list_sgsr_supersampling_level" && values["list_sgsr_quality"] == UpscalerMode.SHARPEN_ONLY.value() ->
                res.getString(R.string.help_note_sharpen_only)
            else -> ""
        }
    }
}

/** The part of a resource name that stands for [value]: lower case, anything but letters and digits becomes `_`. */
internal fun slug(value: String): String = value.lowercase(Locale.ROOT).replace(NOT_ALPHANUMERIC, "_").trim('_')

private val NOT_ALPHANUMERIC = Regex("[^a-z0-9]+")

/**
 * The text of the string resource [name] (a `help_*` or `stream_help_*` key), or null when there is none. It goes through
 * the formatter, so a literal percent sign is written `%%` in the XML.
 */
internal fun lookup(res: Resources, name: String, vararg args: Any): String? {
    val id = res.getIdentifier(name, "string", res.getResourcePackageName(R.string.help_dynamic_fps))
    return if (id == 0) null else res.getString(id, *args)
}
