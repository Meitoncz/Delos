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

    // ---- Which page wins ----

    private val context = ShellContext("Settings", "Select a setting.", emptyList(), null, welcome = false)

    @Test fun nothingPublishedIsTheFallbackCardNeverAnEmptyOne() {
        assertTrue(CompanionState.Shell("PC", emptyList(), context).page is FallbackPage)
        assertTrue(CompanionState.Shell("PC").page is FallbackPage)
    }

    @Test fun nothingPublishedWithoutAPcIsTheWelcomeCard() {
        assertTrue(CompanionState.Shell(null, emptyList(), context.copy(welcome = true)).page is WelcomePage)
    }

    @Test fun aFocusedGameShowsItsDetails() {
        assertTrue(CompanionState.Shell("PC", listOf(GamePage(game())), context).page is GamePage)
    }

    @Test fun theHelpOfASettingsScreenShowsOnItsOwn() {
        assertTrue(CompanionState.Shell("PC", listOf(SettingsHelpPage(help())), context).page is SettingsHelpPage)
    }

    @Test fun helpWinsOverAGameBecauseTheSettingsScreenIsOnTop() {
        assertTrue(CompanionState.Shell("PC", listOf(GamePage(game()), SettingsHelpPage(help())), context).page is SettingsHelpPage)
        assertTrue(CompanionState.Shell("PC", listOf(SettingsHelpPage(help()), GamePage(game())), context).page is SettingsHelpPage)
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

    // ---- What the page shows on every panel (the line budgets of Rule 0) ----

    private val metrics = FakeMetrics
    private val worst = "Requests a larger picture from the host. More pixels need more network bandwidth. A higher resolution can preserve fine detail. " +
        "The host must also render the requested picture. Lower the value when the connection is unstable. The profile value stays available when this game has an override."
    private val short = "Requests the host picture size."
    private val last = "See the main screen for details."

    private fun every() = panels() + panels(1f).map { (name, p) -> "$name@100%" to p }

    @Test fun theDescriptionIsWholeSentencesInTheLineBudgetOfEveryPanel() {
        for ((name, p) in every()) {
            val b = CompanionFit.budget(p)
            val text = CompanionFit.summary(HelpFit.prepare(worst), short, HelpFit.bodyLines(help(), b), b.innerWidth, metrics, last)
            assertTrue("$name: ${metrics.lines(text, b.innerWidth)} lines", metrics.lines(text, b.innerWidth) <= b.helpLines)
            assertTrue("$name: $text", text.endsWith(".") && worst.startsWith(text))
        }
    }

    @Test fun aFirstSentenceTooLongForTheBudgetFallsBackToTheShortFormThenToTheLastResort() {
        val b = CompanionFit.budget(Panel(800f, 700f))
        val long = "This single sentence is far too long for the line budget of a short panel and it keeps going well beyond what any three lines could hold at all, " +
            "really, no matter how the words are wrapped on this narrow card, since it just does not stop, and it adds still more words to be sure of it."
        assertEquals(short, CompanionFit.summary(long, short, 3, b.innerWidth, metrics, last))
        assertEquals(last, CompanionFit.summary(long, long, 3, b.innerWidth, metrics, last))
    }

    @Test fun otherBlocksTakeLinesFromTheDescription() {
        val tall = CompanionFit.budget(Panel(1100f, 957f))
        val low = CompanionFit.budget(Panel(1100f, 700f))
        assertEquals(6, HelpFit.bodyLines(help(), tall))
        assertEquals(3, HelpFit.bodyLines(help(), low))
        assertEquals(3, HelpFit.bodyLines(help().copy(details = "x"), tall))
        assertEquals(1, HelpFit.bodyLines(help().copy(details = "x"), low))
        assertEquals(1, HelpFit.bodyLines(help().copy(swatches = listOf(HelpSwatch(androidx.compose.ui.graphics.Color.Red, "Red"))), low))
        assertEquals(2, HelpFit.bodyLines(help().copy(backdrop = HelpBackdrop("t", true, null, 0f, 0f, "i", 1f)), low))
        assertEquals(4, HelpFit.bodyLines(help().copy(value = "v"), tall))
        assertEquals(2, HelpFit.bodyLines(help().copy(value = "v"), low))
    }

    @Test fun theSourceAlwaysKeepsItsTitleAndAtMostTwoLinesOfSentence() {
        val source = helpSource(true, "i", "Custom for this game", "Profile: %s. Press X on the main display to go back to the profile value. It is saved automatically for the next stream.", "%s", "1080p")
        for ((name, p) in every()) {
            val b = CompanionFit.budget(p)
            val view = HelpFit.source(source, b.sourceLines, b.innerWidth, metrics)
            assertEquals(name, "Custom for this game", view.title)
            val text = "${view.title} ${view.before}${view.strong}${view.after}"
            assertTrue("$name: ${metrics.lines(text, b.innerWidth - HelpFit.SOURCE_CHROME)} lines", metrics.lines(text, b.innerWidth - HelpFit.SOURCE_CHROME) <= b.sourceLines)
            // Whole sentences: the first one is always there when it fits, the strong value stays bold while it is inside the kept text
            assertTrue("$name: $text", view.before.startsWith("Profile: ") && view.strong == "1080p" && view.after.startsWith("."))
        }
    }

    @Test fun aSourceWhoseFirstSentenceDoesNotFitKeepsOnlyItsTitle() {
        val source = HelpSource(false, "i", "Profile", "A sentence that is much too long to share two lines of a narrow card with its title, however it is wrapped around it.", "", "")
        val view = HelpFit.source(source, 2, 300f, metrics)
        assertEquals(SourceView("Profile", "", "", ""), view)
    }

    @Test fun aShortSourceIsNotCut() {
        val source = helpSource(false, "i", "Profile", "%s.", "%s", "1080p")
        assertEquals(SourceView("Profile", "", "1080p", "."), HelpFit.source(source, 2, 900f, metrics))
    }

    @Test fun aTitleTooLongForTheDisplayFaceHandsTheHeadingToItsPlace() {
        val width = 900f
        assertEquals(HelpHeadingText("Resolution", "Settings \u00b7 Display"), HelpFit.heading("Resolution", "Settings \u00b7 Display", width, metrics))
        val long = "Video bitrate on metered networks and Wi-Fi"
        val heading = HelpFit.heading(long, "Settings \u00b7 Display", width, metrics)
        assertEquals("Settings \u00b7 Display", heading.title)
        assertEquals(long, heading.subtitle)
        // Without a place there is nothing to hand it to
        assertEquals(HelpHeadingText(long, null), HelpFit.heading(long, null, width, metrics))
    }

    @Test fun aTextValueKeepsItsEndWhenItDoesNotFit() {
        assertEquals("My PC", HelpFit.value("My PC", 2, 400f, metrics))
        val path = "/storage/emulated/0/Android/media/io.github.meitoncz.delos/backgrounds/summer-2026/very-long-folder-name/picture.png"
        val cut = HelpFit.value(path, 1, 400f, metrics)
        assertTrue(cut, cut.startsWith("\u2026") && path.endsWith(cut.removePrefix("\u2026")) && metrics.lines(cut, 400f) <= 1)
    }

    @Test fun debugDetailsAreWholeLinesInTheRowBudgetAndTheRestIsCounted() {
        val text = (1..8).joinToString("\n") { "line $it" }
        assertEquals(DetailsView(text, 0), HelpFit.details(text, 8, 40))
        val cut = HelpFit.details(text, 3, 40)
        assertEquals(DetailsView("line 1\nline 2", 6), cut)
        // A long line wraps and takes the rows it needs
        val wrapped = HelpFit.details("x".repeat(100) + "\nshort\nlast", 4, 40)
        assertEquals(DetailsView("x".repeat(100), 2), wrapped)
        assertEquals(DetailsView("", 3), HelpFit.details("x".repeat(100) + "\nshort\nlast", 3, 40))
    }

    @Test fun everyLineOfADescriptionEndsInASentenceMarkSoItCanBeCutThere() {
        assertEquals("Balanced.\nSmooths frame delivery.", HelpFit.prepare("Balanced\nSmooths frame delivery."))
        assertEquals("Already marked!\nAsk?\nA list:", HelpFit.prepare("Already marked!\nAsk?\nA list:"))
        assertEquals("標準\u3002\n説明です\u3002", HelpFit.prepare("標準\n説明です\u3002"))
        assertEquals(listOf("Balanced.", "Smooths frame delivery."), CompanionFit.sentences(HelpFit.prepare("Balanced\nSmooths frame delivery.")))
    }

    @Test fun threeSwatchesShareARowWhenTheirNamesFitIt() {
        assertTrue(HelpFit.swatchesInRow(listOf("Main", "Secondary", "Accent"), 700f, metrics))
        assertFalse(HelpFit.swatchesInRow(listOf("Main", "Secondary", "Accent"), 300f, metrics))
    }

    @Test fun theWorstHelpFitsTheCardOnEveryPanel() {
        // Heading, the source (two lines), the description (the full budget), the impact chips and the gaps, in fit px of the page's own scale: at most the card
        for ((name, p) in every()) {
            val b = CompanionFit.budget(p)
            val line = MIN_TEXT * 1.2f
            val heading = TITLE_SIZE * 1.2f + line
            val source = b.sourceLines * line + 2 * 10f
            val body = b.helpLines * line
            val impact = line + 12f
            val total = heading + source + body + impact + 4 * CompanionFit.gap(p)
            val room = CompanionFit.cardHeight(p) - 2 * CompanionFit.cardPadding(p)
            assertTrue("$name: $total of $room", total <= room)
        }
    }

    @Test fun anotherValueOfTheSameRowKeepsTheKeySoThePageDoesNotFade() {
        val row = help("row").copy(value = "one")
        assertEquals(SettingsHelpPage(row).key, SettingsHelpPage(row.copy(value = "two")).key)
        assertEquals(SettingsHelpPage(row).key, SettingsHelpPage(row.copy(body = "Other body", short = "Other")).key)
        assertTrue(SettingsHelpPage(row).key != SettingsHelpPage(help("another")).key)
    }

    @Test fun theShortFormIsTheCatalogSummaryThenTheFirstSentenceThenTheTitle() {
        assertEquals("Summary.", helpShort("Summary.", "Body one. Body two.", "Title"))
        assertEquals("Body one.", helpShort("  ", "Body one. Body two.", "Title"))
        assertEquals("Body one.", helpShort(null, "Body one. Body two.", "Title"))
        assertEquals("Title", helpShort(null, "", "Title"))
    }

    @Test fun theImpactOfARowIsCarriedAsData() {
        val impact = SettingImpact(ImpactLevel.LOW, ImpactLevel.MEDIUM, ImpactLevel.HIGH)
        assertEquals(impact, help().copy(impact = impact).impact)
        assertNull(help().impact)
    }
}
