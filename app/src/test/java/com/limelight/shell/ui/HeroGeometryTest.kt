package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroGeometryTest {
    private val delta = .01f

    @Test fun aWideHeroFillsTheWidthAtTheTopAndFadesIntoTheExtension() {
        // 1920 × 620 and 3840 × 1240 are the same shape: 2100 × 678.125 at the top of the 2100 × 1260 area
        for ((w, h) in listOf(1920 to 620, 3840 to 1240)) {
            val layout = HeroGeometry.layout(w, h)
            assertTrue(layout.extended)
            assertEquals(0f, layout.left, delta)
            assertEquals(0f, layout.top, delta)
            assertEquals(2100f, layout.width, delta)
            assertEquals(678.125f, layout.height, delta)
            assertEquals(160f, layout.fade, delta)
            assertEquals(678.125f, layout.bottom, delta)
        }
    }

    @Test fun lessWideImagesStillGetAnExtension() {
        val wideCover = HeroGeometry.layout(920, 430)
        assertTrue(wideCover.extended)
        assertEquals(981.52f, wideCover.height, delta)
        val sixteenNine = HeroGeometry.layout(1920, 1080)
        assertTrue(sixteenNine.extended)
        assertEquals(1181.25f, sixteenNine.height, delta)
        assertEquals(160f, sixteenNine.fade, delta)
    }

    @Test fun anImageAsTallAsTheAreaOrTallerIsCroppedToFill() {
        // 5 : 3 exactly: the width-fit height is the area's height, nothing to extend
        val exact = HeroGeometry.layout(1000, 600)
        assertFalse(exact.extended)
        assertEquals(0f, exact.left, delta)
        assertEquals(0f, exact.top, delta)
        assertEquals(2100f, exact.width, delta)
        assertEquals(1260f, exact.height, delta)
        assertEquals(0f, exact.fade, delta)

        // A portrait box art: 600 × 900 scaled to 2100 across (3150 tall), centered, cropped top and bottom
        val portrait = HeroGeometry.layout(600, 900)
        assertFalse(portrait.extended)
        assertEquals(2100f, portrait.width, delta)
        assertEquals(3150f, portrait.height, delta)
        assertEquals(-945f, portrait.top, delta)
        assertEquals(1260f + 945f, portrait.bottom, delta)
    }

    @Test fun coverFillsTheAreaWhateverTheShape() {
        // A very wide image is cropped left and right to the area's height
        val wide = HeroGeometry.cover(3840, 1240)
        assertFalse(wide.extended)
        assertEquals(1260f, wide.height, delta)
        assertEquals(1260f * 3840 / 1240, wide.width, delta)
        assertEquals((2100f - wide.width) / 2f, wide.left, delta)
        assertEquals(0f, wide.top, delta)
        assertTrue(wide.left < 0f && wide.right > 2100f)
    }

    @Test fun theFadeNeverLongerThanTheBand() {
        // The area allows nothing shorter than the fade for any real image, but the mask must stay inside the band
        val flat = HeroGeometry.layout(21000, 1000)
        assertTrue(flat.extended)
        assertEquals(100f, flat.height, delta)
        assertEquals(100f, flat.fade, delta)
    }

    @Test fun unknownSizesFillTheArea() {
        val layout = HeroGeometry.layout(0, 0)
        assertFalse(layout.extended)
        assertEquals(2100f, layout.width, delta)
        assertEquals(1260f, layout.height, delta)
    }

    @Test fun theFadeStopsRunFromOpaqueToNothingWithoutSteps() {
        val stops = HeroGeometry.FADE_STOPS
        assertEquals(0f to 1f, stops.first())
        assertEquals(1f to 0f, stops.last())
        assertTrue(stops.zipWithNext().all { (a, b) -> b.first > a.first && b.second < a.second })
    }
}
