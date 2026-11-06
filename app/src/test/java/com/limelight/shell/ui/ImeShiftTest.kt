package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The arithmetic that keeps a text field above the on-screen keyboard (`ImeShift`). A 1080 px tall screen, a 540 px keyboard, a 24 px margin. */
class ImeShiftTest {
    private fun shift(fieldTop: Float, fieldBottom: Float, regionTop: Float = fieldTop, regionBottom: Float = fieldBottom, ime: Float = 540f) =
        ImeShift.shift(fieldTop, fieldBottom, regionTop, regionBottom, 1080f, ime, 24f)

    @Test
    fun nothingMovesWithoutAKeyboard() {
        assertEquals(0f, shift(700f, 784f, ime = 0f), 0f)
    }

    @Test
    fun aFieldAboveTheKeyboardStaysWhereItIs() {
        // Its bottom plus the margin is exactly the keyboard's top edge, and well above it
        assertEquals(0f, shift(432f, 516f), 0f)
        assertEquals(0f, shift(266f, 350f), 0f)
    }

    @Test
    fun aFieldUnderTheKeyboardMovesJustAboveItWithTheMargin() {
        // 784 + 24 - 540 = 268
        assertEquals(268f, shift(700f, 784f), 0f)
    }

    @Test
    fun aRegionThatFitsIsBroughtInWithTheField() {
        // A dialog from 305 to 775 around a field at 420..504: the field alone needs nothing, the dialog's buttons need 259
        assertEquals(259f, shift(420f, 504f, 305f, 775f), 0f)
    }

    @Test
    fun aRegionThatDoesNotFitKeepsItsTopOnTheScreenAndTheFieldVisible() {
        // The region 100..980 would need 464, but its top may only move up by 76; the field at 600..684 needs 168
        assertEquals(168f, shift(600f, 684f, 100f, 980f), 0f)
        // A field near the region's top: only the top limit counts
        assertEquals(76f, shift(120f, 204f, 100f, 980f), 0f)
    }

    @Test
    fun theFieldWinsOverTheRegion() {
        // The region only reaches up to 300 (limit 276) but the field at 800..884 needs 368: the field is shown, the region's top is not
        assertEquals(368f, shift(800f, 884f, 300f, 900f), 0f)
    }

    @Test
    fun neverMoreThanTheKeyboardOrOffTheTop() {
        // A field at the very bottom edge would need 324, but the keyboard is only 300 px: no empty strip opens above it
        assertEquals(300f, shift(1000f, 1080f, ime = 300f), 0f)
        assertEquals(540f, shift(1000f, 1080f), 0f)
        // The field's own top edge keeps the margin: 40 - 24 = 16 at most
        assertEquals(16f, shift(40f, 700f), 0f)
    }
}
