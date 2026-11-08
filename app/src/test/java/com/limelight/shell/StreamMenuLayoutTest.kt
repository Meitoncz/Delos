package com.limelight.shell

import com.limelight.shell.ui.Motion
import com.limelight.shell.settings.StreamMenuHelp
import com.limelight.utils.RumblePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMenuLayoutTest {
    private val c = StreamMenuLayout

    @Test
    fun closingAPopoverReturnsTheFocusToItsTile() {
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
        assertEquals(PageExit(MenuPage.MORE, null, "Rumble feel"), pageAfterBack(MenuPage.RUMBLE))
        for (page in listOf(MenuPage.KEYS, MenuPage.COMMANDS, MenuPage.MOUSE, MenuPage.RUMBLE)) {
            assertEquals(PageExit(MenuPage.MAIN, BarItem.MORE, null), pageAfterDismiss(page))
        }
    }

    @Test
    fun theDockItselfIsLeftOnlyByResumingAndATapOutsideNeverResumes() {
        assertNull(pageAfterBack(MenuPage.MAIN))
        assertNull(pageAfterDismiss(MenuPage.MAIN))
    }

    @Test
    fun everyPageWalksBackToTheDockInAtMostTwoSteps() {
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
    fun nineTilesOf150WithGapsOf40FillTheStageWith125OnEachSide() {
        assertEquals(1670f, c.TILES_WIDTH, 0f)
        assertEquals(125f, c.TILES_LEFT, 0f)
        assertEquals(125f, tileLeft(BarItem.RESUME), 0f)
        val last = tileLeft(BarItem.QUIT) + c.TILE
        assertEquals(c.STAGE_WIDTH - 125f, last, 0f)
        for (item in BarItem.entries) {
            assertEquals(125f + item.ordinal * 190f, tileLeft(item), 0f)
            assertEquals(tileLeft(item) + 75f, tileCenterX(item), 0f)
        }
    }

    @Test
    fun theRingIsA186BoxCenteredOnItsTileAndReachesAbove() {
        for (item in BarItem.entries) assertEquals(tileCenterX(item), ringLeft(item) + c.RING / 2f, 0f)
        assertEquals(-18f, RING_TOP, 0f)
        // The focused tile at 1.12 is 168 wide: the ring is 9 px outside it
        assertEquals(9f, (c.RING - c.TILE * c.FOCUS_SCALE) / 2f, .001f)
    }

    @Test
    fun aPopoverSitsAboveItsOwnTileWithTheCaretPointingAtIt() {
        for (page in MenuPage.entries.filter { it != MenuPage.MAIN }) {
            val placement = popoverPlacement(page)!!
            val tile = tileCenterX(page.barItem()!!)
            // The caret is where the tile is, whatever the card had to move to stay inside the stage
            assertEquals("$page caret", tile, placement.left + placement.caretX, .01f)
            assertTrue("$page left ${placement.left}", placement.left >= c.POPOVER_MIN_LEFT)
            assertTrue("$page right", placement.left + placement.width <= c.POPOVER_MAX_RIGHT)
            assertTrue("$page caret inset", placement.caretX >= c.CARET_MIN_INSET && placement.caretX <= placement.width - c.CARET_MIN_INSET)
        }
        assertEquals(0f, popoverWidth(MenuPage.MAIN), 0f)
        assertNull(popoverPlacement(MenuPage.MAIN))
    }

    @Test
    fun theWidthsOfThePrototypeHold() {
        assertEquals(780f, popoverWidth(MenuPage.BRIGHTNESS), 0f)
        assertEquals(860f, popoverWidth(MenuPage.BITRATE), 0f)
        assertEquals(980f, popoverWidth(MenuPage.UPSCALER), 0f)
        for (page in listOf(MenuPage.MORE, MenuPage.KEYS, MenuPage.COMMANDS, MenuPage.MOUSE, MenuPage.RUMBLE)) assertEquals(920f, popoverWidth(page), 0f)
    }

    @Test
    fun pagesOpenedFromMoreAreAnchoredOverTheMoreTile() {
        for (page in listOf(MenuPage.KEYS, MenuPage.COMMANDS, MenuPage.MOUSE, MenuPage.RUMBLE, MenuPage.MORE)) {
            val placement = popoverPlacement(page)!!
            assertEquals("$page", tileCenterX(BarItem.MORE), placement.left + placement.caretX, .01f)
            assertEquals(popoverPlacement(MenuPage.MORE), placement)
        }
    }

    @Test
    fun theCardIsKeptInsideTheStageAndTheCaretOffItsCorners() {
        // A tile near the left end: the card stops at the margin (120) and the caret moves left inside it
        assertEquals(120f, popoverLeft(200f, 980f), 0f)
        assertEquals(200f - 120f, caretCenterX(200f, 120f, 980f), 0f)
        // Near the right end: it stops 120 from the right edge
        assertEquals(1800f - 920f, popoverLeft(1750f, 920f), 0f)
        // In the middle: centered on the anchor
        assertEquals(960f - 430f, popoverLeft(960f, 860f), 0f)
        // An anchor right at the card's corner is pulled in to 72
        assertEquals(72f, caretCenterX(100f, 120f, 800f), 0f)
        assertEquals(800f - 72f, caretCenterX(5000f, 120f, 800f), 0f)
        assertEquals(380f, caretCenterX(500f, 120f, 800f), 0f)
    }

    @Test
    fun theSideMarginsAreTheTopMarginAndEveryPageKeepsThem() {
        assertEquals(c.POPOVER_MARGIN, c.POPOVER_MIN_LEFT, 0f)
        assertEquals(c.STAGE_WIDTH - c.POPOVER_MARGIN, c.POPOVER_MAX_RIGHT, 0f)
        // A card at its height limit: its top edge is the margin below the stage's top
        assertEquals(c.POPOVER_MARGIN, c.POPOVER_CARD_BOTTOM - c.POPOVER_MAX_HEIGHT, 0f)
        assertEquals(614f, c.POPOVER_MAX_HEIGHT, 0f)
        for (page in MenuPage.entries.filter { it != MenuPage.MAIN }) {
            val placement = popoverPlacement(page)!!
            val left = placement.left
            val right = c.STAGE_WIDTH - (placement.left + placement.width)
            assertTrue("$page left $left", left >= c.POPOVER_MARGIN - .001f)
            assertTrue("$page right $right", right >= c.POPOVER_MARGIN - .001f)
        }
        // Pushed against the right edge (the Disconnect tile): the gap to the edge is the gap to the top
        val atEdge = popoverLeft(tileCenterX(BarItem.DISCONNECT), popoverWidth(MenuPage.MORE))
        assertEquals(c.POPOVER_MARGIN, c.STAGE_WIDTH - (atEdge + popoverWidth(MenuPage.MORE)), 0f)
        // and the caret still points at the tile
        assertEquals(tileCenterX(BarItem.DISCONNECT), atEdge + caretCenterX(tileCenterX(BarItem.DISCONNECT), atEdge, popoverWidth(MenuPage.MORE)), .01f)
    }

    @Test
    fun theCardNeverReachesTheCompactStatisticsLineAtAnyTextScale() {
        for (scale in listOf(1f, 1.15f, 1.3f)) {
            val gap = popoverStatsGap(scale)
            assertTrue("scale $scale gap $gap", gap >= c.STATS_GAP - .01f)
        }
        assertEquals(61.2f, compactStatsBottom(1f), .01f)
        assertEquals(68.76f, compactStatsBottom(c.MAX_TEXT_SCALE), .01f)
    }

    @Test
    fun theCaretTipClearsTheRingOfTheSunkTile() {
        val cardBottom = c.STAGE_HEIGHT - c.POPOVER_BOTTOM - c.CARET_HEIGHT
        val tip = cardBottom + c.CARET_HEIGHT
        val sunkRingTop = c.DOCK_TOP + c.DOCK_SHIFT + RING_TOP
        assertEquals(734f, cardBottom, 0f)
        assertTrue("tip $tip ring $sunkRingTop", tip < sunkRingTop)
        assertEquals(12f, sunkRingTop - tip, 1f)
    }

    @Test
    fun everyPageKeepsItsNaturalHeightUpToOneSharedLimitAndTheRowsScrollBeyondIt() {
        // The Upscaler page with supersampling (toggle, quality, mode, sharpness, Save for this game) is 601.6 tall at
        // text scale 1.0 and fits the limit without scrolling, also at 1.15 (610.8); at 1.3 the three-line help
        // makes it scroll
        assertEquals(601.6f, popoverCardHeight(5, 1f), .01f)
        assertTrue(popoverFits(5, 1f))
        assertEquals(610.84f, popoverCardHeight(5, 1.15f), .01f)
        assertTrue(popoverFits(5, 1.15f))
        assertTrue(!popoverFits(5, c.MAX_TEXT_SCALE))
        assertTrue(popoverFits(4, c.MAX_TEXT_SCALE))
        // Small pages stay small
        assertEquals(30f + 64f + 12f + rowsHeight(2) + 18f + popoverHelpHeight(1f) + 24f, popoverCardHeight(2, 1f), .01f)
        assertTrue(popoverCardHeight(2, 1f) < popoverCardHeight(4, 1f))
        // The More page, centered on its tile, ends exactly the margin from the stage's right edge
        val more = popoverPlacement(MenuPage.MORE)!!
        assertEquals(c.POPOVER_MARGIN, c.STAGE_WIDTH - (more.left + more.width), 0f)
        // More has 11 rows (with Mouse mode): the card stops at the shared limit and the rows scroll
        assertTrue(!popoverFits(11, 1f))
        assertEquals(c.POPOVER_MAX_HEIGHT, popoverCardHeight(11, 1f), 0f)
        assertEquals(72f * 11 + 8f * 10, rowsHeight(11), 0f)
        assertEquals(0f, rowsHeight(0), 0f)
        // No page is ever taller than the limit, at any text scale
        for (scale in listOf(1f, 1.15f, 1.3f)) for (rows in 0..20) assertTrue(popoverCardHeight(rows, scale) <= c.POPOVER_MAX_HEIGHT)
        // The rows area keeps room for at least four rows beside the fixed header and help, even at the largest text scale
        val fixed = c.POPOVER_PADDING_Y + c.POPOVER_HEAD_HEIGHT + c.POPOVER_GAP_AFTER_HEAD + c.POPOVER_HELP_GAP + popoverHelpHeight(c.MAX_TEXT_SCALE) + c.POPOVER_PADDING_BOTTOM
        assertTrue(c.POPOVER_MAX_HEIGHT - fixed >= rowsHeight(4))
    }

    @Test
    fun theHelpBoxesReserveTheirLinesAtEveryTextScale() {
        assertEquals(2, helpLines(1f))
        assertEquals(2, helpLines(1.15f))
        assertEquals(3, helpLines(1.3f))
        assertEquals(22f * 1.4f * 2f, popoverHelpHeight(1f), .01f)
        assertEquals(22f * 1.3f * 1.4f * 3f, popoverHelpHeight(1.3f), .01f)
        assertEquals(28f * 1.4f * 3f, captionHeight(1f), .01f)
    }

    @Test
    fun theCaptionSitsBelowTheLabelsAndEndsOnTheStageAtEveryTextScale() {
        assertEquals(236.8f, captionTop(1f), .01f)
        for (scale in listOf(1f, 1.15f, c.MAX_TEXT_SCALE)) {
            assertTrue("scale $scale", c.DOCK_TOP + captionTop(scale) + captionHeight(scale) <= c.STAGE_HEIGHT)
        }
    }

    /**
     * The width of the labels in Figtree SemiBold, in em, measured with the font file (Pillow ImageFont, Figtree.ttf at
     * weight 600): what the budget of the tiles' cells is checked with. The upscaler tile is called Upscaler or
     * Supersampling.
     */
    private val labelEm = mapOf(
        "Resume" to 3.63f, "Keyboard" to 4.39f, "Overlay" to 3.55f, "Brightness" to 4.83f, "Bitrate" to 3.07f,
        "Upscaler" to 4.0f, "Supersampling" to 6.78f, "More" to 2.34f, "Disconnect" to 5.14f, "Quit game" to 4.76f,
    )

    @Test
    fun neighbouringLabelsKeepTheirClearanceAtEveryTextScale() {
        val pitch = c.TILE + c.TILE_GAP
        for (scale in listOf(1f, 1.15f, c.MAX_TEXT_SCALE)) for (upscaler in listOf("Upscaler", "Supersampling")) {
            val labels = listOf("Resume", "Keyboard", "Overlay", "Brightness", "Bitrate", upscaler, "More", "Disconnect", "Quit game")
            val widths = labels.map { labelEm.getValue(it) * c.LABEL_SIZE * scale }
            for (i in 0 until widths.size - 1) {
                val clear = pitch - widths[i] / 2f - widths[i + 1] / 2f
                assertTrue("${labels[i]} / ${labels[i + 1]} at $scale: $clear", clear >= 28f)
            }
        }
    }

    private val statuses = listOf(
        "Off", "On", "On: SGSR 1 (Normal)", "On: SGSR 1 (Quality)", "On: SGSR2-DV (Normal)", "On: SGSR2-DV (Quality)", "On: Sharpen only",
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
    fun everyHelpTheCaptionCanShowFitsItsThreeLines() {
        for (scale in listOf(1f, 1.15f, c.MAX_TEXT_SCALE)) {
            val chars = captionLineChars(scale)
            for (item in BarItem.entries) for (supersampling in listOf(false, true)) for (status in statuses) {
                val key = circleHelpKey(item, supersampling)
                assertFits("tile $key ($status) at $scale", streamMenuHelp(key, status, 0, 0, false), chars, c.CAPTION_LINES)
            }
        }
    }

    @Test
    fun everyHelpAPopoverCanShowFitsItsReservedLines() {
        val footerKeys = mapOf(
            MenuPage.MORE to listOf(MoreRow.KEYS, "Send clipboard", "Get clipboard", MoreRow.COMMANDS, "Task manager", "Zoom", MoreRow.MOUSE,
                "On-screen controls", MoreRow.ANDROID_KEYBOARD, "Touch sensitivity", MoreRow.RUMBLE, MoreRow.GYRO, MoreRow.GYRO_DISABLED),
            // The labels of key combinations and commands come from the user and the host
            MenuPage.KEYS to listOf("Send keys", "Keys: Ctrl + Alt + Delete", "Keys: " + "K".repeat(40)),
            MenuPage.COMMANDS to listOf("Commands", "Command: Restart the game", "Command: " + "C".repeat(80)),
            MenuPage.BRIGHTNESS to listOf("Screen brightness"),
            MenuPage.BITRATE to listOf("Bitrate", "Apply bitrate"),
            MenuPage.UPSCALER to listOf("Upscaling", "Supersampling on", "Supersampling quality", "Mode", "Sharpness", "Save for this game"),
            MenuPage.MOUSE to listOf("Touchscreen mode", "Local mouse cursor"),
            // Rumble feel page
            MenuPage.RUMBLE to listOf("Rumble feel") + RumblePresets.values().map { StreamMenuHelp.rumbleKey(it) },
        )
        assertEquals(MenuPage.entries.filter { it != MenuPage.MAIN }.toSet(), footerKeys.keys)
        for (scale in listOf(1f, 1.15f, c.MAX_TEXT_SCALE)) for ((page, keys) in footerKeys) {
            val chars = popoverHelpLineChars(page, scale)
            val lines = helpLines(scale)
            for (key in keys) for (status in statuses) for (mode in 0..4) for (mouse in 0..5) for (remember in listOf(false, true)) {
                assertFits("$page/$key ($status, mode $mode, mouse $mouse) at $scale", streamMenuHelp(key, status, mode, mouse, remember), chars, lines)
            }
            assertFits("$page default at $scale", streamMenuHelp(pageHelpKey(page), statuses.last(), 0, 0, false), chars, lines)
        }
    }

    @Test
    fun aFailedBitrateChangeExplainsItselfInTheHelp() {
        val reasons = listOf(
            BitrateFailure.NOT_CONNECTED, BitrateFailure.UNSUPPORTED, BitrateFailure.DENIED, BitrateFailure.UNCONFIRMED,
            BitrateFailure.rejected(null), BitrateFailure.rejected("x".repeat(500)),
        )
        for (scale in listOf(1f, 1.15f, c.MAX_TEXT_SCALE)) for (reason in reasons) {
            assertEquals(reason, streamMenuHelp("Apply bitrate", "On", 0, 0, false, reason))
            assertFits("bitrate failure at $scale", reason, popoverHelpLineChars(MenuPage.BITRATE, scale), helpLines(scale))
        }
        assertTrue(BitrateFailure.rejected("x".repeat(500)).length < 100)
        // Without a failure the row has its own help
        assertEquals(StreamMenuHelp.description("Apply bitrate"), streamMenuHelp("Apply bitrate", "On", 0, 0, false))
    }

    @Test
    fun aVeryLongCommandNameIsCutInItsHelp() {
        val help = StreamMenuHelp.description("Command: " + "C".repeat(80))
        assertTrue(help, help.contains("C".repeat(35) + "\u2026"))
        assertTrue(help, !help.contains("C".repeat(36)))
        assertTrue(StreamMenuHelp.description("Command: Sleep the PC").contains("Sleep the PC on the PC"))
    }

    @Test
    fun theApplyRowSaysWhatTheHostAnsweredOnTheRight() {
        assertNull(bitrateStatusText(false, null))
        assertEquals("Applying\u2026", bitrateStatusText(true, null))
        assertEquals("Applying\u2026", bitrateStatusText(true, BitrateResult.Accepted(50000)))
        assertEquals("Accepted 50 Mbps", bitrateStatusText(false, BitrateResult.Accepted(50000)))
        assertEquals("Limited by the host to 80 Mbps", bitrateStatusText(false, BitrateResult.Limited(80000)))
        assertEquals("Failed", bitrateStatusText(false, BitrateResult.Failed("x")))
    }

    @Test
    fun theUpscalerTileShowsOneWordOfTheRenderersStatus() {
        assertEquals("On", upscalerCircleState("On: SGSR2-DV (Quality)"))
        assertEquals("On", upscalerCircleState("On"))
        assertEquals("Off", upscalerCircleState("Off"))
        assertEquals("Starting", upscalerCircleState("Starting"))
        assertEquals("Unavailable", upscalerCircleState("Unavailable; direct rendering is active"))
    }

    @Test
    fun theHelpLineJoinsParagraphsOnOneLine() {
        assertEquals("Closes this menu.", helpLineText("Closes this menu."))
        assertEquals("On: SGSR2-DV (Normal) \u00B7 Applies live.", helpLineText("On: SGSR2-DV (Normal)\n\nApplies live."))
        assertEquals("Reuses frames. Applies live.", helpLineText("Reuses frames.\n\nApplies live."))
        assertEquals("", helpLineText("\n \n"))
    }

    @Test
    fun gyroSmoothingRowIsShownWhereTheSettingIsAndItsHelpSaysWhyItIsDisabled() {
        assertTrue(gyroSmoothingShown(33, hasDeviceSensors = false))
        assertTrue(gyroSmoothingShown(30, hasDeviceSensors = true))
        assertFalse(gyroSmoothingShown(30, hasDeviceSensors = false))
        assertEquals(MoreRow.GYRO, gyroSmoothingHelpKey(true))
        assertEquals(MoreRow.GYRO_DISABLED, gyroSmoothingHelpKey(false))
        assertNotEquals("", StreamMenuHelp.description(MoreRow.GYRO))
        assertTrue(StreamMenuHelp.description(MoreRow.GYRO_DISABLED).contains("Settings"))
    }

    @Test
    fun everyTileAndEveryPageHasHelp() {
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
        // The header is the tile's label
        assertEquals("Supersampling", pageTitle(MenuPage.UPSCALER, supersampling = true))
        assertEquals("Upscaler", pageTitle(MenuPage.UPSCALER, supersampling = false))
        for (supersampling in listOf(false, true)) {
            assertEquals(circleHelpKey(BarItem.UPSCALER, supersampling), pageTitle(MenuPage.UPSCALER, supersampling))
        }
        for (page in MenuPage.entries.filter { it.parent() == MenuPage.MORE }) {
            assertTrue("$page has no row in More", page.openedByRow() != null)
        }
    }

    // ---- Motion: the delays and durations of the prototype's variables ----

    @Test
    fun theEntranceStaggersTheTilesBy42AndTheRingArrivesAfterItsTile() {
        assertEquals(100, StreamMenuMotion.tileDelay(0))
        assertEquals(100 + 8 * 42, StreamMenuMotion.tileDelay(8))
        assertEquals(StreamMenuMotion.tileDelay(3) + 140, StreamMenuMotion.labelDelay(3))
        assertEquals(StreamMenuMotion.tileDelay(5) + 260, StreamMenuMotion.ringDelay(5))
        assertEquals(496, StreamMenuMotion.LABEL_DURATION)
        // The ring on the last tile is the last to arrive
        assertEquals(100 + 8 * 42 + 260 + 620, StreamMenuMotion.ENTER_TOTAL)
    }

    @Test
    fun theExitStartsWithTheLastTileAndTheMenuClosesEightyMillisecondsAfterIt() {
        assertEquals(0, StreamMenuMotion.exitDelay(8))
        assertEquals(64, StreamMenuMotion.exitDelay(0))
        assertEquals(64 + 240, StreamMenuMotion.EXIT_TOTAL)
        assertEquals(240 + 80, StreamMenuMotion.CLOSE)
        assertTrue(StreamMenuMotion.EXIT_TOTAL <= StreamMenuMotion.CLOSE)
    }

    @Test
    fun thePrototypeDurationsHoldAfterMovingToTheSharedMotion() {
        val m = Motion
        assertEquals(620, m.ENTER)
        assertEquals(240, m.EXIT)
        assertEquals(420, m.FOCUS)
        assertEquals(560, m.RING)
        assertEquals(640, StreamMenuMotion.DOCK)
        assertEquals(560, m.POP_IN)
        assertEquals(240, m.POP_OUT)
        assertEquals(46, m.STAGGER_ROW)
        assertEquals(120, m.ROW_DELAY)
        assertEquals(420, m.ROW_IN)
        assertEquals(18f, m.RISE, 0f)
        assertEquals(420, m.CAPTION)
        assertEquals(280, m.FADE)
        assertEquals(336, m.CARD_FADE_IN)
        assertEquals(190f, m.GEO_STIFFNESS, 0f)
        assertEquals(.8f, m.GEO_DAMPING, 0f)
        assertEquals(c.POPOVER_HIDDEN_SCALE, m.POP_FROM_SCALE, 0f)
        assertEquals(c.PRESS_SCALE, m.PRESS_SCALE, 0f)
        assertEquals(c.FOCUS_SCALE, 1.12f, 0f)
    }
}
