package com.limelight.shell.screens

import com.limelight.shell.ui.StageMetrics
import com.limelight.shell.ui.StageShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The user's layout rules for the settings family, held on every stage the layout sheets are taken in (tools/layout/shapes.txt): the six
 * compact ones (Thor bottom, RG DDS top and bottom, Retroid Pocket Mini, a 1:1 panel, Pocket Micro), 16:9, 4:3 and 21:9.
 */
class FamilyLayoutTest {
    private fun stage(width: Float, height: Float, compact: Boolean) =
        StageMetrics(dpPerPx = .43f, textScale = 1.15f, stageWidth = width, stageHeight = height, shape = if (compact) StageShape.COMPACT else StageShape.STANDARD)

    private val compact = listOf(stage(1099f, 957f, true), stage(1488f, 893f, true), stage(1191f, 893f, true), stage(1108f, 831f, true), stage(1051f, 1051f, true), stage(1082f, 722f, true))
    private val micro = compact.last()
    private val standard = stage(1920f, 1080f, false)
    private val wide = listOf(standard, stage(1920f, 1440f, false), stage(2520f, 1080f, false))

    private fun name(stage: StageMetrics) = "${stage.stageWidth.roundToInt()}x${stage.stageHeight.roundToInt()}"

    /** The hint bar's room at the default interface scale. */
    private val bottom = SettingsLayout.bottom(1.15f)

    // The description dock

    @Test fun theDockIsAboutAQuarterOfTheStageAndTallerThanBefore() {
        compact.forEach { stage ->
            val dock = HelpDockLayout.height(stage.stageHeight)
            assertTrue("${name(stage)} dock $dock", dock in HelpDockLayout.MIN_HEIGHT..HelpDockLayout.MAX_HEIGHT)
            assertTrue("${name(stage)} dock $dock is over the old 168", dock > 168f)
            assertTrue("${name(stage)} dock $dock is a quarter at most", dock <= stage.stageHeight * .25f + .5f || dock == HelpDockLayout.MIN_HEIGHT)
        }
        assertEquals(239.25f, HelpDockLayout.height(957f), .01f)
        assertEquals(262.75f, HelpDockLayout.height(1051f), .01f)
        assertEquals(270f, HelpDockLayout.height(1440f), .01f)
    }

    /**
     * The lists above a dock: where their first row starts (under the tabs and the first section's heading in Settings, under the banner's
     * header per game, under the title and a heading in Debug info) and the hint bar's room under the dock.
     */
    private class Listing(val screen: String, val rowsTop: Float, val bottom: Float)

    private fun listings() = listOf(
        Listing("settings", SettingsLayout.COMPACT_LIST_TOP + 14f + SettingsLayout.SECTION_HEADING, bottom),
        Listing("game", GameSettingsLayout.COMPACT_LIST_TOP + GameSettingsLayout.COMPACT_LIST_INNER_TOP, bottom),
        Listing("debug", 184f + 14f + SettingsLayout.SECTION_HEADING, bottom),
    )

    private fun dock(stage: StageMetrics, list: Listing) = HelpDockLayout.height(stage.stageHeight, list.rowsTop, list.bottom, SettingsLayout.tallRow(stage))

    private fun rows(stage: StageMetrics, list: Listing) = HelpDockLayout.wholeRows(stage.stageHeight, list.rowsTop, list.bottom, SettingsLayout.tallRow(stage))

    @Test fun theDockIsNeverUnder210AndTheListKeepsThreeWholeRowsWhereTheStageHasTheRoom() {
        compact.forEach { stage ->
            listings().forEach {
                val dock = dock(stage, it)
                assertTrue("${name(stage)} ${it.screen} dock $dock", dock >= (if (stage.stageHeight < HelpDockLayout.SHORT_STAGE) 168f else 210f) && dock <= 270f)
                // The list keeps 16 px to the dock and the dock ends at the hint bar's room
                assertTrue(stage.stageHeight - it.bottom - dock - HelpDockLayout.LIST_GAP > it.rowsTop)
                if (stage.stageHeight >= 950f) assertTrue("${name(stage)} ${it.screen} shows ${rows(stage, it)} whole rows", rows(stage, it) >= 3)
            }
        }
    }

    /**
     * The stages under about 950 px high cannot hold a 210 px dock and three whole rows (the 893 px ones are 15 px short in Settings): the
     * dock's height is the user's request, so the list shows fewer whole rows with the next one fading over the dock.
     */
    @Test fun theShortStagesKeepTheDockAt210AndShowWhatRowsFit() {
        val rowsAt = { stage: StageMetrics -> listings().map { rows(stage, it) } }
        assertEquals(listOf(2, 3, 2), rowsAt(compact[1]))
        assertEquals(listOf(2, 2, 2), rowsAt(compact[3]))
        assertEquals(listOf(1, 1, 1), rowsAt(micro).map { 1 })
        assertTrue(rowsAt(micro).all { it >= 1 })
        listings().forEach {
            assertEquals("${it.screen} dock 831", 210f, dock(compact[3], it), .01f)
            assertEquals("${it.screen} dock 722", 168f, dock(micro, it), .01f)
        }
    }

    @Test fun theDocksBodyHoldsTwoLinesOfHelpAtLeast() {
        // A line of the help: 21 px text at the default interface scale of 1.15 and a line height of 1.45
        val line = 21f * 1.15f * 1.45f
        compact.forEach { stage ->
            listings().forEach {
                val body = HelpDockLayout.bodyHeight(dock(stage, it))
                assertTrue("${name(stage)} ${it.screen} body $body", body >= 2f * line)
            }
        }
    }

    @Test fun theLegendSitsInTheHeaderOnlyWhenTheWholeNameFitsBesideIt() {
        val inner = 1099f - 2f * 48f - 2f * HelpDockLayout.PAD_X
        val legend = 300f
        assertTrue(HelpDockLayout.legendInHeader(inner, 400f, legend))
        val widest = inner - HelpDockLayout.DISC - HelpDockLayout.TITLE_GAP - HelpDockLayout.LEGEND_GAP - legend
        assertTrue(HelpDockLayout.legendInHeader(inner, widest, legend))
        assertFalse(HelpDockLayout.legendInHeader(inner, widest + 1f, legend))
    }

    // Tab rows

    @Test fun aTabRowIsCentredOnTheStageWhileItStaysInsideTheContent() {
        (compact + wide).forEach { stage ->
            val margin = SettingsLayout.margin(stage)
            val row = 700f
            val x = TabRowLayout.x(stage.stageWidth, row, SHOULDER_OUTSET, margin, stage.stageWidth - margin)
            assertEquals("${name(stage)} left and right of the row", x, stage.stageWidth - (x + row), .001f)
        }
    }

    @Test fun aTabRowThatWouldLeaveTheContentIsFlushWithItsRightEdge() {
        // The compact settings: the tabs beside the search circle
        compact.forEach { stage ->
            val margin = SettingsLayout.margin(stage)
            val right = stage.stageWidth - margin - SettingsLayout.searchSize(stage) - SettingsLayout.SEARCH_GAP
            listOf(600f, 760f, 820f, 900f).forEach { row ->
                val x = TabRowLayout.x(stage.stageWidth, row, SHOULDER_OUTSET, margin, right)
                val centred = (stage.stageWidth - row) / 2f
                val visibleRight = x + row - SHOULDER_OUTSET
                if (x == centred) assertTrue("${name(stage)} $row centred clear of the circle", visibleRight <= right)
                else assertEquals("${name(stage)} $row flush against the circle's room", right, visibleRight, .001f)
                // Nothing in between
                assertTrue(x == centred || x == TabRowLayout.flushRight(row, SHOULDER_OUTSET, right))
            }
        }
        // A row narrow enough is centred on every compact stage: the search circle stays clear of it
        compact.forEach { stage ->
            val margin = SettingsLayout.margin(stage)
            val right = stage.stageWidth - margin - SettingsLayout.searchSize(stage) - SettingsLayout.SEARCH_GAP
            assertEquals((stage.stageWidth - 700f) / 2f, TabRowLayout.x(stage.stageWidth, 700f, SHOULDER_OUTSET, margin, right), .001f)
        }
    }

    /** 16:9 settings: the row is 12 px wider than the content and has always been centred (L1 begins 6 px before the title): it stays so, untightened. */
    @Test fun aRowThatOverhangsTheContentByAFewPixelsStaysCentredAndKeepsItsWidth() {
        val natural = 1632f + 2f * SHOULDER_OUTSET
        val p = TabRowLayout.place(1920f, natural, SHOULDER_OUTSET, 150f, 1770f)
        assertEquals(natural, p.width, .001f)
        assertEquals((1920f - natural) / 2f, p.x, .001f)
        assertFalse(p.scrolls)
    }

    /** Not compact: a Settings row 40 px wider than its content (both badges, all tabs) stays centred on the stage, unclipped and unscrolled. */
    @Test fun aRoomyStageNeverScrollsARowThatFitsTheStage() {
        listOf(1920f, 2520f, 1920f).forEach { stageWidth ->
            val natural = 1700f
            val p = TabRowLayout.place(stageWidth, natural, SHOULDER_OUTSET, 150f, stageWidth - 150f.coerceAtMost(150f), centred = true, spill = true)
            assertFalse(p.scrolls)
            assertEquals((stageWidth - natural) / 2f, p.x, .001f)
        }
        // Artwork (flush right): the R1 badge's edge on the content's right edge, nothing scrolled
        val a = TabRowLayout.place(1920f, 1500f, SHOULDER_OUTSET, 150f, 1770f, centred = false, spill = true)
        assertFalse(a.scrolls)
        assertEquals(1770f, a.x + a.width - SHOULDER_OUTSET, .001f)
        // A row wider than the stage still scrolls
        assertTrue(TabRowLayout.place(1920f, 2000f, SHOULDER_OUTSET, 150f, 1770f, centred = true, spill = true).scrolls)
    }

    /** A row wider than its room neither tightens nor overhangs nor runs left of the content: it scrolls in the room between the edges. */
    @Test fun aRowWiderThanItsRoomScrollsBetweenTheContentsEdges() {
        (compact + wide).forEach { stage ->
            val margin = SettingsLayout.margin(stage)
            val right = stage.stageWidth - margin
            listOf(true, false).forEach { centred ->
                val room = right - margin
                val p = TabRowLayout.place(stage.stageWidth, room + 2f * SHOULDER_OUTSET + 400f, SHOULDER_OUTSET, margin, right, centred)
                assertTrue("${name(stage)} scrolls", p.scrolls)
                assertEquals("${name(stage)} left edge", margin, p.x + SHOULDER_OUTSET, .001f)
                assertEquals("${name(stage)} right edge", right, p.x + p.width - SHOULDER_OUTSET, .001f)
                assertTrue("never negative", p.x >= 0f)
            }
        }
        // The counterexample of the review: 1830 wide against 150..1770 has the room, not x = -50
        val p = TabRowLayout.place(1920f, 1830f, SHOULDER_OUTSET, 150f, 1770f, centred = false)
        assertTrue(p.scrolls)
        assertEquals(140f, p.x, .001f)
        // Just under the overhang that is allowed stays unscrolled
        assertFalse(TabRowLayout.place(1920f, 1640f + 2f * SHOULDER_OUTSET - 8f, SHOULDER_OUTSET, 150f, 1770f).scrolls)
        assertTrue(TabRowLayout.place(1920f, 1640f + 2f * SHOULDER_OUTSET + 8f, SHOULDER_OUTSET, 150f, 1770f).scrolls)
    }

    @Test fun theArtworkTabsEndExactlyAtTheContentsRightEdge() {
        wide.forEach { stage ->
            val right = stage.stageWidth - SettingsLayout.margin(stage)
            listOf(820f, 905.5f, 1050f).forEach { row ->
                val x = TabRowLayout.flushRight(row, SHOULDER_OUTSET, right)
                assertEquals("${name(stage)} R1's edge", right, x + row - SHOULDER_OUTSET, .001f)
            }
        }
        // 16:9: the R1 badge ended near x 1705 before, the Filter pill at 1770
        assertEquals(1770f, TabRowLayout.flushRight(880f, SHOULDER_OUTSET, 1770f) + 880f - SHOULDER_OUTSET, .001f)
    }

    // The Artwork grid

    private class Kind(val name: String, val ratio: Float, val columns: Int, val compactMin: Float, val compactMax: Float = Float.MAX_VALUE)

    private val kinds = listOf(
        Kind("cover", 600f / 900f, 7, ArtworkLayout.COVER_MIN, ArtworkLayout.COVER_MAX), Kind("wide", 920f / 430f, 4, ArtworkLayout.WIDE_MIN),
        Kind("hero", 1920f / 620f, 3, ArtworkLayout.HERO_MIN), Kind("logo", 2f, 5, ArtworkLayout.LOGO_MIN),
    )

    private fun compactGrid(stage: StageMetrics, kind: Kind): ArtGrid {
        val width = stage.stageWidth - 2f * SettingsLayout.margin(stage)
        val viewport = ArtworkLayout.viewport(stage.stageHeight, bottom, ArtworkLayout.hasInfo(width, stage.stageHeight, bottom))
        return ArtGridLayout.compact(width, viewport, kind.ratio, kind.compactMin, kind.compactMax)
    }

    /** The columns of a stage that is not compact, as `ArtTab.columnsFor` counts them. */
    private fun wideGrid(stage: StageMetrics, kind: Kind): ArtGrid =
        ArtGridLayout.grid(stage.stageWidth - 300f, kind.ratio, (kind.columns * (stage.stageWidth - 200f) / 1720f).roundToInt().coerceAtLeast(1))

    private fun grids(): List<Triple<String, StageMetrics, ArtGrid>> =
        compact.flatMap { stage -> kinds.map { Triple("${name(stage)} ${it.name}", stage, compactGrid(stage, it)) } } +
            wide.flatMap { stage -> kinds.map { Triple("${name(stage)} ${it.name}", stage, wideGrid(stage, it)) } }

    @Test fun theRoomBetweenTilesCoversTheFocusedTilesGrowthItsRingAndTwelvePixels() {
        grids().forEach { (name, _, grid) ->
            val needX = grid.tileWidth * (ArtGridLayout.FOCUS_SCALE - 1f) + ArtGridLayout.RING + ArtGridLayout.CLEARANCE
            val needY = grid.tileHeight * (ArtGridLayout.FOCUS_SCALE - 1f) + ArtGridLayout.RING + ArtGridLayout.CLEARANCE
            assertTrue("$name gap ${grid.gapX} of $needX", grid.gapX >= needX - .01f)
            assertTrue("$name row gap ${grid.gapY} of $needY", grid.gapY >= needY - .01f)
            assertTrue("$name gaps of 28 px at least", grid.gapX >= ArtGridLayout.MIN_GAP - .01f && grid.gapY >= ArtGridLayout.MIN_GAP - .01f)
            // So the focused tile's ring keeps 12 px to its neighbours on every side
            assertTrue("$name ring ${grid.ringX}", grid.ringX + ArtGridLayout.CLEARANCE <= grid.gapX + .01f)
            assertTrue("$name ring ${grid.ringY}", grid.ringY + ArtGridLayout.CLEARANCE <= grid.gapY + .01f)
        }
    }

    @Test fun theTilesAndTheirGapsFillTheContentWidthAndKeepTheirShape() {
        grids().forEach { (name, stage, grid) ->
            val width = stage.stageWidth - 2f * SettingsLayout.margin(stage)
            assertEquals("$name fills", width, grid.columns * grid.tileWidth + (grid.columns - 1) * grid.gapX, .01f)
        }
        compact.forEach { stage -> kinds.forEach { assertEquals(it.ratio, compactGrid(stage, it).let { g -> g.tileWidth / g.tileHeight }, .0001f) } }
    }

    @Test fun theFirstRowsRingEndsTwentyFourPixelsUnderTheFilterStrip() {
        compact.forEach { stage ->
            kinds.forEach { kind ->
                val grid = compactGrid(stage, kind)
                assertEquals("${name(stage)} ${kind.name}", ArtGridLayout.BLOCK_GAP, grid.padTop - grid.ringY, .001f)
            }
        }
        // Not compact: the grid starts at 324, 4 px under the strip's bottom edge (320), and pads its first row by 46 px
        wide.forEach { stage -> kinds.forEach { kind -> assertTrue(46f + 4f - wideGrid(stage, kind).ringY >= ArtGridLayout.BLOCK_GAP) } }
    }

    @Test fun theFocusedTilesGlowEndsInsideTheGridsViewport() {
        grids().forEach { (name, stage, grid) ->
            // The grid's box is the stage's width and the tiles are padded in by the margin, so nothing but the screen's own edge is there to
            // cut the glow: the widest compact tiles (the backgrounds) send the last 2 px of its blur past the 48 px margin
            assertTrue("$name glow ${grid.glowSide} at the side", grid.glowSide <= SettingsLayout.margin(stage) + 2.5f)
            assertTrue("$name glow under the last row", grid.glowBottom <= grid.padBottom)
            // The compact viewport starts 8 px under the toolbar: the first row's glow still ends inside it
            assertTrue("$name glow above the first row", grid.glowTop <= grid.padTop - ArtworkLayout.GRID_INSET)
        }
        wide.forEach { stage -> kinds.forEach { kind -> assertTrue(wideGrid(stage, kind).let { it.glowTop <= 46f && it.glowBottom <= 96f }) } }
    }

    /**
     * The covers are 190 to 210 px wide where the stage's height holds a focused row of them whole (the room left over goes into the gaps):
     * the 957 and 1051 px stages. The shorter ones (893: 173 and 158 px, 831: 145, 722: 141) take smaller ones, never under 72 % of 190, because
     * a row of 190 px covers (285 px tall) with its ring, glow and the next row's peek would not show whole over the info strip.
     */
    @Test fun theCompactCoversAreAboutTwoHundredWideWhereTheStageIsTallEnough() {
        listOf(compact[0], compact[4]).forEach { stage ->
            val grid = compactGrid(stage, kinds[0])
            assertTrue("${name(stage)} cover ${grid.tileWidth}", grid.tileWidth in ArtworkLayout.COVER_MIN..ArtworkLayout.COVER_MAX + .01f)
            assertEquals("${name(stage)} columns", 4, grid.columns)
            // The gaps take what the cap leaves: more than the rule needs, the rows' own clearance under it
            assertTrue("${name(stage)} gap ${grid.gapX}", grid.gapX >= 38f)
        }
        // The 893 px stages take 7 and 6 columns (173 and 158 px): a 205 px row would not show whole above the info strip
        assertEquals(7, compactGrid(compact[1], kinds[0]).columns)
        assertEquals(6, compactGrid(compact[2], kinds[0]).columns)
        compact.forEach { stage ->
            kinds.forEach { kind -> assertTrue("${name(stage)} ${kind.name}", compactGrid(stage, kind).tileWidth >= kind.compactMin * ArtGridLayout.SHORT_SHARE - .01f) }
            assertTrue("${name(stage)} never over the cap", compactGrid(stage, kinds[0]).tileWidth <= ArtworkLayout.COVER_MAX + .01f)
        }
    }

    @Test fun aFocusedRowShowsWholeWithAPeekOfTheNextOnEveryCompactStage() {
        compact.forEach { stage ->
            val width = stage.stageWidth - 2f * SettingsLayout.margin(stage)
            val viewport = ArtworkLayout.viewport(stage.stageHeight, bottom, ArtworkLayout.hasInfo(width, stage.stageHeight, bottom))
            kinds.forEach { kind ->
                val grid = compactGrid(stage, kind)
                assertTrue("${name(stage)} ${kind.name} needs ${grid.rowRoom} of $viewport", grid.rowRoom <= viewport)
                assertTrue(grid.rowRoom >= grid.padTop + grid.tileHeight + grid.gapY + ArtGridLayout.PEEK)
            }
        }
    }

    @Test fun onlyTheShortestStageHasNoRoomForTheInfoStrip() {
        compact.forEach { stage ->
            val width = stage.stageWidth - 2f * SettingsLayout.margin(stage)
            assertEquals(name(stage), stage != micro, ArtworkLayout.hasInfo(width, stage.stageHeight, bottom))
        }
    }

    @Test fun aViewportTooShortForARowTakesSmallerTilesDownToTheFloor() {
        val normal = ArtGridLayout.compact(986f, 400f, 2f / 3f, ArtworkLayout.COVER_MIN, ArtworkLayout.COVER_MAX)
        val short = ArtGridLayout.compact(986f, 330f, 2f / 3f, ArtworkLayout.COVER_MIN, ArtworkLayout.COVER_MAX)
        assertTrue(normal.tileWidth <= ArtworkLayout.COVER_MAX + .01f)
        assertTrue(short.columns > normal.columns)
        assertTrue(short.rowRoom <= 330f)
        // Nothing under 72 % of the kind's least width, however short the viewport is
        val shortest = ArtGridLayout.compact(986f, 100f, 2f / 3f, ArtworkLayout.COVER_MIN, ArtworkLayout.COVER_MAX)
        assertTrue(shortest.tileWidth >= ArtworkLayout.COVER_MIN * ArtGridLayout.SHORT_SHARE - .01f)
    }

    @Test fun sixteenByNineKeepsItsColumnsWithSmallerTilesAndTheRulesGaps() {
        val expected = listOf(7 to 203.8f, 4 to 373.2f, 3 to 506.4f, 5 to 293.9f)
        kinds.forEachIndexed { i, kind ->
            val grid = wideGrid(standard, kind)
            assertEquals(kind.name, expected[i].first, grid.columns)
            assertEquals(kind.name, expected[i].second, grid.tileWidth, .1f)
        }
        // Before: 24 px everywhere, which left the focused cover's ring 9 px from its neighbours and 6 px from the next row
        assertEquals(32.2f, wideGrid(standard, kinds[0]).gapX, .1f)
        assertEquals(38.3f, wideGrid(standard, kinds[0]).gapY, .1f)
    }

    // The Artwork toolbar

    @Test fun theChipDropsItsCountBeforeTheNameIsCut() {
        assertTrue(ArtToolbarLayout.showsCount(inner = 400f, nameWidth = 120f, countWidth = 130f, gap = 16f))
        assertTrue(ArtToolbarLayout.showsCount(inner = 266f, nameWidth = 120f, countWidth = 130f, gap = 16f))
        assertFalse(ArtToolbarLayout.showsCount(inner = 265f, nameWidth = 120f, countWidth = 130f, gap = 16f))
    }

    @Test fun theSearchPillTakesTheLongestLabelThatFits() {
        val labels = listOf(300f, 150f, 85f)
        assertEquals(0, ArtToolbarLayout.label(available = 1000f, fixed = 700f, labels = labels))
        assertEquals(1, ArtToolbarLayout.label(available = 1000f, fixed = 701f, labels = labels))
        assertEquals(2, ArtToolbarLayout.label(available = 1000f, fixed = 851f, labels = labels))
        // Nothing fits (the filter chips scroll): the shortest
        assertEquals(2, ArtToolbarLayout.label(available = 1000f, fixed = 5000f, labels = labels))
    }
}
