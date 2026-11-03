package com.limelight.shell

import com.limelight.shell.settings.StreamMenuHelp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMenuLayoutTest {
    private val safe = StreamMenuLayout.SAFE_MARGIN

    @Test
    fun closingAPopoverReturnsTheFocusToItsCircle() {
        for (page in MenuPage.entries.filter { it != MenuPage.MAIN && it.parent() == MenuPage.MAIN }) {
            assertEquals(PageExit(MenuPage.MAIN, page.barItem(), null), pageAfterBack(page))
            assertEquals(PageExit(MenuPage.MAIN, page.barItem(), null), pageAfterDismiss(page))
        }
        assertEquals(BarItem.UPSCALER, pageAfterBack(MenuPage.UPSCALER)?.focusCircle)
        assertEquals(BarItem.BRIGHTNESS, pageAfterBack(MenuPage.BRIGHTNESS)?.focusCircle)
        assertEquals(BarItem.BITRATE, pageAfterBack(MenuPage.BITRATE)?.focusCircle)
        assertEquals(BarItem.MORE, pageAfterBack(MenuPage.MORE)?.focusCircle)
    }

    @Test
    fun pagesOpenedFromMoreGoBackToTheirRowAndATapOutsideClosesEverything() {
        assertEquals(PageExit(MenuPage.MORE, null, "Send keys"), pageAfterBack(MenuPage.KEYS))
        assertEquals(PageExit(MenuPage.MORE, null, "Commands"), pageAfterBack(MenuPage.COMMANDS))
        assertEquals(PageExit(MenuPage.MORE, null, "Mouse mode"), pageAfterBack(MenuPage.MOUSE))
        for (page in listOf(MenuPage.KEYS, MenuPage.COMMANDS, MenuPage.MOUSE)) {
            assertEquals(PageExit(MenuPage.MAIN, BarItem.MORE, null), pageAfterDismiss(page))
        }
    }

    @Test
    fun theBarItselfIsLeftOnlyByResumingAndATapOutsideNeverResumes() {
        assertNull(pageAfterBack(MenuPage.MAIN))
        assertNull(pageAfterDismiss(MenuPage.MAIN))
    }

    @Test
    fun everyPageWalksBackToTheBarInAtMostTwoSteps() {
        for (page in MenuPage.entries.filter { it != MenuPage.MAIN }) {
            var at = page
            var steps = 0
            while (at != MenuPage.MAIN) {
                at = pageAfterBack(at)!!.page
                steps++
            }
            assertTrue("$page needs $steps steps", steps <= 2)
        }
    }

    @Test
    fun theBarIsCenteredAnd48FromTheBottomOnTheStage() {
        val bar = barFrame(1920f, 1080f)
        assertEquals(110f, bar.left, 0f)
        assertEquals(1810f, bar.right, 0f)
        assertEquals(1080f - safe, bar.bottom, 0f)
        assertEquals(StreamMenuLayout.BAR_HEIGHT, bar.height, 0f)
    }

    @Test
    fun aNarrowScreenKeepsTheBarInsideTheSafeMargins() {
        val bar = barFrame(1440f, 1080f)
        assertEquals(safe, bar.left, 0f)
        assertEquals(1440f - safe, bar.right, 0f)
        assertEquals(0f, barFrame(40f, 1080f).width, 0f)
    }

    @Test
    fun theNineCirclesShareTheSpaceRightOfTheTitleEqually() {
        val bar = barFrame(1920f, 1080f)
        val (start, end) = circlesSpan(bar)
        assertTrue(start > bar.left && end < bar.right)
        val centers = BarItem.entries.map { circleCenterX(bar, it) }
        val step = centers[1] - centers[0]
        for (i in 1 until centers.size) assertEquals(step, centers[i] - centers[i - 1], .01f)
        // Every circle has room for its own size and a gap of at least 20 beside it
        assertTrue("cell $step", step >= StreamMenuLayout.CIRCLE_SIZE + 20f)
        assertEquals(start + step / 2f, centers.first(), .01f)
        assertEquals(end - step / 2f, centers.last(), .01f)
    }

    @Test
    fun aPopoverSitsAboveItsOwnCircleWithTheCaretPointingAtIt() {
        val bar = barFrame(1920f, 1080f)
        for (page in MenuPage.entries.filter { it != MenuPage.MAIN }) {
            val placement = popoverPlacement(page, bar, 1920f)!!
            val circle = circleCenterX(bar, page.barItem()!!)
            // On the full stage nothing needs shifting: the card is centered on its circle and the caret is at its center
            assertEquals("$page card", circle, placement.left + placement.width / 2f, .01f)
            assertEquals("$page caret", circle, placement.caretX, .01f)
            assertTrue("$page width ${placement.width}", placement.width in 700f..860f)
        }
        assertEquals(0f, popoverWidth(MenuPage.MAIN, bar), 0f)
        assertNull(popoverPlacement(MenuPage.MAIN, bar, 1920f))
    }

    @Test
    fun pagesOpenedFromMoreAreAnchoredOverTheMoreCircle() {
        val bar = barFrame(1920f, 1080f)
        for (page in listOf(MenuPage.KEYS, MenuPage.COMMANDS, MenuPage.MOUSE, MenuPage.MORE)) {
            assertEquals("$page", circleCenterX(bar, BarItem.MORE), popoverPlacement(page, bar, 1920f)!!.caretX, .01f)
        }
    }

    @Test
    fun theCardShiftsInwardNearTheEndsOfTheBar() {
        val bar = barFrame(1920f, 1080f)
        val width = 840f
        // Far left: the card starts at the bar's left end, the caret still points at the circle
        assertEquals(bar.left, popoverLeft(200f, width, bar, 1920f), 0f)
        assertEquals(300f, caretCenterX(300f, bar.left, width), 0f)
        // Far right: the card ends at the bar's right end
        assertEquals(bar.right - width, popoverLeft(1750f, width, bar, 1920f), 0f)
        // In the middle: centered on the anchor
        assertEquals(960f - width / 2f, popoverLeft(960f, width, bar, 1920f), 0f)
    }

    @Test
    fun theCaretStaysAtLeast40FromTheCornersOfTheCard() {
        val inset = StreamMenuLayout.CARET_MIN_INSET
        // An anchor right at the card's edge (or outside it) is pulled in
        assertEquals(110f + inset, caretCenterX(100f, 110f, 800f), 0f)
        assertEquals(110f + 800f - inset, caretCenterX(5000f, 110f, 800f), 0f)
        assertEquals(500f, caretCenterX(500f, 110f, 800f), 0f)
    }

    @Test
    fun aNarrowWindowShrinksTheCardToTheBarAndKeepsTheSafeMargins() {
        for (areaWidth in listOf(900f, 1100f, 1280f, 1440f, 1920f, 2560f)) {
            val bar = barFrame(areaWidth, 1080f)
            for (page in MenuPage.entries.filter { it != MenuPage.MAIN }) {
                val placement = popoverPlacement(page, bar, areaWidth)!!
                val right = placement.left + placement.width
                assertTrue("$page at $areaWidth: left ${placement.left}", placement.left >= safe)
                assertTrue("$page at $areaWidth: right $right", right <= areaWidth - safe)
                assertTrue("$page at $areaWidth: inside the bar", placement.left >= bar.left && right <= bar.right)
                assertTrue("$page at $areaWidth: caret ${placement.caretX}",
                    placement.caretX >= placement.left + StreamMenuLayout.CARET_MIN_INSET && placement.caretX <= right - StreamMenuLayout.CARET_MIN_INSET)
                if (bar.width >= StreamMenuLayout.POPOVER_MIN_WIDTH) assertTrue("$page at $areaWidth: ${placement.width}", placement.width >= StreamMenuLayout.POPOVER_MIN_WIDTH)
                else assertEquals(bar.width, placement.width, 0f)
            }
        }
    }

    @Test
    fun thePopoverHasTheRoomBetweenTheBarsGapAndTheTopMargin() {
        for (height in listOf(1080f, 1200f, 720f)) {
            val bar = barFrame(1920f, height)
            assertEquals(bar.top - StreamMenuLayout.POPOVER_GAP - safe, popoverMaxHeight(bar), 0f)
        }
        // A screen too short for the bar and its margins leaves no negative room
        assertEquals(0f, popoverMaxHeight(barFrame(1920f, 100f)), 0f)
    }

    @Test
    fun theTallestPageFitsAboveTheBarWithoutScrollingAtTheLargestTextScale() {
        val bar = barFrame(1920f, 1080f)
        val c = StreamMenuLayout
        // The Upscaler page with supersampling: the toggle, quality, mode, sharpness and Save for this game
        val rows = 5 * c.ROW_HEIGHT + 4 * c.ROW_SPACING
        val footer = 1f + 12f + popoverFooterTextHeight(popoverFooterLines(MenuPage.UPSCALER)) + 14f
        val card = c.POPOVER_PADDING_TOP + c.POPOVER_HEADER_HEIGHT + 6f + rows + 8f + footer
        assertTrue("card $card of ${popoverMaxHeight(bar)}", card + c.CARET_HEIGHT <= popoverMaxHeight(bar))
    }

    @Test
    fun theHelpBoxesHoldTheirLinesAtTheLargestTextScale() {
        val c = StreamMenuLayout
        assertTrue(c.HELP_TEXT_HEIGHT >= c.HELP_LINES * c.HELP_TEXT_SIZE * c.MAX_TEXT_SCALE * c.HELP_LINE_HEIGHT)
        for (page in MenuPage.entries.filter { it != MenuPage.MAIN }) {
            val lines = popoverFooterLines(page)
            assertTrue(popoverFooterTextHeight(lines) >= lines * c.FOOTER_TEXT_SIZE * c.MAX_TEXT_SCALE * c.FOOTER_LINE_HEIGHT)
        }
    }

    private val statuses = listOf(
        "Off", "On", "On: SGSR 1 Standard", "On: SGSR 1 High Quality", "On: SGSR 2 Video", "On: SGSR 2 Video Quality",
        "Starting", "Stopping", "Unavailable; direct rendering is active",
    )

    private val problems = mutableListOf<String>()

    private fun assertFits(what: String, text: String, lineChars: Int, maxLines: Int) {
        val body = helpLineText(text)
        val lines = wrappedLineCount(body, lineChars)
        if (body.isBlank()) problems += "Empty help for $what"
        else if (lines > maxLines) problems += "Help for $what needs $lines lines (max $maxLines at $lineChars characters):\n$body"
    }

    @After
    fun everyHelpFits() {
        assertTrue("${problems.size} help texts do not fit:\n\n" + problems.joinToString("\n\n"), problems.isEmpty())
    }

    @Test
    fun everyHelpTheBarCanShowFitsItsHelpArea() {
        val chars = barHelpLineChars()
        for (item in BarItem.entries) for (supersampling in listOf(false, true)) for (status in statuses) {
            val key = circleHelpKey(item, supersampling)
            assertFits("circle $key ($status)", streamMenuHelp(key, status, 0, 0, false), chars, StreamMenuLayout.HELP_LINES)
        }
    }

    @Test
    fun everyHelpAPopoverCanShowFitsItsFooter() {
        val bar = barFrame(1920f, 1080f)
        val footerKeys = mapOf(
            MenuPage.MORE to listOf("More", MoreRow.KEYS, "Send clipboard", "Get clipboard", MoreRow.COMMANDS, "Task manager", "Zoom", MoreRow.MOUSE,
                "On-screen controls", "Full keyboard", "Key layout", "Touch sensitivity", "Rotate"),
            // The labels of key combinations and commands come from the user and the host
            MenuPage.KEYS to listOf("Send keys", "Keys: Ctrl + Alt + Delete", "Keys: " + "K".repeat(40)),
            MenuPage.COMMANDS to listOf("Commands", "Command: Restart the game", "Command: " + "C".repeat(48)),
            MenuPage.BRIGHTNESS to listOf("Screen brightness"),
            MenuPage.BITRATE to listOf("Bitrate", "Apply bitrate"),
            MenuPage.UPSCALER to listOf("Upscaling", "Supersampling on", "Supersampling quality", "Mode", "Sharpness", "Save for this game"),
            MenuPage.MOUSE to listOf("Touchscreen mode", "Local mouse cursor"),
        )
        assertEquals(MenuPage.entries.filter { it != MenuPage.MAIN }.toSet(), footerKeys.keys)
        for ((page, keys) in footerKeys) {
            val chars = popoverFooterLineChars(page, bar)
            val lines = popoverFooterLines(page)
            for (key in keys) for (status in statuses) for (mode in 0..3) for (mouse in 0..5) for (remember in listOf(false, true)) {
                assertFits("$page/$key ($status, mode $mode, mouse $mouse)", streamMenuHelp(key, status, mode, mouse, remember), chars, lines)
            }
            assertFits("$page default", streamMenuHelp(pageHelpKey(page), statuses[8], 0, 0, false), chars, lines)
        }
    }

    @Test
    fun theUpscalerCircleShowsOneWordOfTheRenderersStatus() {
        assertEquals("On", upscalerCircleState("On: SGSR 2 Video Quality"))
        assertEquals("On", upscalerCircleState("On"))
        assertEquals("Off", upscalerCircleState("Off"))
        assertEquals("Starting", upscalerCircleState("Starting"))
        assertEquals("Unavailable", upscalerCircleState("Unavailable; direct rendering is active"))
    }

    @Test
    fun theHelpLineJoinsParagraphsOnOneLine() {
        assertEquals("Closes this menu.", helpLineText("Closes this menu."))
        assertEquals("On: SGSR 2 Video \u00B7 Applies live.", helpLineText("On: SGSR 2 Video\n\nApplies live."))
        assertEquals("Reuses frames. Applies live.", helpLineText("Reuses frames.\n\nApplies live."))
        assertEquals("", helpLineText("\n \n"))
    }

    @Test
    fun theGradientDarkensLessThanEightPercentOfTheScreenOnAverage() {
        val half = StreamMenuLayout.GRADIENT_MID_ALPHA / 2 * .5f + (StreamMenuLayout.GRADIENT_MID_ALPHA + StreamMenuLayout.GRADIENT_BOTTOM_ALPHA) / 2 * .5f
        val mean = half * StreamMenuLayout.GRADIENT_HEIGHT / 1080f
        assertTrue("mean darkening $mean", mean < .08f)
    }

    @Test
    fun everyCircleAndEveryPageHasHelpForTheSingleHelpLine() {
        val circles = listOf("Resume", "Keyboard", "Overlay", "Brightness", "Bitrate", "Upscaler", "Supersampling", "More", "Disconnect", "Quit game")
        // The mouse page's help comes from the settings catalog (the active mode), not from StreamMenuHelp
        val pages = MenuPage.entries.filter { it != MenuPage.MOUSE }.map { pageHelpKey(it) }
        for (key in circles + pages) {
            assertNotEquals("no help for $key", "", StreamMenuHelp.description(key))
        }
    }

    @Test
    fun everyPageHasATitleAndTheRowsOfMoreThatOpenPagesAreNamed() {
        for (page in MenuPage.entries.filter { it != MenuPage.MAIN }) {
            assertNotEquals("$page", "", pageTitle(page, supersampling = false))
        }
        assertEquals("Supersampling", pageTitle(MenuPage.UPSCALER, supersampling = true))
        assertEquals("Snapdragon Game Super Resolution", pageTitle(MenuPage.UPSCALER, supersampling = false))
        for (page in MenuPage.entries.filter { it.parent() == MenuPage.MORE }) {
            assertTrue("$page has no row in More", page.openedByRow() != null)
        }
    }
}
