package com.limelight.shell.settings

import android.content.res.Resources
import android.view.KeyEvent
import com.limelight.R
import com.limelight.shell.MenuKey
import com.limelight.shell.MoreRow
import com.limelight.utils.RumblePresets

/**
 * Help for actions that are available only while a stream is running. The keys are the identifiers the menu items use
 * (`MenuKey` / `MoreRow` in `StreamMenuLayout.kt`; English words that are never shown); the texts are the
 * `stream_help_<slug of the key>` string resources. A sentence that mentions pad buttons takes their names as arguments
 * (see [buttonArgs]), so a translator places them.
 */
object StreamMenuHelp {
    /** The help key of a preset row on the live rumble feel page. */
    fun rumbleKey(preset: RumblePresets): String = "Rumble: ${preset.name}" // l10n-ignore: help key, never shown

    /** The preset a help key belongs to, or null. */
    fun rumblePreset(key: String): RumblePresets? = RumblePresets.values().firstOrNull { rumbleKey(it) == key }

    /** Labels of key combinations and commands come from the user and the host, so the prefixes are tested before any lookup by name. */
    fun description(res: Resources, label: String): String = when {
        label.startsWith("Keys: ") -> res.getString(R.string.stream_help_format_keys, label.removePrefix("Keys: "))
        label.startsWith("Command: ") -> res.getString(R.string.stream_help_format_command,
            label.removePrefix("Command: ").let { if (it.length > 36) it.take(35) + "\u2026" else it })
        // Rumble feel page: the same explanation as the setting's choice
        label.startsWith("Rumble: ") -> rumblePreset(label)?.let { SettingsHelp.choice(res, "list_rumble_feel", it.name) }.orEmpty()
        else -> lookup(res, "stream_help_${slug(label)}", *buttonArgs(res, label)).orEmpty()
    }

    /**
     * The button names a help sentence mentions, in the order of its placeholders: the Keyboard help says "A types, B closes"
     * (`%1$s` = A, `%2$s` = B), the Rumble feel help "B goes back" (`%1$s` = B).
     */
    private fun buttonArgs(res: Resources, key: String): Array<Any> {
        fun name(code: Int) = StreamMenuSettings.buttonName(res, code)
        return when (key) {
            MenuKey.KEYBOARD -> arrayOf(name(KeyEvent.KEYCODE_BUTTON_A), name(KeyEvent.KEYCODE_BUTTON_B))
            MoreRow.RUMBLE -> arrayOf(name(KeyEvent.KEYCODE_BUTTON_B))
            else -> emptyArray()
        }
    }
}
