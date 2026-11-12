package com.limelight.shell.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A row of circle items (`CircleRowLayout`): one row, the items keep their size, the gap is 56 while they fit and 44 when not, the focus has its room. */
class CircleRowFitTest {
    private val c = CircleRowLayout

    /** The compact stages' heights give the circles' scale (`StageMetrics.compactScale`): the big circle is 220 times it. */
    private val stageHeights = listOf(957f, 893f, 831f, 1051f, 722f)

    private fun side(stageHeight: Float) = 220f * (stageHeight / 1080f).coerceIn(Compact.MIN_SCALE, 1f)

    @Test
    fun aFocusedCircleKeepsItsRingOffItsNeighbourOnEveryCompactStage() {
        for (height in stageHeights + 1080f) {
            val reach = c.focusReach(side(height))
            // The growth to one side and the ring, and 12 px of air, fit the least gap
            assertTrue("stage height $height reach $reach", reach + c.FOCUS_AIR <= c.GAP_TIGHT + .01f)
        }
        // The prototype's circle: 220 at 1.16 grows 17.6 to each side, the 10 px ring with it 11.6
        assertEquals(17.6f + 11.6f, c.focusReach(220f), .001f)
        assertTrue(c.focusReach(220f) + c.FOCUS_AIR <= c.GAP)
    }

    @Test
    fun theGlowHasItsRoomInsideTheScrollArea() {
        // The bloom ends 54 px outside the circle's edge, in the layer scaled to 1.16
        fun glowReach(side: Float) = (side / 2f + c.GLOW_SPREAD + c.GLOW_BLUR) * c.FOCUS_SCALE - side / 2f
        assertEquals(80.24f, glowReach(220f), .01f)
        for (height in stageHeights + 1080f) assertTrue("stage height $height", glowReach(side(height)) <= c.GLOW_ROOM)
        // and the focused circle stays out of the fade of a cut edge
        for (height in stageHeights + 1080f) assertTrue(c.FOCUS_MARGIN - glowReach(side(height)) >= c.EDGE)
    }

    @Test
    fun anItemIsAsWideAsItsCircleOrItsTexts() {
        assertEquals(195f, c.itemWidth(195f, 150f, 120f), 0f)
        assertEquals(215f, c.itemWidth(195f, 215f, 120f), 0f)
        // A long name stops at 260, a long status line at 240
        assertEquals(260f, c.itemWidth(195f, 900f, 120f), 0f)
        assertEquals(240f, c.itemWidth(195f, 100f, 400f), 0f)
        // Never narrower than the circle: the circle's box stays square
        assertEquals(220f, c.itemWidth(220f, 10f, 0f), 0f)
    }

    @Test
    fun thePrototypesGapWhileItFitsTheSmallerOneWhenNot() {
        assertEquals(56, c.gap(sum = 800, count = 4, room = 1000, wide = 56, tight = 40))
        assertEquals(56, c.gap(sum = 832, count = 4, room = 1000, wide = 56, tight = 40))
        assertEquals(40, c.gap(sum = 833, count = 4, room = 1000, wide = 56, tight = 40))
        assertEquals(40, c.gap(sum = 2000, count = 9, room = 1000, wide = 56, tight = 40))
    }

    @Test
    fun centredWhileTheItemsFitFromTheStartWhenTheyScroll() {
        val out = IntArray(3)
        // Three items of 200 in 1000: 56 apart, centred
        c.arrange(intArrayOf(200, 200, 200), 1000, 56, 40, out)
        assertArrayEquals(intArrayOf(144, 400, 656), out)
        // 300 each: 56 does not fit (1012), 40 does (980): centred with 40
        c.arrange(intArrayOf(300, 300, 300), 1000, 56, 40, out)
        assertArrayEquals(intArrayOf(10, 350, 690), out)
        // The row is as wide as its items with 40 between them (it scrolls): from the start
        c.arrange(intArrayOf(400, 400, 400), 1280, 56, 40, out)
        assertArrayEquals(intArrayOf(0, 440, 880), out)
    }

    @Test
    fun nineComputersOnEveryCompactStageStayOneRowOfWholeCircles() {
        // Nine PCs and "Pair a new PC": 220-wide labels on circles of the stage's scale, in the stage width less the 48 px margins
        for ((width, height) in listOf(1099f to 957f, 1488f to 893f, 1191f to 893f, 1108f to 831f, 1051f to 1051f, 1082f to 722f)) {
            val side = side(height)
            val item = c.itemWidth(side, 220f, 180f).toInt()
            val sizes = IntArray(10) { item }
            val room = (width - 96f).toInt()
            val out = IntArray(10)
            val gap = c.gap(sizes.sum(), 10, room, 56, 44)
            assertEquals("$width x $height scrolls with the small gap", 44, gap)
            val total = sizes.sum() + gap * 9
            c.arrange(sizes, total, 56, 44, out)
            for (i in 1 until 10) assertEquals(item + 44, out[i] - out[i - 1])
            assertTrue("no item is narrower than its circle", item >= side.toInt())
        }
    }

    @Test
    fun aRowThatScrollsShowsAPeekOfTheCutItemAtBothEnds() {
        // The compact viewports (stage widths) and item widths (the circle or its label, 176..260) of start, devices and pair-pc, 3 to 12 items
        for (viewport in listOf(1099f, 1488f, 1191f, 1108f, 1051f, 1082f)) for (n in 3..12) for (w in listOf(176f * .9f, 200f, 220f, 260f)) {
            val sizes = FloatArray(n) { w }
            val limit = viewport - c.SIDE
            val tight = c.GAP_TIGHT
            if (sizes.sum() + tight * (n - 1) <= viewport - 2 * c.SIDE) continue
            val g = c.peekGap(sizes, limit, tight)
            val name = "$viewport x $n items of $w: gap $g"
            assertTrue(name, g >= tight && g <= c.MAX_GAP)
            val head = c.cutPeek(sizes, g, limit)
            val tail = c.cutPeek(sizes.reversedArray(), g, limit)
            // At least 60 px of the cut item show at each end, and it really is cut (by 40 px or more)
            assertTrue("$name head $head", head >= c.PEEK - .5f && head <= w - c.CUT)
            assertTrue("$name tail $tail", tail >= c.PEEK - .5f && tail <= w - c.CUT)
        }
    }

    @Test
    fun aRowThatFitsInsideItsMarginsStaysCentred() {
        val out = IntArray(3)
        val extra = c.arrange(intArrayOf(200, 200, 200), 1000, 56, 44, out, viewport = 1096, pad = 48, peek = 60, maxGap = 120)
        assertEquals(0, extra)
        assertArrayEquals(intArrayOf(144, 400, 656), out)
        // Not inside the margins (3 x 300 and two gaps are 988, the room 1000 - 96): it scrolls from the start, wider than with the tight gap
        val wide = IntArray(3)
        val more = c.arrange(intArrayOf(300, 300, 300), 988, 56, 44, wide, viewport = 1096, pad = 48, peek = 60, maxGap = 120)
        assertEquals(0, wide[0])
        assertEquals((wide[1] - 300 - 44) * 2, more)
    }

    @Test
    fun aGridRowKeepsItsSizeWhileItFitsAndShrinksToTheStageWhenItDoesNot() {
        // The avatar picker: eight 184 px cells and seven 32 px gaps are 1696 px, with 52 px on each side 1800
        assertEquals(1f, c.gridFit(8, 184f, 32f, 1920f, 52f), 0f)
        assertEquals(1f, c.gridFit(8, 184f, 32f, 2520f, 52f), 0f)
        assertEquals(1f, c.gridFit(8, 184f, 32f, 1800f, 52f), 1e-6f)
        // The narrowest stage that is not compact (1500 px): every cell, gap and circle scaled by the same factor fills the room exactly
        val k = c.gridFit(8, 184f, 32f, StagePlanner.COMPACT_STAGE_WIDTH, 52f)
        assertEquals((1500f - 104f) / 1696f, k, 1e-6f)
        assertEquals(1500f - 104f, 8 * 184f * k + 7 * 32f * k, .01f)
        assertTrue(k > .8f && k < 1f)
        assertEquals(1f, c.gridFit(1, 184f, 32f, 1920f, 52f), 0f)
    }
}
