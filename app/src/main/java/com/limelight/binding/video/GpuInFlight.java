package com.limelight.binding.video;

/**
 * Bounds how many frames of GPU work the SGSR output has in flight. Submitting is much cheaper than executing (SGSR 2
 * at a 4K target costs the CPU 4-6 ms and the Adreno about 10 ms), so without a bound the commands pile up inside
 * the driver and a frame is displayed 100 ms or more after it was drawn. Every submitted frame gets a fence; before a
 * new draw the oldest fences are polled without waiting, and when {@value #MAX_IN_FLIGHT} frames are still running the
 * draw is skipped (the caller keeps the newest frame and tries again shortly). Two frames in flight let the CPU record
 * the next frame while the GPU executes the previous one, which a bound of one would forbid.
 *
 * A fence that never signals (a hung GPU, a lost fence) must not freeze the picture: after {@value #STUCK_MS} ms of
 * continuous busy the bookkeeping is cleared and drawing resumes.
 *
 * The handle is whatever the fence source uses (a sync-file descriptor, a frame sequence number). GL thread only; no
 * allocation after construction.
 */
final class GpuInFlight {
    static final int MAX_IN_FLIGHT = 2;
    static final long STUCK_MS = 250;
    private static final long STUCK_NS = STUCK_MS * 1_000_000L;
    private static final int RING = 8;

    /** Reads and releases a fence. */
    interface Fences {
        /** -1 while the GPU work is still running, else the signal time in CLOCK_MONOTONIC ns (0 when unknown). */
        long poll(long handle);

        /** Frees the handle (closes the descriptor); called exactly once per added handle. */
        void release(long handle);
    }

    /** Receives each finished frame's GPU execution time in nanoseconds. */
    interface WorkSink {
        void onGpuWork(long workNs);
    }

    private final long[] handles = new long[RING];
    private final long[] submitNs = new long[RING];
    private int head, count;
    private long previousDoneNs;
    private long busySinceNs;

    /** Frames whose GPU work has not been seen finished. */
    int inFlight() {
        return count;
    }

    /** Remembers a submitted frame; when the ring is full (it cannot be, with the bound) the oldest is released. */
    void add(long handle, long submittedNs, Fences fences) {
        if (count == RING) {
            fences.release(handles[head]);
            head = (head + 1) % RING;
            count--;
        }
        int tail = (head + count) % RING;
        handles[tail] = handle;
        submitNs[tail] = submittedNs;
        count++;
    }

    /**
     * Collects the frames that have finished and tells whether the next draw has to be skipped because the GPU still
     * has {@value #MAX_IN_FLIGHT} frames of work. The execution time of a finished frame is measured from the moment the
     * GPU could start it (its submission, or the end of the previous frame if that was later) to its fence's signal
     * time, so waiting behind the previous frame is not counted as work. When no signal time is available the
     * moment of the poll is used, which can only overestimate.
     */
    boolean shouldSkip(Fences fences, long nowNs, WorkSink sink) {
        reap(fences, nowNs, sink);
        if (count < MAX_IN_FLIGHT) {
            busySinceNs = 0;
            return false;
        }
        if (busySinceNs == 0) busySinceNs = nowNs;
        if (nowNs - busySinceNs > STUCK_NS) {
            clear(fences);
            busySinceNs = 0;
            return false;
        }
        return true;
    }

    private void reap(Fences fences, long nowNs, WorkSink sink) {
        while (count > 0) {
            long signalled = fences.poll(handles[head]);
            if (signalled < 0) break;
            long done = signalled > 0 ? signalled : nowNs;
            long start = Math.max(previousDoneNs, submitNs[head]);
            if (done >= start) sink.onGpuWork(done - start);
            previousDoneNs = Math.max(previousDoneNs, done);
            fences.release(handles[head]);
            head = (head + 1) % RING;
            count--;
        }
    }

    /** Releases every remembered fence (teardown, or giving up on one that never signals). */
    void clear(Fences fences) {
        while (count > 0) {
            fences.release(handles[head]);
            head = (head + 1) % RING;
            count--;
        }
    }
}
