package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class DisplayTimestampTrackerTest {
    private static final long MS = 1_000_000L;

    /** Answers from a table: frames not in it are pending; a negative-status entry marks a frame as gone. */
    private static final class FakeSource implements DisplayTimestampTracker.Source {
        final Map<Long, long[]> ready = new HashMap<>();
        final Map<Long, Integer> status = new HashMap<>();
        int queries;

        @Override public int query(long frameId, long[] out) {
            queries++;
            Integer forced = status.get(frameId);
            if (forced != null) return forced;
            long[] values = ready.get(frameId);
            if (values == null) return 1;
            System.arraycopy(values, 0, out, 0, 3);
            return 0;
        }
    }

    @Test
    public void aReadyFrameIsRecordedRelativeToWhenItBecameAvailable() {
        DisplayTimestampTracker tracker = new DisplayTimestampTracker();
        FakeSource source = new FakeSource();
        LatencyStages stages = new LatencyStages();
        tracker.add(7, 100 * MS);
        source.ready.put(7L, new long[]{104 * MS, 108 * MS, 112 * MS});
        assertEquals(1, tracker.poll(source, stages));
        LatencyStages.Summary summary = stages.summarize();
        assertEquals(4f, summary.stage(LatencyStages.AVAILABLE_TO_GPU).avgMs, 1e-3f);
        assertEquals(8f, summary.stage(LatencyStages.AVAILABLE_TO_LATCH).avgMs, 1e-3f);
        assertEquals(12f, summary.stage(LatencyStages.AVAILABLE_TO_PRESENT).avgMs, 1e-3f);
        assertEquals(0, tracker.waiting());
    }

    @Test
    public void aPendingFrameHoldsBackTheOnesBehindIt() {
        DisplayTimestampTracker tracker = new DisplayTimestampTracker();
        FakeSource source = new FakeSource();
        LatencyStages stages = new LatencyStages();
        tracker.add(1, 10 * MS);
        tracker.add(2, 20 * MS);
        source.ready.put(2L, new long[]{21 * MS, 22 * MS, 23 * MS});
        assertEquals(0, tracker.poll(source, stages));
        assertEquals(2, tracker.waiting());
        source.ready.put(1L, new long[]{11 * MS, 12 * MS, 13 * MS});
        assertEquals(2, tracker.poll(source, stages));
        assertEquals(0, tracker.waiting());
    }

    @Test
    public void aFrameThePlatformForgotIsDroppedWithoutASample() {
        DisplayTimestampTracker tracker = new DisplayTimestampTracker();
        FakeSource source = new FakeSource();
        LatencyStages stages = new LatencyStages();
        tracker.add(1, 10 * MS);
        source.status.put(1L, -1);
        assertEquals(0, tracker.poll(source, stages));
        assertEquals(0, tracker.waiting());
        assertNull(stages.summarize().stage(LatencyStages.AVAILABLE_TO_PRESENT));
    }

    @Test
    public void aTimestampThePlatformDoesNotGiveLeavesThatStageEmpty() {
        DisplayTimestampTracker tracker = new DisplayTimestampTracker();
        FakeSource source = new FakeSource();
        LatencyStages stages = new LatencyStages();
        tracker.add(3, 50 * MS);
        source.ready.put(3L, new long[]{53 * MS, -1, 60 * MS});
        tracker.poll(source, stages);
        LatencyStages.Summary summary = stages.summarize();
        assertEquals(3f, summary.stage(LatencyStages.AVAILABLE_TO_GPU).avgMs, 1e-3f);
        assertNull(summary.stage(LatencyStages.AVAILABLE_TO_LATCH));
        assertEquals(10f, summary.stage(LatencyStages.AVAILABLE_TO_PRESENT).avgMs, 1e-3f);
    }

    @Test
    public void aFullRingForgetsTheOldestFrame() {
        DisplayTimestampTracker tracker = new DisplayTimestampTracker();
        for (int i = 0; i < 20; i++) tracker.add(i, i * MS);
        assertEquals(16, tracker.waiting());
        FakeSource source = new FakeSource();
        LatencyStages stages = new LatencyStages();
        // Frames 0..3 were forgotten: the oldest remembered one is 4
        source.ready.put(4L, new long[]{10 * MS, 10 * MS, 10 * MS});
        assertEquals(1, tracker.poll(source, stages));
        assertEquals(6f, stages.summarize().stage(LatencyStages.AVAILABLE_TO_PRESENT).avgMs, 1e-3f);
    }

    @Test
    public void onePollNeverAsksForMoreThanAFewFrames() {
        DisplayTimestampTracker tracker = new DisplayTimestampTracker();
        FakeSource source = new FakeSource();
        for (int i = 0; i < 10; i++) {
            tracker.add(i, i * MS);
            source.ready.put((long) i, new long[]{i * MS + 1, i * MS + 2, i * MS + 3});
        }
        assertEquals(4, tracker.poll(source, new LatencyStages()));
        assertEquals(4, source.queries);
        assertEquals(6, tracker.waiting());
    }
}
