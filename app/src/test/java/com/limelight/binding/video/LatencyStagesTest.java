package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LatencyStagesTest {
    private static final long MS = 1_000_000L;

    @Test
    public void aStageReportsAverageNearestRankPercentileAndMaximum() {
        LatencyStages stages = new LatencyStages();
        // 1..100 ms: average 50.5, nearest-rank 95th percentile 95, maximum 100
        for (int i = 1; i <= 100; i++) stages.record(LatencyStages.PACING, i * MS);
        StageSummary pacing = stages.summarize().stage(LatencyStages.PACING);
        assertEquals(100, pacing.count);
        assertEquals(50.5f, pacing.avgMs, 1e-3f);
        assertEquals(95f, pacing.p95Ms, 1e-3f);
        assertEquals(100f, pacing.maxMs, 1e-3f);
    }

    @Test
    public void stagesWithoutSamplesAreAbsent() {
        LatencyStages stages = new LatencyStages();
        stages.record(LatencyStages.DECODE, 2 * MS);
        LatencyStages.Summary summary = stages.summarize();
        assertNotNull(summary.stage(LatencyStages.DECODE));
        assertNull(summary.stage(LatencyStages.AVAILABLE_TO_PRESENT));
    }

    @Test
    public void unavailableNegativeTimestampsAreIgnored() {
        LatencyStages stages = new LatencyStages();
        stages.record(LatencyStages.AVAILABLE_TO_PRESENT, -1);
        stages.record(LatencyStages.AVAILABLE_TO_PRESENT, 5 * MS);
        StageSummary present = stages.summarize().stage(LatencyStages.AVAILABLE_TO_PRESENT);
        assertEquals(1, present.count);
        assertEquals(5f, present.avgMs, 1e-3f);
    }

    @Test
    public void summarisingStartsANewWindow() {
        LatencyStages stages = new LatencyStages();
        stages.record(LatencyStages.DRAW, 9 * MS);
        LatencyStages.Summary first = stages.summarize();
        assertSame(first, stages.lastSummary());
        assertNull(stages.summarize().stage(LatencyStages.DRAW));
        stages.record(LatencyStages.DRAW, 1 * MS);
        StageSummary draw = stages.summarize().stage(LatencyStages.DRAW);
        assertEquals(1f, draw.avgMs, 1e-3f);
        assertEquals(1f, draw.maxMs, 1e-3f);
    }

    @Test
    public void moreSamplesThanTheRingHoldsKeepExactSumsAndTheNewestForThePercentile() {
        LatencyStages stages = new LatencyStages();
        int total = 1000;
        for (int i = 1; i <= total; i++) stages.record(LatencyStages.DECODE, i * MS);
        StageSummary decode = stages.summarize().stage(LatencyStages.DECODE);
        assertEquals(total, decode.count);
        assertEquals(500.5f, decode.avgMs, 1e-2f);
        assertEquals(1000f, decode.maxMs, 1e-3f);
        // The ring keeps the newest 512 samples (489..1000): nearest rank 95 percent of them is the 487th
        assertEquals(975f, decode.p95Ms, 1e-3f);
    }

    @Test
    public void nearestRankIndexStaysInsideTheArray() {
        assertEquals(0, LatencyStages.percentileIndex(1, 0.95));
        assertEquals(18, LatencyStages.percentileIndex(20, 0.95));
        assertEquals(94, LatencyStages.percentileIndex(100, 0.95));
        assertEquals(0, LatencyStages.percentileIndex(0, 0.95));
    }

    @Test
    public void theLogLineNamesEveryStageThatHasSamples() {
        LatencyStages stages = new LatencyStages();
        stages.record(LatencyStages.PACING, 4 * MS);
        stages.record(LatencyStages.AVAILABLE_TO_PRESENT, 12 * MS);
        String line = stages.summarize().describe();
        assertTrue(line, line.contains("pacing 4.00/4.00/4.00 ms (n=1)"));
        assertTrue(line, line.contains("avail->present 12.00/12.00/12.00 ms (n=1)"));
        assertEquals("no samples", new LatencyStages().summarize().describe());
    }
}
