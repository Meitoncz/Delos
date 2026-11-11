package com.limelight.shell

import com.limelight.shell.ui.StagePlanner
import com.limelight.shell.ui.StageMetrics
import com.limelight.shell.ui.StageShape
import com.limelight.shell.ui.compactScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stream menu's dock on a compact stage (issue #9, phase C): the prototype's numbers elsewhere, a fitted dock on small panels. */
class DockGeometryTest {
    private fun stage(widthPx: Float, heightPx: Float, dpi: Float): StageMetrics {
        val density = dpi / 160f
        val plan = StagePlanner.plan(widthPx / density, heightPx / density)
        return StageMetrics(plan.dpPerPx, 1f, plan.stageWidth, plan.stageHeight, plan.shape)
    }

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
        assertEquals(1f, stage(1920f, 1080f, 369f).compactScale, 0f)
    }

    @Test
    fun everyCompactPanelFitsNineTilesBetweenTheMargins() {
        val panels = listOf(
            "thor bottom" to stage(1240f, 1080f, 420f), "dds bottom" to stage(960f, 720f, 300f), "cube" to stage(720f, 720f, 255f),
            "retroid mini" to stage(1280f, 960f, 430f), "micro" to stage(960f, 640f, 330f), "dds top" to stage(1200f, 720f, 300f),
        )
        for ((name, s) in panels) {
            assertEquals("$name shape", StageShape.COMPACT, s.shape)
            val g = DockGeometry.forStage(s)
            assertTrue("$name tile ${g.tile} is a target of at least 64", g.tile >= 64f)
            assertTrue("$name tile ${g.tile} not larger than the prototype's", g.tile <= 150f)
            assertTrue("$name left ${g.tilesLeft} keeps the 48 margin", g.tilesLeft >= 48f - .01f)
            assertEquals("$name tiles centered", g.tilesLeft, g.width - g.tilesLeft - g.tilesWidth, .01f)
            // A popover keeps its margins on both sides and clears the stage top
            for (page in MenuPage.entries.filter { it != MenuPage.MAIN }) {
                val p = popoverPlacement(page, g)!!
                assertTrue("$name $page left", p.left >= g.margin - .01f)
                assertTrue("$name $page right", p.left + p.width <= g.width - g.margin + .01f)
            }
            assertTrue("$name popover has room to be a card", popoverMaxHeight(s.stageHeight, g) >= 300f)
            // The sunk dock keeps its label above the bottom margin
            assertTrue("$name sunk dock", g.dockHeight - g.dockShift >= g.tile)
        }
    }

    @Test
    fun compactScaleFollowsTheStageHeightWithinItsLimits() {
        val micro = stage(960f, 640f, 330f).compactScale
        val thor = stage(1240f, 1080f, 420f).compactScale
        assertTrue("micro $micro below thor $thor", micro < thor)
        assertTrue(micro >= .62f && thor <= 1f)
    }
}
