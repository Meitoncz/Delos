package com.limelight.shell.keyboard

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.limelight.shell.ui.DelosColors
import com.limelight.shell.ui.hsl

/**
 * The colors of the Full keyboard, from the theme: the bar and the keys in the background's hue, a focused key inverted
 * (the text color as fill), sticky in the accent, pressed in the second accent. For the default Delos palette these are
 * the prototype's (`#131c31` to `#0d1424` bar, `#1f2b46` keys, `#151e35` quiet keys); the light theme gets a light bar.
 */
@Immutable
class KeyboardPalette(
    val barTop: Color,
    val barBottom: Color,
    val key: Color,
    val quiet: Color,
    /** The one tinted key: it leaves the keyboard. */
    val hide: Color,
    val hideIcon: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    /** What the text is written on when [text] is the fill (hint glyphs). */
    val onText: Color,
    val focus: Color,
    val onFocus: Color,
    /** The shifted character of a stacked key on the focused (white) key. */
    val focusTop: Color,
    val focusShiftTop: Color,
    val latched: Color,
    val onLatched: Color,
    val latchedBarFocused: Color,
    val pressed: Color,
    val onPressed: Color,
    val popup: Color,
    val onPopup: Color,
)

internal fun keyboardPalette(c: DelosColors): KeyboardPalette {
    val base = c.baseHue
    return if (c.dark) {
        KeyboardPalette(
            barTop = hsl(base, 44f, 13f), barBottom = hsl(base, 47f, 9.5f),
            key = hsl(base, 39f, 20f), quiet = hsl(base, 42f, 14.5f),
            hide = hsl(c.accent3Hue + 30f, 28f, 18f), hideIcon = c.accent2,
            text = c.text, text2 = c.text2, text3 = c.text3, onText = c.bg,
            focus = c.text, onFocus = c.bg,
            focusTop = hsl(base, 17f, 42f), focusShiftTop = hsl(c.accentHue, 100f, 31f),
            latched = c.accent, onLatched = c.onAccent, latchedBarFocused = hsl(c.accentHue, 100f, 43f),
            pressed = c.accent2, onPressed = hsl(c.accent2Hue, 85f, 9f),
            popup = c.text, onPopup = c.bg,
        )
    } else {
        KeyboardPalette(
            barTop = hsl(base, 30f, 92f), barBottom = hsl(base, 34f, 88f),
            key = hsl(base, 40f, 99f), quiet = hsl(base, 30f, 94f),
            hide = hsl(c.accent3Hue + 30f, 40f, 92f), hideIcon = c.accent2,
            text = c.text, text2 = c.text2, text3 = c.text3, onText = c.bg,
            focus = c.text, onFocus = c.bg,
            focusTop = hsl(base, 15f, 70f), focusShiftTop = hsl(c.accentHue, 100f, 70f),
            latched = c.accent, onLatched = c.onAccent, latchedBarFocused = hsl(c.accentHue, 100f, 38f),
            pressed = c.accent2, onPressed = Color.White,
            popup = c.text, onPopup = c.bg,
        )
    }
}
