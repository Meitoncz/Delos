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
}
