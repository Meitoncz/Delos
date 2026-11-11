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
    const val CARET_WIDTH = 72f
    const val CARET_DEPTH = 30f

    /** The caret keeps this far from the card's corners. */
    const val CARET_INSET = StreamMenuLayout.CARET_MIN_INSET

    /**
     * The caret's tip relative to the element's edge (the bounds already contain the focus scale: a centre-origin cover grows on all sides, the wide
     * first cover only to the left, its right edge stays): negative = the tip reaches INTO the element. The focus ring adds 10 stage px outside the
     * cover (5 ring + 5 gap), and the caret should sit half (CARET_DEPTH / 2 = 15) over the cover with its ring: 10 - 15 = -5. Measured on the Odin
     * with the old -12 and depth 24: the card edge lay exactly on the ring's outer edge and the whole caret over the cover.
     */
    const val TIP_GAP = -5f

    /** The card keeps the stream popover's margin from the top and bottom of the stage, and a smaller one from its sides. */
    const val MARGIN_Y = StreamMenuLayout.POPOVER_MARGIN
    const val MARGIN_X = 60f

    /** The tallest card: the stage's height between the two vertical margins ([marginBottom] is [marginY] unless the bottom has its own). */
    fun maxHeight(stageHeight: Float, marginY: Float = MARGIN_Y, marginBottom: Float = marginY): Float = stageHeight - marginY - marginBottom

    /** A compact stage keeps the card 48 stage pixels from the top and the sides (its stage is too small for the 120 and 60 above and below / beside). */
    const val COMPACT_MARGIN = 48f

    /** A compact stage's card ends this far above the hint bar (which shows what A and B do in the menu); a taller menu scrolls inside the card. */
    const val HINT_BAR_GAP = 16f

    /**
     * The bottom margin of a compact stage's card: the hint bar ([Compact.HINT_BAR], zoomed by the interface scale [textScale], above the
     * cutout [safeBottom]) and [HINT_BAR_GAP].
     */
    fun compactBottomMargin(textScale: Float, safeBottom: Float = 0f): Float = Compact.HINT_BAR * textScale + safeBottom + HINT_BAR_GAP

    /** The room of a focused row's glow around the rows' scroll area (the plate's glow is a 20 px blur drawn 6 px inside the row: about 14 px). */
    const val ROW_GLOW = 16f

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
 * kept inside the margins of the [stageW] x [stageH] stage ([marginBottom] when the bottom edge has its own, a compact stage's hint bar). With [below] under the element, centered on it, caret on top; when it does not fit
 * there but fits above, above it with the caret on the bottom; when it fits neither, pushed up from the bottom edge over the element, or
 * with [besideWhenCramped] (a compact stage, where that would cover the element) beside it like a card that is not [below].
 */
fun placeContextMenu(
    anchor: Rect?, w: Float, h: Float, below: Boolean, stageW: Float, stageH: Float,
    marginX: Float = ContextMenuLayout.MARGIN_X, marginY: Float = ContextMenuLayout.MARGIN_Y, marginBottom: Float = marginY,
    besideWhenCramped: Boolean = false,
): MenuPlacement {
    val l = ContextMenuLayout
    val bottomLimit = stageH - marginBottom
    if (anchor == null) {
        val x = l.FALLBACK_X.coerceIn(marginX, (stageW - marginX - w).coerceAtLeast(marginX))
        val y = l.FALLBACK_Y.coerceIn(marginY, (bottomLimit - h).coerceAtLeast(marginY))
        return MenuPlacement(x, y, CaretSide.None, 0f, x, y)
    }
    val reach = l.TIP_GAP + l.CARET_DEPTH
    if (below) {
        val x = (anchor.center.x - w / 2f).coerceIn(marginX, (stageW - marginX - w).coerceAtLeast(marginX))
        val caret = caretAlong(anchor.center.x - x, w)
        val under = anchor.bottom + reach
        val fitsUnder = under + h <= bottomLimit
        val fitsAbove = anchor.top - reach - h >= marginY
        if (fitsUnder || (!fitsAbove && !besideWhenCramped)) {
            val y = under.coerceAtMost((bottomLimit - h).coerceAtLeast(marginY))
            return MenuPlacement(x, y, CaretSide.Top, caret, x + caret, y - l.CARET_DEPTH)
        }
        if (fitsAbove) {
            val y = anchor.top - reach - h
            return MenuPlacement(x, y, CaretSide.Bottom, caret, x + caret, y + h + l.CARET_DEPTH)
        }
        // Neither under nor above the element (a short compact stage): beside it, as every other card, instead of over it
    }
    val y = (anchor.center.y - h / 2f).coerceIn(marginY, (bottomLimit - h).coerceAtLeast(marginY))
    val caret = caretAlong(anchor.center.y - y, h)
    val right = anchor.right + reach
    if (right + w <= stageW - marginX || anchor.left - reach - w < marginX) {
        val x = right.coerceAtMost((stageW - marginX - w).coerceAtLeast(marginX))
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
