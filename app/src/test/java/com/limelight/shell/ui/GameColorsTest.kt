package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The three main colors of a cover (HeroBitmaps.dominantHues) on simple made-up images. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameColorsTest {
    private fun image(vararg parts: Pair<Int, Int>): IntArray = parts.flatMap { (argb, count) -> List(count) { argb } }.toIntArray()

    private fun hueDistance(a: Float, b: Float): Float {
        val d = Math.abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    @Test fun redOnNearBlackIsRedNotTheDarkBackground() {
        // Carrion: mostly a near-black background with a hint of red, some vivid red
        val colors = HeroBitmaps.dominantHues(image(0xFF100007.toInt() to 6000, 0xFFFC1A0C.toInt() to 600, 0xFFB60420.toInt() to 400))
        assertNotNull(colors)
        assertTrue("main hue ${colors!!.hues[0]}", hueDistance(colors.hues[0], 3f) < 12f)
        assertEquals(3, colors.hues.size)
        assertEquals(3, colors.saturations.size)
    }

    @Test fun greyImageHasNoColors() {
        assertNull(HeroBitmaps.dominantHues(image(0xFFF0F0F0.toInt() to 5000, 0xFF202020.toInt() to 3000, 0xFF808080.toInt() to 2000)))
    }

    @Test fun twoColorsComeOutApartAndTheThirdIsAShade() {
        val colors = HeroBitmaps.dominantHues(image(0xFFFFFFFF.toInt() to 5000, 0xFF2060E0.toInt() to 3000, 0xFFF0C020.toInt() to 2000))!!
        assertTrue(hueDistance(colors.hues[0], colors.hues[1]) > 60f)
        assertTrue(hueDistance(colors.hues[2], colors.hues[0]) <= 15f)
    }

    @Test fun aSmallVividLogoCountsBesideALargeMutedColor() {
        // Returnal: large muted teal, a small vivid yellow logo
        val colors = HeroBitmaps.dominantHues(image(0xFF0A1416.toInt() to 5000, 0xFF2A5A60.toInt() to 3000, 0xFF3C7078.toInt() to 1500, 0xFFE8E010.toInt() to 120))!!
        assertTrue("hues ${colors.hues}", colors.hues.any { hueDistance(it, 58f) < 15f })
    }

    @Test fun mutedColorsStayMuted() {
        val colors = HeroBitmaps.dominantHues(image(0xFFF0F0F0.toInt() to 5000, 0xFF3E5364.toInt() to 3000))!!
        assertTrue("saturation ${colors.saturations[0]}", colors.saturations[0] < .4f)
    }
}
