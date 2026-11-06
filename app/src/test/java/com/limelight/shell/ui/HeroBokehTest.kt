package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroBokehTest {
    private fun gray(level: Int) = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

    @Test
    fun percentileInterpolatesLinearlyLikeNumpy() {
        val sorted = floatArrayOf(0f, 1f, 2f, 3f, 4f)
        assertEquals(0f, HeroBokeh.percentile(sorted, 0.0), 0f)
        assertEquals(4f, HeroBokeh.percentile(sorted, 100.0), 0f)
        assertEquals(2f, HeroBokeh.percentile(sorted, 50.0), 1e-6f)
        assertEquals(1.5f, HeroBokeh.percentile(sorted, 37.5), 1e-6f)
        assertEquals(3.88f, HeroBokeh.percentile(sorted, 97.0), 1e-5f)
        assertEquals(7f, HeroBokeh.percentile(floatArrayOf(7f), 99.4), 0f)
    }

    @Test
    fun lumaUsesRec709WeightsInGammaSpace() {
        assertEquals(1f, HeroBokeh.luma(gray(255)), 1e-6f)
        assertEquals(0f, HeroBokeh.luma(gray(0)), 0f)
        assertEquals(0.2126f, HeroBokeh.luma(0xFFFF0000.toInt()), 1e-6f)
        assertEquals(0.7152f, HeroBokeh.luma(0xFF00FF00.toInt()), 1e-6f)
        assertEquals(0.0722f, HeroBokeh.luma(0xFF0000FF.toInt()), 1e-6f)
    }

    @Test
    fun thresholdsSitAtTheTopFewPercentOfTheArtwork() {
        // 970 mid-grey pixels and 30 light ones: p97 interpolates 3 % of the way into the step, p99.4 is the light level.
        val pixels = IntArray(1000) { gray(if (it >= 970) 200 else 100) }
        val (t0, t1) = HeroBokeh.thresholds(pixels)
        assertEquals(103f / 255f, t0, 1e-4f)
        assertEquals(200f / 255f, t1, 1e-5f)
    }

    @Test
    fun darkArtworkGetsLowThresholdsAndFlatArtworkStaysWellFormed() {
        val dark = IntArray(1000) { gray(if (it >= 970) 80 else 10) }
        val (d0, d1) = HeroBokeh.thresholds(dark)
        assertTrue(d0 < 0.1f && d1 in 0.30f..0.32f)
        assertEquals(80f / 255f, d1, 1e-6f)

        val (f0, f1) = HeroBokeh.thresholds(IntArray(500) { gray(128) })
        assertEquals(128f / 255f + HeroBokeh.MIN_THRESHOLD_GAP, f1, 1e-6f)
        assertEquals(128f / 255f, f0, 1e-6f)
        val (w0, w1) = HeroBokeh.thresholds(IntArray(500) { gray(255) })
        assertEquals(1f + HeroBokeh.MIN_THRESHOLD_GAP, w1, 1e-6f)
        assertEquals(1f, w0, 1e-6f)
    }

    @Test
    fun sampleCountFollowsTheApertureAreaInStepsOf32WithinTheCaps() {
        assertEquals(HeroBokeh.MIN_TAPS, HeroBokeh.sampleCount(0f))
        assertEquals(HeroBokeh.MIN_TAPS, HeroBokeh.sampleCount(2f))
        // Blur 15 → R = 6 px → pi R² / 0.6 = 188.5 → 192.
        assertEquals(192, HeroBokeh.sampleCount(HeroBokeh.radiusPx(15, 420)))
        // R = 10 → 523.6 → 512.
        assertEquals(512, HeroBokeh.sampleCount(10f))
        // Blur 40 → R = 16 px → 1340 → capped at 1280.
        assertEquals(1280, HeroBokeh.sampleCount(HeroBokeh.radiusPx(40, 420)))
        var previous = 0
        for (blur in 0..40) {
            val taps = HeroBokeh.sampleCount(HeroBokeh.radiusPx(blur, 420))
            assertTrue(taps in 128..1280 && taps % 32 == 0 && taps >= previous)
            previous = taps
        }
    }

    @Test
    fun radiusIsPointFourPixelsPerBlurUnitAt420AndClampedToTheSettingsRange() {
        assertEquals(6f, HeroBokeh.radiusPx(15, 420), 1e-5f)
        assertEquals(16f, HeroBokeh.radiusPx(40, 420), 1e-5f)
        assertEquals(16f, HeroBokeh.radiusPx(90, 420), 1e-5f)
        assertEquals(0f, HeroBokeh.radiusPx(-3, 420), 0f)
    }

    @Test
    fun shaderBakesTheTapCountAndTheBokehConstants() {
        val source = HeroBokeh.shaderSource(480)
        assertTrue("i < 480;" in source && "/ 480.0)" in source)
        assertTrue("120.0 * h * sqrt(h)" in source)
        assertTrue("(1.0 - 0.4) + 0.4 * smoothstep(0.55, 0.97, rho)" in source)
        assertTrue("uniform float2 thresholds;" in source && "break" !in source)
        assertTrue("clamp((0.95 - t) / 0.65, 0.0, 1.0)" in source)
        assertTrue(HeroBokeh.shaderSource(192) != source)
    }
}
