package com.limelight.shell.companion

import com.limelight.shell.settings.ImpactLevel
import com.limelight.shell.settings.SettingImpact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the settings help on the second panel: which published state the window shows, when the main screen gives up its description, the texts, the layout rules. */
class CompanionHelpTest {
    private fun help(identity: String = "row") = CompanionHelp(identity, "icon", "Title", "Body")

    private fun game() = CompanionGame(
        key = "pc/1", name = "Game", running = false, otherRunning = null, image = null, hue = 0f, hue2 = 0f, summary = null, genres = emptyList(),
        completionSeconds = null, hdr = null, impact = null, onAction = {},
    )

    // ---- Which state wins ----

    @Test fun nothingPublishedIsTheIdleMark() {
        assertEquals(ShellView.IDLE, shellView(CompanionState.Shell("PC")))
    }

    @Test fun aFocusedGameShowsItsDetails() {
        assertEquals(ShellView.GAME, shellView(CompanionState.Shell("PC", game())))
    }

    @Test fun theHelpOfASettingsScreenShowsOnItsOwn() {
        assertEquals(ShellView.HELP, shellView(CompanionState.Shell("PC", help = help())))
    }

    @Test fun helpWinsOverAGameBecauseTheSettingsScreenIsOnTop() {
        assertEquals(ShellView.HELP, shellView(CompanionState.Shell("PC", game(), help())))
    }

    // ---- When the main screen gives its description's room to the list ----

    @Test fun theMainScreenKeepsItsDescriptionUntilAWindowShowsTheHelp() {
        // A second display that exists but whose window has not taken the help (or has none) never costs the description
        assertFalse(hidesDescription(windowShowsHelp = false, help = help()))
    }

    @Test fun theMainScreenGivesTheRoomWhenAWindowShowsTheHelp() {
        assertTrue(hidesDescription(windowShowsHelp = true, help = help()))
    }

    @Test fun withNothingPublishedThereIsNothingShownElsewhere() {
        assertFalse(hidesDescription(windowShowsHelp = true, help = null))
        assertFalse(hidesDescription(windowShowsHelp = false, help = null))
    }

    // ---- The source sentence ----

    @Test fun theValueReplacesTheMarkerAndIsKeptApartForBold() {
        val source = helpSource(true, "i", "Custom", "Profile value: %s \u00b7 press X", "%s", "720p")
        assertEquals("Profile value: ", source.before)
        assertEquals("720p", source.strong)
        assertEquals(" \u00b7 press X", source.after)
        assertTrue(source.custom)
    }

    @Test fun aSentenceWithoutAMarkerIsPlainText() {
        val source = helpSource(false, "i", "Title", "1 on Stream \u00b7 0 on Display", "", "")
        assertEquals("1 on Stream \u00b7 0 on Display", source.before)
        assertEquals("", source.strong)
        assertEquals("", source.after)
        assertEquals("plain", helpSource(false, "i", "t", "plain", "%s", "x").before)
    }

    // ---- The dim of the settings help ----

    @Test fun theHelpDimsLaterThanTheGameDetails() {
        assertEquals(CompanionBurnIn.DIM_AFTER_MS, CompanionBurnIn.dimAfter(showsHelp = false))
        assertEquals(CompanionBurnIn.DIM_AFTER_HELP_MS, CompanionBurnIn.dimAfter(showsHelp = true))
        assertTrue(CompanionBurnIn.DIM_AFTER_HELP_MS > CompanionBurnIn.DIM_AFTER_MS)
    }

    // ---- The page's geometry, in the user's rules (stage px) ----

    @Test fun theRulesHoldOnEveryCompanionStage() {
        // Outer margin at least 48; at least 24 between neighbouring blocks and from the header; text at least the companion's 28
        assertTrue(HelpLayout.MARGIN >= 48f && HelpLayout.BOTTOM >= 48f)
        assertTrue(HelpLayout.TOP >= 24f && HelpLayout.GAP >= 24f && HelpLayout.DISC_GAP >= 24f)
        assertTrue(HelpLayout.BODY >= MIN_TEXT && HelpLayout.TITLE >= MIN_TEXT)
        // The content never reaches the card's edge closer than its corner radius needs, and the fade fits in the padding the page rests at
        assertTrue(HelpLayout.PAD_X >= 32f && HelpLayout.PAD_Y >= 24f)
        for ((width, height) in listOf(1099 to 957, 1488 to 893, 1191 to 893, 1108 to 831, 1051 to 1051, 1082 to 722, 1116 to 837)) {
            val text = width - 2 * HelpLayout.MARGIN - 2 * HelpLayout.PAD_X
            val room = height - 92f - HelpLayout.TOP - HelpLayout.BOTTOM - 2 * HelpLayout.PAD_Y
            // The icon disc and a title line of a short help fit beside each other, and the card has height for the disc and a few lines of text
            assertTrue("$width x $height: text width $text", text >= HelpLayout.DISC + HelpLayout.DISC_GAP + 400f)
            assertTrue("$width x $height: room $room", room >= HelpLayout.DISC + 3 * HelpLayout.BODY * 1.45f)
        }
    }

    @Test fun theImpactOfARowIsCarriedAsData() {
        val impact = SettingImpact(ImpactLevel.LOW, ImpactLevel.MEDIUM, ImpactLevel.HIGH)
        assertEquals(impact, help().copy(impact = impact).impact)
        assertNull(help().impact)
    }
}
