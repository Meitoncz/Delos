package com.limelight.shell.keyboard

import android.view.KeyEvent
import androidx.compose.runtime.Immutable

/** What a key is: it decides its look (character keys are the brightest) and whether it has a popup or can be latched. */
enum class KeyKind { CHAR, SPECIAL, MODIFIER, HIDE }

/** How a key is drawn. */
enum class KeyGlyph { TEXT, STACKED, ARROW, SPACE, HIDE }

/**
 * One key of the Full keyboard. [code] is the Android key code that goes to the host ([HIDE_CODE] for the Hide key, which
 * sends nothing), [weight] its share of the row (like `layout_weight` of the old layout), [marginStart] / [marginEnd] the
 * extra gap next to Esc, Ins and around Space (the 5 dp margins of the old layout).
 */
@Immutable
data class KeyDef(
    val code: Int,
    val legend: String,
    val weight: Float,
    val kind: KeyKind,
    val glyph: KeyGlyph,
    val marginStart: Boolean = false,
    val marginEnd: Boolean = false,
    /** Home, End, PgUp and PgDn: a smaller legend. */
    val small: Boolean = false,
) {
    /** Function and modifier keys sit one step quieter than the characters. */
    val quiet: Boolean get() = kind == KeyKind.SPECIAL || kind == KeyKind.MODIFIER

    /** Only the character keys get a popup above them while pressed. */
    val hasPopup: Boolean get() = kind == KeyKind.CHAR
}

/** The Hide key has no Android key code; it closes the keyboard. */
const val HIDE_CODE = -1

/** The keys a sticky modifier setting latches: both sides, although the keyboard only has the left ones. */
private val MODIFIER_CODES = setOf(
    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
)

/** The keys that get no popup above them when pressed (besides the modifiers). */
private val SPECIAL_CODES = setOf(
    KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
    KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_INSERT,
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
)

fun isModifierCode(code: Int): Boolean = code in MODIFIER_CODES

fun isShiftCode(code: Int): Boolean = code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT

/** The arrow keys, drawn as strokes. */
enum class Arrow { UP, DOWN, LEFT, RIGHT }

fun arrowOf(code: Int): Arrow? = when (code) {
    KeyEvent.KEYCODE_DPAD_UP -> Arrow.UP
    KeyEvent.KEYCODE_DPAD_DOWN -> Arrow.DOWN
    KeyEvent.KEYCODE_DPAD_LEFT -> Arrow.LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> Arrow.RIGHT
    else -> null
}

/** Punctuation keys write both characters in one string, the one Shift gives first ("_-", "{[", "?/"). */
private fun isStacked(legend: String) = legend.length == 2 && !legend[0].isLetterOrDigit()

private fun keyOf(code: Int, legend: String, weight: Int, marginStart: Boolean = false, marginEnd: Boolean = false, small: Boolean = false): KeyDef {
    val kind = when {
        code == HIDE_CODE -> KeyKind.HIDE
        code in MODIFIER_CODES -> KeyKind.MODIFIER
        code in SPECIAL_CODES -> KeyKind.SPECIAL
        else -> KeyKind.CHAR
    }
    val glyph = when {
        code == HIDE_CODE -> KeyGlyph.HIDE
        arrowOf(code) != null -> KeyGlyph.ARROW
        code == KeyEvent.KEYCODE_SPACE -> KeyGlyph.SPACE
        isStacked(legend) -> KeyGlyph.STACKED
        else -> KeyGlyph.TEXT
    }
    return KeyDef(code, legend, weight.toFloat(), kind, glyph, marginStart, marginEnd, small)
}

/** The symbols the number row types with Shift (US layout), by digit. */
private const val DIGIT_SHIFTED = ")!@#$%^&*("

/**
 * What a key shows now: the [primary] label is what the key types, the [secondary] one (null for most keys) the other
 * character of a punctuation key, drawn small above it. A letter shows lower case, upper case while Shift is down or
 * Caps Lock is on (both: lower again); a digit shows its symbol while Shift is down; a punctuation key swaps its two
 * characters while Shift is down. Labels are the US layout whatever the stream's key translation setting says (it only
 * changes the codes the host receives). Keys with a fixed legend show it as written.
 */
@Immutable
data class KeyLabel(val primary: String, val secondary: String? = null)

fun keyLabel(key: KeyDef, shift: Boolean, caps: Boolean): KeyLabel {
    val legend = key.legend
    return when {
        key.glyph == KeyGlyph.STACKED ->
            if (shift) KeyLabel(legend.substring(0, 1), legend.substring(1)) else KeyLabel(legend.substring(1), legend.substring(0, 1))
        key.kind == KeyKind.CHAR && key.glyph == KeyGlyph.TEXT && legend.length == 1 && legend[0].isLetter() ->
            KeyLabel(if (shift != caps) legend.uppercase() else legend.lowercase())
        key.kind == KeyKind.CHAR && key.glyph == KeyGlyph.TEXT && legend.length == 1 && legend[0].isDigit() ->
            KeyLabel(if (shift) DIGIT_SHIFTED[legend[0] - '0'].toString() else legend)
        else -> KeyLabel(legend)
    }
}

/** What a pressed key shows in the popup above it, null for a key without one: its [keyLabel], what the key types. */
fun popupText(key: KeyDef, shift: Boolean = false, caps: Boolean = false): String? =
    if (key.hasPopup) keyLabel(key, shift, caps).primary else null

/**
 * The 80 keys of the Full keyboard in its six rows: the keys, weights and margins of the old `layout_axixi_keyboard.xml`.
 * The legends of letters are upper case (see [keyLabel] for what is drawn); there are only the left modifiers.
 */
object FullKeyboardKeys {
    val rows: List<List<KeyDef>> = listOf(
        listOf(keyOf(KeyEvent.KEYCODE_ESCAPE, "ESC", 150, marginEnd = true)) +
            (1..12).map { keyOf(KeyEvent.KEYCODE_F1 + it - 1, "F$it", 100) } +
            listOf(keyOf(KeyEvent.KEYCODE_INSERT, "Ins", 100, marginStart = true), keyOf(KeyEvent.KEYCODE_FORWARD_DEL, "Del", 100)),
        listOf(
            keyOf(KeyEvent.KEYCODE_GRAVE, "~`", 90),
            keyOf(KeyEvent.KEYCODE_1, "1", 100), keyOf(KeyEvent.KEYCODE_2, "2", 100), keyOf(KeyEvent.KEYCODE_3, "3", 100),
            keyOf(KeyEvent.KEYCODE_4, "4", 100), keyOf(KeyEvent.KEYCODE_5, "5", 100), keyOf(KeyEvent.KEYCODE_6, "6", 100),
            keyOf(KeyEvent.KEYCODE_7, "7", 100), keyOf(KeyEvent.KEYCODE_8, "8", 100), keyOf(KeyEvent.KEYCODE_9, "9", 100),
            keyOf(KeyEvent.KEYCODE_0, "0", 100),
            keyOf(KeyEvent.KEYCODE_MINUS, "_-", 100), keyOf(KeyEvent.KEYCODE_EQUALS, "+=", 100),
            keyOf(KeyEvent.KEYCODE_DEL, "Back", 150), // l10n-ignore
        ),
        listOf(
            keyOf(KeyEvent.KEYCODE_TAB, "Tab", 140),
            keyOf(KeyEvent.KEYCODE_Q, "Q", 100), keyOf(KeyEvent.KEYCODE_W, "W", 100), keyOf(KeyEvent.KEYCODE_E, "E", 100),
            keyOf(KeyEvent.KEYCODE_R, "R", 100), keyOf(KeyEvent.KEYCODE_T, "T", 100), keyOf(KeyEvent.KEYCODE_Y, "Y", 100),
            keyOf(KeyEvent.KEYCODE_U, "U", 100), keyOf(KeyEvent.KEYCODE_I, "I", 100), keyOf(KeyEvent.KEYCODE_O, "O", 100),
            keyOf(KeyEvent.KEYCODE_P, "P", 100),
            keyOf(KeyEvent.KEYCODE_LEFT_BRACKET, "{[", 100), keyOf(KeyEvent.KEYCODE_RIGHT_BRACKET, "}]", 100),
            keyOf(KeyEvent.KEYCODE_BACKSLASH, "|\\", 100),
        ),
        listOf(
            keyOf(KeyEvent.KEYCODE_CAPS_LOCK, "Caps", 160), // l10n-ignore
            keyOf(KeyEvent.KEYCODE_A, "A", 100), keyOf(KeyEvent.KEYCODE_S, "S", 100), keyOf(KeyEvent.KEYCODE_D, "D", 100),
            keyOf(KeyEvent.KEYCODE_F, "F", 100), keyOf(KeyEvent.KEYCODE_G, "G", 100), keyOf(KeyEvent.KEYCODE_H, "H", 100),
            keyOf(KeyEvent.KEYCODE_J, "J", 100), keyOf(KeyEvent.KEYCODE_K, "K", 100), keyOf(KeyEvent.KEYCODE_L, "L", 100),
            keyOf(KeyEvent.KEYCODE_SEMICOLON, ":;", 100), keyOf(KeyEvent.KEYCODE_APOSTROPHE, "\"'", 100),
            keyOf(KeyEvent.KEYCODE_ENTER, "Enter", 160), // l10n-ignore
        ),
        listOf(
            keyOf(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift", 160), // l10n-ignore
            keyOf(KeyEvent.KEYCODE_Z, "Z", 100), keyOf(KeyEvent.KEYCODE_X, "X", 100), keyOf(KeyEvent.KEYCODE_C, "C", 100),
            keyOf(KeyEvent.KEYCODE_V, "V", 100), keyOf(KeyEvent.KEYCODE_B, "B", 100), keyOf(KeyEvent.KEYCODE_N, "N", 100),
            keyOf(KeyEvent.KEYCODE_M, "M", 100),
            keyOf(KeyEvent.KEYCODE_COMMA, "<,", 100), keyOf(KeyEvent.KEYCODE_PERIOD, ">.", 100), keyOf(KeyEvent.KEYCODE_SLASH, "?/", 100),
            keyOf(KeyEvent.KEYCODE_DPAD_UP, "\u2191", 103),
            keyOf(HIDE_CODE, "Hide", 103), // l10n-ignore
        ),
        listOf(
            keyOf(KeyEvent.KEYCODE_CTRL_LEFT, "Ctrl", 105), keyOf(KeyEvent.KEYCODE_META_LEFT, "Win", 105), keyOf(KeyEvent.KEYCODE_ALT_LEFT, "Alt", 105), // l10n-ignore
            keyOf(KeyEvent.KEYCODE_SPACE, "___", 310, marginStart = true, marginEnd = true),
            keyOf(KeyEvent.KEYCODE_MOVE_HOME, "Home", 98, small = true), keyOf(KeyEvent.KEYCODE_MOVE_END, "End", 98, small = true), // l10n-ignore
            keyOf(KeyEvent.KEYCODE_PAGE_UP, "PgUp", 98, small = true), keyOf(KeyEvent.KEYCODE_PAGE_DOWN, "PgDn", 98, small = true),
            keyOf(KeyEvent.KEYCODE_DPAD_LEFT, "\u2190", 100), keyOf(KeyEvent.KEYCODE_DPAD_DOWN, "\u2193", 100), keyOf(KeyEvent.KEYCODE_DPAD_RIGHT, "\u2192", 100),
        ),
    )
}
