package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SharpenCostTest {
    @Test
    public void pyroWaveWindowAveragesOverTheSharpenedFramesOnly() {
        long[] stats = new long[PyroWaveRenderer.STAT_COUNT];
        stats[PyroWaveRenderer.STAT_RENDERED_FRAMES] = 120;
        stats[PyroWaveRenderer.STAT_SHARPEN_FRAMES] = 100;
        stats[PyroWaveRenderer.STAT_SHARPEN_TOTAL_US] = 40_000;
        stats[PyroWaveRenderer.STAT_SHARPEN_MAX_US] = 900;
        stats[PyroWaveRenderer.STAT_SHARPEN_PREPARE_TOTAL_US] = 25_000;
        stats[PyroWaveRenderer.STAT_SHARPEN_PREPARE_MAX_US] = 600;
        SharpenCost cost = SharpenCost.fromPyroWave(stats);
        assertTrue(cost.gpuTimed);
        assertEquals(0.4f, cost.passAvgMs, 1e-5f);
        assertEquals(0.9f, cost.passMaxMs, 1e-5f);
        assertEquals(0.25f, cost.prepareAvgMs, 1e-5f);
        assertEquals(0.6f, cost.prepareMaxMs, 1e-5f);
        assertEquals(100, cost.samples);
    }

    @Test
    public void aWindowWithoutSharpenedFramesHasNoCost() {
        assertNull(SharpenCost.fromPyroWave(new long[PyroWaveRenderer.STAT_COUNT]));
    }
}
