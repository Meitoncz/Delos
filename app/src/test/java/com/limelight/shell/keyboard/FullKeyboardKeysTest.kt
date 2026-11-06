package com.limelight.shell.keyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullKeyboardKeysTest {
    private val rows = FullKeyboardKeys.rows

    private fun key(legend: String) = rows.flatten().single { it.legend == legend }

    @Test
    fun theKeyboardHasTheSameEightyKeysInSixRowsAsTheOldLayout() {
        assertEquals(listOf(15, 14, 14, 13, 13, 11), rows.map { it.size })
        assertEquals(80, rows.sumOf { it.size })
        assertEquals(
            listOf("ESC", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12", "Ins", "Del"),
            rows[0].map { it.legend },
        )
        assertEquals(listOf("~`", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "_-", "+=", "Back"), rows[1].map { it.legend })
        assertEquals(listOf("Tab", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "{[", "}]", "|\\"), rows[2].map { it.legend })
        assertEquals(listOf("Caps", "A", "S", "D", "F", "G", "H", "J", "K", "L", ":;", "\"'", "Enter"), rows[3].map { it.legend })
        assertEquals(listOf("Shift", "Z", "X", "C", "V", "B", "N", "M", "<,", ">.", "?/", "\u2191", "Hide"), rows[4].map { it.legend })
        assertEquals(listOf("Ctrl", "Win", "Alt", "___", "Home", "End", "PgUp", "PgDn", "\u2190", "\u2193", "\u2192"), rows[5].map { it.legend })
    }

    @Test
    fun everyKeyHasItsOwnAndroidKeyCodeAndOnlyHideHasNone() {
        val codes = rows.flatten().map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertEquals(listOf(HIDE_CODE), codes.filter { it < 0 })
        assertEquals(KeyEvent.KEYCODE_Q, key("Q").code)
        assertEquals(KeyEvent.KEYCODE_F12, key("F12").code)
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, key("Del").code)
        assertEquals(KeyEvent.KEYCODE_DEL, key("Back").code)
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, key("Shift").code)
        assertEquals(KeyEvent.KEYCODE_META_LEFT, key("Win").code)
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, key("\u2191").code)
    }

    @Test
    fun weightsAndMarginsAreTheOnesOfTheOldLayout() {
        assertEquals(150f, key("ESC").weight, 0f)
        assertTrue(key("ESC").marginEnd && !key("ESC").marginStart)
        assertTrue(key("Ins").marginStart && !key("Ins").marginEnd)
        assertEquals(310f, key("___").weight, 0f)
        assertTrue(key("___").marginStart && key("___").marginEnd)
        assertEquals(103f, key("Hide").weight, 0f)
        assertEquals(listOf("Home", "End", "PgUp", "PgDn"), rows.flatten().filter { it.small }.map { it.legend })
    }

    @Test
    fun keyKindsFollowTheOldModifierAndSpecialKeySets() {
        assertEquals(KeyKind.MODIFIER, key("Ctrl").kind)
        assertEquals(KeyKind.MODIFIER, key("Shift").kind)
        assertEquals(KeyKind.SPECIAL, key("Tab").kind)
        assertEquals(KeyKind.SPECIAL, key("Enter").kind)
        assertEquals(KeyKind.SPECIAL, key("___").kind)
        assertEquals(KeyKind.SPECIAL, key("\u2190").kind)
        assertEquals(KeyKind.CHAR, key("F1").kind)
        assertEquals(KeyKind.CHAR, key("Q").kind)
        assertEquals(KeyKind.HIDE, key("Hide").kind)
        assertTrue(isModifierCode(KeyEvent.KEYCODE_ALT_RIGHT))
    }

    @Test
    fun glyphsAreStackedForPunctuationAndDrawnForArrowsAndSpace() {
        assertEquals(KeyGlyph.STACKED, key("_-").glyph)
        assertEquals(KeyGlyph.STACKED, key("|\\").glyph)
        assertEquals(KeyGlyph.TEXT, key("F1").glyph)
        assertEquals(KeyGlyph.TEXT, key("1").glyph)
        assertEquals(KeyGlyph.ARROW, key("\u2193").glyph)
        assertEquals(KeyGlyph.SPACE, key("___").glyph)
        assertEquals(KeyGlyph.HIDE, key("Hide").glyph)
        assertEquals(Arrow.LEFT, arrowOf(KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(arrowOf(KeyEvent.KEYCODE_Q))
    }

    @Test
    fun popupsShowTheCharacterATypeKeyWritesAndNothingForTheRest() {
        assertEquals("e", popupText(key("E")))
        assertEquals("5", popupText(key("5")))
        assertEquals("F1", popupText(key("F1")))
        assertEquals("-", popupText(key("_-")))
        assertEquals("\\", popupText(key("|\\")))
        assertEquals("'", popupText(key("\"'")))
        assertEquals("/", popupText(key("?/")))
        assertNull(popupText(key("Shift")))
        assertNull(popupText(key("Tab")))
        assertNull(popupText(key("___")))
        assertNull(popupText(key("Hide")))
    }

    private fun primary(legend: String, shift: Boolean = false, caps: Boolean = false) = keyLabel(key(legend), shift, caps).primary

    @Test
    fun lettersAreLowerCaseUntilShiftOrCapsLockMakesThemUpperAndBothMakeThemLowerAgain() {
        assertEquals("q", primary("Q"))
        assertEquals("Q", primary("Q", shift = true))
        assertEquals("Q", primary("Q", caps = true))
        assertEquals("q", primary("Q", shift = true, caps = true))
        assertNull(keyLabel(key("Q"), shift = true, caps = false).secondary)
    }

    @Test
    fun theNumberRowShowsItsSymbolsWhileShiftIsDownAndCapsLockDoesNotChangeThem() {
        val plain = "1234567890"
        val shifted = "!@#$%^&*()"
        for (i in plain.indices) {
            assertEquals(plain[i].toString(), primary(plain[i].toString()))
            assertEquals(shifted[i].toString(), primary(plain[i].toString(), shift = true))
            assertEquals(plain[i].toString(), primary(plain[i].toString(), caps = true))
        }
    }

    @Test
    fun punctuationKeysSwapTheirTwoCharactersWithShift() {
        val pairs = listOf("_-", "+=", "{[", "}]", ":;", "\"'", "<,", ">.", "?/", "~`", "|\\")
        for (pair in pairs) {
            assertEquals(KeyLabel(pair.substring(1), pair.substring(0, 1)), keyLabel(key(pair), shift = false, caps = false))
            assertEquals(KeyLabel(pair.substring(0, 1), pair.substring(1)), keyLabel(key(pair), shift = true, caps = false))
            // Caps Lock changes letters only
            assertEquals(keyLabel(key(pair), shift = false, caps = false), keyLabel(key(pair), shift = false, caps = true))
        }
    }

    @Test
    fun otherKeysKeepTheirLegendWhateverShiftDoes() {
        for (legend in listOf("Tab", "Enter", "Caps", "Shift", "F1", "Home", "Hide", "\u2191", "___")) {
            assertEquals(KeyLabel(legend), keyLabel(key(legend), shift = true, caps = true))
            assertEquals(KeyLabel(legend), keyLabel(key(legend), shift = false, caps = false))
        }
    }

    @Test
    fun popupsShowTheShiftedCharacterToo() {
        assertEquals("E", popupText(key("E"), shift = true))
        assertEquals("%", popupText(key("5"), shift = true))
        assertEquals("_", popupText(key("_-"), shift = true))
        assertEquals("e", popupText(key("E"), shift = true, caps = true))
    }

    @Test
    fun bothShiftKeyCodesAreShift() {
        assertTrue(isShiftCode(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertTrue(isShiftCode(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertFalse(isShiftCode(KeyEvent.KEYCODE_CAPS_LOCK))
    }
}
