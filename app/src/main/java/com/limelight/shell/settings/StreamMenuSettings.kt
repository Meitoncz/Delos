package com.limelight.shell.settings

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import com.limelight.shell.data.ProfileStore

/**
 * How the Delos stream menu opens: the buttons pressed together (L3 + R3 unless the user chose others)
 * and the swipe up from the bottom edge of the touchscreen. Kept with the profile's settings.
 */
object StreamMenuSettings {
    const val KEY_BUTTONS = "ui_stream_menu_buttons"
    const val KEY_SWIPE = "ui_stream_menu_swipe"
    val DEFAULT_BUTTONS = setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)

    fun buttons(prefs: SharedPreferences): Set<Int> =
        prefs.getString(KEY_BUTTONS, null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BUTTONS

    fun swipe(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_SWIPE, true)

    fun setButtons(prefs: SharedPreferences, buttons: Set<Int>) {
        prefs.edit().putString(KEY_BUTTONS, buttons.sorted().joinToString(",")).apply()
    }

    /** For the stream window: the current profile's choice. */
    @JvmStatic fun buttons(context: Context): Set<Int> = buttons(ProfileStore.currentSettings(context))

    @JvmStatic fun swipe(context: Context): Boolean = swipe(ProfileStore.currentSettings(context))

    /** "L3 + R3", "Start + Select", … */
    fun label(buttons: Set<Int>): String = buttons.sorted().joinToString(" + ") { buttonName(it) }

    fun buttonName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
        KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_START -> "Start"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
        KeyEvent.KEYCODE_BUTTON_MODE -> "Home"
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").removePrefix("BUTTON_").replace('_', ' ')
            .lowercase().replaceFirstChar { it.uppercase() }
    }

    /** Keys that can be part of the shortcut: anything but system keys and the D-pad. */
    fun usable(code: Int): Boolean = code !in setOf(
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_UNKNOWN,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
    )
}
