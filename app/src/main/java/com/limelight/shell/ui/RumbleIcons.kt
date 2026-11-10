package com.limelight.shell.ui

import com.limelight.utils.RumblePresets

/** The icon of a rumble feel, one per preset so the Debug laboratory and the stream menu list never repeat a glyph. */
fun RumblePresets.icon(): String = when (this) {
    RumblePresets.ORIGINAL -> Symbols.PRESET_ORIGINAL
    RumblePresets.GENTLE -> Symbols.PRESET_SOFT
    RumblePresets.KICK_ONLY -> Symbols.PRESET_KICK
    RumblePresets.PULSE_25 -> Symbols.PRESET_PULSING
}
