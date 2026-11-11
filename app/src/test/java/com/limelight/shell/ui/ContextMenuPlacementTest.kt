package com.limelight.shell.ui

import androidx.compose.ui.geometry.Rect
import com.limelight.shell.MenuItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMenuPlacementTest {
    private val l = ContextMenuLayout
    private val reach = l.TIP_GAP + l.CARET_DEPTH
    private val stageW = 1920f
    private val stageH = 1080f

    @Test
    fun aTileOnTheLeftGetsTheCardOnItsRightWithTheCaretOnTheCardsLeftPointingAtIt() {
        val tile = Rect(125f, 300f, 425f, 750f)
        val p = placeContextMenu(tile, 560f, 400f, below = false, stageW, stageH)
        assertEquals(CaretSide.Left, p.side)
        assertEquals(tile.right + reach, p.x, 0f)
        assertEquals(tile.center.y - 200f, p.y, 0f)
        assertEquals(200f, p.caret, 0f)
        assertEquals(p.x - l.CARET_DEPTH, p.tipX, 0f)
        assertEquals(tile.center.y, p.tipY, 0f)
    }

    @Test
    fun aTileTooFarRightFlipsTheCardToItsLeft() {
        val tile = Rect(1500f, 300f, 1800f, 750f)
        val p = placeContextMenu(tile, 600f, 400f, below = false, stageW, stageH)
        assertEquals(CaretSide.Right, p.side)
        assertEquals(tile.left - reach - 600f, p.x, 0f)
        assertEquals(p.x + 600f + l.CARET_DEPTH, p.tipX, 0f)
    }

    @Test
    fun theCardStaysInsideTheVerticalMarginsAndTheCaretKeepsPointingAtTheTile() {
        val high = placeContextMenu(Rect(125f, 0f, 425f, 100f), 560f, 400f, below = false, stageW, stageH)
        assertEquals(l.MARGIN_Y, high.y, 0f)
        assertEquals(l.CARET_INSET, high.caret, 0f)

        val low = placeContextMenu(Rect(125f, 900f, 425f, 1050f), 560f, 400f, below = false, stageW, stageH)
        assertEquals(1080f - l.MARGIN_Y - 400f, low.y, 0f)
        assertEquals(400f - l.CARET_INSET, low.caret, 0f)
    }

    @Test
    fun theCaretNeverLeavesTheCardsCorners() {
        assertEquals(l.CARET_INSET, caretAlong(-50f, 400f), 0f)
        assertEquals(400f - l.CARET_INSET, caretAlong(900f, 400f), 0f)
        assertEquals(250f, caretAlong(250f, 400f), 0f)
        // A card shorter than both insets keeps the caret at the first inset instead of inverting the range
        assertEquals(l.CARET_INSET, caretAlong(10f, 100f), 0f)
    }

    @Test
    fun aMenuThatBelongsToAnElementOpensBelowItCenteredWithTheCaretOnTop() {
        val pc = Rect(800f, 300f, 1000f, 500f)
        val p = placeContextMenu(pc, 500f, 300f, below = true, stageW, stageH)
        assertEquals(CaretSide.Top, p.side)
        assertEquals(pc.center.x - 250f, p.x, 0f)
        assertEquals(pc.bottom + reach, p.y, 0f)
        assertEquals(250f, p.caret, 0f)
        assertEquals(pc.center.x, p.tipX, 0f)
        assertEquals(p.y - l.CARET_DEPTH, p.tipY, 0f)
    }

    @Test
    fun aBelowCardIsKeptInsideTheSideMarginsWhileItsCaretStillPointsAtTheElement() {
        val pc = Rect(1750f, 300f, 1900f, 500f)
        val p = placeContextMenu(pc, 600f, 300f, below = true, stageW, stageH)
        assertEquals(1920f - l.MARGIN_X - 600f, p.x, 0f)
        // The element's center is 565 from the card's edge, beyond the last place the caret may sit
        assertEquals(600f - l.CARET_INSET, p.caret, 0f)
        val left = placeContextMenu(Rect(0f, 300f, 100f, 500f), 600f, 300f, below = true, stageW, stageH)
        assertEquals(l.MARGIN_X, left.x, 0f)
        assertEquals(l.CARET_INSET, left.caret, 0f)
    }

    @Test
    fun aBelowCardThatDoesNotFitUnderTheElementGoesAboveItWithTheCaretOnTheBottom() {
        val pc = Rect(800f, 700f, 1000f, 900f)
        val p = placeContextMenu(pc, 500f, 300f, below = true, stageW, stageH)
        assertEquals(CaretSide.Bottom, p.side)
        assertEquals(pc.top - reach - 300f, p.y, 0f)
        assertEquals(p.y + 300f + l.CARET_DEPTH, p.tipY, 0f)
    }

    @Test
    fun aBelowCardThatFitsNeitherWayIsPulledIntoTheMarginsUnderTheElement() {
        val pc = Rect(800f, 150f, 1000f, 950f)
        val p = placeContextMenu(pc, 500f, 700f, below = true, stageW, stageH)
        assertEquals(CaretSide.Top, p.side)
        assertEquals(1080f - l.MARGIN_Y - 700f, p.y, 0f)
    }

    @Test
    fun withoutAnAnchorTheCardHasNoCaret() {
        val p = placeContextMenu(null, 500f, 300f, below = false, stageW, stageH)
        assertEquals(CaretSide.None, p.side)
        assertEquals(l.FALLBACK_X, p.x, 0f)
        assertEquals(l.FALLBACK_Y, p.y, 0f)
    }

    @Test
    fun theTallestCardFillsTheSpaceBetweenTheMargins() {
        assertEquals(1080f - 2 * l.MARGIN_Y, l.maxHeight(1080f), 0f)
        val p = placeContextMenu(Rect(125f, 400f, 425f, 600f), 560f, l.maxHeight(1080f), below = false, stageW, stageH)
        assertEquals(l.MARGIN_Y, p.y, 0f)
    }

    private val leaf = MenuItem("a", "Leaf")
    private val deeper = MenuItem("b", "Deeper", children = listOf(MenuItem("c", "First"), MenuItem("d", "Second")))
    private val root = listOf(leaf, deeper)

    @Test
    fun theRootPathShowsTheMenuItself() {
        assertEquals(root, MenuPath.items(root, emptyList()))
        assertNull(MenuPath.parent(root, emptyList()))
    }

    @Test
    fun choosingAnItemWithChildrenOpensThemAndAnActionDoesNot() {
        assertEquals(listOf(1), MenuPath.open(root, emptyList(), 1))
        assertNull(MenuPath.open(root, emptyList(), 0))
        assertNull(MenuPath.open(root, emptyList(), 7))
        assertEquals(deeper.children, MenuPath.items(root, listOf(1)))
        assertEquals(deeper, MenuPath.parent(root, listOf(1)))
        assertNull(MenuPath.open(root, listOf(1), 0))
    }

    @Test
    fun backGoesUpOneLevelAndStopsAtTheRoot() {
        assertEquals(emptyList<Int>(), MenuPath.back(listOf(1)))
        assertEquals(listOf(1), MenuPath.back(listOf(1, 0)))
        assertEquals(emptyList<Int>(), MenuPath.back(emptyList()))
    }

    @Test
    fun aPathThatNoLongerFitsTheItemsResolvesAsFarAsItGoes() {
        assertEquals(root, MenuPath.items(root, listOf(5)))
        assertEquals(root, MenuPath.items(root, listOf(0)))
        assertEquals(deeper.children, MenuPath.items(root, listOf(1, 0)))
        assertTrue(MenuPath.parent(root, listOf(5)) == null)
    }
}
