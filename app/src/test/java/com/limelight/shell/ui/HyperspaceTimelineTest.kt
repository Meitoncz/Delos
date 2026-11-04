package com.limelight.shell.ui

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperspaceTimelineTest {
    @Test
    fun smootherstepClampsAndUsesTheQuinticCurve() {
        assertEquals(0f, HyperspaceTimeline.smootherstep(-1f), 0f)
        assertEquals(0f, HyperspaceTimeline.smootherstep(0f), 0f)
        assertEquals(.103515625f, HyperspaceTimeline.smootherstep(.25f), 1e-6f)
        assertEquals(.5f, HyperspaceTimeline.smootherstep(.5f), 1e-6f)
        assertEquals(.896484375f, HyperspaceTimeline.smootherstep(.75f), 1e-6f)
        assertEquals(1f, HyperspaceTimeline.smootherstep(1f), 0f)
        assertEquals(1f, HyperspaceTimeline.smootherstep(2f), 0f)
    }

    @Test
    fun theTunnelWaitsThenExpandsQuadraticallyRatherThanSpringing() {
        assertEquals(1f, HyperspaceTimeline.tunnelScale(-1f), 0f)
        assertEquals(1f, HyperspaceTimeline.tunnelScale(0f), 0f)
        assertEquals(1f, HyperspaceTimeline.tunnelScale(HyperspaceConfig.ZOOM_START), 0f)
        val halfway = (HyperspaceConfig.ZOOM_START + HyperspaceConfig.ZOOM_END) / 2f
        assertEquals(1f + (HyperspaceConfig.ZOOM_SCALE - 1f) / 4f, HyperspaceTimeline.tunnelScale(halfway), 1e-5f)
        assertEquals(HyperspaceConfig.ZOOM_SCALE, HyperspaceTimeline.tunnelScale(1f), 0f)
        assertEquals(HyperspaceConfig.ZOOM_SCALE, HyperspaceTimeline.tunnelScale(2f), 0f)
        val scales = (0..1000).map { HyperspaceTimeline.tunnelScale(it / 1000f) }
        assertTrue(scales.all { it.isFinite() && it in 1f..HyperspaceConfig.ZOOM_SCALE })
        assertTrue(scales.zipWithNext().all { (before, after) -> after >= before })
    }

    @Test
    fun informationFadesAwayBeforeTheApertureStarts() {
        assertEquals(1f, HyperspaceTimeline.infoAlpha(-1f), 0f)
        assertEquals(1f, HyperspaceTimeline.infoAlpha(0f), 0f)
        assertEquals(0f, HyperspaceTimeline.infoAlpha(HyperspaceConfig.INFO_FADE_END), 0f)
        assertEquals(0f, HyperspaceTimeline.infoAlpha(HyperspaceConfig.IRIS_START), 0f)
        assertEquals(0f, HyperspaceTimeline.infoAlpha(1f), 0f)
        val alphas = (0..1000).map { HyperspaceTimeline.infoAlpha(it / 1000f) }
        assertTrue(alphas.all { it.isFinite() && it in 0f..1f })
        assertTrue(alphas.zipWithNext().all { (before, after) -> after <= before })
    }

    @Test
    fun noPartOfTheStreamIsExposedBeforeTheLateIrisStartDespiteTunnelExpansion() {
        assertTrue(HyperspaceTimeline.tunnelScale(HyperspaceConfig.IRIS_START) > 1f)
        for (opening in listOf(0f, .34f, .5f, HyperspaceConfig.IRIS_START)) {
            for (x in listOf(0f, 480f, 960f, 1440f, 1920f)) {
                for (y in listOf(0f, 270f, 540f, 810f, 1080f)) {
                    assertEquals(1f, opacity(opening, x, y), 0f)
                }
            }
        }
    }

    @Test
    fun theCenterIsRevealedBeforeEdgesAndCorners() {
        assertEquals(0f, opacity(.76f, 960f, 540f), 0f)
        assertEquals(1f, opacity(.76f, 1920f, 540f), 0f)
        assertEquals(1f, opacity(.76f, 1920f, 1080f), 0f)
        for (step in 0..100) {
            val opening = HyperspaceConfig.IRIS_START + (HyperspaceConfig.IRIS_END - HyperspaceConfig.IRIS_START) * step / 100f
            val center = opacity(opening, 960f, 540f)
            val middle = opacity(opening, 1440f, 540f)
            val edge = opacity(opening, 1920f, 540f)
            val corner = opacity(opening, 1920f, 1080f)
            assertTrue(center <= middle && middle <= edge && edge <= corner)
        }
    }

    @Test
    fun theApertureHasASoftBoundaryInsteadOfAHardCut() {
        val opening = (HyperspaceConfig.IRIS_START + HyperspaceConfig.IRIS_END) / 2f
        val aspect = 1920f / 1080f
        val radiusStart = -HyperspaceConfig.IRIS_SOFTNESS - .001f
        val radiusEnd = sqrt(aspect * aspect + 1f) / 2f + HyperspaceConfig.IRIS_SOFTNESS + .01f
        val boundaryX = 960f + (radiusStart + radiusEnd) / 2f * 1080f
        assertEquals(.5f, opacity(opening, boundaryX, 540f), 1e-4f)
        val justInside = opacity(opening, boundaryX - 10f, 540f)
        val justOutside = opacity(opening, boundaryX + 10f, 540f)
        assertTrue(justInside > 0f && justInside < .5f)
        assertTrue(justOutside > .5f && justOutside < 1f)
    }

    @Test
    fun thePhysicalPixelApertureIsSymmetricAndCircularOnANonSquareViewport() {
        for (opening in listOf(.72f, .75f, .78f, .82f, .86f)) {
            for (distance in listOf(50f, 150f, 250f, 450f)) {
                val right = opacity(opening, 960f + distance, 540f)
                assertEquals(right, opacity(opening, 960f - distance, 540f), 1e-5f)
                assertEquals(right, opacity(opening, 960f, 540f + distance), 1e-5f)
                assertEquals(right, opacity(opening, 960f, 540f - distance), 1e-5f)
            }
            assertEquals(opacity(opening, 1400f, 800f), opacity(opening, 520f, 280f), 1e-5f)
        }
    }

    @Test
    fun apertureCoverageIsIndependentOfRenderResolution() {
        for (opening in listOf(.7f, .75f, .78f, .82f, .88f)) {
            for ((x, y) in listOf(960f to 540f, 1400f to 800f, 1920f to 1080f)) {
                val full = opacity(opening, x, y)
                assertEquals(full, HyperspaceTimeline.apertureOpacity(opening, x * .6f, y * .6f, 1152f, 648f), 1e-5f)
            }
        }
    }

    @Test
    fun everyViewportCornerIsClearByIrisEndAndStaysClearThroughCompletion() {
        for ((width, height) in listOf(1920f to 1080f, 1080f to 1920f, 1000f to 1000f, 3200f to 900f)) {
            for (opening in listOf(HyperspaceConfig.IRIS_END, .95f, 1f, 2f)) {
                for (x in listOf(0f, width / 2f, width)) {
                    for (y in listOf(0f, height / 2f, height)) {
                        assertEquals(0f, HyperspaceTimeline.apertureOpacity(opening, x, y, width, height), 0f)
                    }
                }
            }
        }
    }

    @Test
    fun eachPixelOnlyBecomesMoreTransparentAsOpeningAdvances() {
        for ((x, y) in listOf(960f to 540f, 1200f to 540f, 1500f to 900f, 1920f to 1080f)) {
            var previous = 1f
            for (step in 0..1000) {
                val current = opacity(step / 1000f, x, y)
                assertTrue(current.isFinite() && current in 0f..1f)
                assertTrue("Opacity increased at step $step for ($x, $y)", current <= previous + 1e-6f)
                previous = current
            }
        }
    }

    @Test
    fun invalidGeometryStaysOpaqueUntilTheOverlayIsCompletelyFinished() {
        val invalidDimensions = listOf(0f to 1080f, -1f to 1080f, 1920f to 0f, 1920f to -1f,
            Float.NaN to 1080f, 1920f to Float.NaN, Float.POSITIVE_INFINITY to 1080f, 1920f to Float.NEGATIVE_INFINITY)
        for ((width, height) in invalidDimensions) {
            assertEquals(1f, HyperspaceTimeline.apertureOpacity(.8f, 0f, 0f, width, height), 0f)
            assertEquals(0f, HyperspaceTimeline.apertureOpacity(1f, 0f, 0f, width, height), 0f)
        }
        for ((x, y) in listOf(Float.NaN to 540f, 960f to Float.POSITIVE_INFINITY)) {
            assertEquals(1f, opacity(.8f, x, y), 0f)
            assertEquals(0f, opacity(1f, x, y), 0f)
        }
    }

    @Test
    fun nonfiniteOpeningIsConservativeOrClampedAtTheAppropriateEndpoint() {
        assertEquals(0f, HyperspaceTimeline.smootherstep(Float.NaN), 0f)
        assertEquals(0f, HyperspaceTimeline.smootherstep(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(1f, HyperspaceTimeline.smootherstep(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, HyperspaceTimeline.tunnelScale(Float.NaN), 0f)
        assertEquals(1f, HyperspaceTimeline.infoAlpha(Float.NaN), 0f)
        assertEquals(1f, opacity(Float.NaN, 960f, 540f), 0f)
        assertEquals(1f, opacity(Float.NEGATIVE_INFINITY, 960f, 540f), 0f)
        assertEquals(0f, opacity(Float.POSITIVE_INFINITY, 960f, 540f), 0f)
    }

    private fun opacity(opening: Float, x: Float, y: Float): Float =
        HyperspaceTimeline.apertureOpacity(opening, x, y, 1920f, 1080f)
}
