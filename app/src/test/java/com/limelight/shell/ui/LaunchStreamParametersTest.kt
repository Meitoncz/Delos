package com.limelight.shell.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchStreamParametersTest {
    @Test
    fun effectiveDimensionsCodecAndFrameRateAreDisplayedWithoutSubstitution() {
        assertEquals("1920 × 1080 · AV1 · 60 FPS", formatLaunchStreamParameters(1920, 1080, "AV1", 60))
        assertEquals("1280 × 720 · H.264 · 30 FPS", formatLaunchStreamParameters(1280, 720, "H.264", 30))
        assertEquals("3840 × 2160 · HEVC · 120 FPS", formatLaunchStreamParameters(3840, 2160, "HEVC", 120))
        assertEquals("2560 × 1600 · PyroWave · 90 FPS", formatLaunchStreamParameters(2560, 1600, "PyroWave", 90))
        assertEquals("1080 × 1920 · HEVC Main10 · 144 FPS", formatLaunchStreamParameters(1080, 1920, "HEVC Main10", 144))
    }

    @Test
    fun floatFrameRatesPreserveCustomFractionsAndOmitAnIntegralDecimalSuffix() {
        assertEquals("1920 × 1080 · AV1 · 60 FPS", formatLaunchStreamParameters(1920, 1080, "AV1", 60f))
        assertEquals("1920 × 1080 · AV1 · 59.94 FPS", formatLaunchStreamParameters(1920, 1080, "AV1", 59.94f))
        assertEquals("2560 × 1440 · HEVC · 119.88 FPS", formatLaunchStreamParameters(2560, 1440, "HEVC", 119.88f))
        assertEquals("1280 × 800 · H.264 · 23.976 FPS", formatLaunchStreamParameters(1280, 800, "H.264", 23.976f))
    }

    @Test
    fun formattingDoesNotDependOnTheDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1920 × 1080 · AV1 · 59.94 FPS", formatLaunchStreamParameters(1920, 1080, "AV1", 59.94f))
            assertEquals("3840 × 2160 · HEVC · 120 FPS", formatLaunchStreamParameters(3840, 2160, "HEVC", 120))
        } finally {
            Locale.setDefault(original)
        }
    }
}
