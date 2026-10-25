package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FrameTimesTest {
    private static final long MS = 1_000_000L;

    @Test
    public void slowestOnePercentSetsTheLow() {
        FrameTimes times = new FrameTimes();
        long now = 1;
        times.add(now);
        // 990 frames at 8 ms and 10 hitches of 50 ms: the slowest 1% are the hitches
        for (int i = 0; i < 1000; i++) {
            now += (i % 100 == 0) ? 50 * MS : 8 * MS;
            times.add(now);
        }
        assertEquals(20f, times.onePercentLowFps(), 0.01f);
    }

    @Test
    public void stillPicturesAndFewFramesAreNoHitch() {
        FrameTimes times = new FrameTimes();
        assertEquals(-1f, times.onePercentLowFps(), 0f);
        long now = 1;
        times.add(now);
        for (int i = 0; i < 200; i++) {
            now += (i == 100) ? 3000 * MS : 10 * MS;
            times.add(now);
        }
        assertEquals(100f, times.onePercentLowFps(), 0.01f);
    }
}
