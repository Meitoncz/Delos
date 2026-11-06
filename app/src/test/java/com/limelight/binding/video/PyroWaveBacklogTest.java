package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PyroWaveBacklogTest {
    @Test
    public void averagesDivideByTheMatchingFrameCount() {
        long[] stats = new long[PyroWaveRenderer.STAT_COUNT];
        stats[PyroWaveRenderer.STAT_RECEIVED_FRAMES] = 100;
        stats[PyroWaveRenderer.STAT_RENDERED_FRAMES] = 50;
        stats[PyroWaveRenderer.STAT_STALL_TOTAL_US] = 500_000;
        stats[PyroWaveRenderer.STAT_STALL_MAX_US] = 40_000;
        stats[PyroWaveRenderer.STAT_SUBMIT_AGE_FRAMES] = 80;
        stats[PyroWaveRenderer.STAT_SUBMIT_AGE_TOTAL_US] = 160_000;
        stats[PyroWaveRenderer.STAT_SUBMIT_AGE_MAX_US] = 9_000;
        stats[PyroWaveRenderer.STAT_PRESENT_AGE_FRAMES] = 50;
        stats[PyroWaveRenderer.STAT_PRESENT_AGE_TOTAL_US] = 500_000;
        stats[PyroWaveRenderer.STAT_PRESENT_AGE_MAX_US] = 25_000;
        stats[PyroWaveRenderer.STAT_FENCE_WAIT_TOTAL_US] = 100_000;
        stats[PyroWaveRenderer.STAT_ACQUIRE_WAIT_TOTAL_US] = 50_000;
        stats[PyroWaveRenderer.STAT_SUBMIT_PRESENT_TOTAL_US] = 25_000;
        PyroWaveBacklog b = new PyroWaveBacklog(stats, 1f);
        assertEquals(5f, b.stallAvgMs, 1e-4f);
        assertEquals(40f, b.stallMaxMs, 1e-4f);
        assertEquals(2f, b.submitAgeAvgMs, 1e-4f);
        assertEquals(9f, b.submitAgeMaxMs, 1e-4f);
        assertEquals(10f, b.presentAgeAvgMs, 1e-4f);
        assertEquals(25f, b.presentAgeMaxMs, 1e-4f);
        assertEquals(2f, b.fenceWaitAvgMs, 1e-4f);
        assertEquals(1f, b.acquireWaitAvgMs, 1e-4f);
        assertEquals(0.5f, b.submitPresentAvgMs, 1e-4f);
    }

    @Test
    public void ratesUseTheWindowLengthAndEmptyWindowsAreZero() {
        long[] stats = new long[PyroWaveRenderer.STAT_COUNT];
        PyroWaveBacklog empty = new PyroWaveBacklog(stats, 1f);
        assertEquals(0f, empty.stallAvgMs, 0f);
        assertEquals(0f, empty.presentAgeAvgMs, 0f);
        stats[PyroWaveRenderer.STAT_PARTIAL_FRAMES] = 6;
        stats[PyroWaveRenderer.STAT_SKIPPED_FRAMES] = 3;
        PyroWaveBacklog b = new PyroWaveBacklog(stats, 2f);
        assertEquals(3f, b.partialPerSecond, 1e-4f);
        assertEquals(1.5f, b.skippedPerSecond, 1e-4f);
    }
}
