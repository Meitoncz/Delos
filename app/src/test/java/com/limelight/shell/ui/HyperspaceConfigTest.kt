package com.limelight.shell.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class HyperspaceConfigTest {
    @Test
    fun renderingAndFixedShaderLoopsStayWithinTheApprovedCostBudget() {
        val c = HyperspaceConfig
        assertTrue(c.RENDER_SCALE.isFinite() && c.RENDER_SCALE > 0f && c.RENDER_SCALE <= 1f)
        assertTrue(c.STREAK_COUNT > 0 && c.STREAK_COUNT <= 48)
        assertTrue(c.CLOUD_COUNT > 0 && c.CLOUD_COUNT <= 48)
        assertTrue("The launch shader must not grow beyond 48 fixed iterations", c.STREAK_COUNT + c.CLOUD_COUNT <= 48)
    }

    @Test
    fun durationsAndRevealIntervalsArePositiveAndOrdered() {
        val c = HyperspaceConfig
        assertTrue(c.OPENING_MS > 0)
        assertTrue(c.REDUCED_OPENING_MS > 0 && c.REDUCED_OPENING_MS <= c.OPENING_MS)
        assertTrue(c.LINE_MS > 0L)
        for (phase in listOf(c.INFO_FADE_END, c.ZOOM_START, c.ZOOM_END, c.IRIS_START, c.IRIS_END)) {
            assertTrue(phase.isFinite() && phase in 0f..1f)
        }
        assertTrue(c.INFO_FADE_END > 0f && c.INFO_FADE_END < c.IRIS_START)
        assertTrue(c.ZOOM_START < c.IRIS_START)
        assertTrue(c.IRIS_START < c.IRIS_END && c.IRIS_END <= c.ZOOM_END)
        assertTrue(c.ZOOM_END > c.ZOOM_START)
        assertTrue(c.IRIS_SOFTNESS.isFinite() && c.IRIS_SOFTNESS > 0f && c.IRIS_SOFTNESS < .5f)
        assertTrue(c.ZOOM_SCALE.isFinite() && c.ZOOM_SCALE > 1f)
    }

    @Test
    fun geometryAndMotionCannotIntroduceSingularOrNonfiniteCoefficients() {
        val c = HyperspaceConfig
        for (center in listOf(c.CENTER_X, c.CENTER_Y)) {
            assertTrue(center.isFinite() && center > 0f && center < 1f)
        }
        for (positive in listOf(c.DEPTH, c.THROAT, c.SPOKES, c.BAND_FREQUENCY, c.VELOCITY, c.SPEED_DECAY)) {
            assertTrue(positive.isFinite() && positive > 0f)
        }
        for (nonnegative in listOf(c.WALL_GAIN, c.SWIRL, c.LENS_PINCH, c.ACCELERATION, c.STREAK_LENGTH,
            c.OPENING_ACCELERATION, c.CLOUD_DRIFT)) {
            assertTrue(nonnegative.isFinite() && nonnegative >= 0f)
        }
        assertTrue(c.ABERRATION.isFinite() && c.ABERRATION >= 0f && c.ABERRATION < .05f)
    }

    @Test
    fun cloudsAndAtmosphereStayFiniteWithoutNegativeOpacityOrUnboundedBloom() {
        val c = HyperspaceConfig
        assertTrue(c.CLOUD_SIZE.isFinite() && c.CLOUD_SIZE > 0f && c.CLOUD_SIZE <= 1f)
        for (bounded in listOf(c.CLOUD_DENSITY, c.CLOUD_FLASH_GAIN, c.EDGE_BLOOM, c.CLOUD_RIM)) {
            assertTrue(bounded.isFinite() && bounded in 0f..1f)
        }
        assertTrue(c.CONTRAST.isFinite() && c.CONTRAST > 0f)
        assertTrue(c.EXPOSURE.isFinite() && c.EXPOSURE > 0f)
    }
}
