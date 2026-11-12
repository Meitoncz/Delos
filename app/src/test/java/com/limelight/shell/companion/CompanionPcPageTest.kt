package com.limelight.shell.companion

import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.shell.data.App
import com.limelight.shell.data.Library
import com.limelight.shell.data.Pc
import com.limelight.shell.ui.StagePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the PC pages: the card's data, the order and cut of its facts, the buttons per status and screen, the alias of a long name, the PIN's size. */
class CompanionPcPageTest {
    /** Text measured at a flat 15 px per character at the body size and 21 px in the title face (as in CompanionFitTest). */
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

    private val online = PcCardData(
        "uuid-online-7c2a", "Gaming PC", "Gaming PC", PcStatus.ONLINE, canWake = true, isDefault = true, connected = true,
        software = HostSoftware.VIBEPOLLO, apps = 48, appsCached = false, busy = true, runningGame = "Asterfall",
    )
    private val asleep = online.copy(status = PcStatus.ASLEEP, connected = false, isDefault = false, software = null, busy = false, runningGame = null, apps = 31, appsCached = true)
    private val offlineNoMac = online.copy(status = PcStatus.OFFLINE, canWake = false, connected = false, isDefault = false, software = null, busy = false, runningGame = null, apps = null)
    private val offlineWithMac = offlineNoMac.copy(canWake = true)
    private val longName = "The extraordinarily long living-room gaming workstation for the entire household and all our shared adventures \u2014 primary desktop PC"

    private fun pc(online: Boolean, canWake: Boolean, runningGameId: Int = 0, details: ComputerDetails = ComputerDetails()) =
        Pc("pc-1", "Custom", "SERVER", online, true, runningGameId, canWake, details)

    // ---- Data from the shell's snapshot ----

    @Test fun statusIsOnlineThenAsleepWhenItCanWakeElseOffline() {
        assertEquals(PcStatus.ONLINE, PcCard.status(pc(true, false)))
        assertEquals(PcStatus.ONLINE, PcCard.status(pc(true, true)))
        assertEquals(PcStatus.ASLEEP, PcCard.status(pc(false, true)))
        assertEquals(PcStatus.OFFLINE, PcCard.status(pc(false, false)))
    }

    @Test fun softwareIsOnlyAssertedForAPcThatAnswers() {
        assertNull(PcCard.software(pc(false, true)))
        assertEquals(HostSoftware.SUNSHINE, PcCard.software(pc(true, true)))
        val geforce = ComputerDetails().apply { nvidiaServer = true }
        assertEquals(HostSoftware.GEFORCE, PcCard.software(pc(true, true, details = geforce)))
    }

    @Test fun theCardTakesCountAndRunningGameFromTheLibraryOfThatPcOnly() {
        val running = App(2, "Asterfall", false, true, NvApp())
        val idle = App(3, "Hades", false, false, NvApp())
        val me = pc(true, true, runningGameId = 2)
        val library = Library.Ready(me, listOf(running, idle), emptySet(), -1)
        val card = PcCard.of(me, defaultUuid = "pc-1", library = library)
        assertEquals(2, card.apps)
        assertEquals("Asterfall", card.runningGame)
        assertTrue(card.busy && card.connected && card.isDefault == true && !card.appsCached)
        // Another PC focused: nothing is counted, nothing queried
        val other = me.copy(uuid = "pc-2")
        val otherCard = PcCard.of(other, defaultUuid = "pc-1", library = library)
        assertNull(otherCard.apps)
        assertNull(otherCard.runningGame)
        assertFalse(otherCard.connected)
        assertEquals(false, otherCard.isDefault)
    }

    @Test fun anOfflinePcsCountIsLabelledCachedAndTheLibrarysDefaultStaysUnknown() {
        val off = pc(false, true)
        val library = Library.Ready(off, listOf(App(1, "A", false, false, NvApp())), emptySet(), -1)
        val card = PcCard.of(off, defaultUuid = null, library = library, defaultKnown = false)
        assertEquals(1, card.apps)
        assertTrue(card.appsCached)
        assertNull(card.isDefault)
        assertFalse(card.busy)
    }

    // ---- Facts: order, unknown last, budgets ----

    @Test fun anOnlinePcListsWhatItKnowsInTheUsualOrderAndTheVersionLast() {
        assertEquals(
            listOf(PcFactKind.FLAGS, PcFactKind.HOST, PcFactKind.LIBRARY, PcFactKind.SESSION, PcFactKind.LAST_SEEN, PcFactKind.VERSION),
            PcCard.facts(online),
        )
    }

    @Test fun theHostNameIsOnlyAFactWhenTheUserNamedThePcDifferently() {
        assertFalse(PcFactKind.HOST_NAME in PcCard.facts(online))
        assertTrue(PcFactKind.HOST_NAME in PcCard.facts(online.copy(serverName = "DESKTOP-9KQ2L7M")))
    }

    @Test fun theBudgetCutsSixOnATallPanelAndFourOnAShortOne() {
        panels.forEach { (name, p) ->
            val budget = CompanionFit.budget(p)
            assertEquals(name, if (budget.roomy) 6 else 4, budget.facts)
        }
        assertEquals(6, CompanionFit.budget(panels.first { it.first == "1240x1080@420" }.second).facts)
        listOf("960x720@300", "1024x768@320", "960x640@330").forEach { shape -> assertEquals(shape, 4, CompanionFit.budget(panels.first { it.first == shape }.second).facts) }
        val facts = PcCard.facts(online)
        assertEquals(listOf(PcFactKind.FLAGS, PcFactKind.HOST, PcFactKind.LIBRARY, PcFactKind.SESSION), PcCard.select(facts, 4) { true })
        assertEquals(facts.take(6), PcCard.select(facts, 6) { true })
    }

    @Test fun anOfflinePcShowsWhatIsKnownBeforeWhatIsUnavailable() {
        val facts = PcCard.facts(offlineNoMac)
        // Known: flags, wake (a MAC yes/no). Unknown: the library (nothing cached), last seen, host, session, version
        assertEquals(listOf(PcFactKind.FLAGS, PcFactKind.WAKE), facts.take(2))
        assertTrue(facts.drop(2).none { PcCard.known(offlineNoMac, it) })
        // A cached library counts as known and comes before the unknown ones
        val cached = PcCard.facts(asleep)
        assertEquals(listOf(PcFactKind.FLAGS, PcFactKind.LIBRARY, PcFactKind.WAKE), cached.take(3))
        assertEquals(PcFactKind.VERSION, cached.last())
    }

    @Test fun everyUnknownFactIsOneThePageLabelsItselfBecauseShortPanelsShowNoLabel() {
        // The page words each of these as "<what> unavailable/unknown" (pc_unknown_*), so the value alone says what is missing
        val unknown = PcCard.facts(offlineNoMac).filter { !PcCard.known(offlineNoMac, it) }
        assertEquals(setOf(PcFactKind.LIBRARY, PcFactKind.LAST_SEEN, PcFactKind.HOST, PcFactKind.SESSION, PcFactKind.VERSION), unknown.toSet())
        assertTrue(PcCard.facts(online).filter { !PcCard.known(online, it) } == listOf(PcFactKind.VERSION))
    }

    @Test fun aFactThatDoesNotFitOneLineIsLeftOutNotCut() {
        val facts = PcCard.facts(online.copy(serverName = "DESKTOP-9KQ2L7M"))
        val shown = PcCard.select(facts, 6) { it != PcFactKind.HOST_NAME }
        assertFalse(PcFactKind.HOST_NAME in shown)
        assertEquals(6, shown.size)
    }

    @Test fun theFlagsOfALibraryCardWithoutADefaultAreUnknown() {
        val library = online.copy(isDefault = null, connected = false)
        assertFalse(PcCard.known(library, PcFactKind.FLAGS))
        assertTrue(PcCard.known(library.copy(connected = true), PcFactKind.FLAGS))
    }

    // ---- Buttons ----

    @Test fun anOnlinePcOpensItsLibraryAsThePrimaryAction() {
        assertEquals(
            listOf(PcActionKind.LIBRARY, PcActionKind.RENAME, PcActionKind.DEFAULT_OFF),
            PcCard.actions(online, PcContext.START).primary,
        )
        assertEquals(PcActionKind.DEFAULT_ON, PcCard.actions(online.copy(isDefault = false), PcContext.START).primary.last())
    }

    @Test fun anAsleepOrOfflinePcWithAMacWakesAndOneWithoutSearchesWithNoFakeWake() {
        assertEquals(PcActionKind.WAKE, PcCard.actions(asleep, PcContext.START).primary.first())
        assertEquals(PcActionKind.WAKE, PcCard.actions(offlineWithMac, PcContext.START).primary.first())
        val none = PcCard.actions(offlineNoMac, PcContext.DEVICES)
        assertEquals(PcActionKind.SEARCH, none.primary.first())
        assertFalse(PcActionKind.WAKE in none.primary)
        assertFalse(PcActionKind.LIBRARY in none.primary)
    }

    @Test fun unpairIsOnlyOnDevicesAndOnlyForAnOnlinePcAndRemoveIsAlwaysSeparated() {
        assertEquals(listOf(PcDangerKind.UNPAIR, PcDangerKind.REMOVE), PcCard.actions(online, PcContext.DEVICES).separated)
        assertEquals(listOf(PcDangerKind.REMOVE), PcCard.actions(asleep, PcContext.DEVICES).separated)
        assertEquals(listOf(PcDangerKind.REMOVE), PcCard.actions(offlineNoMac, PcContext.DEVICES).separated)
        // Start has the companion's Remove shortcut but never an Unpair
        assertEquals(listOf(PcDangerKind.REMOVE), PcCard.actions(online, PcContext.START).separated)
        // Nothing destructive in the pairing list or the library
        assertTrue(PcCard.actions(online, PcContext.PAIR).separated.isEmpty())
        assertTrue(PcCard.actions(offlineWithMac, PcContext.HOME).separated.isEmpty())
        // No button is offered twice
        PcContext.entries.forEach { context ->
            val set = PcCard.actions(online, context)
            assertEquals(set.primary.size, set.primary.distinct().size)
        }
    }

    @Test fun theLibrarysOfflineCardOnlyHasTheWayInAndRename() {
        assertEquals(listOf(PcActionKind.WAKE, PcActionKind.RENAME), PcCard.actions(offlineWithMac, PcContext.HOME).primary)
        assertEquals(listOf(PcActionKind.SEARCH, PcActionKind.RENAME), PcCard.actions(offlineNoMac, PcContext.HOME).primary)
    }

    @Test fun aNarrowPanelGetsTwoRowsInsteadOfACutLabel() {
        val widths = listOf("Library", "Rename", "Clear default").map { metrics.width(it) * 1.12f }
        // A wide card: all three share a row; a narrow one: fewer, never more than fit and never zero
        assertEquals(3, PcCard.buttonsPerRow(widths, 1000f, 8f, 44f))
        assertEquals(2, PcCard.buttonsPerRow(widths, 600f, 8f, 44f))
        assertEquals(1, PcCard.buttonsPerRow(widths, 260f, 8f, 44f))
        assertEquals(1, PcCard.buttonsPerRow(emptyList(), 100f, 8f, 44f))
    }

    // ---- Name, alias, hint ----

    @Test fun aWorstCaseNameGetsTheAuthoredAliasOnEveryPanelAndAShortOneKeepsItsName() {
        panels.forEach { (name, p) ->
            val width = CompanionFit.budget(p).innerWidth - 80f
            val alias = CompanionFit.alias("PC", "worst-pc-7c2a")
            assertEquals(name, "PC \u00b7 7C2A", alias)
            assertEquals(name, alias, CompanionFit.title(longName, alias, width, metrics))
            assertEquals(name, "Gaming PC", CompanionFit.title("Gaming PC", alias, width, metrics))
            // The alias itself fits one title line on this panel
            assertEquals(name, 1, metrics.lines(alias, width, FitText.TITLE))
        }
    }

    @Test fun theMainHintQuotesTheFirstButtonAndCountsTheRest() {
        val hints = listOf(CompanionHint(null, "x", "Menu"), CompanionHint("A", null, "Connect"), CompanionHint("B", null, "Back"))
        val (first, rest) = PcCard.mainHint(hints)!!
        assertEquals("A", first.key)
        assertEquals(2, rest)
        assertNull(PcCard.mainHint(emptyList()))
        assertNull(PcCard.mainHint(listOf(CompanionHint(null, "x", "Menu"))))
    }

    // ---- The PIN ----

    @Test fun pinDigitsFillTheWidthOnANarrowPanelAndTheHeightOnAWideOne() {
        // 4 digits that measure 300 px at the 100 px reference
        val narrow = PinFit.fontSize(boxWidth = 150f, boxHeight = 400f, widthAtRef = 300f, ref = 100f)
        assertEquals(50f, narrow, 0.001f)
        val wide = PinFit.fontSize(boxWidth = 2000f, boxHeight = 120f, widthAtRef = 300f, ref = 100f)
        assertEquals(100f, wide, 0.001f)
        // Whatever the box, the scaled digits are never wider than it (they never wrap)
        listOf(90f to 600f, 600f to 90f, 301f to 301f).forEach { (w, h) ->
            val size = PinFit.fontSize(w, h, 300f, 100f)
            assertTrue(300f * size / 100f <= w + 0.01f)
        }
        assertEquals(0f, PinFit.fontSize(0f, 100f, 300f, 100f), 0f)
    }

    // ---- Pages are values: an update of the same PC keeps the key, another PC changes it ----

    @Test fun pagesKeepTheirKeyOnAnUpdateAndChangeItAcrossPcs() {
        val calls = PcCallbacks({}, {}, {}, {}, {}, {}, {}, {})
        val a = PcPage(online, PcContext.START, emptyList(), calls)
        val updated = PcPage(online.copy(apps = 49), PcContext.START, emptyList(), calls)
        val other = PcPage(online.copy(uuid = "uuid-other"), PcContext.START, emptyList(), calls)
        assertEquals(a.key, updated.key)
        assertNotEquals(a.key, other.key)
        assertEquals(PagePriority.SCREEN, a.priority)
        assertEquals(PagePriority.SCREEN, PinPage("u", "PC", "1234") {}.priority)
        // The PIN page is a different page from the result: the crossfade shows the pairing moving on
        assertNotEquals(PinPage("u", "PC", "1234") {}.key, PairResultPage("u", "PC", {}, {}).key)
    }
}
