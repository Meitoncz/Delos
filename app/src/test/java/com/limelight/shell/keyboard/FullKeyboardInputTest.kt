package com.limelight.shell.keyboard

import android.view.KeyEvent
import com.limelight.shell.MenuDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullKeyboardInputTest {
    private val grid = KeyGrid.standard

    private fun pos(legend: String): KeyPos {
        for (r in grid.rows.indices) {
            val c = grid.rows[r].indexOfFirst { it.legend == legend }
            if (c >= 0) return KeyPos(r, c)
        }
        error("no key $legend")
    }

    private fun legend(pos: KeyPos) = grid.key(pos).legend

    private fun step(from: String, direction: MenuDirection, anchor: Float = grid.span(pos(from)).center): NavMove? =
        grid.move(pos(from), anchor, direction)

    // ---- Focus movement ----

    @Test
    fun leftAndRightStayInTheRowAndStopAtItsEnds() {
        assertEquals("W", legend(step("Q", MenuDirection.RIGHT)!!.pos))
        assertEquals("Q", legend(step("W", MenuDirection.LEFT)!!.pos))
        assertNull(step("Tab", MenuDirection.LEFT))
        assertNull(step("|\\", MenuDirection.RIGHT))
        assertNull(step("Del", MenuDirection.RIGHT))
        assertNull(step("ESC", MenuDirection.LEFT))
    }

    @Test
    fun upAndDownStopAtTheTopAndBottomRows() {
        assertNull(step("F5", MenuDirection.UP))
        assertNull(step("___", MenuDirection.DOWN))
        assertNull(step("\u2192", MenuDirection.DOWN))
    }

    @Test
    fun upAndDownFollowTheColumnUnderTheCurrentKeyEvenThoughTheRowsAreNotAligned() {
        assertEquals("A", legend(step("Q", MenuDirection.DOWN)!!.pos))
        assertEquals("1", legend(step("Q", MenuDirection.UP)!!.pos))
        assertEquals("~`", legend(step("ESC", MenuDirection.DOWN)!!.pos))
        // The wide space bar reaches up to V, the middle of the bottom row's letters
        assertEquals("V", legend(step("___", MenuDirection.UP)!!.pos))
        assertEquals("\u2192", legend(step("Hide", MenuDirection.DOWN)!!.pos))
    }

    @Test
    fun theColumnIsRememberedAcrossVerticalMovesAndRestartsWithAHorizontalOne() {
        // Del is at the far right: down goes to Back, then the next row's last key; the column stays that of Del all the way
        val first = step("Del", MenuDirection.DOWN)!!
        assertEquals("Back", legend(first.pos))
        val second = grid.move(first.pos, first.anchorX, MenuDirection.DOWN)!!
        assertEquals("|\\", legend(second.pos))
        assertEquals(first.anchorX, second.anchorX, 0f)
        // A step left makes the key it lands on the new column: up from there aims for that key's center
        val left = grid.move(second.pos, second.anchorX, MenuDirection.LEFT)!!
        assertEquals("}]", legend(left.pos))
        assertEquals(grid.span(left.pos).center, left.anchorX, 0f)
    }

    @Test
    fun aVerticalMoveGoesToTheKeyUnderTheColumnOrElseTheNearestOne() {
        for (r in grid.rows.indices) for (c in grid.rows[r].indices) {
            val from = KeyPos(r, c)
            val anchor = grid.span(from).center
            for ((direction, target) in listOf(MenuDirection.UP to r - 1, MenuDirection.DOWN to r + 1)) {
                val move = grid.move(from, anchor, direction)
                if (target !in grid.rows.indices) {
                    assertNull(move)
                    continue
                }
                assertNotNull(move)
                assertEquals(target, move!!.pos.row)
                val chosen = grid.span(move.pos)
                val distance = if (anchor in chosen.start..chosen.end) 0f else minOf(kotlin.math.abs(anchor - chosen.start), kotlin.math.abs(anchor - chosen.end))
                for (other in grid.rows[target].indices) {
                    val s = grid.span(KeyPos(target, other))
                    val d = if (anchor in s.start..s.end) 0f else minOf(kotlin.math.abs(anchor - s.start), kotlin.math.abs(anchor - s.end))
                    assertTrue("$from $direction: ${legend(move.pos)} is not the nearest", distance <= d)
                }
            }
        }
    }

    @Test
    fun landingOnAKeyMakesItsCenterTheColumn() {
        val q = grid.find(KeyGrid.FIRST_KEY)!!
        assertEquals("Q", legend(q))
        val landed = grid.land(q)
        assertEquals(q, landed.pos)
        assertEquals(grid.span(q).center, landed.anchorX, 0f)
    }

    @Test
    fun spansShareTheWidthByWeightAndMarginsTakeFromTheirOwnKeyOnly() {
        val row = FullKeyboardKeys.rows[0]
        val spans = KeyGrid.layoutSpans(row, width = 1000f, gap = 6f, sep = 12f)
        assertEquals(row.size, spans.size)
        assertEquals(0f, spans.first().start, 0f)
        // Esc is 150 of 1550 weight units of what the 14 gaps leave, less its margin on the right
        val free = 1000f - 6f * 14
        assertEquals(free * 150f / 1550f - 12f, spans.first().end, .001f)
        // Ins starts 12 later than its cell: the F12 key and Ins are further apart than F11 and F12
        val gapF = spans[12].end.let { spans[13].start - it }
        val gapNormal = spans[11].end.let { spans[12].start - it }
        assertEquals(6f + 12f, gapF, .001f)
        assertEquals(6f, gapNormal, .001f)
        assertEquals(1000f, spans.last().end, .001f)
    }

    // ---- Modifiers ----

    @Test
    fun touchingAModifierPressesItAndHoldingLatchesIt() {
        val latch = ModifierLatch()
        val ctrl = KeyEvent.KEYCODE_CTRL_LEFT
        assertEquals(KeySend.DOWN, latch.touchDown(ctrl, sticky = true))
        // released before the hold time: an ordinary tap
        assertEquals(KeySend.UP, latch.touchUp(ctrl, sticky = true))
        assertFalse(latch.isLatched(ctrl))

        assertEquals(KeySend.DOWN, latch.touchDown(ctrl, sticky = true))
        latch.latchHeld(ctrl)
        assertTrue(latch.isLatched(ctrl))
        // the release of a latched modifier is not sent: it stays down on the host
        assertEquals(KeySend.NONE, latch.touchUp(ctrl, sticky = true))
        assertEquals(setOf(ctrl), latch.codes())
    }

    @Test
    fun touchingALatchedModifierReleasesItWithTheNextUp() {
        val latch = ModifierLatch()
        val shift = KeyEvent.KEYCODE_SHIFT_LEFT
        latch.touchDown(shift, true)
        latch.latchHeld(shift)
        assertEquals(KeySend.NONE, latch.touchDown(shift, sticky = true))
        assertFalse(latch.isLatched(shift))
        assertEquals(KeySend.UP, latch.touchUp(shift, sticky = true))
    }

    @Test
    fun withoutStickyModifiersEveryKeyIsAnOrdinaryKey() {
        val latch = ModifierLatch()
        val alt = KeyEvent.KEYCODE_ALT_LEFT
        assertEquals(KeySend.DOWN, latch.touchDown(alt, sticky = false))
        assertEquals(KeySend.UP, latch.touchUp(alt, sticky = false))
        // an ordinary key is never latched, even if asked
        val q = KeyEvent.KEYCODE_Q
        assertEquals(KeySend.DOWN, latch.touchDown(q, sticky = true))
        assertEquals(KeySend.UP, latch.touchUp(q, sticky = true))
    }

    @Test
    fun aControllerPressTogglesTheLatchWhateverTheSettingSays() {
        val latch = ModifierLatch()
        val shift = KeyEvent.KEYCODE_SHIFT_LEFT
        assertEquals(KeySend.DOWN, latch.padPress(shift))
        assertTrue(latch.isLatched(shift))
        assertEquals(KeySend.UP, latch.padPress(shift))
        assertFalse(latch.isLatched(shift))
    }

    @Test
    fun hidingReleasesEveryLatchedModifierAndClearsTheLatch() {
        val latch = ModifierLatch()
        val shift = KeyEvent.KEYCODE_SHIFT_LEFT
        val ctrl = KeyEvent.KEYCODE_CTRL_LEFT
        val alt = KeyEvent.KEYCODE_ALT_LEFT
        // one latched by touch (held), one by the controller, one only pressed and not latched
        latch.touchDown(ctrl, true)
        latch.latchHeld(ctrl)
        latch.padPress(shift)
        latch.touchDown(alt, true)

        assertEquals(listOf(ctrl, shift), latch.releaseAll())
        assertTrue(latch.codes().isEmpty())
        assertFalse(latch.isLatched(ctrl))
        // nothing is left to release, and the next touch starts a fresh press
        assertTrue(latch.releaseAll().isEmpty())
        assertEquals(KeySend.DOWN, latch.touchDown(ctrl, sticky = true))
        assertEquals(KeySend.DOWN, latch.padPress(shift))
    }

    // ---- Controller buttons ----

    @Test
    fun dpadKeyCodesMapToDirectionsAndTheOtherKeysDoNot() {
        assertEquals(MenuDirection.LEFT, dpadDirection(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(MenuDirection.RIGHT, dpadDirection(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(MenuDirection.UP, dpadDirection(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(MenuDirection.DOWN, dpadDirection(KeyEvent.KEYCODE_DPAD_DOWN))
        assertNull(dpadDirection(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(dpadDirection(KeyEvent.KEYCODE_BUTTON_A))
        // The D-pad is not a PadAction: it goes through the tracker, which also takes the hat
        assertNull(padKeyAction(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, 0, false))
    }

    @Test
    fun aHatPressMovesAtOnceThenRepeatsAfterTheInitialDelayUntilItReturnsToCenter() {
        val dpad = DpadTracker(initialDelayMs = 300, repeatIntervalMs = 100)
        assertEquals(MenuDirection.RIGHT, dpad.onHat(1f, 0f, 1000))
        assertEquals(1300L, dpad.repeatAt)
        // Re-sent hat events change nothing
        assertNull(dpad.onHat(1f, 0f, 1100))
        assertNull(dpad.repeat(1299))
        assertEquals(MenuDirection.RIGHT, dpad.repeat(1300))
        assertEquals(1400L, dpad.repeatAt)
        assertNull(dpad.repeat(1399))
        assertEquals(MenuDirection.RIGHT, dpad.repeat(1410))
        assertEquals(1510L, dpad.repeatAt)
        // Back to the center: no move, no more repeats
        assertNull(dpad.onHat(0f, 0f, 1500))
        assertNull(dpad.repeatAt)
        assertNull(dpad.repeat(2000))
    }

    @Test
    fun everyHatDirectionMapsToItsOwnMove() {
        val dpad = DpadTracker()
        assertEquals(MenuDirection.LEFT, dpad.onHat(-1f, 0f, 0))
        dpad.onHat(0f, 0f, 10)
        assertEquals(MenuDirection.UP, dpad.onHat(0f, -1f, 20))
        dpad.onHat(0f, 0f, 30)
        assertEquals(MenuDirection.DOWN, dpad.onHat(0f, 1f, 40))
        // Straight from one direction to another counts as a new press
        assertEquals(MenuDirection.LEFT, dpad.onHat(-1f, 0f, 50))
    }

    @Test
    fun aDiagonalIsTheSideThatWasPressedLastAndLettingGoOfItStopsTheRepeat() {
        val dpad = DpadTracker(initialDelayMs = 300, repeatIntervalMs = 100)
        assertEquals(MenuDirection.RIGHT, dpad.onHat(1f, 0f, 0))
        // Thumb rolls up: Up is new and moves, and repeats from now on
        assertEquals(MenuDirection.UP, dpad.onHat(1f, -1f, 50))
        assertEquals(350L, dpad.repeatAt)
        assertEquals(MenuDirection.UP, dpad.repeat(350))
        // Letting go of Right (the older side) changes nothing
        assertNull(dpad.onHat(0f, -1f, 400))
        assertEquals(MenuDirection.UP, dpad.repeat(450))
        // Back to Up-right: Right is new and moves
        assertEquals(MenuDirection.RIGHT, dpad.onHat(1f, -1f, 500))
        // Letting go of Right (the one repeating) leaves Up, which was not pressed anew: the repeat stops
        assertNull(dpad.onHat(0f, -1f, 520))
        assertNull(dpad.repeatAt)
        assertNull(dpad.repeat(2000))
        // The next press works as ever
        dpad.onHat(0f, 0f, 2100)
        assertEquals(MenuDirection.DOWN, dpad.onHat(0f, 1f, 2200))
    }

    @Test
    fun aDiagonalPressedInOneReportMovesHorizontally() {
        val dpad = DpadTracker()
        assertEquals(MenuDirection.LEFT, dpad.onHat(-1f, 1f, 0))
        assertNull(dpad.onHat(-1f, 1f, 5))
    }

    @Test
    fun smallHatValuesAreTheCenter() {
        val dpad = DpadTracker()
        assertNull(dpad.onHat(.3f, -.2f, 0))
        assertNull(dpad.repeatAt)
    }

    @Test
    fun aDpadKeyMovesWhenNoHatSendsItToo() {
        val dpad = DpadTracker()
        assertTrue(dpad.onKey(MenuDirection.LEFT, down = true))
        // The system's key repeat comes as more downs
        assertTrue(dpad.onKey(MenuDirection.LEFT, down = true))
        assertFalse(dpad.onKey(MenuDirection.LEFT, down = false))
        assertNull(dpad.repeatAt)
    }

    @Test
    fun aDeviceThatSendsTheHatAndTheKeyMovesOncePerPress() {
        val dpad = DpadTracker()
        // Hat first, then the key of the same press
        assertEquals(MenuDirection.DOWN, dpad.onHat(0f, 1f, 0))
        assertFalse(dpad.onKey(MenuDirection.DOWN, down = true))
        assertFalse(dpad.onKey(MenuDirection.DOWN, down = true))
        assertNull(dpad.onHat(0f, 0f, 80))
        assertFalse(dpad.onKey(MenuDirection.DOWN, down = false))
        // Key first, then the hat of the same press: the hat does not move, nor repeat (the key's repeat does)
        assertTrue(dpad.onKey(MenuDirection.UP, down = true))
        assertNull(dpad.onHat(0f, -1f, 100))
        assertNull(dpad.repeatAt)
        assertNull(dpad.onHat(0f, 0f, 200))
        assertFalse(dpad.onKey(MenuDirection.UP, down = false))
        // After the release everything works again
        assertEquals(MenuDirection.UP, dpad.onHat(0f, -1f, 300))
        assertNull(dpad.onHat(0f, 0f, 310))
        assertTrue(dpad.onKey(MenuDirection.UP, down = true))
    }

    @Test
    fun aDifferentDirectionOnTheKeyIsNotMistakenForTheHat() {
        val dpad = DpadTracker()
        assertEquals(MenuDirection.LEFT, dpad.onHat(-1f, 0f, 0))
        assertTrue(dpad.onKey(MenuDirection.UP, down = true))
    }

    @Test
    fun resetForgetsAHeldHatAndKey() {
        val dpad = DpadTracker()
        dpad.onHat(1f, 0f, 0)
        dpad.onKey(MenuDirection.LEFT, down = true)
        dpad.reset()
        assertNull(dpad.repeatAt)
        assertEquals(MenuDirection.RIGHT, dpad.onHat(1f, 0f, 10))
        dpad.reset()
        assertTrue(dpad.onKey(MenuDirection.RIGHT, down = true))
    }

    @Test
    fun aHoldsTheFocusedKeyDownAndLetsGoEvenWhenTheReleaseIsCancelled() {
        for (key in intArrayOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER)) {
            assertEquals(PadAction.PressDown, padKeyAction(key, KeyEvent.ACTION_DOWN, 0, false))
            assertNull(padKeyAction(key, KeyEvent.ACTION_DOWN, 1, false))
            assertEquals(PadAction.PressUp, padKeyAction(key, KeyEvent.ACTION_UP, 0, false))
            assertEquals(PadAction.PressUp, padKeyAction(key, KeyEvent.ACTION_UP, 0, true))
        }
    }

    @Test
    fun bAndBackCloseOnRelease() {
        for (key in intArrayOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK)) {
            assertNull(padKeyAction(key, KeyEvent.ACTION_DOWN, 0, false))
            assertEquals(PadAction.Close, padKeyAction(key, KeyEvent.ACTION_UP, 0, false))
            assertNull(padKeyAction(key, KeyEvent.ACTION_UP, 0, true))
        }
    }

    @Test
    fun l3TogglesShiftOnPressOnlyAndMovesNoFocus() {
        val l3 = KeyEvent.KEYCODE_BUTTON_THUMBL
        assertEquals(PadAction.ToggleShift, padKeyAction(l3, KeyEvent.ACTION_DOWN, 0, false))
        assertNull(padKeyAction(l3, KeyEvent.ACTION_DOWN, 2, false))
        assertNull(padKeyAction(l3, KeyEvent.ACTION_UP, 0, false))
        assertNull(dpadDirection(l3))
        assertFalse(padKeyAction(l3, KeyEvent.ACTION_DOWN, 0, false) is PadAction.Move)
    }

    @Test
    fun l3LatchesShiftAndTheNextPressReleasesIt() {
        val latch = ModifierLatch()
        val shift = KeyEvent.KEYCODE_SHIFT_LEFT
        assertEquals(KeySend.DOWN, latch.padPress(shift))
        assertTrue(latch.isLatched(shift))
        assertEquals(KeySend.UP, latch.padPress(shift))
        assertFalse(latch.isLatched(shift))
    }

    @Test
    fun xIsBackspaceAndYIsSpaceDownOnPressUpOnReleaseOrCancel() {
        val keys = mapOf(
            KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_SPACE,
        )
        for ((button, key) in keys) {
            assertEquals(PadAction.BoundDown(key), padKeyAction(button, KeyEvent.ACTION_DOWN, 0, false))
            // The keyboard repeats the key itself: the system's repeats are ignored
            assertNull(padKeyAction(button, KeyEvent.ACTION_DOWN, 1, false))
            assertEquals(PadAction.BoundUp(key), padKeyAction(button, KeyEvent.ACTION_UP, 0, false))
            assertEquals(PadAction.BoundUp(key), padKeyAction(button, KeyEvent.ACTION_UP, 0, true))
            assertEquals(key, boundKeyCode(button))
        }
    }

    @Test
    fun heldKeysRepeatLikeTheDpad() {
        assertEquals(350L, PAD_REPEAT_DELAY_MS)
        assertEquals(100L, PAD_REPEAT_INTERVAL_MS)
        val dpad = DpadTracker()
        assertEquals(MenuDirection.RIGHT, dpad.onHat(1f, 0f, 1000))
        assertEquals(1000 + PAD_REPEAT_DELAY_MS, dpad.repeatAt)
    }

    @Test
    fun otherButtonsDoNothingForTheKeyboard() {
        for (key in intArrayOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_THUMBR)) {
            assertNull(padKeyAction(key, KeyEvent.ACTION_DOWN, 0, false))
            assertNull(padKeyAction(key, KeyEvent.ACTION_UP, 0, false))
        }
    }

    @Test
    fun aStickMovesTheFocusInItsStrongerDirectionPastTheThreshold() {
        assertNull(analogDirection(.3f, -.4f))
        assertEquals(MenuDirection.LEFT, analogDirection(-.9f, .2f))
        assertEquals(MenuDirection.RIGHT, analogDirection(.8f, -.7f))
        assertEquals(MenuDirection.UP, analogDirection(.2f, -.9f))
        assertEquals(MenuDirection.DOWN, analogDirection(-.5f, .6f))
        // Equal strength goes vertical, like the stream menu
        assertEquals(MenuDirection.DOWN, analogDirection(.7f, .7f))
    }

    @Test
    fun onlyControllersAreInputSourcesTheOpenKeyboardOwns() {
        assertTrue(isControllerSource(android.view.InputDevice.SOURCE_GAMEPAD))
        assertTrue(isControllerSource(android.view.InputDevice.SOURCE_GAMEPAD or android.view.InputDevice.SOURCE_DPAD or android.view.InputDevice.SOURCE_JOYSTICK))
        assertTrue(isControllerSource(android.view.InputDevice.SOURCE_DPAD))
        assertTrue(isControllerSource(android.view.InputDevice.SOURCE_JOYSTICK))
        assertFalse(isControllerSource(android.view.InputDevice.SOURCE_KEYBOARD))
        assertFalse(isControllerSource(android.view.InputDevice.SOURCE_MOUSE))
        assertFalse(isControllerSource(android.view.InputDevice.SOURCE_TOUCHSCREEN))
        // The keys the keyboard sends itself have source 0
        assertFalse(isControllerSource(0))
    }

    // ---- Size ----

    @Test
    fun theAutomaticFitFillsTheWidthUpToTheCapAndSizesTheKeysFromIt() {
        val prefs = KeyboardPrefs()
        val sixteenNine = keyboardMetrics(1920f, prefs, .444f)
        assertEquals(1840f, sixteenNine.bodyWidth, 0f)
        assertEquals(1840f * .0365f, sixteenNine.keyHeight, .001f)
        // 21:9 keeps the width and the keys: it only centers
        val ultrawide = keyboardMetrics(2520f, prefs, .444f)
        assertEquals(1840f, ultrawide.bodyWidth, 0f)
        assertEquals(sixteenNine.keyHeight, ultrawide.keyHeight, 0f)
        // 4:3 is narrower: smaller keys, not a taller keyboard
        val fourThree = keyboardMetrics(1440f, prefs, .444f)
        assertEquals(1440f - 48f, fourThree.bodyWidth, 0f)
        assertTrue(fourThree.keyHeight < sixteenNine.keyHeight && fourThree.keyHeight >= KeyboardMetrics.MIN_KEY)
        assertEquals(KeyboardMetrics.MIN_KEY, keyboardMetrics(400f, prefs, .444f).keyHeight, 0f)
    }

    @Test
    fun theBarIsAboutHalfTheScreenAtSixteenByNine() {
        val bar = keyboardMetrics(1920f, KeyboardPrefs(), .444f).barHeight
        assertEquals(532f, bar, 1f)
    }

    @Test
    fun theSizeSettingsApplyOnlyWithTheAutomaticFitOff() {
        val ignored = keyboardMetrics(1920f, KeyboardPrefs(autoFitDisabled = false, heightDp = 400, widthDp = 300), .444f)
        assertEquals(keyboardMetrics(1920f, KeyboardPrefs(), .444f), ignored)

        val custom = keyboardMetrics(1920f, KeyboardPrefs(autoFitDisabled = true, heightDp = 200, widthDp = 500), .444f)
        assertEquals(500f / .444f, custom.bodyWidth, .01f)
        // The whole bar is as high as the setting says: 200 dp = 450 stage pixels
        assertEquals(200f / .444f, custom.barHeight, .5f)

        val tall = keyboardMetrics(1920f, KeyboardPrefs(autoFitDisabled = true, heightDp = 300, widthDp = 500), .444f)
        assertTrue(tall.keyHeight > custom.keyHeight)
        val full = keyboardMetrics(1920f, KeyboardPrefs(autoFitDisabled = true, heightDp = 200, widthDp = 1000), .444f)
        assertEquals(1840f, full.bodyWidth, 0f)
        // A width wider than the screen never overflows it
        assertEquals(1840f, keyboardMetrics(1920f, KeyboardPrefs(autoFitDisabled = true, widthDp = 990), .444f).bodyWidth, 0f)
    }

    @Test
    fun theAlignModeSettingIsParsedAndUnknownValuesCenter() {
        assertEquals(KeyboardAlign.LEFT, KeyboardAlign.parse("left"))
        assertEquals(KeyboardAlign.RIGHT, KeyboardAlign.parse("right"))
        assertEquals(KeyboardAlign.CENTER, KeyboardAlign.parse("center"))
        assertEquals(KeyboardAlign.CENTER, KeyboardAlign.parse(null))
        assertEquals(KeyboardAlign.CENTER, KeyboardAlign.parse("middle"))
    }
}
