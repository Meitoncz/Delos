package com.limelight.shell.ui

import com.limelight.shell.ui.HintDetail.BARE
import com.limelight.shell.ui.HintDetail.FULL
import com.limelight.shell.ui.HintDetail.SHORT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How a compact hint bar degrades (`fitHints`, `fitHintBar`): widths are abstract pixels, a button is 40, a label is 10 per character plus a 12 gap. */
class HintFitTest {
    private fun item(glyph: Glyph, label: String, short: String? = null) =
        HintFitItem(hintRank(glyph), 40, short?.let { 52 + it.length * 10 }, 52 + label.length * 10, hintGroup(glyph))

    private val gap = 28

    // Artwork, as Shell lays it out: L1, R1, A, X, Y, B
    private val artwork = listOf(
        item(Glyph.L1, "Previous tab"), item(Glyph.R1, "Next tab"), item(Glyph.A, "Use image", "Choose"),
        item(Glyph.X, "Search name", "Search"), item(Glyph.Y, "Filter"), item(Glyph.B, "Back"),
    )

    private fun fit(items: List<HintFitItem>, available: Int) = fitHints(items, gap, available)
    private fun width(items: List<HintFitItem>, details: List<HintDetail>) =
        items.indices.sumOf { items[it].width(details[it]) } + gap * (items.size - 1)

    @Test
    fun rankFollowsTheButtons() {
        assertEquals(listOf(0, 1, 2, 2, 2, 3, 3, 4, 4), listOf(Glyph.A, Glyph.B, Glyph.X, Glyph.Y, Glyph.ADJUST, Glyph.L1, Glyph.R1, Glyph.MENU_LEFT, Glyph.MENU_RIGHT).map(::hintRank))
    }

    @Test
    fun everythingKeepsItsLabelWhenItFits() {
        val full = artwork.sumOf { it.full } + gap * 5
        assertEquals(List(6) { FULL }, fit(artwork, full))
        assertEquals(List(6) { FULL }, fit(artwork, full + 500))
    }

    @Test
    fun theLeastImportantLabelsGoFirstTheTopOnesStay() {
        val full = artwork.sumOf { it.full } + gap * 5
        // One pixel short: the tab hints (rank 3) lose their text, together
        assertEquals(listOf(BARE, BARE, FULL, FULL, FULL, FULL), fit(artwork, full - 1))
        // Without the room of the tab hints' words the next to go are the screen's own actions, Y before X (the later one first)
        val tabs = artwork[0].full - 40 + artwork[1].full - 40
        assertEquals(listOf(BARE, BARE, FULL, FULL, FULL, FULL), fit(artwork, full - tabs))
        assertEquals(listOf(BARE, BARE, FULL, FULL, BARE, FULL), fit(artwork, full - tabs - 1))
        assertEquals(listOf(BARE, BARE, FULL, FULL, BARE, FULL), fit(artwork, full - tabs - (artwork[4].full - 40)))
        // X would lose its words next, but its short word ("Search") still fits
        assertEquals(listOf(BARE, BARE, FULL, SHORT, BARE, FULL), fit(artwork, full - tabs - (artwork[4].full - 40) - 1))
    }

    @Test
    fun theTabHintsAreWordedTogetherOrNotAtAll() {
        val bare = artwork.sumOf { it.bare } + gap * 5
        val full = artwork.sumOf { it.full } + gap * 5
        for (room in bare..full) {
            val details = fit(artwork, room)
            assertEquals("room $room", details[0], details[1])
        }
    }

    @Test
    fun aShorterLabelSavesItsHintWhenTheFullOneDoesNotFit() {
        val items = listOf(item(Glyph.A, "Use image", "Choose"), item(Glyph.B, "Back"))
        val bare = items.sumOf { it.bare } + gap
        // A's full text needs 102 more than its button, its short one 72; B's word 52. 101 is enough for the short word only
        assertEquals(listOf(SHORT, BARE), fit(items, bare + 101))
        assertEquals(listOf(FULL, BARE), fit(items, bare + 102))
        assertEquals(listOf(FULL, FULL), fit(items, bare + 102 + 52))
    }

    @Test
    fun aLessImportantLabelIsDroppedBeforeAMoreImportantOneIsShortened() {
        val full = artwork.sumOf { it.full } + gap * 5
        // "Search name" -> "Search" would save 50, but the tab hints' words (172 and 92) go first, X keeps its full text
        val details = fit(artwork, full - 50)
        assertEquals(listOf(BARE, BARE, FULL, FULL, FULL, FULL), details)
    }

    @Test
    fun theTopRankedHintsKeepTheirLabelsLongest() {
        val bare = artwork.sumOf { it.bare } + gap * 5
        // Room for the words of A ("Use image") and B ("Back") only
        val room = bare + (artwork[2].full - 40) + (artwork[5].full - 40)
        val details = fit(artwork, room)
        assertEquals(listOf(BARE, BARE, FULL, BARE, BARE, FULL), details)
        assertEquals(room, width(artwork, details))
    }

    @Test
    fun aHintWithoutRoomForAnyWordIsOnlyItsButton() {
        assertEquals(List(6) { BARE }, fit(artwork, artwork.sumOf { it.bare } + gap * 5))
        // Not even the buttons fit: still buttons, the bar never promises more than it can draw
        assertEquals(List(6) { BARE }, fit(artwork, 10))
    }

    @Test
    fun theFullTextOfAnImportantHintBeatsTwoShortOnes() {
        val items = listOf(item(Glyph.A, "Reset to profile value", "Reset"), item(Glyph.X, "Change", "Set"), item(Glyph.B, "Back"))
        val bare = items.sumOf { it.bare } + gap * 2
        // A's full text costs 232 more than a button; the leftover goes to B (rank 1) before X (rank 2)
        assertEquals(listOf(FULL, BARE, FULL), fit(items, bare + 232 + 52))
        // Short of B's word by one pixel, the rest goes to X, whose short word fits (B stays a button: its word is wider)
        assertEquals(listOf(FULL, SHORT, BARE), fit(items, bare + 232 + 51))
    }

    @Test
    fun aShortLabelIsIgnoredWhenItIsNotNarrower() {
        val items = listOf(HintFitItem(0, 40, null, 100))
        assertEquals(listOf(FULL), fit(items, 100))
        assertEquals(listOf(BARE), fit(items, 99))
    }

    @Test
    fun noHints() {
        assertEquals(emptyList<HintDetail>(), fit(emptyList(), 0))
    }

    private val home = listOf(item(Glyph.A, "Start"), item(Glyph.Y, "All games"), item(Glyph.MENU_LEFT, "Menu"), item(Glyph.MENU_RIGHT, "Options"))
    private val homeFull = home.sumOf { it.full } + gap * 3

    @Test
    fun theNoteStaysWhileEveryHintKeepsALabel() {
        val fit = fitHintBar(listOf(300, 200), 34, home, gap, homeFull + 300 + 34)
        assertEquals(0, fit.note)
        assertEquals(List(4) { FULL }, fit.details)
        // Too narrow for the long note with the labels, the short one fits
        val second = fitHintBar(listOf(300, 200), 34, home, gap, homeFull + 200 + 34)
        assertEquals(1, second.note)
        assertEquals(List(4) { FULL }, second.details)
    }

    @Test
    fun theNoteGoesBeforeAHintLosesItsLabel() {
        val fit = fitHintBar(listOf(300, 200), 34, home, gap, homeFull + 100)
        assertNull(fit.note)
        assertEquals(List(4) { FULL }, fit.details)
    }

    @Test
    fun withoutANoteTheHintsDegradeOnTheirOwn() {
        val fit = fitHintBar(emptyList(), 34, home, gap, homeFull - 1)
        assertNull(fit.note)
        assertEquals(listOf(FULL, FULL, FULL, BARE), fit.details)
    }

    @Test
    fun aNoteWiderThanTheBarIsNeverKept() {
        val fit = fitHintBar(listOf(300), 34, emptyList(), gap, 200)
        assertNull(fit.note)
    }
}
