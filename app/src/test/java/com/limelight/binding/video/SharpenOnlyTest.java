package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class SharpenOnlyTest {
    private static final float EPS = 1e-5f;

    @Test
    public void sharpnessZeroIsOffAndOneIsCasMaximum() {
        assertEquals(0f, SharpenOnly.peak(0f), 0f);
        assertEquals(0.2f, SharpenOnly.peak(1f), EPS);
        // The square root: a quarter of the slider already gives half of the maximum
        assertEquals(0.1f, SharpenOnly.peak(0.25f), EPS);
        // 50 % is close to AMD's own default of 1/6.5
        assertEquals(0.14142f, SharpenOnly.peak(0.5f), 1e-4f);
        // Out-of-range input is clamped
        assertEquals(0f, SharpenOnly.peak(-3f), 0f);
        assertEquals(0.2f, SharpenOnly.peak(7f), EPS);
    }

    @Test
    public void amplitudeIsOneInTheMiddleAndFallsToZeroAtTheSignalLimits() {
        assertEquals(1f, SharpenOnly.amplitude(0.5f, 0.5f), EPS);
        // Step edge 0.2 .. 0.8: sqrt(0.2 / 0.8)
        assertEquals(0.5f, SharpenOnly.amplitude(0.2f, 0.8f), EPS);
        // Touching white or black leaves no room for the overshoot
        assertEquals(0f, SharpenOnly.amplitude(0.1f, 1f), EPS);
        assertEquals(0f, SharpenOnly.amplitude(0f, 0.7f), EPS);
        // Black stays finite
        assertEquals(0f, SharpenOnly.amplitude(0f, 0f), 0f);
    }

    @Test
    public void flatAreasNeverChangeAtAnySharpness() {
        for (float s = 0f; s <= 1f; s += 0.125f) {
            assertEquals(0.3f, SharpenOnly.channel(0.3f, 0.3f, 0.3f, 0.3f, 0.3f, s), EPS);
            assertEquals(0f, SharpenOnly.channel(0f, 0f, 0f, 0f, 0f, s), 0f);
            assertEquals(1f, SharpenOnly.channel(1f, 1f, 1f, 1f, 1f, s), EPS);
        }
    }

    @Test
    public void sharpnessZeroReturnsThePixelUnchanged() {
        assertEquals(0.4f, SharpenOnly.channel(0.4f, 0.4f, 0.2f, 0.8f, 0.4f, 0f), 0f);
    }

    @Test
    public void documentedValuesOfAnEdgePixel() {
        // Centre 0.4 between 0.2 (left) and 0.8 (right): mn 0.2, mx 0.8, amplitude 0.5.
        // Full sharpness: w = -0.5 * 0.2 = -0.1, (1.8 * -0.1 + 0.4) / (1 - 0.4) = 0.36667: pushed darker, away from the mean
        assertEquals(0.366667f, SharpenOnly.channel(0.4f, 0.4f, 0.2f, 0.8f, 0.4f, 1f), EPS);
        // A quarter of the slider: w = -0.05, (1.8 * -0.05 + 0.4) / (1 - 0.2) = 0.3875
        assertEquals(0.3875f, SharpenOnly.channel(0.4f, 0.4f, 0.2f, 0.8f, 0.4f, 0.25f), EPS);
        // The same edge with a brighter centre of 0.6: (2.2 * -0.1 + 0.6) / 0.6 = 0.63333, the centre moves towards its brighter neighbour
        assertEquals(0.633333f, SharpenOnly.channel(0.6f, 0.6f, 0.2f, 0.8f, 0.6f, 1f), EPS);
    }

    @Test
    public void nothingOvershootsNearWhite() {
        // A neighbour at 1.0 leaves no headroom: amplitude 0, the pixel is returned as is
        assertEquals(0.9f, SharpenOnly.channel(0.9f, 1f, 1f, 1f, 0.1f, 1f), EPS);
    }

    @Test
    public void strongerSharpnessMovesAnEdgePixelFurtherFromItsNeighbours() {
        float previous = 0f;
        for (float s = 0f; s <= 1f; s += 0.1f) {
            float moved = Math.abs(SharpenOnly.channel(0.4f, 0.4f, 0.2f, 0.8f, 0.4f, s) - 0.4f);
            assertTrue("sharpness " + s, moved >= previous - EPS);
            previous = moved;
        }
        assertTrue(previous > 0.02f);
    }

    @Test
    public void resultStaysInRangeAndWeightsSumToOne() {
        Random random = new Random(67);
        for (int i = 0; i < 20000; i++) {
            float e = random.nextFloat(), b = random.nextFloat(), d = random.nextFloat(), f = random.nextFloat(), h = random.nextFloat();
            float s = random.nextFloat();
            float out = SharpenOnly.channel(e, b, d, f, h, s);
            assertTrue(out >= 0f && out <= 1f);
            // The neighbour weight w and the centre weight 1 / (1 + 4w) normalise to one
            float mn = Math.min(Math.min(Math.min(d, e), Math.min(f, b)), h);
            float mx = Math.max(Math.max(Math.max(d, e), Math.max(f, b)), h);
            float w = SharpenOnly.weight(mn, mx, s);
            assertTrue(w <= 0f && w >= -SharpenOnly.MAX_PEAK);
            assertEquals(1f, (4f * w + 1f) / (1f + 4f * w), EPS);
        }
    }
}
