package com.limelight.shell.ui

import androidx.compose.ui.geometry.Rect
import com.limelight.shell.MenuItem
import com.limelight.shell.StreamMenuLayout

/** The side of the card its caret sits on: [Left] for a card to the right of its element, [Top] for one below it. */
enum class CaretSide { None, Left, Right, Top, Bottom }

/** Geometry of the context menu card, stage pixels (docs/GUI.md, "Context menu"). */
internal object ContextMenuLayout {
    const val CORNER = 34f
    const val PADDING = 14f
    const val MIN_WIDTH = 470f
    const val MAX_WIDTH = 640f

    /** The caret: [CARET_WIDTH] along the card's edge, [CARET_DEPTH] out of it (its tip). Same shape as the stream popover's. */
    const val CARET_WIDTH = 60f
    const val CARET_DEPTH = 24f

    /** The caret keeps this far from the card's corners. */
    const val CARET_INSET = StreamMenuLayout.CARET_MIN_INSET

    /** The caret's tip relative to the element's edge: negative = the tip reaches INTO the element (half the caret's depth, the user's wish: the menu sits right at its cover). */
    const val TIP_GAP = -12f

    /** The card keeps the stream popover's margin from the top and bottom of the stage, and a smaller one from its sides. */
    const val MARGIN_Y = StreamMenuLayout.POPOVER_MARGIN
    const val MARGIN_X = 60f
    const val MAX_HEIGHT = StreamMenuLayout.STAGE_HEIGHT - 2 * MARGIN_Y

    /** Where a card without an element to point at (no anchor) sits. */
    const val FALLBACK_X = 760f
    const val FALLBACK_Y = 300f

    const val ROW_HEIGHT = 64f
    const val ROW_GAP = 4f
    const val ROW_RADIUS = 28f
    const val ROW_RING = 3f
}

/**
 * Where the card is, stage pixels: its top-left corner ([x], [y]), the [side] its caret is on and the caret's center
 * [caret] along that edge from the card's top-left corner, and the caret's tip ([tipX], [tipY]), which the card opens from.
 */
data class MenuPlacement(val x: Float, val y: Float, val side: CaretSide, val caret: Float, val tipX: Float, val tipY: Float)

/**
 * The card of size [w] x [h] for the element [anchor] (stage pixels). Beside the element by default: to its right with the
 * caret on the card's left, flipped to its left when it would leave the stage; vertically centered on the element and
 * kept inside the margins. With [below] under the element, centered on it, caret on top; when it does not fit
 * there but fits above, above it with the caret on the bottom.
 */
fun placeContextMenu(anchor: Rect?, w: Float, h: Float, below: Boolean): MenuPlacement {
    val l = ContextMenuLayout
    val stageW = StreamMenuLayout.STAGE_WIDTH
    val bottomLimit = StreamMenuLayout.STAGE_HEIGHT - l.MARGIN_Y
    if (anchor == null) {
        val x = l.FALLBACK_X.coerceIn(l.MARGIN_X, (stageW - l.MARGIN_X - w).coerceAtLeast(l.MARGIN_X))
        val y = l.FALLBACK_Y.coerceIn(l.MARGIN_Y, (bottomLimit - h).coerceAtLeast(l.MARGIN_Y))
        return MenuPlacement(x, y, CaretSide.None, 0f, x, y)
    }
    val reach = l.TIP_GAP + l.CARET_DEPTH
    if (below) {
        val x = (anchor.center.x - w / 2f).coerceIn(l.MARGIN_X, (stageW - l.MARGIN_X - w).coerceAtLeast(l.MARGIN_X))
        val caret = caretAlong(anchor.center.x - x, w)
        val under = anchor.bottom + reach
        if (under + h <= bottomLimit || anchor.top - reach - h < l.MARGIN_Y) {
            val y = under.coerceAtMost((bottomLimit - h).coerceAtLeast(l.MARGIN_Y))
            return MenuPlacement(x, y, CaretSide.Top, caret, x + caret, y - l.CARET_DEPTH)
        }
        val y = anchor.top - reach - h
        return MenuPlacement(x, y, CaretSide.Bottom, caret, x + caret, y + h + l.CARET_DEPTH)
    }
    val y = (anchor.center.y - h / 2f).coerceIn(l.MARGIN_Y, (bottomLimit - h).coerceAtLeast(l.MARGIN_Y))
    val caret = caretAlong(anchor.center.y - y, h)
    val right = anchor.right + reach
    if (right + w <= stageW - l.MARGIN_X || anchor.left - reach - w < l.MARGIN_X) {
        val x = right.coerceAtMost((stageW - l.MARGIN_X - w).coerceAtLeast(l.MARGIN_X))
        return MenuPlacement(x, y, CaretSide.Left, caret, x - l.CARET_DEPTH, y + caret)
    }
    val x = anchor.left - reach - w
    return MenuPlacement(x, y, CaretSide.Right, caret, x + w + l.CARET_DEPTH, y + caret)
}

/** The caret's center along an edge of [length]: the element's center [at], kept [ContextMenuLayout.CARET_INSET] from both corners. */
internal fun caretAlong(at: Float, length: Float): Float =
    at.coerceIn(ContextMenuLayout.CARET_INSET, (length - ContextMenuLayout.CARET_INSET).coerceAtLeast(ContextMenuLayout.CARET_INSET))

/**
 * The submenus of a context menu: a path is the index of the opened item at each level, empty at the menu itself.
 * A path that no longer fits the items (they changed) resolves as far as it goes.
 */
object MenuPath {
    /** The items shown at [path]. */
    fun items(root: List<MenuItem>, path: List<Int>): List<MenuItem> {
        var items = root
        for (index in path) items = items.getOrNull(index)?.children ?: break
        return items
    }

    /** The item whose children [path] shows, null at the root. */
    fun parent(root: List<MenuItem>, path: List<Int>): MenuItem? {
        var items = root
        var parent: MenuItem? = null
        for (index in path) {
            val item = items.getOrNull(index) ?: break
            items = item.children ?: break
            parent = item
        }
        return parent
    }

    /** The path after choosing the item [index] of [path]: one level deeper when it has children, else null (it is an action). */
    fun open(root: List<MenuItem>, path: List<Int>, index: Int): List<Int>? =
        if (items(root, path).getOrNull(index)?.children != null) path + index else null

    /** One level up. */
    fun back(path: List<Int>): List<Int> = path.dropLast(1)
}
