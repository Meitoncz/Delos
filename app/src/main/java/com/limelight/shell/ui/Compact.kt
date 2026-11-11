package com.limelight.shell.ui

/**
 * Layout numbers of [StageShape.COMPACT] (issue #9, phase C): small panels and squarish windows, whose stage holds only about 1000 to
 * 1500 stage pixels across and 720 to 1100 down, because the legibility floor ([StagePlanner.MIN_DP_PER_PX]) keeps text as large
 * as on the Odin. Every screen that changes for COMPACT asks here; STANDARD, WIDE and TALL never read any of this, so they stay as
 * they were.
 *
 * What may shrink on a compact stage is everything that is not text: cover tiles, the hero and title block, glyph discs, circle
 * buttons, avatars, paddings and gaps. Text keeps its stage size (so its physical size), and so do the touch and controller targets,
 * which stay at least [MIN_TARGET] stage pixels high. Covers keep their 2:3 ratio (and the wide first cover its own): a tile is
 * scaled as a whole.
 */
object Compact {
    /** The lowest [StageMetrics.compactScale]: 0.62 of the 1080-high prototype (the Pocket Micro's 722-high stage is 0.67). */
    const val MIN_SCALE = .62f

    /** The smallest touch / controller target, stage pixels (0.43 dp each: about 9 mm on the Odin's density). */
    const val MIN_TARGET = 64f

    /** The side margin of the status bar's and the covers' start: the bar's own 72, the same on every shape. */
    const val SIDE = 72f

    /** The least margin of anything else from the edges of a compact stage (the stream menu's overscan margin); the display cutout comes on top. */
    const val MARGIN = 48f

    /** The status bar's and the hint bar's heights, which centered screens keep clear of. */
    const val STATUS_BAR = 104f
    const val HINT_BAR = 92f

    /** The stage widths where the wordmark next to the logo goes ([StatusBar]) and where a hint bar without a note still wants labels. */
    const val NARROW_STAGE = 1280f
}

/** True on a [StageShape.COMPACT] stage. */
val StageMetrics.compact: Boolean get() = shape == StageShape.COMPACT

/**
 * The one scale of non-text elements on a compact stage: the stage's height against the prototype's 1080 (a 957-high stage is 0.89),
 * never under [Compact.MIN_SCALE]; exactly 1 on every other shape. Screens multiply covers, discs, avatars, gaps and paddings by it.
 */
val StageMetrics.compactScale: Float
    get() = if (shape != StageShape.COMPACT) 1f else (stageHeight / StagePlanner.STAGE_HEIGHT).coerceIn(Compact.MIN_SCALE, 1f)
