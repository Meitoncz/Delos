package com.limelight.shell.companion

import com.limelight.shell.ui.StagePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of the launch / connect flow pages (docs/DUAL_SCREEN.md, "Launch and connect"): the stage of a start and its steps, what folds on a short
 * panel, when Cancel exists, and the wording of the result and the problem against the budgets of the five panel shapes, with the 610-character worst error.
 */
class CompanionFlowPagesTest {
    /** Text measured at a flat 15 px per character at the body size and 21 px in the title face (as CompanionFitTest does). */
    private val metrics = object : TextMetrics {
        private fun char(kind: FitText) = if (kind == FitText.BODY) 15f else 21f

        override fun lines(text: String, width: Float, kind: FitText): Int {
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

    /** The prototype's 610-character error. */
    private val worstError = "The connection to the extraordinarily long living-room gaming workstation timed out while waiting for the first video frame after the host " +
        "accepted the launch request. The host remained reachable during the initial handshake but did not deliver a usable video frame before the connection deadline. " +
        "The game may still be running on the host and its status must be refreshed rather than inferred from this timeout. This example intentionally contains a long " +
        "technical explanation and repeated context that must not take over the companion screen. Check the host service, firewall and network before trying again."
    private val longName = "The extraordinarily long living-room gaming workstation for the entire household and all our shared adventures \u2014 primary desktop PC"
    private val recovery = "Check the host service, firewall and network. Then try again."
    private val recoveryShort = "Check the host service and network."
    private val errorShort = "The stream could not start."
    private val lostGuidance = "The game may still be running on the host. Reconnect to resume when available."
    private val lostShort = "The game may still be running."

    // ---- The stage and its steps ----

    @Test fun theWorstErrorIsAsLongAsThePrototypeSays() {
        assertEquals(610, worstError.length)
    }

    @Test fun aStartAdvancesOnlyForward() {
        assertEquals(StartStage.CONNECTING, StartStage.LAUNCHING.launchAccepted())
        // Every later stage that completes is a native one: it changes nothing
        assertEquals(StartStage.CONNECTING, StartStage.CONNECTING.launchAccepted())
        assertEquals(StartStage.CONNECTED, StartStage.CONNECTED.launchAccepted())
        assertEquals(StartStage.CONNECTED, StartStage.LAUNCHING.connectionStarted())
        assertEquals(StartStage.CONNECTED, StartStage.CONNECTING.connectionStarted())
    }

    @Test fun everyStageHasExactlyOneCurrentStepWithTheDoneOnesBeforeItAndThePendingOnesAfter() {
        for (stage in StartStage.entries) {
            val marks = FlowSteps.steps(stage).map { it.second }
            assertEquals("$stage: $marks", 1, marks.count { it == StepMark.CURRENT })
            val current = marks.indexOf(StepMark.CURRENT)
            marks.forEachIndexed { i, mark ->
                assertEquals("$stage step $i", if (i < current) StepMark.DONE else if (i == current) StepMark.CURRENT else StepMark.PENDING, mark)
            }
            // The host was found whenever a start runs at all
            assertEquals(StepKind.HOST_FOUND, FlowSteps.steps(stage).first().first)
            assertTrue(FlowSteps.steps(stage).first().second == StepMark.DONE || current == 0)
        }
    }

    @Test fun theStepsFollowWhatTheAppObserves() {
        fun current(stage: StartStage) = FlowSteps.steps(stage).first { it.second == StepMark.CURRENT }.first
        assertEquals(StepKind.LAUNCH_ACCEPTED, current(StartStage.LAUNCHING))
        assertEquals(StepKind.WAITING_VIDEO, current(StartStage.CONNECTING))
        assertEquals(StepKind.STREAM_READY, current(StartStage.CONNECTED))
        assertEquals(4, StepKind.entries.size)
    }

    @Test fun aTallPanelShowsAllFourStepsAndAShortOneFoldsThePendingOnes() {
        for ((name, p) in panels) {
            val budget = CompanionFit.budget(p)
            val maxRows = FlowSteps.rowBudget(budget)
            assertEquals(name, if (budget.roomy) 4 else 3, maxRows)
            for (stage in StartStage.entries) {
                val rows = FlowSteps.rows(stage, maxRows)
                assertTrue("$name $stage: ${rows.size} rows", rows.size <= maxRows)
                // The current step is always shown, once
                assertEquals("$name $stage", 1, rows.count { it.mark == StepMark.CURRENT })
                // Folded steps are counted, not lost: shown + folded + dropped completed ones = all
                val all = FlowSteps.steps(stage)
                val folded = rows.sumOf { it.hiddenAfter }
                val droppedDone = all.size - rows.size - folded
                assertTrue("$name $stage", droppedDone >= 0)
                assertTrue("$name $stage: only completed steps are dropped", all.take(droppedDone).all { it.second == StepMark.DONE })
                // Only trailing pending steps fold, and only into the last row
                rows.dropLast(1).forEach { assertEquals(0, it.hiddenAfter) }
                if (folded > 0) assertTrue(all.takeLast(folded).all { it.second == StepMark.PENDING })
            }
        }
    }

    @Test fun theShortPanelCollapsesTheLastStepIntoPlusOnePendingLikeThePrototype() {
        val rows = FlowSteps.rows(StartStage.CONNECTING, 3)
        assertEquals(listOf(StepKind.HOST_FOUND, StepKind.LAUNCH_ACCEPTED, StepKind.WAITING_VIDEO), rows.map { it.kind })
        assertEquals(listOf(0, 0, 1), rows.map { it.hiddenAfter })
        assertEquals(StepMark.CURRENT, rows.last().mark)
        // The tall panel has no fold
        assertTrue(FlowSteps.rows(StartStage.CONNECTING, 4).all { it.hiddenAfter == 0 })
        // The last stage has nothing pending: the oldest completed step makes room, nothing is folded
        val last = FlowSteps.rows(StartStage.CONNECTED, 3)
        assertEquals(listOf(StepKind.LAUNCH_ACCEPTED, StepKind.WAITING_VIDEO, StepKind.STREAM_READY), last.map { it.kind })
        assertTrue(last.all { it.hiddenAfter == 0 })
    }

    // ---- Cancel ----

    @Test fun theLaunchSceneCancelsUntilTheStreamWindowOwnsTheStart() {
        assertTrue(FlowCancel.launch(launchShown = true, diving = false))
        assertFalse(FlowCancel.launch(launchShown = true, diving = true))
        assertFalse(FlowCancel.launch(launchShown = false, diving = false))
    }

    @Test fun theStreamStartCancelsOnlyOnceTheMenuItRunsIsBound() {
        assertTrue(FlowCancel.streamStart(menuBound = true))
        assertFalse(FlowCancel.streamStart(menuBound = false))
    }

    // ---- The loader's lines ----

    @Test fun theRequestedSettingsComeInTheirLongFormThenShorterOnes() {
        assertEquals(listOf("1920 \u00d7 1080 \u00b7 HEVC \u00b7 120 FPS", "1920 \u00d7 1080 \u00b7 120 FPS", "1920 \u00d7 1080"), FlowText.requestedForms("1920 \u00d7 1080 \u00b7 HEVC \u00b7 120 FPS"))
        assertEquals(listOf("1920 \u00d7 1080"), FlowText.requestedForms("1920 \u00d7 1080"))
        assertEquals(emptyList<String>(), FlowText.requestedForms(null))
        assertEquals(emptyList<String>(), FlowText.requestedForms("  "))
    }

    @Test fun theLoaderLineKeepsTheSettingsWhenTheyFitAndNeverOverflowsOnAnyPanel() {
        val format = { pc: String, requested: String -> "$pc \u00b7 requested $requested" }
        val requested = "1920 \u00d7 1080 \u00b7 HEVC \u00b7 120 FPS"
        for ((name, p) in panels) {
            val width = CompanionFit.budget(p).innerWidth - FlowText.HEADING_INSET
            assertEquals(name, "Gaming PC \u00b7 requested $requested", FlowText.requestedLine("Gaming PC", "PC \u00b7 7C2A", requested, format, width, metrics))
            // Unknown settings: the PC alone
            assertEquals(name, "Gaming PC", FlowText.requestedLine("Gaming PC", "PC \u00b7 7C2A", null, format, width, metrics))
            // A PC whose name is too long is its alias; the settings stay when they still fit
            val long = FlowText.requestedLine(longName, "PC \u00b7 7C2A", requested, format, width, metrics)
            assertTrue("$name: $long", metrics.lines(long, width) <= 1)
            assertTrue("$name: $long", long.startsWith("PC \u00b7 7C2A"))
        }
        // A narrow line drops the codec first
        assertEquals("Gaming PC \u00b7 requested 1920 \u00d7 1080 \u00b7 120 FPS", FlowText.requestedLine("Gaming PC", "PC \u00b7 7C2A", requested, format, 660f, metrics))
    }

    @Test fun aTitleThatDoesNotFitFallsBackToTheAuthoredOne() {
        for ((name, p) in panels) {
            val width = CompanionFit.budget(p).innerWidth - FlowText.HEADING_INSET
            assertEquals(name, "Starting Asterfall", FlowText.title(listOf("Starting Asterfall", "Starting the game"), width, metrics))
            assertEquals(name, "Starting the game", FlowText.title(listOf("Starting $longName", "Starting the game"), width, metrics))
        }
    }

    @Test fun gameAndPcFallsBackToTheAliasThenToTheGameAlone() {
        for ((name, p) in panels) {
            val width = CompanionFit.budget(p).innerWidth - FlowText.HEADING_INSET
            assertEquals(name, "Asterfall \u00b7 Gaming PC", FlowText.gameAndPc("Asterfall", "Gaming PC", "PC \u00b7 7C2A", width, metrics))
            assertEquals(name, "Asterfall \u00b7 PC \u00b7 7C2A", FlowText.gameAndPc("Asterfall", longName, "PC \u00b7 7C2A", width, metrics))
            // A blank game (a shortcut that named none) leaves it out
            assertEquals(name, "Gaming PC", FlowText.gameAndPc("", "Gaming PC", "PC \u00b7 7C2A", width, metrics))
            val worst = FlowText.gameAndPc(longName, longName, "PC \u00b7 7C2A", width, metrics)
            assertEquals(name, "PC \u00b7 7C2A", worst)
        }
        assertEquals("PC \u00b7 7C2A", FlowText.pcAlias("PC", "3f9a-c4b1-7c2a"))
        assertEquals("PC", FlowText.pcAlias("PC", ""))
    }

    // ---- The result ----

    @Test fun aResultKeepsWhatHappenedAndWhatToDoInWholeSentences() {
        for ((name, p) in panels) {
            val b = CompanionFit.budget(p)
            val text = FlowText.resultBody("No video arrived from the PC.", lostGuidance, lostShort, b, metrics, "See the main screen for details.")
            assertTrue("$name: $text", metrics.lines(text, b.innerWidth) <= b.bodyLines)
            assertEquals(name, "No video arrived from the PC. $lostGuidance", text)
        }
    }

    @Test fun aResultThatDoesNotFitDropsTheSentenceBeforeTheGuidanceAndNeverCutsOne() {
        for ((name, p) in panels) {
            val b = CompanionFit.budget(p)
            val text = FlowText.resultBody(worstError, lostGuidance, lostShort, b, metrics, "See the main screen for details.")
            assertTrue("$name: $text", metrics.lines(text, b.innerWidth) <= b.bodyLines)
            assertTrue("$name: $text", text == lostGuidance || text == lostShort || text == "See the main screen for details.")
            // The worst text never claims the game stopped
            assertFalse(name, text.contains("stopped", ignoreCase = true))
        }
        // A narrow panel: the sentence plus the short guidance, else the short guidance alone
        val narrow = CompanionFit.budget(Panel(1100f, 700f)).copy(innerWidth = 420f, bodyLines = 2)
        val text = FlowText.resultBody("No video arrived.", lostGuidance, lostShort, narrow, metrics, "last")
        assertEquals("No video arrived. $lostShort", text)
        assertEquals("The PC ended the stream.", FlowText.resultBody("", "The PC ended the stream.", "Short.", CompanionFit.budget(panels[0].second), metrics, "last"))
    }

    // ---- The problem ----

    @Test fun theErrorLineIsTheFailingStageWhenItFitsAndNothingElse() {
        val details = "Failed to start video stream (error 110)\n\nNo video received from host."
        for ((name, p) in panels) {
            val b = CompanionFit.budget(p)
            assertEquals(name, "Failed to start video stream (error 110)", FlowText.errorLine(details, if (b.roomy) 2 else 1, b.innerWidth, metrics))
            assertNull(name, FlowText.errorLine(null, 1, b.innerWidth, metrics))
            assertNull(name, FlowText.errorLine("   \n  ", 1, b.innerWidth, metrics))
            assertNull(name, FlowText.errorLine(longName.repeat(4), 1, b.innerWidth, metrics))
        }
    }

    @Test fun theRealErrorTextKeepsTheSentenceAndTheTechnicalLinesThatFit() {
        val details = "Failed to start video stream (error -408)\n\nConnection Timeout"
        for ((name, p) in panels) {
            val b = CompanionFit.budget(p)
            val text = FlowText.errorText("The PC didn't answer in time.", details, errorShort, b, metrics, "See the main screen for details.")
            assertTrue("$name: $text", metrics.lines(text, b.innerWidth) <= b.errorLines)
            // The error line (the first technical line) is shown apart, never repeated in the text
            assertFalse(name, text.contains("error -408"))
            assertTrue(name, text.startsWith("The PC didn't answer in time."))
        }
    }

    @Test fun worstErrorOf610CharactersFitsTheErrorBudgetOnEveryPanelInWholeSentences() {
        val details = "Failed to start video stream (error 110)"
        val sentences = CompanionFit.sentences(worstError)
        for ((name, p) in panels) {
            val b = CompanionFit.budget(p)
            val text = FlowText.errorText(worstError, details, errorShort, b, metrics, "See the main screen for details.")
            assertTrue("$name: ${metrics.lines(text, b.innerWidth)} lines of $text", metrics.lines(text, b.innerWidth) <= b.errorLines)
            assertTrue("$name: $text", text.isNotBlank())
            // Whole leading sentences of the error, or an authored fallback; never a cut sentence
            val leading = (1..sentences.size).map { sentences.take(it).joinToString(" ") }
            assertTrue("$name: $text", text in leading || text == errorShort || text == "See the main screen for details.")
            assertTrue("$name: $text", text.endsWith("."))
            val rec = FlowText.recovery(recovery, recoveryShort, b, metrics)
            assertTrue("$name: $rec", rec.isEmpty() || metrics.lines(rec, b.innerWidth) <= b.recoveryLines)
            assertTrue("$name: $rec", rec == recovery || rec == recoveryShort)
        }
    }

    @Test fun theWholeProblemCardFitsEveryPanelWithTheWorstError() {
        val details = "Failed to start video stream (error 110)"
        for ((name, p) in panels) {
            val b = CompanionFit.budget(p)
            val line = 34f
            val error = FlowText.errorText(worstError, details, errorShort, b, metrics, "See the main screen for details.")
            val errorLine = FlowText.errorLine(details, if (b.roomy) 2 else 1, b.innerWidth, metrics)
            val rec = FlowText.recovery(recovery, recoveryShort, b, metrics)
            val blocks = ArrayList<Float>()
            blocks += 80f // heading: disc 64, title and one line of subtitle
            errorLine?.let { blocks += metrics.lines(it, b.innerWidth) * line }
            blocks += metrics.lines(error, b.innerWidth) * line
            if (rec.isNotBlank()) blocks += metrics.lines(rec, b.innerWidth) * line
            if (b.roomy) blocks += line
            blocks += 64f // the two buttons
            val used = blocks.sum() + CompanionFit.gap(p) * (blocks.size - 1)
            assertTrue("$name: $used in ${CompanionFit.cardHeight(p) - 2 * CompanionFit.cardPadding(p)}", used <= CompanionFit.cardHeight(p) - 2 * CompanionFit.cardPadding(p))
        }
    }

    @Test fun theLoaderFitsEveryPanelInItsRowBudget() {
        for ((name, p) in panels) {
            val b = CompanionFit.budget(p)
            val rows = FlowSteps.rows(StartStage.CONNECTING, FlowSteps.rowBudget(b)).size
            // Heading, the rows with 8 px between, the no-percentage line, and the Cancel button
            val used = 80f + rows * 34f + (rows - 1) * 8f + 34f + 64f + CompanionFit.gap(p) * 4
            assertTrue("$name: $used", used <= CompanionFit.cardHeight(p) - 2 * CompanionFit.cardPadding(p))
        }
    }

    @Test fun aPortalNoteKeepsWholeSentencesAndIsBlankWhenNothingFits() {
        for ((name, p) in panels) {
            val b = CompanionFit.budget(p)
            assertEquals(name, "Wake-up signal sent \u00b7 B cancels", FlowText.portalNote("Wake-up signal sent \u00b7 B cancels", b, metrics))
            assertEquals(name, "", FlowText.portalNote(longName.repeat(3), b, metrics))
            assertEquals(name, "", FlowText.portalNote("", b, metrics))
        }
    }
}
