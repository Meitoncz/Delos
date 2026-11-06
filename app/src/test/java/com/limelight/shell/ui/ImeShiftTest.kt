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

    // The editing band: the field being edited is pinned into the top quarter whatever the keyboard reports (1920 x 1080 landscape unless said otherwise)
    private fun edit(
        fieldTop: Float, fieldBottom: Float, engaged: Boolean = true, keyboard: Float = 0f, regionTop: Float = fieldTop, regionBottom: Float = fieldBottom,
        width: Float = 1920f, height: Float = 1080f, visible: Boolean = true, moverTop: Float = 0f,
    ) = ImeShift.editShift(engaged, visible, fieldTop, fieldBottom, regionTop, regionBottom, moverTop, width, height, keyboard, 24f, 48f)

    @Test
    fun theBandIsAQuarterOfALandscapeWindowAndAFifthOfAPortraitOne() {
        assertEquals(270f, ImeShift.editBandBottom(1920f, 1080f), 0f)
        assertEquals(.25f, ImeShift.EDIT_BAND_BOTTOM_FRACTION, 0f)
        assertEquals(.20f, ImeShift.EDIT_BAND_BOTTOM_FRACTION_PORTRAIT, 0f)
        // Portrait: 1080 x 1920, the band ends at 384
        assertEquals(384f, ImeShift.editBandBottom(1080f, 1920f), 0f)
        // The band asks for 1116 and the page may reach it, but the field only has to be at or above 40% (768): 1500 - 768
        assertEquals(732f, edit(1416f, 1500f, width = 1080f, height = 1920f), 0f)
    }

    @Test
    fun theProfilePageMovesUntilTheFieldIsAtFortyPercentAndMayLeaveTheTop() {
        // field 790..874 on a page whose content starts at 110 (or the whole window, 0): the band asks for 604, the page may only go up 62,
        // but the field must be at or above 40% of 1080 = 432: 874 - 432 = 442. The field wins over the margin; the heading leaves the top
        assertEquals(442f, edit(790f, 874f, moverTop = 110f), 0f)
        assertEquals(442f, edit(790f, 874f), 0f)
        // SwiftKey reports 111 px (and covers 623): the same
        assertEquals(442f, edit(790f, 874f, keyboard = 111f), 0f)
        // With the button as a region and a measured 540 px keyboard (it needs 482 for the region, but the margin caps that to what keeps the field above it, 358)
        assertEquals(442f, edit(790f, 874f, keyboard = 540f, regionTop = 790f, regionBottom = 998f, moverTop = 110f), 0f)
    }

    @Test
    fun aDialogKeepsItsTopMarginFromTheEdge() {
        // Rename profile: card top 324, field 514..598. The band says 328, the margin cap 324 - 48 = 276, the field floor 598 - 432 = 166: 276,
        // the card is 48 px from the top, the field's bottom at 322, above the keyboard (about 457)
        assertEquals(276f, edit(514f, 598f, moverTop = 324f), 0f)
        assertEquals(598f - 276f, 322f, 0f)
    }

    @Test
    fun aTinyDialogIsNotCappedByTheMarginAndALargeOneLetsTheFieldWin() {
        // A small dialog low on the screen: card top 560, field 600..684: the band's 414 is within the margin's 512
        assertEquals(414f, edit(600f, 684f, moverTop = 560f), 0f)
        // A dialog taller than the free area: card top 100, field 500..584: the band says 314, the margin 52, the floor 152: the field wins, the card's top leaves the screen by 52
        assertEquals(152f, edit(500f, 584f, moverTop = 100f), 0f)
        // And one that starts at the top edge: the field is still above 40%
        assertEquals(584f - 432f, edit(500f, 584f, moverTop = 0f), 0f)
    }

    @Test
    fun aFieldThatIsAlreadyInTheBandStaysWhereItIs() {
        // A field at 266..350 is 80 px under the band's edge but above 40%, and a mover with room to spare (card top 200) does not go over it; one at 100..184 is in the band
        assertEquals(80f, edit(266f, 350f, moverTop = 200f), 0f)
        // ... a page that begins at the top edge cannot go up without leaving it, and the field is above 40%: nothing moves
        assertEquals(0f, edit(266f, 350f, moverTop = 0f), 0f)
        assertEquals(0f, edit(100f, 184f), 0f)
        assertEquals(0f, edit(100f, 184f, keyboard = 0f), 0f)
    }

    @Test
    fun aMeasuredKeyboardThatNeedsMoreBeatsTheBandAndIsCappedByTheMarginExceptForTheField() {
        // A reported 700 px keyboard (free 380) under a field at 400..484: the band needs 214 but is reached only to the floor, 52; the keyboard needs
        // 484 + 24 - 380 = 128 to keep the field above it, and nothing caps that
        assertEquals(128f, edit(400f, 484f, keyboard = 700f), 0f)
        // A 600 px tall window, the field at 300..384: the band's 234 is cut to the floor 144; a 450 px keyboard needs 384 + 24 - 150 = 258: the keyboard
        assertEquals(258f, edit(300f, 384f, keyboard = 450f, height = 600f), 0f)
        // A dialog (card top 324) over a 700 px keyboard: field 600..684 needs 328 for the keyboard itself, past the margin's 276; the field would be covered
        assertEquals(328f, edit(600f, 684f, keyboard = 700f, moverTop = 324f), 0f)
        // The same card with a keyboard whose region asks for more than the field needs: the margin caps the region, not the field
        assertEquals(276f, edit(514f, 598f, keyboard = 540f, regionTop = 324f, regionBottom = 820f, moverTop = 324f), 0f)
    }

    @Test
    fun aFieldThatIsNotBeingEditedFollowsOnlyTheKeyboard() {
        // Deactivated (focus left, field dismissed): no band, no keyboard: back to 0
        assertEquals(0f, edit(790f, 874f, engaged = false), 0f)
        assertEquals(0f, edit(790f, 874f, engaged = false, keyboard = 0f), 0f)
        // Not engaged but a keyboard that is measured: the old rule (874 + 24 - 540)
        assertEquals(358f, edit(790f, 874f, engaged = false, keyboard = 540f), 0f)
    }

    @Test
    fun theBandNeverPushesTheFieldOutOfTheWindow() {
        // A field 700 px tall at 40..740 would need 470; its top keeps the 24 px margin: 16
        assertEquals(16f, edit(40f, 740f), 0f)
        // A field already at the very top with its bottom below the band
        assertEquals(0f, edit(0f, 300f), 0f)
    }

    @Test
    fun theBandOnlyHoldsWhileTheKeyboardIsVisible() {
        // The user hid the keyboard (SwiftKey's chevron, Back): the field is still focused and engaged, but everything glides back
        assertEquals(442f, edit(790f, 874f, visible = true), 0f)
        assertEquals(0f, edit(790f, 874f, visible = false), 0f)
        assertEquals(0f, edit(514f, 598f, visible = false, moverTop = 324f), 0f)
        // ... and A or a tap opens it again: visible, engaged, the same shift as before
        assertEquals(276f, edit(514f, 598f, visible = true, moverTop = 324f), 0f)
    }

    @Test
    fun theVisibleFlagAloneOrAnyMeasuredHeightMeansTheKeyboardIsUp() {
        fun up(inset: Float = 0f, compose: Float = 0f, visibleBottom: Float = 1080f, flag: Boolean = false) =
            ImeShift.keyboardVisible(1080f, inset, compose, visibleBottom, flag)
        // The Odin's SwiftKey: a bogus 111 px, but the flag is true while it is open and false, with 0 everywhere, when it is closed
        assertEquals(true, up(inset = 111f, flag = true))
        assertEquals(false, up(inset = 0f, compose = 0f, flag = false))
        // The flag without a height, a height without the flag, the frame alone
        assertEquals(true, up(flag = true))
        assertEquals(true, up(inset = 111f))
        assertEquals(true, up(compose = 300f))
        assertEquals(true, up(visibleBottom = 540f))
        // A navigation bar's worth of visible frame is no keyboard
        assertEquals(false, up(visibleBottom = 1026f))
    }
}
