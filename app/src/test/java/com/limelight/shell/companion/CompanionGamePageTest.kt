package com.limelight.shell.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the game card of the Home screen shows on every panel: the genre chips and their count, the one muted line, the banner's title size, the buttons. */
class CompanionGamePageTest {
    private val metrics = FakeMetrics
    private val genres10 = listOf("Action", "Adventure", "Role-playing", "Strategy", "Simulation", "Puzzle", "Platformer", "Racing", "Sport", "Arcade")
    private val more: (Int) -> String = { "+$it genres" }
    private val facts = "HLTB 19 h \u00b7 Native HDR"
    private val summary = "Trace a lost signal through a changing world. Every return rewrites the map. Nobody remembers the first run."

    private fun budgets() = panels().map { (name, p) -> name to CompanionFit.budget(p) } + panels(1f).map { (name, p) -> "$name@100%" to CompanionFit.budget(p) }

    @Test fun twoGenresAreBothShownWithoutACount() {
        for ((name, b) in budgets()) {
            val text = GameFit.text(listOf("Action", "Roguelike"), summary, facts, b, metrics, more)
            assertEquals(name, listOf("Action", "Roguelike"), text.genres)
        }
    }

    @Test fun tenGenresShowTheBudgetAndCountTheRestInOneRow() {
        for ((name, b) in budgets()) {
            val text = GameFit.text(genres10, summary, facts, b, metrics, more)
            val named = text.genres.filter { !it.startsWith("+") }
            assertTrue("$name: $named", named.size <= b.genres)
            assertEquals("$name: ${text.genres}", "+${genres10.size - named.size} genres", text.genres.last())
            assertEquals(named, genres10.take(named.size))
            val row = text.genres.sumOf { (metrics.width(it) + 26f).toDouble() } + 8f * (text.genres.size - 1)
            assertTrue("$name: row $row in ${b.innerWidth}", row <= b.innerWidth)
        }
    }

    @Test fun aTallPanelShowsTheSummaryInWholeSentencesAndAShortOneTheFolded() {
        for ((name, b) in budgets()) {
            val text = GameFit.text(listOf("Action"), summary, facts, b, metrics, more)
            if (b.roomy) {
                val body = checkNotNull(text.body)
                assertTrue("$name: $body", summary.startsWith(body) && body.endsWith("."))
                assertTrue("$name: ${metrics.lines(body, b.innerWidth)} lines", metrics.lines(body, b.innerWidth) <= GameFit.PROSE_LINES)
            } else assertEquals(name, facts, text.body)
        }
    }

    @Test fun theMutedLineFallsBackToWhatTheGameHas() {
        val tall = CompanionFit.budget(Panel(1100f, 957f))
        val short = CompanionFit.budget(Panel(1100f, 700f))
        assertEquals(facts, GameFit.text(emptyList(), null, facts, tall, metrics, more).body)
        assertEquals(summary.substringBefore(". ") + ".", GameFit.text(emptyList(), summary.substringBefore(". ") + ".", "", short, metrics, more).body)
        assertNull(GameFit.text(emptyList(), null, "", tall, metrics, more).body)
        assertNull(GameFit.text(emptyList(), " ", "", short, metrics, more).body)
    }

    @Test fun aSentenceTooLongForTheLineBudgetLeavesTheSummaryOut() {
        val b = CompanionFit.budget(Panel(1100f, 700f))
        val long = "A single endless sentence that goes on and on and on without ever stopping for breath so that no line budget can hold it whole."
        assertNull(GameFit.text(emptyList(), long, "", b, metrics, more).body)
    }

    @Test fun theNameOnTheBannerNeverFallsBelowTheMinimumTextOrOverflowsTwoLines() {
        for (name in listOf("Asterfall", "Hades II", "The Legend of Zelda: Breath of the Wild", "A".repeat(80))) {
            for ((width, height) in listOf(900f to 300f, 700f to 140f, 500f to 90f)) {
                val size = GameFit.nameSize(name, width, height)
                assertTrue("$name $width x $height: $size", size >= MIN_TEXT && size <= 58)
            }
        }
        // A short name on a roomy banner is as large as the display face goes
        assertEquals(58, GameFit.nameSize("Hades", 900f, 300f))
        // A long one is set smaller
        assertTrue(GameFit.nameSize("The Legend of Zelda: Breath of the Wild", 900f, 300f) < 58)
        // And a banner with no height to speak of still gets the minimum, not zero
        assertEquals(MIN_TEXT, GameFit.nameSize("Asterfall", 900f, 10f))
    }

    @Test fun theButtonsFollowTheSessionStateAndTheFirstIsThePrimaryOne() {
        assertEquals(listOf(ShellButton.START, ShellButton.GAME_SETTINGS), shellButtons(running = false, otherRunning = false))
        assertEquals(listOf(ShellButton.RESUME, ShellButton.GAME_SETTINGS), shellButtons(running = true, otherRunning = false))
        assertEquals(listOf(ShellButton.RESUME_OTHER, ShellButton.QUIT_AND_START, ShellButton.GAME_SETTINGS), shellButtons(running = false, otherRunning = true))
    }

    @Test fun theCardKeepsRoomForTheBannerOnEveryPanelAtTheDefaultScale() {
        // In stage px (text is [scale] times bigger, 64 px targets and the gaps are not): the heading, one chip row, one muted line, the impact chips and a row
        // of buttons with the gaps between them leave at least the banner's minimum height (120 stage px)
        for (scale in listOf(1f, DEFAULT_SCALE)) {
            for ((name, p) in panels(1f)) {
                val card = CompanionFit.cardHeight(p) - 2 * CompanionFit.cardPadding(p)
                val line = MIN_TEXT * scale * 1.2f
                val others = TITLE_SIZE * scale * 1.2f + line + (line + 10f) + line + (line + 12f) + 64f + 5 * CompanionFit.gap(p)
                assertTrue("$name @ $scale: card $card, others $others", card - others >= 120f)
            }
        }
    }
}
