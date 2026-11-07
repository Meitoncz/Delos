package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FlowGovernorTest {
    private static FrameQueueStats window(int arrived, float gpuAvgMs) {
        return new FrameQueueStats(arrived, 0, 0, 1f, 1, 1f, 2f, gpuAvgMs, gpuAvgMs, 0, 0, arrived, 1f, 0);
    }

    @Test
    public void nothingChangesWhileTheGpuKeepsUp() {
        FlowGovernor governor = new FlowGovernor();
        // 120 frames/s, 5 ms is 60 % of the 8.33 ms period
        assertFalse(governor.onWindow(window(120, 5f)));
        assertFalse(governor.isReduced());
        for (int i = 0; i < 6; i++) assertFalse(governor.reuseFlowThisFrame());
    }

    @Test
    public void aboveSeventyPercentEverySecondFrameReusesTheFlow() {
        FlowGovernor governor = new FlowGovernor();
        assertTrue(governor.onWindow(window(120, 6.5f)));
        assertTrue(governor.isReduced());
        boolean[] expected = {false, true, false, true, false, true};
        for (boolean reuse : expected) assertEquals(reuse, governor.reuseFlowThisFrame());
    }

    @Test
    public void itStaysReducedUntilTheLoadFallsBelowFortyFivePercent() {
        FlowGovernor governor = new FlowGovernor();
        governor.onWindow(window(120, 7f));
        // The reduced mode lowers the figure to about 60 %: still reduced
        assertFalse(governor.onWindow(window(120, 5f)));
        assertTrue(governor.isReduced());
        assertTrue(governor.onWindow(window(120, 3.5f)));
        assertFalse(governor.isReduced());
        assertFalse(governor.reuseFlowThisFrame());
    }

    @Test
    public void theSameGpuTimeIsFineAtAHalfTheFrameRate() {
        FlowGovernor governor = new FlowGovernor();
        assertFalse(governor.onWindow(window(60, 9f)));
    }

    @Test
    public void windowsWithoutAGpuTimeOrWithFewFramesAreIgnored() {
        FlowGovernor governor = new FlowGovernor();
        assertFalse(governor.onWindow(window(120, -1f)));
        assertFalse(governor.onWindow(window(5, 9f)));
        assertFalse(governor.isReduced());
    }

    @Test
    public void resetGoesBackToFullQuality() {
        FlowGovernor governor = new FlowGovernor();
        governor.onWindow(window(120, 8f));
        governor.reset();
        assertFalse(governor.isReduced());
        assertFalse(governor.reuseFlowThisFrame());
    }
}
