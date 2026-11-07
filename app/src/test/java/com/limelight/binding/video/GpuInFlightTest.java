package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GpuInFlightTest {
    private static final long MS = 1_000_000L;

    /** Fences that signal when the test says so; records every release. */
    private static final class FakeFences implements GpuInFlight.Fences {
        final Map<Long, Long> signalled = new HashMap<>();
        final List<Long> released = new ArrayList<>();

        void signal(long handle, long timeNs) { signalled.put(handle, timeNs); }

        @Override public long poll(long handle) { return signalled.containsKey(handle) ? signalled.get(handle) : -1; }
        @Override public void release(long handle) { released.add(handle); }
    }

    private static final class Work implements GpuInFlight.WorkSink {
        final List<Long> values = new ArrayList<>();
        @Override public void onGpuWork(long workNs) { values.add(workNs); }
    }

    @Test
    public void oneFrameInFlightLetsTheNextOneBeSubmittedForPipelining() {
        GpuInFlight gpu = new GpuInFlight();
        FakeFences fences = new FakeFences();
        gpu.add(1, 0, fences);
        assertFalse(gpu.shouldSkip(fences, 1 * MS, new Work()));
    }

    @Test
    public void withTheMaximumInFlightTheDrawIsSkipped() {
        GpuInFlight gpu = new GpuInFlight();
        FakeFences fences = new FakeFences();
        gpu.add(1, 0, fences);
        gpu.add(2, 8 * MS, fences);
        assertTrue(gpu.shouldSkip(fences, 9 * MS, new Work()));
        assertEquals(2, gpu.inFlight());
    }

    @Test
    public void aFinishedFrameFreesASlotAndItsFenceIsReleasedOnce() {
        GpuInFlight gpu = new GpuInFlight();
        FakeFences fences = new FakeFences();
        gpu.add(1, 0, fences);
        gpu.add(2, 8 * MS, fences);
        fences.signal(1, 10 * MS);
        assertFalse(gpu.shouldSkip(fences, 11 * MS, new Work()));
        assertEquals(1, gpu.inFlight());
        assertFalse(gpu.shouldSkip(fences, 12 * MS, new Work()));
        assertEquals(1, fences.released.size());
        assertEquals(Long.valueOf(1), fences.released.get(0));
    }

    @Test
    public void frames_areReapedInOrderEvenIfALaterOneSignalledFirst() {
        GpuInFlight gpu = new GpuInFlight();
        FakeFences fences = new FakeFences();
        gpu.add(1, 0, fences);
        gpu.add(2, 8 * MS, fences);
        fences.signal(2, 20 * MS);
        assertTrue(gpu.shouldSkip(fences, 21 * MS, new Work()));
        assertEquals(2, gpu.inFlight());
    }

    @Test
    public void gpuTimeIsFromTheEndOfThePreviousFrameWhenTheGpuWasStillBusy() {
        GpuInFlight gpu = new GpuInFlight();
        FakeFences fences = new FakeFences();
        Work work = new Work();
        // Frame 1 is submitted at 0 and done at 10 ms; frame 2 is submitted at 4 ms but cannot start before 10 ms
        gpu.add(1, 0, fences);
        gpu.add(2, 4 * MS, fences);
        fences.signal(1, 10 * MS);
        fences.signal(2, 20 * MS);
        gpu.shouldSkip(fences, 25 * MS, work);
        assertEquals(2, work.values.size());
        assertEquals(10 * MS, (long) work.values.get(0));
        assertEquals(10 * MS, (long) work.values.get(1));
    }

    @Test
    public void gpuTimeOfAFrameSubmittedToAnIdleGpuStartsAtItsSubmission() {
        GpuInFlight gpu = new GpuInFlight();
        FakeFences fences = new FakeFences();
        Work work = new Work();
        gpu.add(1, 0, fences);
        fences.signal(1, 3 * MS);
        gpu.shouldSkip(fences, 4 * MS, work);
        gpu.add(2, 50 * MS, fences);
        fences.signal(2, 58 * MS);
        gpu.shouldSkip(fences, 60 * MS, work);
        assertEquals(3 * MS, (long) work.values.get(0));
        assertEquals(8 * MS, (long) work.values.get(1));
    }

    @Test
    public void withoutASignalTimeThePollTimeIsUsedAndOnlyOverestimates() {
        GpuInFlight gpu = new GpuInFlight();
        FakeFences fences = new FakeFences();
        Work work = new Work();
        gpu.add(1, 0, fences);
        fences.signal(1, 0);
        gpu.shouldSkip(fences, 7 * MS, work);
        assertEquals(7 * MS, (long) work.values.get(0));
    }

    @Test
    public void aFenceThatNeverSignalsDoesNotFreezeTheOutput() {
        GpuInFlight gpu = new GpuInFlight();
        FakeFences fences = new FakeFences();
        gpu.add(1, 0, fences);
        gpu.add(2, 1 * MS, fences);
        long now = 10 * MS;
        assertTrue(gpu.shouldSkip(fences, now, new Work()));
        assertTrue(gpu.shouldSkip(fences, now + GpuInFlight.STUCK_MS * MS / 2, new Work()));
        // After the limit it gives up on the fences, releasing both exactly once, and lets a draw through
        assertFalse(gpu.shouldSkip(fences, now + GpuInFlight.STUCK_MS * MS + 1, new Work()));
        assertEquals(0, gpu.inFlight());
        assertEquals(2, fences.released.size());
    }

    @Test
    public void theBusyTimerRestartsOnceTheGpuCatchesUp() {
        GpuInFlight gpu = new GpuInFlight();
        FakeFences fences = new FakeFences();
        gpu.add(1, 0, fences);
        gpu.add(2, 1 * MS, fences);
        assertTrue(gpu.shouldSkip(fences, 10 * MS, new Work()));
        fences.signal(1, 12 * MS);
        assertFalse(gpu.shouldSkip(fences, 13 * MS, new Work()));
        gpu.add(3, 14 * MS, fences);
        // Busy again, but only just: not stuck
        assertTrue(gpu.shouldSkip(fences, 15 * MS + GpuInFlight.STUCK_MS * MS - 1, new Work()));
    }

    @Test
    public void clearReleasesEveryFenceExactlyOnce() {
        GpuInFlight gpu = new GpuInFlight();
        FakeFences fences = new FakeFences();
        for (int i = 1; i <= 3; i++) gpu.add(i, i * MS, fences);
        gpu.clear(fences);
        gpu.clear(fences);
        assertEquals(3, fences.released.size());
        assertEquals(0, gpu.inFlight());
    }

    @Test
    public void aFullRingReleasesTheOldestInsteadOfLeakingIt() {
        GpuInFlight gpu = new GpuInFlight();
        FakeFences fences = new FakeFences();
        for (int i = 1; i <= 9; i++) gpu.add(i, i * MS, fences);
        assertEquals(1, fences.released.size());
        assertEquals(Long.valueOf(1), fences.released.get(0));
        gpu.clear(fences);
        assertEquals(9, fences.released.size());
    }
}
