package com.limelight.shell

import com.limelight.shell.ui.StagePlanner
import com.limelight.shell.ui.StageMetrics
import com.limelight.shell.ui.StageShape
import com.limelight.shell.ui.compactScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stream menu's dock on a compact stage (issue #9): the prototype's numbers everywhere, and on small panels the same tiles in a row
 * that scrolls instead of nine shrunken ones.
 */
class DockGeometryTest {
    private val c = StreamMenuLayout

    private fun stage(widthPx: Float, heightPx: Float, dpi: Float, textScale: Float = 1f): StageMetrics {
        val density = dpi / 160f
        val plan = StagePlanner.plan(widthPx / density, heightPx / density)
        return StageMetrics(plan.dpPerPx, textScale, plan.stageWidth, plan.stageHeight, plan.shape)
    }

    /** The compact panels of docs/HANDHELD_DEVICES.md (stages 1099x957, 1191x893, 1051x1051, 1108x831, 1082x722, 1488x893), at interface scale 1 and the default 1.15. */
    private fun panels(textScale: Float = 1f) = listOf(
        "thor bottom" to stage(1240f, 1080f, 420f, textScale), "dds bottom" to stage(960f, 720f, 300f, textScale), "cube" to stage(720f, 720f, 255f, textScale),
        "retroid mini" to stage(1280f, 960f, 430f, textScale), "micro" to stage(960f, 640f, 330f, textScale), "dds top" to stage(1200f, 720f, 300f, textScale),
    )

    @Test
    fun theOdinKeepsThePrototypesDock() {
        val g = DockGeometry.forStage(stage(1920f, 1080f, 369f))
        assertTrue(g === DockGeometry.Standard)
        assertEquals(1920f, g.width, 0f)
        assertEquals(150f, g.tile, 0f)
        assertEquals(125f, g.tilesLeft, 0f)
        assertEquals(452f, g.dockHeight, 0f)
        assertEquals(160f, g.dockShift, 0f)
        assertEquals(322f, g.popoverBottom, 0f)
        assertEquals(-18f, g.ringTop, 0f)
        assertFalse(g.scrolls)
        assertEquals(0f, g.scrollMax, 0f)
        assertEquals(1920f, g.rowWidth, 0f)
        for (item in BarItem.entries) assertEquals(0f, dockScrollFor(item, 0f, g), 0f)
        assertEquals(1f, stage(1920f, 1080f, 369f).compactScale, 0f)
        // 21:9 keeps it too (a 1440 x 1080 panel at this density is a compact stage: 1440 stage pixels across)
        assertTrue(DockGeometry.forStage(stage(2520f, 1080f, 369f)) === DockGeometry.Standard)
        assertEquals(163.4f, g.headHeight, .01f)
    }

    @Test
    fun aCompactDockKeepsThePrototypesTilesAndScrolls() {
        for ((name, s) in panels()) {
            assertEquals("$name shape", StageShape.COMPACT, s.shape)
            val g = DockGeometry.forStage(s)
            // Nothing shrinks: tiles, gaps, ring, corners and icon are the 16:9 ones
            assertEquals("$name tile", c.TILE, g.tile, 0f)
            assertEquals("$name gap", c.TILE_GAP, g.gap, 0f)
            assertEquals("$name ring", c.RING, g.ring, 0f)
            assertEquals("$name icon", c.TILE_ICON, g.icon, 0f)
            assertEquals("$name corner", c.TILE_RADIUS, g.tileRadius, 0f)
            assertEquals("$name tiles", 1670f, g.tilesWidth, 0f)
            // The row is wider than the stage and starts 80 px from the edge: the first tile's ring (18) and its glow (62) are on the screen
            assertTrue("$name scrolls", g.scrolls)
            assertEquals("$name first tile", DockGeometry.COMPACT_SIDE, g.tilesLeft, 0f)
            assertEquals("$name row", 1670f + 160f, g.rowWidth, 0f)
            assertEquals("$name scroll range", g.rowWidth - g.width, g.scrollMax, .01f)
            assertTrue("$name side margin", DockGeometry.COMPACT_SIDE >= 48f && DockGeometry.COMPACT_SIDE >= -g.ringTop + 62f)
        }
    }

    @Test
    fun theFocusedTileIsWholeWithTheNextOnePeeking() {
        for ((name, s) in panels()) {
            val g = DockGeometry.forStage(s)
            // Walk right from Resume to Quit game, then back
            var scroll = 0f
            val walk = BarItem.entries + BarItem.entries.reversed()
            for (item in walk) {
                scroll = dockScrollFor(item, scroll, g)
                assertTrue("$name $item scroll $scroll", scroll >= 0f && scroll <= g.scrollMax + .01f)
                val left = tileLeft(item, g) - scroll
                val right = left + g.tile
                // The tile with its ring (18 px outside it) is on the stage, clear of the fade of a cut edge
                val fadeLeft = if (scroll > 0f) DockGeometry.FADE else 0f
                val fadeRight = if (scroll < g.scrollMax) DockGeometry.FADE else 0f
                assertTrue("$name $item ring left ${left - 18f}", left - 18f >= fadeLeft)
                assertTrue("$name $item ring right ${right + 18f}", right + 18f <= g.width - fadeRight)
                // The neighbours peek in: at least 60 px of the next tile and of the one before are on the stage
                if (item != BarItem.QUIT) assertTrue("$name next after $item", g.width - (right + g.gap) >= 60f)
                if (item != BarItem.RESUME) assertTrue("$name before $item", left - g.gap >= 60f)
            }
        }
    }

    @Test
    fun theRowMovesOnlyAsFarAsItMustAndStopsAtItsEnds() {
        val g = DockGeometry.forStage(stage(1240f, 1080f, 420f))
        // Resume and Keyboard are in view from the start
        assertEquals(0f, dockScrollFor(BarItem.RESUME, 0f, g), 0f)
        assertEquals(0f, dockScrollFor(BarItem.KEYBOARD, 0f, g), 0f)
        // Quit game from the start (Left on Resume wraps round): all the way to the end, the last tile 80 px from the right edge
        val end = dockScrollFor(BarItem.QUIT, 0f, g)
        assertEquals(g.scrollMax, end, .01f)
        assertEquals(g.width - DockGeometry.COMPACT_SIDE, tileLeft(BarItem.QUIT, g) + g.tile - end, .01f)
        // and Resume from the end (Right on Quit game): back to the start
        assertEquals(0f, dockScrollFor(BarItem.RESUME, end, g), 0f)
        // A tile that is already inside the margins does not move the row
        val mid = dockScrollFor(BarItem.BITRATE, 0f, g)
        assertEquals(mid, dockScrollFor(BarItem.BITRATE, mid, g), 0f)
        assertEquals(mid, dockScrollFor(BarItem.BRIGHTNESS, mid, g), 0f)
        // The tile that had to be brought in sits 150 px from the edge it came from
        assertEquals(g.width - DockGeometry.FOCUS_MARGIN, tileLeft(BarItem.BITRATE, g) + g.tile - mid, .01f)
    }

    @Test
    fun thePopoverFollowsItsTileAndKeepsItsMargins() {
        for (textScale in listOf(1f, 1.15f, 1.3f)) for ((name, s) in panels(textScale)) {
            val g = DockGeometry.forStage(s)
            for (page in MenuPage.entries.filter { it != MenuPage.MAIN }) {
                // The page opens from its focused tile: the row is where the focus put it, coming from either end
                for (from in listOf(0f, g.scrollMax)) {
                    val scroll = dockScrollFor(page.barItem()!!, from, g)
                    val p = popoverPlacement(page, g, scroll)!!
                    assertTrue("$name $page left ${p.left}", p.left >= g.margin - .01f)
                    assertTrue("$name $page right", p.left + p.width <= g.width - g.margin + .01f)
                    // The caret points at the tile where it is on the stage
                    assertEquals("$name $page caret", tileCenterX(page.barItem()!!, g) - scroll, p.left + p.caretX, .01f)
                }
            }
            assertTrue("$name at $textScale popover has room to be a card: ${popoverMaxHeight(s.stageHeight, g)}", popoverMaxHeight(s.stageHeight, g) >= 280f)
            // The sunk dock keeps its tiles and their label above the bottom margin, and the caption its three lines above it
            // (a stage under 800 high sinks the tiles without their labels, which fade out, for a card of three rows instead of two)
            assertEquals("$name sunk labels", s.stageHeight >= 800f, g.sunkLabels)
            val sunkLabel = if (g.sunkLabels) 24f + 24f * textScale * 1.2f else 0f
            assertEquals("$name sunk dock", g.tile + sunkLabel + g.margin, g.dockHeight - g.dockShift, .01f)
            // The card's rows: at least two and a half are in view at the default interface scale
            val chrome = c.POPOVER_PADDING_Y + c.POPOVER_HEAD_HEIGHT + c.POPOVER_GAP_AFTER_HEAD + c.POPOVER_HELP_GAP + popoverHelpHeight(textScale) + c.POPOVER_PADDING_BOTTOM
            if (textScale <= 1.15f) assertTrue("$name at $textScale rows ${popoverMaxHeight(s.stageHeight, g) - chrome}", popoverMaxHeight(s.stageHeight, g) - chrome >= 2.5f * 72f + 16f)
            assertEquals("$name caption ends 48 above the bottom", g.dockHeight - g.margin, captionTop(textScale, g) + captionHeight(textScale), .01f)
            // The game's name and the hints above the dock stay on the stage (the compact statistics line ends at about 69)
            // (a stage under 800 high sets the two hints side by side for that)
            assertEquals("$name hints", s.stageHeight < 800f, g.hintsInRow)
            val headTop = s.stageHeight - g.dockHeight - c.HEAD_BOTTOM - g.headHeight
            assertTrue("$name at $textScale head top $headTop", headTop >= compactStatsBottom(textScale) + 16f)
        }
    }

    @Test
    fun theOpenCardHidesTheNameAndHintsItWouldCover() {
        // A card from 48 to 1003 on a 1051 stage lies over the block at 80..330
        assertTrue(dockHeadCovered(48f, 1003f, 80f, 330f))
        // A card that starts 24 px or more right of the block leaves it alone
        assertFalse(dockHeadCovered(354f, 1100f, 80f, 330f))
        assertTrue(dockHeadCovered(353f, 1100f, 80f, 330f))
        // On the widest compact stage the Bitrate card (860 wide, centred on its tile) starts right of a short name
        val g = DockGeometry.forStage(stage(1200f, 720f, 300f))
        val p = popoverPlacement(MenuPage.BITRATE, g, dockScrollFor(BarItem.BITRATE, 0f, g))!!
        assertFalse(dockHeadCovered(p.left, p.left + p.width, g.tilesLeft, g.tilesLeft + 250f))
        assertTrue(dockHeadCovered(p.left, p.left + p.width, g.tilesLeft, g.tilesLeft + 900f))
    }

    @Test
    fun compactScaleFollowsTheStageHeightWithinItsLimits() {
        val micro = stage(960f, 640f, 330f).compactScale
        val thor = stage(1240f, 1080f, 420f).compactScale
        assertTrue("micro $micro below thor $thor", micro < thor)
        assertTrue(micro >= .62f && thor <= 1f)
    }
}
