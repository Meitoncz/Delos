package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FrameQueueTest {
    private static final long MS = 1_000_000L;

    @Test
    public void everyQueuedFrameCountsAsPendingUntilConsumed() {
        FrameQueue queue = new FrameQueue();
        assertEquals(0, queue.pending());
        queue.onFrameAvailable(10 * MS);
        queue.onFrameAvailable(18 * MS);
        queue.onFrameAvailable(26 * MS);
        assertEquals(3, queue.pending());
        queue.consumeOne();
        assertEquals(2, queue.pending());
    }

    @Test
    public void consumingAllPendingFramesLeavesTheNewestArrivalTimeForTheAge() {
        FrameQueue queue = new FrameQueue();
        queue.onFrameAvailable(10 * MS);
        queue.onFrameAvailable(18 * MS);
        queue.onFrameAvailable(26 * MS);
        int pending = queue.pending();
        long first = 0;
        for (int i = 0; i < pending; i++) {
            long arrival = queue.consumeOne();
            if (i == 0) first = arrival;
        }
        assertEquals(10 * MS, first);
        assertEquals(26 * MS, queue.lastConsumedArrivalNs());
        assertEquals(0, queue.pending());
    }

    @Test
    public void aFrameQueuedWhileDrawingStaysPendingForTheNextDraw() {
        FrameQueue queue = new FrameQueue();
        queue.onFrameAvailable(10 * MS);
        queue.onFrameAvailable(18 * MS);
        int pending = queue.pending();
        queue.consumeOne();
        queue.onFrameAvailable(30 * MS);
        queue.consumeOne();
        assertEquals(2, pending);
        assertEquals(1, queue.pending());
        assertEquals(30 * MS, queue.consumeOne());
    }

    @Test
    public void aFailedUpdateLeavesTheFrameCountedSoNothingIsLeftBehind() {
        FrameQueue queue = new FrameQueue();
        queue.onFrameAvailable(1 * MS);
        queue.onFrameAvailable(2 * MS);
        // The first update succeeds, the second throws before consumeOne()
        queue.consumeOne();
        assertEquals(1, queue.pending());
    }

    @Test
    public void theWindowClosesAfterASecondWithAveragesMaximaAndTheSkippedCount() {
        FrameQueue queue = new FrameQueue();
        assertNull(queue.recordDraw(1_000 * MS, 1, 1 * MS));
        assertNull(queue.recordDraw(1_100 * MS, 3, 5 * MS));
        assertNull(queue.recordDraw(1_500 * MS, 2, 3 * MS));
        FrameQueueStats stats = queue.recordDraw(2_000 * MS, 1, 3 * MS);
        assertNotNull(stats);
        assertEquals(4, stats.draws);
        assertEquals(3, stats.skipped);
        assertEquals(3, stats.skippedTotal);
        assertEquals(1.75f, stats.pendingAvg, 1e-5f);
        assertEquals(3, stats.pendingMax);
        assertEquals(3f, stats.ageAvgMs, 1e-5f);
        assertEquals(5f, stats.ageMaxMs, 1e-5f);
        assertEquals(1f, stats.windowSeconds, 1e-5f);
        assertEquals(3f, stats.skippedPerSecond(), 1e-5f);
        assertEquals(stats, queue.snapshot());
    }

    @Test
    public void aNewWindowStartsEmptyButTheTotalKeepsCounting() {
        FrameQueue queue = new FrameQueue();
        queue.recordDraw(1_000 * MS, 3, 1 * MS);
        queue.recordDraw(2_000 * MS, 1, 1 * MS);
        queue.recordDraw(2_500 * MS, 2, 1 * MS);
        FrameQueueStats stats = queue.recordDraw(3_000 * MS, 1, 1 * MS);
        assertNotNull(stats);
        assertEquals(2, stats.draws);
        assertEquals(1, stats.skipped);
        assertEquals(3, stats.skippedTotal);
    }

    @Test
    public void aWindowWithoutAgeSamplesReportsNoAge() {
        FrameQueue queue = new FrameQueue();
        queue.recordDraw(1_000 * MS, 1, -1);
        FrameQueueStats stats = queue.recordDraw(2_000 * MS, 1, -1);
        assertNotNull(stats);
        assertEquals(-1f, stats.ageAvgMs, 0f);
        assertEquals(-1f, stats.ageMaxMs, 0f);
    }

    @Test
    public void aStaleWindowIsNotShown() {
        FrameQueueStats stats = new FrameQueueStats(1, 0, 1f, 1, 1f, 1f, 0, 1f, 5_000 * MS);
        assertTrue(stats.isFresh(7_000 * MS));
        assertFalse(stats.isFresh(8_100 * MS));
    }
}
