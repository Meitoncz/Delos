package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroGeometryTest {
    private val delta = .01f
    private val area = HeroArea.Standard

    @Test fun aWideHeroFillsTheWidthAtTheTopAndFadesIntoTheExtension() {
        // 1920 × 620 and 3840 × 1240 are the same shape: 2100 × 678.125 at the top of the 2100 × 1260 area
        for ((w, h) in listOf(1920 to 620, 3840 to 1240)) {
            val layout = HeroGeometry.layout(area, w, h)
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
        val wideCover = HeroGeometry.layout(area, 920, 430)
        assertTrue(wideCover.extended)
        assertEquals(981.52f, wideCover.height, delta)
        val sixteenNine = HeroGeometry.layout(area, 1920, 1080)
        assertTrue(sixteenNine.extended)
        assertEquals(1181.25f, sixteenNine.height, delta)
        assertEquals(160f, sixteenNine.fade, delta)
    }

    @Test fun anImageAsTallAsTheAreaOrTallerIsCroppedToFill() {
        // 5 : 3 exactly: the width-fit height is the area's height, nothing to extend
        val exact = HeroGeometry.layout(area, 1000, 600)
        assertFalse(exact.extended)
        assertEquals(0f, exact.left, delta)
        assertEquals(0f, exact.top, delta)
        assertEquals(2100f, exact.width, delta)
        assertEquals(1260f, exact.height, delta)
        assertEquals(0f, exact.fade, delta)

        // A portrait box art: 600 × 900 scaled to 2100 across (3150 tall), centered, cropped top and bottom
        val portrait = HeroGeometry.layout(area, 600, 900)
        assertFalse(portrait.extended)
        assertEquals(2100f, portrait.width, delta)
        assertEquals(3150f, portrait.height, delta)
        assertEquals(-945f, portrait.top, delta)
        assertEquals(1260f + 945f, portrait.bottom, delta)
    }

    @Test fun coverFillsTheAreaWhateverTheShape() {
        // A very wide image is cropped left and right to the area's height
        val wide = HeroGeometry.cover(area, 3840, 1240)
        assertFalse(wide.extended)
        assertEquals(1260f, wide.height, delta)
        assertEquals(1260f * 3840 / 1240, wide.width, delta)
        assertEquals((2100f - wide.width) / 2f, wide.left, delta)
        assertEquals(0f, wide.top, delta)
        assertTrue(wide.left < 0f && wide.right > 2100f)
    }

    @Test fun theFadeNeverLongerThanTheBand() {
        // The area allows nothing shorter than the fade for any real image, but the mask must stay inside the band
        val flat = HeroGeometry.layout(area, 21000, 1000)
        assertTrue(flat.extended)
        assertEquals(100f, flat.height, delta)
        assertEquals(100f, flat.fade, delta)
    }

    @Test fun unknownSizesFillTheArea() {
        val layout = HeroGeometry.layout(area, 0, 0)
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

    @Test fun aBlurredLiveHeroIsDrawnAtAQuarterOfTheAreaAndASharpOneInFull() {
        // The default blur (15 stage px) and everything above 8 px: a quarter, a 16th of the pixels
        assertEquals(.25f, HeroGeometry.liveScale(15f), 0f)
        assertEquals(.25f, HeroGeometry.liveScale(8f), 0f)
        assertEquals(.25f, HeroGeometry.liveScale(40f), 0f)
        // A lighter blur needs more pixels to stay smooth: half, then full
        assertEquals(.5f, HeroGeometry.liveScale(7.9f), 0f)
        assertEquals(.5f, HeroGeometry.liveScale(4f), 0f)
        assertEquals(1f, HeroGeometry.liveScale(3.9f), 0f)
        assertEquals(1f, HeroGeometry.liveScale(0f), 0f)
    }

    @Test fun theBlurNeverShrinksBelowItsMinimumWidthInTheLiveLayer() {
        for (sigma in listOf(0f, 1f, 2f, 3f, 5f, 8f, 12f, 20f, 40f)) {
            val scale = HeroGeometry.liveScale(sigma)
            assertTrue(scale == 1f || sigma * scale >= HeroGeometry.LIVE_MIN_SIGMA)
        }
    }

    @Test fun theStandardAreaIsTheOldFixedOne() {
        assertEquals(2100f, area.width, 0f)
        assertEquals(1260f, area.height, 0f)
        assertEquals(area, HeroArea.of(1920f, 1080f))
    }

    @Test fun theAreaAddsAMarginAroundAnyStageAndCoversIt() {
        for ((w, h) in listOf(1920f to 1200f, 1920f to 1280f, 1920f to 1440f, 2400f to 1080f, 2520f to 1080f, 1500f to 1080f)) {
            val a = HeroArea.of(w, h)
            assertTrue("$w x $h width", a.width > w)
            assertTrue("$w x $h height", a.height > h)
            // symmetric, and never less than the 90 stage pixels of the 16:9 stage
            assertTrue("$w x $h margin x", (a.width - w) / 2f >= HeroArea.MARGIN_X * w / 1920f - .01f)
            assertTrue("$w x $h margin y", (a.height - h) / 2f >= HeroArea.MARGIN_Y * h / 1080f - .01f)
        }
        val wide = HeroArea.of(2520f, 1080f)
        assertEquals(2756.25f, wide.width, delta)
        assertEquals(1260f, wide.height, delta)
        val tall = HeroArea.of(1920f, 1440f)
        assertEquals(2100f, tall.width, delta)
        assertEquals(1680f, tall.height, delta)
    }

    @Test fun theBitmapKeepsTheAreasAspectAt420Wide() {
        assertEquals(252, HeroBitmaps.height(area))
        assertEquals(420, HeroBitmaps.WIDTH)
        assertEquals(192, HeroBitmaps.height(HeroArea.of(2520f, 1080f)))
        assertEquals(336, HeroBitmaps.height(HeroArea.of(1920f, 1440f)))
    }

    @Test fun aWideAreaFitsTheHeroToItsWidthAndAnImageIsCroppedByTheTallAreasHeight() {
        val wide = HeroArea.of(2520f, 1080f)
        val band = HeroGeometry.layout(wide, 1920, 620)
        assertTrue(band.extended)
        assertEquals(wide.width, band.width, delta)
        assertEquals(wide.width * 620f / 1920f, band.height, delta)
        val tall = HeroArea.of(1920f, 1440f)
        // 16:9 art: fitted to 2100 wide it is 1181 high, shorter than the 1680 area: extended
        assertTrue(HeroGeometry.layout(tall, 1920, 1080).extended)
        // 5:4 art fills the tall area
        val fill = HeroGeometry.layout(tall, 1000, 800)
        assertFalse(fill.extended)
        val cover = HeroGeometry.cover(tall, 1920, 1080)
        assertEquals(tall.height, cover.height, delta)
        assertTrue(cover.left < 0f)
    }
}
