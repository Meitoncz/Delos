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

    /**
     * The factor that makes a grid row of [columns] cells [cell] wide with [gap] between them fit a stage [stageWidth] wide with [margin] on
     * each side: 1 while it fits (the 1920 px stage keeps the prototype's sizes), else the part of it that does. The caller scales the circle,
     * the cell and the gap by it (the text keeps its size), so the circles stay round and the row is never cut.
     */
    fun gridFit(columns: Int, cell: Float, gap: Float, stageWidth: Float, margin: Float): Float {
        val row = columns * cell + (columns - 1).coerceAtLeast(0) * gap
        return if (row <= 0f) 1f else ((stageWidth - 2f * margin) / row).coerceIn(0f, 1f)
    }

    /** An item is as wide as the widest of its circle, its label and its status line, the texts capped. */
    fun itemWidth(side: Float, label: Float, meta: Float): Float = maxOf(side, minOf(label, LABEL_MAX), minOf(meta, META_MAX))

    /** The gap of a row whose items add up to [sum] in [room]: [wide] while they fit with it, else [tight]. */
    fun gap(sum: Int, count: Int, room: Int, wide: Int, tight: Int): Int = if (sum + wide * (count - 1).coerceAtLeast(0) <= room) wide else tight

    /** The outer margin of a compact stage's row (the circle's own edge: its growth and ring come on top, so the ring keeps 48 or more), the least part of the next item (or the one before) that shows at a cut edge, and the widest gap that makes room for it. */
    const val SIDE = 72f
    const val PEEK = 60f
    const val MAX_GAP = 120f

    /** The cut item shows at most this much less than its whole width: an item that is all but whole does not tell that the row goes on. */
    const val CUT = 40f

    /**
     * How much of the first item that is cut by [limit] (the room from the first item's left edge to the stage's edge) shows when [sizes] stand
     * [gap] apart from 0; -1 when none is cut, or when it is cut by less than [CUT] (it looks whole and the next one is out of sight).
     */
    fun cutPeek(sizes: FloatArray, gap: Float, limit: Float): Float {
        var x = 0f
        for (w in sizes) {
            if (x + w > limit + .5f) return if (limit - x > w - CUT) -1f else limit - x
            x += w + gap
        }
        return -1f
    }

    /**
     * The gap that shows at least [peek] of the cut item at both ends of a row that scrolls: at its start (the first items from the left) and
     * at its end (the last ones from the right, the same reckoning backwards). The least gap from [tight] up to [maxGap] that does; the
     * one that shows the most when none does. [limit] is the room from the first item's left edge to the stage's edge.
     */
    fun peekGap(sizes: FloatArray, limit: Float, tight: Float, peek: Float = PEEK, maxGap: Float = MAX_GAP): Float {
        val backwards = sizes.reversedArray()
        var best = tight
        var bestScore = -Float.MAX_VALUE
        var g = tight
        while (g <= maxGap) {
            val score = minOf(cutPeek(sizes, g, limit), cutPeek(backwards, g, limit))
            if (score >= peek) return g
            if (score > bestScore) {
                bestScore = score
                best = g
            }
            g += 1f
        }
        return best
    }

    /**
     * The left edges of items [sizes] wide in a row [total] wide (pixels): centred while they fit, from the start when the row is only as
     * wide as they are (it scrolls then). [wide] and [tight] are the two gaps. With the stage's [viewport] width and the outer margin [pad]
     * (pixels; 0 for neither) a row that does not fit inside the margins scrolls with the gap [peekGap] picks, so the item at the cut edge
     * shows [peek] pixels at the start and at the end; returns how much wider than with [tight] the row got (0 where it fits).
     */
    fun arrange(sizes: IntArray, total: Int, wide: Int, tight: Int, out: IntArray, viewport: Int = 0, pad: Int = 0, peek: Int = 0, maxGap: Int = 0): Int {
        val sum = sizes.sum()
        val n = sizes.size
        if (viewport > 0 && n > 1 && sum + tight * (n - 1) > viewport - 2 * pad) {
            val gap = Math.round(peekGap(FloatArray(n) { sizes[it].toFloat() }, (viewport - pad).toFloat(), tight.toFloat(), peek.toFloat(), maxGap.toFloat()))
            var x = 0
            for (i in sizes.indices) {
                out[i] = x
                x += sizes[i] + gap
            }
            return (gap - tight) * (n - 1)
        }
        val gap = gap(sum, n, total, wide, tight)
        var x = ((total - sum - gap * (n - 1).coerceAtLeast(0)) / 2).coerceAtLeast(0)
        for (i in sizes.indices) {
            out[i] = x
            x += sizes[i] + gap
        }
        return 0
    }
}
