package com.limelight.shell

import com.limelight.shell.settings.StreamMenuHelp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        assertEquals(200f, bar.left, 0f)
        assertEquals(1720f, bar.right, 0f)
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
    fun thePopoverEndsAtTheBarsRightEndAndStopsAt440() {
        val bar = barFrame(1920f, 1080f)
        val popover = popoverFrame(bar, Float.MAX_VALUE)
        assertEquals(bar.right, popover.right, 0f)
        assertEquals(StreamMenuLayout.POPOVER_WIDTH, popover.width, 0f)
        assertEquals(StreamMenuLayout.POPOVER_MAX_HEIGHT, popover.height, 0f)
        assertEquals(bar.top - StreamMenuLayout.POPOVER_GAP, popover.bottom, 0f)
        // The picture's centre stays clear: the popover starts right of the middle of the screen
        assertTrue(popover.left > 1920f / 2)
    }

    @Test
    fun shortContentKeepsItsHeightAndTheWidthNeverExceedsTheBar() {
        val bar = barFrame(1920f, 1080f)
        assertEquals(160f, popoverFrame(bar, 160f).height, 0f)
        val narrow = barFrame(600f, 1080f)
        assertEquals(narrow.width, popoverFrame(narrow, 200f).width, 0f)
    }

    @Test
    fun aShortScreenNeverPushesThePopoverIntoTheTopMargin() {
        for (height in listOf(540f, 600f, 720f, 1080f)) {
            val popover = popoverFrame(barFrame(1920f, height), Float.MAX_VALUE)
            assertTrue("height $height: top ${popover.top}", popover.top >= safe)
            assertTrue(popover.bottom <= barFrame(1920f, height).top)
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
