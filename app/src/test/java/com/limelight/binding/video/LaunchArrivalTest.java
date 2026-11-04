package com.limelight.binding.video;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LaunchArrivalTest {
    private static final float[] IDENTITY = {1f, 1f, 0f, 0f, 0f, 0f};

    @Test public void realImageProcessingStartsLateButBeforeTheAperture() {
        assertFalse(LaunchArrival.shouldActivate(0f));
        assertFalse(LaunchArrival.shouldActivate(LaunchArrival.ACTIVATE_AT - .001f));
        assertTrue(LaunchArrival.shouldActivate(LaunchArrival.ACTIVATE_AT));
        assertTrue(LaunchArrival.shouldActivate(LaunchArrival.READY_CHECK_AT));
        assertFalse(LaunchArrival.shouldActivate(1f));
        assertFalse(LaunchArrival.shouldActivate(Float.NaN));
        assertTrue(LaunchArrival.ACTIVATE_AT < LaunchArrival.READY_CHECK_AT);
        assertTrue(LaunchArrival.READY_CHECK_AT < LaunchArrival.SETTLE_START);
    }

    @Test public void readinessAndCompletionCannotHoldTheLoaderIndefinitely() {
        assertTrue(LaunchArrival.READY_WAIT_MS > 0 && LaunchArrival.READY_WAIT_MS <= 1500L);
        assertTrue(LaunchArrival.COMPLETE_WAIT_MS > 0 && LaunchArrival.COMPLETE_WAIT_MS <= 1000L);
        assertEquals(LaunchArrival.MAX_BLUR_SOURCE_PIXELS, pose(LaunchArrival.SETTLE_START)[LaunchArrival.BLUR], 0f);
    }

    @Test public void imageWaitsZoomedAndBlurredUntilTheLateAperture() {
        for (float opening : new float[]{0f, .25f, .5f, LaunchArrival.SETTLE_START}) {
            assertArrayEquals(new float[]{1.18f, 1.18f, 0f, 0f, 20f, 0f}, pose(opening), 1e-6f);
        }
    }

    @Test public void arrivalMatchesTheApprovedVisibleSpringPose() {
        assertArrayEquals(new float[]{1.2326705f, 1.0984540f, .005695637f, 41.533218f, 16.532829f, 2.9644082f},
                pose(.72f), .0002f);
        assertArrayEquals(new float[]{1.0706805f, 1.1196314f, -.002121894f, -15.473235f, 11.156508f, 4.714538f},
                pose(.76f), .0002f);
    }

    @Test public void elasticDisplacementReversesWhileZoomAndFocusSettle() {
        float[] first = pose(.72f), rebound = pose(.76f), next = pose(.84f);
        assertTrue(first[LaunchArrival.WARP] > 0f);
        assertTrue(rebound[LaunchArrival.WARP] < 0f);
        assertTrue(next[LaunchArrival.WARP] > 0f);
        assertTrue(first[LaunchArrival.SCALE_X] > first[LaunchArrival.SCALE_Y]);
        assertTrue(rebound[LaunchArrival.SCALE_X] < rebound[LaunchArrival.SCALE_Y]);
        assertTrue(Math.abs(next[LaunchArrival.WARP]) < Math.abs(first[LaunchArrival.WARP]));
        assertTrue(next[LaunchArrival.BLUR] < rebound[LaunchArrival.BLUR]);
    }

    @Test public void focusNeverReblursAndSampleCoordinatesRemainFinite() {
        float previousBlur = 20f;
        for (int step = 0; step <= 10000; step++) {
            float[] values = pose(step / 10000f);
            for (float value : values) assertTrue(Float.isFinite(value));
            assertTrue(values[LaunchArrival.SCALE_X] > .95f && values[LaunchArrival.SCALE_X] < 1.4f);
            assertTrue(values[LaunchArrival.SCALE_Y] > .95f && values[LaunchArrival.SCALE_Y] < 1.4f);
            assertTrue(values[LaunchArrival.BLUR] >= 0f && values[LaunchArrival.BLUR] <= LaunchArrival.MAX_BLUR_SOURCE_PIXELS);
            assertTrue(values[LaunchArrival.BLUR] <= previousBlur + .0001f);
            assertTrue(values[LaunchArrival.CHROMATIC] >= 0f && values[LaunchArrival.CHROMATIC] <= 9f);
            previousBlur = values[LaunchArrival.BLUR];
        }
    }

    @Test public void completionAndInactiveOrInvalidInputAreExactlyUnmodified() {
        for (float opening : new float[]{-1f, 1f, 2f, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertArrayEquals(IDENTITY, pose(opening), 0f);
        }
        float[] almost = pose(.99999f);
        assertEquals(1f, almost[LaunchArrival.SCALE_X], .00001f);
        assertEquals(1f, almost[LaunchArrival.SCALE_Y], .00001f);
        assertEquals(0f, almost[LaunchArrival.BLUR], .0001f);
        assertEquals(0f, almost[LaunchArrival.CHROMATIC], .0001f);
    }


    private static float[] pose(float opening) {
        float[] result = new float[LaunchArrival.POSE_FLOATS];
        LaunchArrival.writePose(opening, result);
        return result;
    }
}
