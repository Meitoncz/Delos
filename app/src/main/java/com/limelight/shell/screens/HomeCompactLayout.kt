package com.limelight.shell.screens

import com.limelight.shell.ui.Compact

/**
 * Home on a compact stage, laid out top-down (issue #9), stage pixels from the stage's top: the status bar, the title (or logo) box, the
 * info chips (room for two rows), the covers and the hint bar, with the gaps between them. [HomeCompact.layout] computes it; pure,
 * covered by `HomeCompactLayoutTest` on every compact stage.
 */
internal class HomeCompactLayout(
    /** The covers' scale against the prototype's 300 x 450 (the wide first one 988 x 450). */
    val coverScale: Float,
    val titleTop: Float,
    val titleBox: Float,
    val chipsTop: Float,
    val chipsHeight: Float,
    val coverTop: Float,
    val coverHeight: Float,
    /** How far the focused cover's ring reaches above and below the cover. */
    val ringReach: Float,
    val statusBottom: Float,
    val hintTop: Float,
) {
    val titleBottom get() = titleTop + titleBox
    val chipsBottom get() = chipsTop + chipsHeight
    val coverBottom get() = coverTop + coverHeight

    /** The four gaps: status bar to title, title to chips, chips to the focused cover's ring, that ring to the hint bar. */
    val statusGap get() = titleTop - statusBottom
    val titleGap get() = chipsTop - titleBottom
    val chipsGap get() = coverTop - ringReach - chipsBottom
    val hintGap get() = hintTop - (coverBottom + ringReach)
}

internal object HomeCompact {
    /** The prototype's cover and the wide first one (three covers and two gaps), which every scale keeps the ratio of. */
    const val TILE = 300f
    const val COVER_HEIGHT = 450f
    const val COVER_GAP = 44f
    const val WIDE = 3 * TILE + 2 * COVER_GAP

    /** The focused cover grows to this and wears a ring (5 px gap, 5 px ring) that grows with it. */
    const val FOCUS_SCALE = 1.055f
    const val RING = 10f

    /** The least gaps: status bar to title, title to chips (and the most there), chips to the focused cover's ring, ring to hint bar. */
    const val GAP_STATUS = 16f
    const val GAP_TITLE = 12f
    const val GAP_TITLE_MAX = 16f
    const val GAP_CHIPS = 24f
    const val GAP_HINT = 28f

    /** The title box: tall enough for the 60 px title at the least, the logo's 120 at the most. */
    const val TITLE_MIN = 64f
    const val TITLE_MAX = 120f

    /** An info chip is 12 px of padding around a 23 px line of text; the block keeps room for two rows, 6 px apart. */
    const val CHIP_PADDING = 12f
    const val CHIP_LINE = 23f
    const val CHIP_ROW_GAP = 6f

    /** The gaps above and under the covers that a stage with room to spare gives them before the covers grow further. */
    const val COMFORT_CHIPS = 40f
    const val COMFORT_HINT = 44f

    /**
     * A cover is at most this share of the stage's height. It gives way to the comfortable gaps down to [MIN_COVER] (the old minimum),
     * and below that only where not even the least gaps fit otherwise (the 722-high stage at the default interface scale), never under [FLOOR].
     */
    const val MAX_HEIGHT_SHARE = .40f
    const val MIN_COVER = .6f
    const val FLOOR = .45f

    /** How the room that is left over is shared: a little above the title, most of it between the chips and the covers and under the covers. */
    const val SHARE_STATUS = .5f
    const val SHARE_CHIPS = 2f
    const val SHARE_HINT = 2f

    /** How far the ring of a focused cover [height] high reaches above and below it. */
    fun ringReach(height: Float): Float = height * (FOCUS_SCALE - 1f) / 2f + RING * FOCUS_SCALE

    /** The least air between a focused cover's ring and the cover next to it (the user's rule: the growth, the ring and 12 px). */
    const val FOCUS_AIR = 12f

    /**
     * The gap between two covers at [scale]: the prototype's 44 scaled with them, but never less than a focused cover's growth to one side,
     * its ring and [FOCUS_AIR] (28 px on the smallest covers, where 44 scaled would be 22 to 26).
     */
    fun coverGap(scale: Float): Float = maxOf(COVER_GAP * scale, TILE * scale * (FOCUS_SCALE - 1f) / 2f + RING * FOCUS_SCALE + FOCUS_AIR)

    /** The room two rows of chips take at interface scale [scale]. */
    fun chipsHeight(scale: Float): Float = 2f * (CHIP_PADDING + CHIP_LINE * scale) + CHIP_ROW_GAP

    /**
     * The widest cover scale at which, next to the wide first cover (which starts [Compact.SIDE] from the left edge), one whole cover and
     * a third of the next one are on a stage [stageWidth] wide: a second game is always clearly there.
     */
    fun widthScale(stageWidth: Float): Float = (stageWidth - Compact.SIDE) / (WIDE + COVER_GAP + TILE + COVER_GAP + TILE / 3f)

    /**
     * The layout on a stage [stageWidth] x [stageHeight] at interface scale [scale] (the bars and the texts grow with it), [safeTop] and
     * [safeBottom] being the display cutout. The cover height is the smallest of what the height leaves after the comfortable gaps (after
     * the least gaps once that would be under [MIN_COVER]), 0.40 of the stage's height and [widthScale]; what is left over then widens the
     * title box (up to the logo's 120), the gap under it (up to 16) and is shared between the other three gaps by
     * [SHARE_STATUS] : [SHARE_CHIPS] : [SHARE_HINT].
     */
    fun layout(stageWidth: Float, stageHeight: Float, scale: Float = 1f, safeTop: Float = 0f, safeBottom: Float = 0f): HomeCompactLayout {
        val statusBottom = safeTop + Compact.STATUS_BAR * scale
        val hintTop = stageHeight - safeBottom - Compact.HINT_BAR * scale
        val room = hintTop - statusBottom
        val chips = chipsHeight(scale)
        val titleMin = TITLE_MIN * scale
        val titleMax = maxOf(TITLE_MAX, titleMin)
        // Everything but the cover and the part of its ring that grows with it
        val fixed = GAP_STATUS + titleMin + GAP_TITLE + chips + GAP_CHIPS + GAP_HINT + 2f * RING * FOCUS_SCALE
        val byHeight = (room - fixed) / FOCUS_SCALE / COVER_HEIGHT
        // The same with the logo's full box and comfortable gaps: what the cover gets while that is not under the minimum
        val comfortable = GAP_STATUS + titleMax + GAP_TITLE_MAX + chips + COMFORT_CHIPS + COMFORT_HINT + 2f * RING * FOCUS_SCALE
        val byComfort = (room - comfortable) / FOCUS_SCALE / COVER_HEIGHT
        val byShare = stageHeight * MAX_HEIGHT_SHARE / COVER_HEIGHT
        val coverScale = minOf(byShare, widthScale(stageWidth), maxOf(byComfort, minOf(byHeight, MIN_COVER))).coerceAtLeast(FLOOR)
        val cover = COVER_HEIGHT * coverScale
        val reach = ringReach(cover)
        var left = room - (GAP_STATUS + titleMin + GAP_TITLE + chips + GAP_CHIPS + cover + 2f * reach + GAP_HINT)
        var statusGap = GAP_STATUS
        var titleGap = GAP_TITLE
        var chipsGap = GAP_CHIPS
        var hintGap = GAP_HINT
        var title = titleMin
        if (left > 0f) {
            val grow = minOf(left, titleMax - titleMin)
            title += grow
            left -= grow
            val widen = minOf(left, GAP_TITLE_MAX - GAP_TITLE)
            titleGap += widen
            left -= widen
            val shares = SHARE_STATUS + SHARE_CHIPS + SHARE_HINT
            statusGap += left * SHARE_STATUS / shares
            chipsGap += left * SHARE_CHIPS / shares
            hintGap += left * SHARE_HINT / shares
        } else if (left < 0f) {
            // Not even the floor fits (a tiny stage at a large interface scale): the gaps give way, the one above the title first,
            // then the one above the hint bar, then the one above the covers
            var over = -left
            fun give(gap: Float): Float {
                val cut = minOf(over, gap)
                over -= cut
                return gap - cut
            }
            statusGap = give(statusGap)
            hintGap = give(hintGap)
            chipsGap = give(chipsGap)
        }
        val titleTop = statusBottom + statusGap
        val chipsTop = titleTop + title + titleGap
        val coverTop = chipsTop + chips + chipsGap + reach
        return HomeCompactLayout(coverScale, titleTop, title, chipsTop, chips, coverTop, cover, reach, statusBottom, hintTop)
    }
}
