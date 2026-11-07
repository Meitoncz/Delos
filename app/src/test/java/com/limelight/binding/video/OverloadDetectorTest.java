package com.limelight.binding.video;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OverloadDetectorTest {
    /** A one-second window: frames delivered, frames drawn, frames dropped for a busy GPU, GPU time per frame. */
    private static FrameQueueStats window(int arrived, int draws, int busySkipped, float gpuAvgMs) {
        return new FrameQueueStats(draws, 0, busySkipped, 1f, 1, 1f, 2f, gpuAvgMs, gpuAvgMs, 0, busySkipped, arrived, 1f, 0);
    }

    @Test
    public void aGpuTimeAboveNinetyPercentOfTheFramePeriodIsOverloaded() {
        // 120 frames/s: period 8.33 ms, 90 % is 7.5 ms
        assertTrue(OverloadDetector.isOverloaded(window(120, 100, 0, 10f)));
        assertFalse(OverloadDetector.isOverloaded(window(120, 120, 0, 7.4f)));
    }

    @Test
    public void theSameGpuTimeIsFineAtALowerFrameRate() {
        assertFalse(OverloadDetector.isOverloaded(window(60, 60, 0, 10f)));
    }

    @Test
    public void manyDroppedFramesAreOverloadEvenWithoutAGpuTime() {
        assertTrue(OverloadDetector.isOverloaded(window(120, 80, 30, -1f)));
        assertFalse(OverloadDetector.isOverloaded(window(120, 110, 10, -1f)));
    }

    @Test
    public void twoOverloadedWindowsInARowReportOnce() {
        OverloadDetector detector = new OverloadDetector();
        assertFalse(detector.onWindow(window(120, 90, 30, 10f)));
        assertTrue(detector.onWindow(window(120, 90, 30, 10f)));
        // The streak starts over after a report
        assertFalse(detector.onWindow(window(120, 90, 30, 10f)));
        assertTrue(detector.onWindow(window(120, 90, 30, 10f)));
    }

    @Test
    public void aHealthyWindowBreaksTheStreak() {
        OverloadDetector detector = new OverloadDetector();
        assertFalse(detector.onWindow(window(120, 90, 30, 10f)));
        assertFalse(detector.onWindow(window(120, 120, 0, 5f)));
        assertFalse(detector.onWindow(window(120, 90, 30, 10f)));
    }

    @Test
    public void aWindowWithFewFramesNeitherCountsNorBreaksTheStreak() {
        OverloadDetector detector = new OverloadDetector();
        assertFalse(detector.onWindow(window(120, 90, 30, 10f)));
        assertFalse(detector.onWindow(window(5, 5, 0, 1f)));
        assertTrue(detector.onWindow(window(120, 90, 30, 10f)));
    }

    @Test
    public void resetForgetsTheStreak() {
        OverloadDetector detector = new OverloadDetector();
        assertFalse(detector.onWindow(window(120, 90, 30, 10f)));
        detector.reset();
        assertFalse(detector.onWindow(window(120, 90, 30, 10f)));
    }
}
