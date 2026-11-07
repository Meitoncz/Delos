package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WorkHintMathTest {
    @Test
    public void targetIsSixtyPercentOfTheFramePeriod() {
        assertEquals(5_000_000L, WorkHintMath.targetNanos(120));
        assertEquals(10_000_000L, WorkHintMath.targetNanos(60));
        assertEquals(6_666_667L, WorkHintMath.targetNanos(90));
        assertEquals(3_000_000L, WorkHintMath.targetNanos(200));
    }

    @Test
    public void absurdFrameRatesStayInsideTheClamp() {
        assertEquals(WorkHintMath.targetNanos(1), WorkHintMath.targetNanos(0));
        assertEquals(WorkHintMath.targetNanos(1), WorkHintMath.targetNanos(-30));
        assertEquals(WorkHintMath.targetNanos(1000), WorkHintMath.targetNanos(100_000));
        assertEquals(600_000L, WorkHintMath.targetNanos(1000));
    }

    @Test
    public void windowReportsTheMeanOnlyWhenFull() {
        WorkHintMath.Window window = new WorkHintMath.Window(4);
        assertEquals(0, window.add(2_000_000));
        assertEquals(0, window.add(4_000_000));
        assertEquals(0, window.add(6_000_000));
        assertEquals(5_000_000L, window.add(8_000_000));
    }

    @Test
    public void windowStartsOverAfterEachReport() {
        WorkHintMath.Window window = new WorkHintMath.Window(2);
        window.add(1_000);
        assertEquals(2_000L, window.add(3_000));
        window.add(10_000);
        assertEquals(20_000L, window.add(30_000));
    }

    @Test
    public void aReportIsNeverZeroAndNeverNegative() {
        WorkHintMath.Window window = new WorkHintMath.Window(2);
        window.add(0);
        assertEquals(1L, window.add(0));
        window.add(-5_000);
        assertEquals(500L, window.add(1_000));
    }

    @Test
    public void resetDropsAPartialWindow() {
        WorkHintMath.Window window = new WorkHintMath.Window(2);
        window.add(9_000_000);
        window.reset();
        window.add(1_000);
        assertEquals(2_000L, window.add(3_000));
    }

    @Test
    public void defaultReportIntervalIsFourFrames() {
        WorkHintMath.Window window = new WorkHintMath.Window(WorkHintMath.REPORT_EVERY_FRAMES);
        for (int i = 0; i < WorkHintMath.REPORT_EVERY_FRAMES - 1; i++) assertEquals(0, window.add(1_000));
        assertEquals(1_000L, window.add(1_000));
    }
}
