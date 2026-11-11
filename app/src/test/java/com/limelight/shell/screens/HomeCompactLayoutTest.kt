package com.limelight.shell.screens

import com.limelight.shell.ui.Compact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home on a compact stage (`HomeCompact.layout`): the gaps the user asked for hold on every compact stage, the covers keep their ratio and a second game shows. */
class HomeCompactLayoutTest {
    private val c = HomeCompact

    /** The compact stages of the handhelds (docs/HANDHELD_DEVICES.md): Thor bottom, RG DDS top and bottom, Retroid Pocket Mini, a 1:1 panel, Pocket Micro. */
    private val stages = listOf(1099f to 957f, 1488f to 893f, 1191f to 893f, 1108f to 831f, 1051f to 1051f, 1082f to 722f)

    private fun name(stage: Pair<Float, Float>) = "${stage.first.toInt()}x${stage.second.toInt()}"

    /** The interface scales: 1, and the default 1.15 (the bars and the texts are that much larger). */
    private val scales = listOf(1f, 1.15f)

    @Test
    fun theBlocksFollowEachOtherFromTheStatusBarToTheHintBar() {
        for (stage in stages) for (scale in scales) {
            val l = c.layout(stage.first, stage.second, scale)
            val n = "${name(stage)} at $scale"
            assertEquals("$n status bar", Compact.STATUS_BAR * scale, l.statusBottom, .01f)
            assertEquals("$n hint bar", stage.second - Compact.HINT_BAR * scale, l.hintTop, .01f)
            assertTrue("$n order", l.statusBottom <= l.titleTop && l.titleBottom <= l.chipsTop && l.chipsBottom <= l.coverTop && l.coverBottom <= l.hintTop)
            // The four gaps, the three blocks and the two ring reaches fill the room between the bars exactly
            val used = l.statusGap + l.titleBox + l.titleGap + l.chipsHeight + l.chipsGap + 2f * l.ringReach + l.coverHeight + l.hintGap
            assertEquals("$n fills the room", l.hintTop - l.statusBottom, used, .05f)
        }
    }

    @Test
    fun theGapsTheUserAskedFor() {
        for (stage in stages) for (scale in scales) {
            val l = c.layout(stage.first, stage.second, scale)
            val n = "${name(stage)} at $scale"
            assertTrue("$n status bar to title ${l.statusGap}", l.statusGap >= 16f - .05f)
            assertTrue("$n title to chips ${l.titleGap}", l.titleGap >= 12f - .01f && l.titleGap <= 16f + .01f)
            assertTrue("$n chips to the focused cover's ring ${l.chipsGap}", l.chipsGap >= 24f - .05f)
            assertTrue("$n ring to the hint bar ${l.hintGap}", l.hintGap >= 28f - .05f)
            // The left-over room is not piled above the title: the gap above the covers and the one under them get four times as much
            assertTrue("$n more above the covers than above the title", l.chipsGap - 24f >= (l.statusGap - 16f) * 4f - .05f)
            assertTrue("$n more under the covers than above the title", l.hintGap - 28f >= (l.statusGap - 16f) * 4f - .05f)
            // The title box holds the title's line and at most the logo's 120
            assertTrue("$n title box ${l.titleBox}", l.titleBox >= 64f * scale - .01f && l.titleBox <= 120f + .01f)
        }
    }

    @Test
    fun theRingIsTheFocusedCoversGrowthAndItsRing() {
        // A 450 px cover at 1.055 grows 12.375 px up and down; the 10 px ring grows with it
        assertEquals(12.375f + 10.55f, c.ringReach(450f), .001f)
        for (stage in stages) {
            val l = c.layout(stage.first, stage.second)
            assertEquals(c.ringReach(l.coverHeight), l.ringReach, .001f)
        }
    }

    @Test
    fun theCoverIsNeverNeedlesslyHugeAndNeverUnderTheMinimum() {
        for (stage in stages) for (scale in scales) {
            val l = c.layout(stage.first, stage.second, scale)
            val n = "${name(stage)} at $scale"
            assertEquals("$n the cover keeps 2:3", 450f * l.coverScale, l.coverHeight, .01f)
            assertTrue("$n at most 0.40 of the height: ${l.coverHeight}", l.coverHeight <= stage.second * .40f + .01f)
            assertTrue("$n not larger than the prototype's", l.coverScale <= 1f)
            // The old minimum of 0.6 holds wherever the gaps leave it. The 722-high stage at the default interface scale does not: its bars
            // and texts take 30 px more, the gaps the user asked for come first, and the covers are 0.50 (226 px high)
            if (stage.second > 800f || scale == 1f) assertTrue("$n at least 0.6 of the prototype's: ${l.coverScale}", l.coverScale >= .6f - .0002f)
            else assertEquals("$n", .5035f, l.coverScale, .001f)
        }
    }

    @Test
    fun aSecondGameIsAlwaysClearlyThere() {
        for (stage in stages) for (scale in scales) {
            val l = c.layout(stage.first, stage.second, scale)
            val s = l.coverScale
            val tile = c.TILE * s
            // The wide cover, a whole cover and at least a third of the next one end on the stage
            val shown = stage.first - (Compact.SIDE + c.WIDE * s + c.coverGap(s) + tile + c.coverGap(s))
            assertTrue("${name(stage)} at $scale shows $shown of a $tile cover", shown >= tile / 3f - 6f)
        }
    }

    @Test
    fun aFocusedCoverKeepsItsRingOffItsNeighbour() {
        for (s in listOf(.45f, .5f, .6f, .7f, .8f, 1f)) {
            val reach = c.TILE * s * (c.FOCUS_SCALE - 1f) / 2f + c.RING * c.FOCUS_SCALE
            assertTrue("scale $s gap ${c.coverGap(s)}", c.coverGap(s) >= reach + 12f - .001f)
            assertTrue("scale $s never under the prototype's scaled gap", c.coverGap(s) >= 44f * s)
        }
        // From 0.63 up the prototype's gap, scaled, is enough by itself
        assertEquals(44f * .7f, c.coverGap(.7f), .001f)
        assertEquals(27.5f, c.coverGap(.6f), .01f)
    }

    @Test
    fun theNumbersOfTheSixStages() {
        // Cover scale at interface scale 1: the width rule where the stage is tall for its width, the comfortable gaps on the lower ones,
        // the least gaps on the lowest
        val expected = mapOf("1099x957" to .6958f, "1488x893" to .7665f, "1191x893" to .7581f, "1108x831" to .6359f, "1051x1051" to .6633f, "1082x722" to .6f)
        for (stage in stages) assertEquals(name(stage), expected.getValue(name(stage)), c.layout(stage.first, stage.second).coverScale, .0005f)
        // The lowest stage has nothing left over: the least gaps and the 64 px title box
        val low = c.layout(1082f, 722f)
        assertEquals(64f, low.titleBox, .1f)
        assertEquals(16f, low.statusGap, .1f)
        assertEquals(24f, low.chipsGap, .1f)
        assertEquals(28f, low.hintGap, .1f)
        // At the default interface scale the covers of the wide and the low stages give way to the comfortable gaps
        assertEquals(.69f, c.layout(1488f, 893f, 1.15f).coverScale, .0005f)
        assertEquals(.6f, c.layout(1108f, 831f, 1.15f).coverScale, .0005f)
        assertTrue(c.layout(1488f, 893f, 1.15f).chipsGap >= 38f && c.layout(1488f, 893f, 1.15f).hintGap >= 42f)
        // The others give the logo its 120 px box, also at the default interface scale
        for (stage in stages.filter { it.second > 800f }) for (scale in scales) assertEquals(name(stage), 120f, c.layout(stage.first, stage.second, scale).titleBox, .01f)
    }

    @Test
    fun aLargerInterfaceScaleGrowsTheBarsAndTheChipsAndStillKeepsTheOrder() {
        for (stage in stages) for (scale in listOf(1.1f, 1.2f, 1.3f)) {
            val l = c.layout(stage.first, stage.second, scale)
            val n = "${name(stage)} at $scale"
            assertEquals("$n status bar", Compact.STATUS_BAR * scale, l.statusBottom, .01f)
            assertTrue("$n order", l.statusBottom <= l.titleTop + .05f && l.titleBottom <= l.chipsTop && l.chipsBottom <= l.coverTop - l.ringReach + .05f)
            assertTrue("$n the ring stays off the hint bar", l.coverBottom + l.ringReach <= l.hintTop + .05f)
            assertTrue("$n floor", l.coverScale >= c.FLOOR)
        }
    }

    @Test
    fun theCutoutMovesTheBars() {
        val l = c.layout(1099f, 957f, safeTop = 30f, safeBottom = 20f)
        assertEquals(134f, l.statusBottom, 0f)
        assertEquals(957f - 20f - 92f, l.hintTop, 0f)
        assertTrue(l.statusGap >= 16f && l.hintGap >= 28f)
    }
}
