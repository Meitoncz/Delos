package com.limelight.shell.screens

/*
 * The geometry of the settings family (Settings, per-game settings, Debug info, Artwork) that the user's layout rules can be computed for:
 * the description dock, the place of a tab row and the artwork grid. Pure functions in stage pixels (docs/GUI.md, "Screen shapes"), so
 * `FamilyLayoutTest` holds the rules on every stage size instead of the eye alone.
 */

/**
 * The description dock under the compact lists ([HelpDock]): about a quarter of the stage's height. Inside it the header (the icon disc, the
 * name and, when it fits, the legend of the impact chips) stays put and everything else is ONE area under it that scrolls as a whole.
 */
internal object HelpDockLayout {
    const val MIN_HEIGHT = 210f
    const val MAX_HEIGHT = 270f

    /** Stages under this height (the Pocket Micro's 722) trade dock height for list rows: the dock gives way down to [SHORT_MIN_HEIGHT]. */
    const val SHORT_STAGE = 800f
    const val SHORT_MIN_HEIGHT = 168f
    const val ROW_PEEK = 30f

    /** The room between the list and the dock, and between the header and the scrolling body. */
    const val LIST_GAP = 16f
    const val BODY_GAP = 14f

    /** The room between two rows of a list. */
    const val ROW_GAP = 10f

    const val PAD_X = 24f
    const val PAD_Y = 14f

    /** The header: the icon disc, the room to the name, and the least room between the name and the legend. */
    const val DISC = 48f
    const val TITLE_GAP = 14f
    const val LEGEND_GAP = 24f

    /** How far the body fades at an edge it continues behind. */
    const val FADE = 36f

    /** About a quarter of the stage's height. */
    fun height(stageHeight: Float): Float = (stageHeight * .25f).coerceIn(MIN_HEIGHT, MAX_HEIGHT)

    /**
     * The dock under a list whose first row may start at [rowsTop], over the hint bar's [bottom]: the quarter of the stage, less what three
     * whole rows [rowHeight] high need above it, but never under [MIN_HEIGHT]: the dock's height is the user's request, so a stage too short for
     * both (under about 950 px high) shows fewer whole rows, the next one fading over the dock.
     */
    fun height(stageHeight: Float, rowsTop: Float, bottom: Float, rowHeight: Float): Float {
        if (stageHeight < SHORT_STAGE) {
            // A short stage keeps two whole rows and a peek of the third over the dock, which gives way down to SHORT_MIN_HEIGHT (its body scrolls)
            val twoRowsAndPeek = 2f * rowHeight + 2f * ROW_GAP + ROW_PEEK
            return minOf(height(stageHeight), stageHeight - rowsTop - bottom - LIST_GAP - twoRowsAndPeek).coerceAtLeast(SHORT_MIN_HEIGHT)
        }
        val threeRows = 3f * rowHeight + 2f * ROW_GAP
        return minOf(height(stageHeight), stageHeight - rowsTop - bottom - LIST_GAP - threeRows).coerceAtLeast(MIN_HEIGHT)
    }

    /** How many whole rows [rowHeight] high the list above that dock shows from [rowsTop] down. */
    fun wholeRows(stageHeight: Float, rowsTop: Float, bottom: Float, rowHeight: Float): Int {
        val list = stageHeight - rowsTop - bottom - LIST_GAP - height(stageHeight, rowsTop, bottom, rowHeight)
        return ((list + ROW_GAP + .01f) / (rowHeight + ROW_GAP)).toInt()
    }

    /** What the scrolling body gets of a dock [dockHeight] high. */
    fun bodyHeight(dockHeight: Float): Float = dockHeight - 2f * PAD_Y - DISC - BODY_GAP

    /** Whether the legend sits at the right of the name ([innerWidth]: the dock's width less its padding); otherwise it goes into the body. */
    fun legendInHeader(innerWidth: Float, titleWidth: Float, legendWidth: Float): Boolean =
        DISC + TITLE_GAP + titleWidth + LEGEND_GAP + legendWidth <= innerWidth
}

/**
 * Where a row of tabs (its L1 / R1 badges included) sits: exactly centred on the stage, or with its outer edge exactly flush with the
 * content's edge; nothing in between. A row wider than the room between the content's edges neither tightens nor overhangs: it becomes
 * one row that scrolls inside that room (the selected tab kept in view, a fade at the cut edges). Any unit, as long as all values share
 * it (the callers pass raw pixels, so the rounding is the row's).
 */
internal object TabRowLayout {
    /**
     * How far a centred row may overhang the content's edges before it scrolls instead (stage px in all): the 16:9 settings' ten tabs are
     * 12 px wider than their content, which has always been centred, so L1 starts 6 px before the title.
     */
    const val OVERHANG = 12f

    /** Half a pixel of the row's own rounding does not push a centred row out of the content. */
    private const val ROUNDING = .5f

    /** Where a row goes: its [x], the [width] it is given (the room between the edges when it [scrolls]). */
    data class Placement(val x: Float, val width: Float, val scrolls: Boolean)

    /**
     * The placement of a row [rowWidth] wide (natural) whose first and last visible edges sit [outset] inside it (the badges' outer padding)
     * between the content's [left] and [right] edges (what a title or a button beside it leaves). A [centred] row stays centred on the
     * stage while its visible ends stay inside the edges (or overhang them by [OVERHANG] in all); otherwise, and for rows that are not
     * [centred], its visible right edge is at [right]. A row that is wider than that scrolls in the room between the edges. With [spill] (not
     * compact) the row only scrolls when it does not fit the stage.
     */
    fun place(stageWidth: Float, rowWidth: Float, outset: Float, left: Float, right: Float, centred: Boolean = true, spill: Boolean = false): Placement {
        if (spill) {
            // A roomy stage (every shape but the compact one): the row is never clipped or scrolled while it fits the stage itself, centred
            // (the settings: both badges visible, as always) or flush with [right] (Artwork); the content's edges are guides, not a clip
            val x = if (centred) (stageWidth - rowWidth) / 2f else flushRight(rowWidth, outset, right)
            if (x >= 0f && x + rowWidth <= stageWidth) return Placement(x, rowWidth, scrolls = false)
        }
        val allowed = if (centred) OVERHANG else 0f
        if (rowWidth - 2f * outset > right - left + allowed + ROUNDING) {
            return Placement(left - outset, (right - left + 2f * outset).coerceAtLeast(0f), scrolls = true)
        }
        val x = if (centred) x(stageWidth, rowWidth, outset, left, right, allowed / 2f) else flushRight(rowWidth, outset, right)
        return Placement(x, rowWidth, scrolls = false)
    }

    /**
     * The x of a row [rowWidth] wide: centred on the stage when the visible row then stays inside [left]..[right] (less [slack] at each
     * end), otherwise its visible right edge is at [right].
     */
    fun x(stageWidth: Float, rowWidth: Float, outset: Float, left: Float, right: Float, slack: Float = 0f): Float {
        val centred = (stageWidth - rowWidth) / 2f
        return if (centred + outset >= left - slack - ROUNDING && centred + rowWidth - outset <= right + slack + ROUNDING) centred else flushRight(rowWidth, outset, right)
    }

    /** The x of a row whose visible right edge is exactly at [right]. */
    fun flushRight(rowWidth: Float, outset: Float, right: Float): Float = right - rowWidth + outset
}

/** One grid of the Artwork screen (stage px): its columns, the tile and the room between tiles and rows, with what the focused tile needs around it. */
internal data class ArtGrid(val columns: Int, val tileWidth: Float, val tileHeight: Float, val gapX: Float, val gapY: Float) {
    /** How far the focused tile's ring reaches beyond the tile's resting edge: above and below, and at the sides. */
    val ringY: Float get() = ArtGridLayout.ringReach(tileHeight)
    val ringX: Float get() = ArtGridLayout.ringReach(tileWidth)

    /** How far its glow (box-shadow 0 18 50 -12, drawn with the focus scale) reaches beyond the resting edge. */
    val glowSide: Float get() = ArtGridLayout.growth(tileWidth) + (ArtGridLayout.GLOW_BLUR + ArtGridLayout.GLOW_SPREAD) * ArtGridLayout.FOCUS_SCALE
    val glowTop: Float get() = ArtGridLayout.growth(tileHeight) + (ArtGridLayout.GLOW_BLUR + ArtGridLayout.GLOW_SPREAD - ArtGridLayout.GLOW_OFFSET) * ArtGridLayout.FOCUS_SCALE
    val glowBottom: Float get() = ArtGridLayout.growth(tileHeight) + (ArtGridLayout.GLOW_BLUR + ArtGridLayout.GLOW_SPREAD + ArtGridLayout.GLOW_OFFSET) * ArtGridLayout.FOCUS_SCALE

    /** The room above the first row: its focused tile's ring ends [ArtGridLayout.BLOCK_GAP] under what is above the grid (and the glow has room). */
    val padTop: Float get() = ArtGridLayout.BLOCK_GAP + ringY

    /** The room under the last row: its focused tile's glow ends inside the viewport. */
    val padBottom: Float get() = glowBottom + 4f

    /** What a viewport needs to show the focused first row whole: its room above, the tile, and its glow or a peek of the next row under it. */
    val rowRoom: Float get() = padTop + tileHeight + maxOf(glowBottom, gapY + ArtGridLayout.PEEK)
}

/**
 * The Artwork grid. The user's rule: the focused tile (scaled up, with its ring) has room around it like the others, so the room between
 * two tiles covers the growth, the ring and 12 px: `gap >= tile × (focusScale - 1) + ring + 12`; rows get the same clearance from the
 * tile's height. The tiles pay for it: the width is what the columns leave.
 */
internal object ArtGridLayout {
    const val FOCUS_SCALE = 1.06f

    /** The tile's `FocusRing(4, 4)`: 4 px of gap and 4 px of ring outside the tile. */
    const val RING = 8f
    const val CLEARANCE = 12f
    const val MIN_GAP = 28f

    /** Between neighbouring blocks: the ring of a focused first-row tile ends this far under the filter strip. */
    const val BLOCK_GAP = 24f

    /** The tile's `FocusGlow(18, 50, -12)`. */
    const val GLOW_OFFSET = 18f
    const val GLOW_BLUR = 50f
    const val GLOW_SPREAD = -12f

    /** How much of the next row shows under a focused row at least, so the grid reads as scrolling. */
    const val PEEK = 28f

    /** A stage too short for a row of the normal tiles may take tiles down to this share of the kind's smallest width (the Pocket Micro's 140 of 190). */
    const val SHORT_SHARE = .72f

    /** How far a focused tile of this size grows beyond its resting edge on each side. */
    fun growth(tile: Float): Float = tile * (FOCUS_SCALE - 1f) / 2f

    /** The ring's outer edge beyond the resting edge (the ring is drawn scaled with the tile). */
    fun ringReach(tile: Float): Float = growth(tile) + RING * FOCUS_SCALE

    /** The room between two tiles of this size (along the same axis). */
    fun gap(tile: Float): Float = maxOf(MIN_GAP, tile * (FOCUS_SCALE - 1f) + RING + CLEARANCE)

    /** The tile width that [columns] tiles and their gaps ([gap]) fill [width] with. */
    fun tileWidth(width: Float, columns: Int): Float {
        if (columns <= 1) return width
        val byRule = (width - (columns - 1) * (RING + CLEARANCE)) / (columns + (columns - 1) * (FOCUS_SCALE - 1f))
        return if (gap(byRule) > MIN_GAP) byRule else (width - (columns - 1) * MIN_GAP) / columns
    }

    /**
     * The grid of [columns] tiles of [ratio] (width / height) across [width]. A tile never grows over [maxTile]: what the columns then leave
     * of the width goes into the gaps between them, the room between rows stays the rule's.
     */
    fun grid(width: Float, ratio: Float, columns: Int, maxTile: Float = Float.MAX_VALUE): ArtGrid {
        val tile = minOf(tileWidth(width, columns), maxTile)
        val height = tile / ratio
        val gapX = if (columns > 1) (width - columns * tile) / (columns - 1) else gap(tile)
        val capped = tileWidth(width, columns) > maxTile
        return ArtGrid(columns, tile, height, gapX, if (capped) gap(height) else maxOf(gapX, gap(height)))
    }

    /**
     * The compact grid: as many columns as keep the tile at [minTile] wide or more (and at [maxTile] at most); a [viewport] too short to
     * show a focused row of those whole ([ArtGrid.rowRoom]) takes more, smaller ones, down to [SHORT_SHARE] of [minTile].
     */
    fun compact(width: Float, viewport: Float, ratio: Float, minTile: Float, maxTile: Float = Float.MAX_VALUE): ArtGrid {
        var columns = 1
        while (tileWidth(width, columns + 1) >= minTile) columns++
        while (grid(width, ratio, columns, maxTile).rowRoom > viewport && tileWidth(width, columns + 1) >= minTile * SHORT_SHARE) columns++
        return grid(width, ratio, columns, maxTile)
    }
}

/**
 * The compact Artwork screen from top to bottom: the game chip and the tabs (flush right) on one row, the filter strip with the Search and
 * Filter pills on the next, the grid, the info strip. A stage too short to show a row of covers above the info strip has none.
 */
internal object ArtworkLayout {
    const val TABS_Y = SettingsLayout.COMPACT_TABS_Y
    const val TABS_HEIGHT = SettingsLayout.TABS_HEIGHT

    /** Between the two rows: the growth of a focused toolbar pill (64 × 0.08), its ring (8) and 12 px. */
    const val ROW_GAP = 26f
    const val TOOLBAR_Y = TABS_Y + TABS_HEIGHT + ROW_GAP
    const val TOOLBAR_HEIGHT = 64f

    /** Between the strip and the two toolbar pills, and between the pills: half the growth of a focused pill up to 300 px wide (12), its ring (8.6) and 12 px. */
    const val PILL_GAP = 34f

    /**
     * The grid's room starts at the toolbar's bottom edge ([ArtGrid.padTop] is measured from there); its scrolling viewport starts
     * [GRID_INSET] lower, so rows scrolled away dissolve under the toolbar instead of ending at its pills.
     */
    const val GRID_Y = TOOLBAR_Y + TOOLBAR_HEIGHT
    const val GRID_INSET = 8f
    const val INFO_HEIGHT = 84f
    const val INFO_GAP = 16f

    /**
     * The compact grid's fades, shorter than the 30 / 70 px of the tall 16:9 grid. They show only where rows continue; a focused row that
     * has rows above it keeps its ring under the top one.
     */
    const val FADE_TOP = 28f
    const val FADE_BOTTOM = 40f

    /** The filter sheet's padding above its header: the glow of a focused header pill (a circle of 70 px around the pill's centre, times 1.08) ends inside the sheet. */
    const val SHEET_PAD_TOP = 44f

    /** A cover under this width that is in use and animated shows "In use" as its check alone, beside the animation's icon. */
    const val COVER_BADGES_MIN = 175f

    /** The other kinds show "In use" as words from this tile width (a check alone under it: the tile's own name sits under the badge). */
    const val IN_USE_LABEL_MIN = 300f

    /** The other kinds label their "Animated" badge from this tile width, and beside "In use" from [BOTH_LABELS_MIN]. */
    const val ANIMATED_LABEL_MIN = 190f
    const val BOTH_LABELS_MIN = 310f

    /** The compact grid's covers (2:3): 190 to 210 stage px wide (the room left over goes into the gaps); the other kinds: [WIDE_MIN], [HERO_MIN], [LOGO_MIN]. */
    const val COVER_MIN = 190f
    const val COVER_MAX = 210f
    const val COVER_RATIO = 600f / 900f
    const val WIDE_MIN = 210f
    const val HERO_MIN = 290f
    const val LOGO_MIN = 190f

    /** The game chip keeps this much of the first row beside the tabs, so its name stays readable (the tabs scroll instead). */
    const val CHIP_MIN = 300f

    /** The grid's height with ([info]) or without the info strip under it; [bottom] is the hint bar's room. */
    fun viewport(stageHeight: Float, bottom: Float, info: Boolean): Float =
        (stageHeight - bottom - (if (info) INFO_HEIGHT + INFO_GAP else 0f) - GRID_Y).coerceAtLeast(0f)

    /**
     * Whether the info strip fits: with it, a focused row of the covers (the tallest tiles) still shows whole at their least width the short
     * stages take ([ArtGridLayout.SHORT_SHARE] of [COVER_MIN]).
     */
    fun hasInfo(width: Float, stageHeight: Float, bottom: Float): Boolean {
        val viewport = viewport(stageHeight, bottom, info = true)
        val grid = ArtGridLayout.compact(width, viewport, COVER_RATIO, COVER_MIN, COVER_MAX)
        return grid.rowRoom <= viewport
    }
}

/** What the Artwork toolbar gives up when it is short of room, in this order: the chip's image count, the Search pill's long label, the game's name. */
internal object ArtToolbarLayout {
    /** Whether the game chip shows the image count: only when the whole name still fits beside it ([inner]: the chip's room for both). */
    fun showsCount(inner: Float, nameWidth: Float, countWidth: Float, gap: Float): Boolean = nameWidth + gap + countWidth <= inner

    /** The first of [labels] (their widths, longest first) that fits [available] beside the [fixed] rest of the row; the last one when none does. */
    fun label(available: Float, fixed: Float, labels: List<Float>): Int =
        labels.indexOfFirst { fixed + it <= available }.let { if (it < 0) labels.lastIndex else it }
}
