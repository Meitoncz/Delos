package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PyroWavePresentInfoTest {
    @Test
    public void namesTheVulkanEnumsOfTheSwapchain() {
        assertEquals("present=MAILBOX images=4 preTransform=identity", PyroWavePresentInfo.describe(1, 4, 0x1));
        assertEquals("present=FIFO images=3 preTransform=rotate90", PyroWavePresentInfo.describe(2, 3, 0x2));
    }

    @Test
    public void readsTheValuesFromTheStatisticsArray() {
        long[] stats = new long[PyroWaveRenderer.STAT_COUNT];
        stats[PyroWaveRenderer.STAT_PRESENT_MODE] = 2;
        stats[PyroWaveRenderer.STAT_SWAPCHAIN_IMAGES] = 3;
        stats[PyroWaveRenderer.STAT_PRE_TRANSFORM] = 0x100;
        assertEquals("present=FIFO images=3 preTransform=inherit", PyroWavePresentInfo.describe(stats));
    }

    @Test
    public void beforeASwapchainExistsItSaysSo() {
        assertEquals("present=none", PyroWavePresentInfo.describe(-1, -1, -1));
    }

    @Test
    public void unknownValuesKeepTheirNumber() {
        assertEquals("present=mode9 images=2 preTransform=other(64)", PyroWavePresentInfo.describe(9, 2, 64));
    }
}
