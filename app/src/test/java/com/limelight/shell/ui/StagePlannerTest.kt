package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class StagePlannerTest {
    /** A panel as the window sees it: pixels and the density (dpi) Android reports for it. */
    private class Panel(val name: String, val widthPx: Int, val heightPx: Int, val dpi: Int) {
        val density get() = dpi / 160f
        val plan get() = StagePlanner.plan(widthPx / density, heightPx / density)
    }

    // dpi: the reference's reported 369; the others are the computed ppi (diagonal and resolution from docs/HANDHELD_DEVICES.md) rounded to 10
    private val odin = Panel("Odin 2 Portal", 1920, 1080, 369)
    private val thorTop = Panel("Thor top 6.0 in", 1920, 1080, 370)
    private val thorBottom = Panel("Thor bottom 3.92 in", 1240, 1080, 420)
    private val ddsTop = Panel("RG DDS top 4.7 in", 1200, 720, 300)
    private val ddsBottom = Panel("RG DDS bottom 4 in", 960, 720, 300)

    // Shapes of no particular device, at the Odin's density
    private val tenEighteen = Panel("16:10", 1920, 1200, 369)
    private val threeTwo = Panel("3:2", 1620, 1080, 369)
    private val fourThree = Panel("4:3", 1440, 1080, 369)
    private val square = Panel("1:1", 720, 720, 369)
    private val twentyNine = Panel("20:9", 2400, 1080, 369)
    private val twentyOneNine = Panel("21:9", 2520, 1080, 369)
    private val splitHalf = Panel("split-screen half of the Odin", 960, 1080, 369)

    private val all = listOf(odin, thorTop, thorBottom, ddsTop, ddsBottom, tenEighteen, threeTwo, fourThree, square, twentyNine, twentyOneNine, splitHalf)

    private fun check(panel: Panel, shape: StageShape, stageWidth: Float, stageHeight: Float) {
        val plan = panel.plan
        assertEquals("${panel.name} shape", shape, plan.shape)
        assertEquals("${panel.name} stage width", stageWidth, plan.stageWidth, 1f)
        assertEquals("${panel.name} stage height", stageHeight, plan.stageHeight, 1f)
    }

    @Test
    fun theReferenceDeviceKeepsTodaysScaleExactly() {
        val w = odin.widthPx / odin.density
        val h = odin.heightPx / odin.density
        val plan = odin.plan
        assertEquals(min(w / 1920f, h / 1080f), plan.dpPerPx, 0f)
        assertEquals(1920f, plan.stageWidth, 0f)
        assertEquals(1080f, plan.stageHeight, 0f)
        assertEquals(StageShape.STANDARD, plan.shape)
        // 832.5 / 1920 = 0.4336: the floor sits just below it
        assertEquals(0.4336f, plan.dpPerPx, 0.0001f)
        assertTrue(StagePlanner.MIN_DP_PER_PX < plan.dpPerPx)
    }

    @Test
    fun everySixteenByNineWindowAtLeastAsLargeAsTheFloorRequiresIsIdentical() {
        // 826 dp wide is just above where 16:9 meets the floor (1920 * 0.43 = 825.6)
        for (width in listOf(826f, 832.5f, 900f, 960f, 1024f, 1280f, 1920f, 2560f)) {
            val height = width * 9f / 16f
            val plan = StagePlanner.plan(width, height)
            assertEquals("$width dp", min(width / 1920f, height / 1080f), plan.dpPerPx, 0f)
            assertEquals(1920f, plan.stageWidth, 0f)
            assertEquals(1080f, plan.stageHeight, 0f)
            assertEquals(StageShape.STANDARD, plan.shape)
        }
        check(thorTop, StageShape.STANDARD, 1920f, 1080f)
        assertEquals(min(1920f / thorTop.density / 1920f, 1080f / thorTop.density / 1080f), thorTop.plan.dpPerPx, 0f)
    }

    @Test
    fun aSixteenByNineWindowBelowTheFloorShrinksTheStageAndStaysStandardWhileItHoldsEnoughStagePixels() {
        val plan = StagePlanner.plan(800f, 450f)
        assertEquals(StagePlanner.MIN_DP_PER_PX, plan.dpPerPx, 0f)
        assertEquals(800f / 0.43f, plan.stageWidth, 0.01f)
        assertEquals(450f / 0.43f, plan.stageHeight, 0.01f)
        assertEquals(StageShape.STANDARD, plan.shape)
    }

    @Test
    fun theFloorNeverLetsTheScaleGoBelowItAndNeverRaisesAWindowThatHasMore() {
        for (panel in all) {
            val fit = min(panel.widthPx / panel.density / 1920f, panel.heightPx / panel.density / 1080f)
            val plan = panel.plan
            assertTrue("${panel.name} floor", plan.dpPerPx >= StagePlanner.MIN_DP_PER_PX)
            if (fit >= StagePlanner.MIN_DP_PER_PX) assertEquals("${panel.name} untouched", fit, plan.dpPerPx, 0f)
            // The stage always covers exactly the window
            assertEquals("${panel.name} width", panel.widthPx / panel.density, plan.stageWidth * plan.dpPerPx, 0.1f)
            assertEquals("${panel.name} height", panel.heightPx / panel.density, plan.stageHeight * plan.dpPerPx, 0.1f)
        }
    }

    @Test
    fun deviceTable() {
        check(odin, StageShape.STANDARD, 1920f, 1080f)
        check(thorTop, StageShape.STANDARD, 1920f, 1080f)
        // 1240 x 1080 at 420 dpi is 472 x 411 dp: well below the floor
        check(thorBottom, StageShape.COMPACT, 1099f, 957f)
        // 1200 x 720 at 300 dpi is 640 x 384 dp: 1488 x 893 stage px, just under 1500 wide
        check(ddsTop, StageShape.COMPACT, 1488f, 893f)
        check(ddsBottom, StageShape.COMPACT, 1191f, 893f)
        check(tenEighteen, StageShape.TALL, 1920f, 1200f)
        // 1620 px wide is 702 dp: the floor shrinks the stage to 1634 x 1089
        check(threeTwo, StageShape.TALL, 1634f, 1089f)
        check(fourThree, StageShape.COMPACT, 1452f, 1089f)
        check(square, StageShape.COMPACT, 726f, 726f)
        check(twentyNine, StageShape.WIDE, 2400f, 1080f)
        check(twentyOneNine, StageShape.WIDE, 2520f, 1080f)
        check(splitHalf, StageShape.COMPACT, 968f, 1089f)
    }

    @Test
    fun shapesAreClassedByAspectOnceTheStageIsWideEnough() {
        // Tablet-sized windows (no floor): 4:3 down to 16:9 is Tall, 16:9 Standard, wider Wide
        val fourThreeTablet = StagePlanner.plan(1024f, 768f)
        assertEquals(1920f, fourThreeTablet.stageWidth, 0f)
        assertEquals(1440f, fourThreeTablet.stageHeight, 0.01f)
        assertEquals(StageShape.TALL, fourThreeTablet.shape)
        assertEquals(StageShape.TALL, StagePlanner.plan(1000f, 625f).shape) // 16:10
        assertEquals(StageShape.TALL, StagePlanner.plan(1000f, 600f).shape) // 5:3
        assertEquals(StageShape.COMPACT, StagePlanner.plan(1000f, 1000f).shape) // 1:1 holds 1920 x 1920 but is squarish
        assertEquals(StageShape.COMPACT, StagePlanner.plan(1000f, 800f).shape) // 5:4, narrower than 4:3
        assertEquals(StageShape.WIDE, StagePlanner.plan(1100f, 470f).shape)
    }

    @Test
    fun theClassBoundaries() {
        assertEquals(StageShape.STANDARD, StagePlanner.classify(1920f, 1080f))
        // 1920 x 1056 (a window with a bar) and 1.83:1 are still the prototype
        assertEquals(StageShape.STANDARD, StagePlanner.classify(1920f, 1056f))
        assertEquals(StageShape.STANDARD, StagePlanner.classify(1976f, 1080f))
        assertEquals(StageShape.WIDE, StagePlanner.classify(2100f, 1080f))
        assertEquals(StageShape.TALL, StagePlanner.classify(1920f, 1200f))
        assertEquals(StageShape.TALL, StagePlanner.classify(1920f, 1440f))
        assertEquals(StageShape.COMPACT, StagePlanner.classify(1920f, 1600f))
        assertEquals(StageShape.COMPACT, StagePlanner.classify(1499f, 843f))
        assertEquals(StageShape.STANDARD, StagePlanner.classify(1500f, 843f))
    }

    @Test
    fun anUnmeasuredWindowGetsTheStageAsItIs() {
        val plan = StagePlanner.plan(0f, 0f)
        assertEquals(1f, plan.dpPerPx, 0f)
        assertEquals(StageShape.STANDARD, plan.shape)
    }

    @Test
    fun cutoutInsetsConvertToStagePixelsAndStayZeroWithoutACutout() {
        assertEquals(0f, StagePlanner.insetToStage(0, 2.3f, 0.43f), 0f)
        assertEquals(0f, StagePlanner.insetToStage(-4, 2.3f, 0.43f), 0f)
        // 100 px at 2 px per dp is 50 dp; one stage pixel is 0.5 dp
        assertEquals(100f, StagePlanner.insetToStage(100, 2f, 0.5f), 0f)
        // The Odin: 90 px at 369 dpi, one stage pixel 0.4336 dp
        assertEquals(90f / (369f / 160f) / 0.4336f, StagePlanner.insetToStage(90, 369f / 160f, 0.4336f), 0.001f)
    }

    @Test
    fun insetsInAZoomedBarAreDividedByItsScale() {
        val safe = StageInsets(left = 60f, top = 30f, right = 0f, bottom = 12f)
        assertEquals(StageInsets(40f, 20f, 0f, 8f), safe.dividedBy(1.5f))
        assertEquals(safe, safe.dividedBy(1f))
        assertEquals(StageInsets.None, StageInsets.None.dividedBy(1.25f))
    }
}
