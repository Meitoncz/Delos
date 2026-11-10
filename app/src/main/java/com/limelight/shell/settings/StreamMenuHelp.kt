package com.limelight.shell.settings

import android.content.res.Resources
import com.limelight.R
import com.limelight.utils.RumblePresets

/**
 * Help for actions that are available only while a stream is running. The keys are the identifiers the menu items use
 * (their English names, never shown); the texts are the `stream_help_<slug of the key>` string resources.
 */
object StreamMenuHelp {
    /** The help key of a preset row on the live rumble feel page. */
    fun rumbleKey(preset: RumblePresets): String = "Rumble: ${preset.label}" // l10n-ignore: help key, never shown

    /** The preset a help key belongs to, or null. */
    fun rumblePreset(key: String): RumblePresets? = RumblePresets.values().firstOrNull { rumbleKey(it) == key }

    /** Labels of key combinations and commands come from the user and the host, so the prefixes are tested before any lookup by name. */
    fun description(res: Resources, label: String): String = when {
        label.startsWith("Keys: ") -> res.getString(R.string.stream_help_format_keys, label.removePrefix("Keys: "))
        label.startsWith("Command: ") -> res.getString(R.string.stream_help_format_command,
            label.removePrefix("Command: ").let { if (it.length > 36) it.take(35) + "\u2026" else it })
        // Rumble feel page: the same explanation as the setting's choice
        label.startsWith("Rumble: ") -> rumblePreset(label)?.let { SettingsHelp.choice(res, "list_rumble_feel", it.name) }.orEmpty()
        else -> lookup(res, "stream_help_${slug(label)}").orEmpty()
    }
}
