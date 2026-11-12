package com.limelight.shell.companion

import com.limelight.shell.ui.StagePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/** The fitting function of the companion's pages (Rule 0: nothing scrolls): the budgets, the sentence cut, the chip rows, with worst-case data on every panel shape. */
class CompanionFitTest {
    /** Text measured at a flat 15 px per character at the body size (a little wider than Figtree's average) and 21 px in the title face. */
    private val metrics = object : TextMetrics {
        private fun char(kind: FitText) = if (kind == FitText.BODY) 15f else 21f

        override fun lines(text: String, width: Float, kind: FitText): Int {
            // Greedy word wrap, a word wider than a line counts as impossible (a huge number)
            var lines = 1
            var current = 0f
            for (word in text.split(' ')) {
                val w = word.length * char(kind)
                if (w > width) return 1000
                val next = if (current == 0f) w else current + char(kind) + w
                if (next > width) {
                    lines++
                    current = w
                } else current = next
            }
            return lines
        }

        override fun width(text: String, kind: FitText): Float = text.length * char(kind)
    }

    /** The panels the user named, from the simulator shapes (px at dpi), as the companion's stage sees them. */
    private val panels = listOf(
        "1240x1080@420" to panel(1240, 1080, 420),
        "960x720@300" to panel(960, 720, 300),
        "1024x768@320" to panel(1024, 768, 320),
        "720x720@255" to panel(720, 720, 255),
        "960x640@330" to panel(960, 640, 330),
    )

    private fun panel(w: Int, h: Int, dpi: Int): Panel {
        val plan = StagePlanner.plan(w / (dpi / 160f), h / (dpi / 160f))
        return Panel(plan.stageWidth, plan.stageHeight)
    }

    private val helpText = "Requests a larger picture from the host. More pixels need more network bandwidth. A higher resolution can preserve fine detail. " +
        "The host must also render the requested picture. Lower the value when the connection is unstable. The profile value stays available when this game has an override."
    private val errorText = "The connection to the extraordinarily long living-room gaming workstation timed out while waiting for the first video frame after the host accepted the " +
        "launch request. The host remained reachable during the initial handshake but did not deliver a usable video frame before the connection deadline. The game may still be " +
        "running on the host and its status must be refreshed rather than inferred from this timeout. Check the host service, firewall and network before trying again."
    private val genres = listOf("Action", "Adventure", "Role-playing", "Strategy", "Simulation", "Puzzle", "Platformer", "Racing", "Sport", "Arcade")
    private val longName = "The extraordinarily long living-room gaming workstation for the entire household and all our shared adventures \u2014 primary desktop PC"

    // ---- Sentences ----

    @Test fun sentencesKeepTheirMarksAndSplitOnlyAtASpaceAfterThem() {
        assertEquals(listOf("One.", "Two!", "Three?"), CompanionFit.sentences("One. Two! Three?"))
        // A mark inside a number, a version or an ellipsis-free name is not an end
        assertEquals(listOf("Needs v2.0.1 or 1.5x scaling.", "Done."), CompanionFit.sentences("Needs v2.0.1 or 1.5x scaling. Done."))
        assertEquals(listOf("No mark at all"), CompanionFit.sentences("No mark at all"))
        assertEquals(listOf("Line one.", "Line two"), CompanionFit.sentences("Line one.\nLine two"))
        assertEquals(listOf("He said \"stop.\"", "Then left."), CompanionFit.sentences("He said \"stop.\" Then left."))
        assertEquals(emptyList<String>(), CompanionFit.sentences("   "))
    }

    @Test fun cjkSentencesEndWithoutASpace() {
        assertEquals(listOf("設定を選びます。", "続けます。"), CompanionFit.sentences("設定を選びます。続けます。"))
    }

    // ---- Summary: complete sentences, else the short form, else the last resort ----

    @Test fun summaryTakesTheLongestRunOfCompleteLeadingSentences() {
        val text = CompanionFit.summary("First one. Second one. Third one.", "short", 1, 330f, metrics, "last")
        assertEquals("First one. Second one.", text)
        // Never a prefix of a sentence: every result is a concatenation of whole sentences
        assertTrue(text.endsWith("."))
    }

    @Test fun summaryFallsBackToTheShortFormThenToTheLastResort() {
        val long = "This one sentence is far too long for a single narrow line of text."
        assertEquals("Short.", CompanionFit.summary(long, "Short.", 1, 300f, metrics, "last"))
        assertEquals("last", CompanionFit.summary(long, long, 1, 300f, metrics, "last"))
        assertEquals("last", CompanionFit.summary("", "", 1, 300f, metrics, "last"))
    }

    @Test fun worstCaseHelpFitsTheLineBudgetOnEveryPanel() {
        for ((name, p) in panels) {
            val b = CompanionFit.budget(p)
            val text = CompanionFit.summary(helpText, "Requests the host picture size.", b.helpLines, b.innerWidth, metrics, "See the main screen for details.")
            assertTrue("$name: ${metrics.lines(text, b.innerWidth)} lines", metrics.lines(text, b.innerWidth) <= b.helpLines)
            assertTrue("$name: $text", text.isNotBlank())
            // Whole sentences only
            assertTrue("$name: $text", text.endsWith(".") && helpText.startsWith(text))
        }
    }

    @Test fun worstCaseErrorFitsTheLineBudgetOnEveryPanel() {
        for ((name, p) in panels) {
            val b = CompanionFit.budget(p)
            val text = CompanionFit.summary(errorText, "The host did not send a video frame.", b.errorLines, b.innerWidth, metrics, "See the main screen for details.")
            assertTrue("$name: ${metrics.lines(text, b.innerWidth)} lines", metrics.lines(text, b.innerWidth) <= b.errorLines)
            assertTrue("$name: $text", text.isNotBlank())
        }
    }

    // ---- Lists: the top entries and a count ----

    @Test fun genreChipsShowTheFirstEntriesAndTheRemainingCountInOneRow() {
        for ((name, p) in panels) {
            val b = CompanionFit.budget(p)
            val shown = CompanionFit.chips(genres, b.genres, b.innerWidth, metrics, { "+$it genres" })
            val named = shown.count { !it.startsWith("+") }
            assertTrue("$name: $shown", named <= b.genres)
            assertEquals("$name: the rest is counted", genres.size - named, shown.last().removePrefix("+").removeSuffix(" genres").toInt())
            val row = shown.sumOf { (metrics.width(it) + 26f).toDouble() } + 8f * (shown.size - 1)
            assertTrue("$name: row $row in ${b.innerWidth}", row <= b.innerWidth)
        }
    }

    @Test fun fewGenresAreAllShownWithoutACount() {
        val b = CompanionFit.budget(panels[0].second)
        assertEquals(listOf("Action", "Roguelike"), CompanionFit.chips(listOf("Action", "Roguelike"), b.genres, b.innerWidth, metrics, { "+$it genres" }))
    }

    @Test fun topKeepsTheFirstEntriesAndCountsTheRest() {
        assertEquals(listOf(1, 2) to 3, CompanionFit.top(listOf(1, 2, 3, 4, 5), 2))
        assertEquals(listOf(1, 2) to 0, CompanionFit.top(listOf(1, 2), 5))
        assertEquals(emptyList<Int>() to 0, CompanionFit.top(emptyList<Int>(), 5))
    }

    // ---- Titles ----

    @Test fun aNameThatFitsOneLineStaysAndALongOneBecomesItsAlias() {
        for ((name, p) in panels) {
            val width = CompanionFit.budget(p).innerWidth - 80f
            assertEquals("Gaming PC", CompanionFit.title("Gaming PC", "PC \u00b7 7C2A", width, metrics))
            assertEquals("$name", "PC \u00b7 7C2A", CompanionFit.title(longName, "PC \u00b7 7C2A", width, metrics))
        }
        assertEquals("PC \u00b7 7C2A", CompanionFit.alias("PC", "3f9a-c4b1-7c2a"))
    }

    // ---- The budgets ----

    @Test fun tallPanelsAreRoomyAndTheShortOnesAreNot() {
        val byName = panels.toMap()
        assertTrue(byName.getValue("1240x1080@420").roomy)
        assertTrue(byName.getValue("720x720@255").roomy)
        assertFalse(byName.getValue("960x720@300").roomy)
        assertFalse(byName.getValue("1024x768@320").roomy)
        assertFalse(byName.getValue("960x640@330").roomy)
    }

    @Test fun everyPanelHasRoomForTheLeastACardNeeds() {
        // The heading disc, one line of text and one row of buttons, with the gaps between them, and a line wide enough for a short sentence
        for ((name, p) in panels) {
            assertTrue("$name: card height ${CompanionFit.cardHeight(p)}", CompanionFit.cardHeight(p) >= 64f + 34f + 64f + 3 * CompanionFit.gap(p))
            assertTrue("$name: inner width ${CompanionFit.budget(p).innerWidth}", CompanionFit.budget(p).innerWidth >= 500f)
        }
    }

    @Test fun theBudgetsOfARoomyPanelAreNeverSmallerThanThoseOfAShortOne() {
        val roomy = CompanionFit.budget(Panel(1100f, 957f))
        val short = CompanionFit.budget(Panel(1100f, 837f))
        assertTrue(roomy.bodyLines >= short.bodyLines && roomy.helpLines >= short.helpLines && roomy.genres >= short.genres)
        assertTrue(roomy.hints >= short.hints && roomy.facts >= short.facts && roomy.errorLines >= short.errorLines)
        // The source block and the recovery sentence are two lines on every panel
        assertEquals(2, roomy.sourceLines)
        assertEquals(2, short.sourceLines)
        assertEquals(2, short.recoveryLines)
    }

    @Test fun theMarginsAndTheFooterFollowTheRule() {
        assertTrue(CompanionFit.MARGIN >= 48f)
        assertEquals(64f, CompanionFit.HEADER)
        assertEquals(32f, CompanionFit.FOOTER)
        // 1240 x 1080 in the prototype: 888 px of card height at its own native stage
        assertEquals(888f, 1080f - CompanionFit.HEADER - CompanionFit.FOOTER - 2 * CompanionFit.MARGIN)
    }

    @Test fun theCardKeepsAtLeastAThirdOfTheStageWidthForTextOnTheNarrowestPanel() {
        for ((name, p) in panels) assertTrue(name, CompanionFit.budget(p).innerWidth >= ceil(p.width / 3f))
    }
}
