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

    // The keyboard's height from every source: a 1080 px window and a 540 px keyboard unless said otherwise
    private fun keyboard(window: Float = 1080f, inset: Float = 0f, compose: Float = 0f, visibleBottom: Float = window, visible: Boolean = false) =
        ImeShift.keyboardHeight(window, inset, compose, visibleBottom, visible)

    @Test
    fun anySingleSourceIsEnoughAndTheLargestWins() {
        assertEquals(540f, keyboard(inset = 540f), 0f)
        assertEquals(540f, keyboard(compose = 540f), 0f)
        // The visible frame alone: it ends where the keyboard begins
        assertEquals(540f, keyboard(visibleBottom = 540f), 0f)
        assertEquals(560f, keyboard(inset = 540f, compose = 500f, visibleBottom = 520f), 0f)
    }

    @Test
    fun aVisibleFrameThatLostLessThanFifteenPercentIsNoKeyboard() {
        // A navigation bar or a cutout: 5% of the window, and exactly 15%
        assertEquals(0f, keyboard(visibleBottom = 1026f), 0f)
        assertEquals(0f, keyboard(visibleBottom = 1080f - 162f), 0f)
        assertEquals(165f, keyboard(visibleBottom = 1080f - 165f), 0f)
    }

    @Test
    fun noSourceMeansNoKeyboardUnlessTheSystemSaysItIsVisible() {
        assertEquals(0f, keyboard(), 0f)
        // Visible with no height anywhere: half the window, so the field is not left under it
        assertEquals(540f, keyboard(visible = true), 0f)
        // A measured height beats the estimate
        assertEquals(300f, keyboard(inset = 300f, visible = true), 0f)
    }

    @Test
    fun aWindowThatAdjustResizeShrankToTheKeyboardIsNeverShiftedAgain() {
        // 540 px window above a keyboard: every source measures against it, nothing overlaps
        assertEquals(0f, keyboard(window = 540f, visibleBottom = 540f), 0f)
        assertEquals(0f, ImeShift.shift(300f, 384f, 100f, 500f, 540f, keyboard(window = 540f), 24f), 0f)
        // A partial resize (to 700 px) leaves 100 px of the keyboard on the window: only that part is shifted
        val overlap = keyboard(window = 700f, inset = 100f, visibleBottom = 600f)
        assertEquals(100f, overlap, 0f)
        // field at 520..604, free area 600: 604 + 24 - 600
        assertEquals(28f, ImeShift.shift(520f, 604f, 520f, 604f, 700f, overlap, 24f), 0f)
    }

    @Test
    fun aKeyboardNeverCoversMoreThanTheWindow() {
        assertEquals(1080f, keyboard(inset = 5000f), 0f)
        assertEquals(0f, keyboard(window = 0f, inset = 100f), 0f)
    }

    @Test
    fun theProfilePageSlidesUntilTheNameFieldAndItsButtonAreAboveTheKeyboard() {
        // CreateProfileScreen on the 1920 x 1080 Odin: a column of about 860 px centered on the stage, the name field
        // at 790..874 and "Create profile" at 914..998. The keyboard covers the lower half (540 px).
        val kb = keyboard(visibleBottom = 540f)
        val moved = ImeShift.shift(790f, 874f, 790f, 998f, 1080f, kb, 24f)
        // 998 + 24 - 540 = 482: the button's bottom ends 24 px above the keyboard and the field is far above it
        assertEquals(482f, moved, 0f)
        assertEquals(516f, 998f - moved, 0f)
        assertEquals(true, 874f - moved + 24f <= 540f)
        // Without the region (the field alone) it would only be brought in, and the button stays behind the keyboard
        assertEquals(358f, ImeShift.shift(790f, 874f, 790f, 874f, 1080f, kb, 24f), 0f)
    }
}
