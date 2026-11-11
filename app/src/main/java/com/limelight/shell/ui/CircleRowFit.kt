package com.limelight.shell.ui

/**
 * The arithmetic of a row of circle items ([Circles]: Start, Devices, Pair a PC, Profile), stage pixels. One row, never wrapped and never
 * squeezed: the items keep their size, the gap between them is the prototype's 56 while they all fit and 44 on a compact stage when
 * they do not; what still does not fit scrolls sideways with a fade at the cut edges. Pure, covered by `CircleRowFitTest`.
 */
object CircleRowLayout {
    /** The gap between two items: the prototype's, and the least a compact stage uses. */
    const val GAP = 56f
    const val GAP_TIGHT = 44f

    /** The widest label and status line of an item on a compact stage (a longer name is cut in its middle, a longer status wraps). */
    const val LABEL_MAX = 260f
    const val META_MAX = 240f

    /** The fade at a cut edge of a compact stage's row, and the air above and below the circles there (the ring next to the headline). */
    const val EDGE = 48f
    const val RING_AIR = 16f

    /**
     * The glow of a focused circle (`FocusGlow(26, 70, -16)` of [CircleItem]: a radial bloom from 16 px inside the circle's edge to 54 px
     * outside it, drawn in the scaled layer) and the room the scroll area keeps for it on every side: [glowReach] of the largest circle (220).
     */
    const val GLOW_BLUR = 70f
    const val GLOW_SPREAD = -16f
    const val GLOW_ROOM = 84f

    /** A focused circle keeps this far from the cut edges of a scrolling row, so the next item peeks in beside it. */
    const val FOCUS_MARGIN = 150f

    /** What a focused circle is scaled to, and its ring (a 5 px gap and 5 px of ring, drawn in the scaled layer). */
    const val FOCUS_SCALE = 1.16f
    const val RING = 10f

    /** The least air between a focused circle's ring and its neighbour (the user's rule: the growth, the ring and 12 px). */
    const val FOCUS_AIR = 12f

    /** How far a focused circle [side] wide reaches beyond its own box on each side: half its growth and the ring. */
    fun focusReach(side: Float): Float = side * (FOCUS_SCALE - 1f) / 2f + RING * FOCUS_SCALE

    /**
     * The gap between a circle and its label on a compact stage: the focused circle's reach and 8 px (the label's line has about 6 px of
     * air above its capitals, so the ring stays 12 px or more from the letters).
     */
    fun labelGap(side: Float): Float = focusReach(side) + 8f

    /** The gap between two rows of a grid of circles (the avatar picker) on a compact stage: the reach of a focused circle and [FOCUS_AIR] above it. */
    fun rowGap(side: Float): Float = focusReach(side) + FOCUS_AIR

    /** An item is as wide as the widest of its circle, its label and its status line, the texts capped. */
    fun itemWidth(side: Float, label: Float, meta: Float): Float = maxOf(side, minOf(label, LABEL_MAX), minOf(meta, META_MAX))

    /** The gap of a row whose items add up to [sum] in [room]: [wide] while they fit with it, else [tight]. */
    fun gap(sum: Int, count: Int, room: Int, wide: Int, tight: Int): Int = if (sum + wide * (count - 1).coerceAtLeast(0) <= room) wide else tight

    /**
     * The left edges of items [sizes] wide in a row [total] wide (pixels): centred while they fit, from the start when the row is only as
     * wide as they are (it scrolls then). [wide] and [tight] are the two gaps.
     */
    fun arrange(sizes: IntArray, total: Int, wide: Int, tight: Int, out: IntArray) {
        val sum = sizes.sum()
        val gap = gap(sum, sizes.size, total, wide, tight)
        var x = ((total - sum - gap * (sizes.size - 1).coerceAtLeast(0)) / 2).coerceAtLeast(0)
        for (i in sizes.indices) {
            out[i] = x
            x += sizes[i] + gap
        }
    }
}
