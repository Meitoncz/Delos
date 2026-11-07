package com.limelight.binding.video;

/**
 * Matches the display timestamps the platform reports a few frames after a swap (GPU done, SurfaceFlinger latch, display
 * present) with the frames the SGSR output drew, and turns them into the {@link LatencyStages} stages measured from the
 * moment each frame became available. A small preallocated ring remembers the frames waiting for their timestamps, so
 * nothing is allocated per frame. GL thread only.
 */
final class DisplayTimestampTracker {
    /** Reads a frame's timestamps. */
    interface Source {
        /**
         * Fills {@code out} with the GPU-done, latch and present times in CLOCK_MONOTONIC ns (-1 for one that is not
         * available). Returns 0 when all are known, 1 while one is still pending and -1 when the frame is gone.
         */
        int query(long frameId, long[] out);
    }

    private static final int RING = 16;
    // The platform answers a few frames late, one frame is resolved per swap on average: a few per poll is plenty
    private static final int MAX_PER_POLL = 4;
    private final long[] ids = new long[RING];
    private final long[] availableNs = new long[RING];
    private final long[] values = new long[3];
    private int head, count;

    /** Remembers a swapped frame; when the ring is full the oldest is forgotten. */
    void add(long frameId, long frameAvailableNs) {
        if (count == RING) {
            head = (head + 1) % RING;
            count--;
        }
        int tail = (head + count) % RING;
        ids[tail] = frameId;
        availableNs[tail] = frameAvailableNs;
        count++;
    }

    /** Frames still waiting for their timestamps. */
    int waiting() {
        return count;
    }

    /**
     * Resolves the oldest frames whose timestamps are ready, in order, and records their stages. A frame the platform
     * no longer remembers is dropped. Returns how many frames were recorded.
     */
    int poll(Source source, LatencyStages stages) {
        int recorded = 0;
        for (int i = 0; i < MAX_PER_POLL && count > 0; i++) {
            int status = source.query(ids[head], values);
            if (status == 1) break;
            if (status == 0) {
                long available = availableNs[head];
                if (values[0] > 0) stages.record(LatencyStages.AVAILABLE_TO_GPU, values[0] - available);
                if (values[1] > 0) stages.record(LatencyStages.AVAILABLE_TO_LATCH, values[1] - available);
                if (values[2] > 0) stages.record(LatencyStages.AVAILABLE_TO_PRESENT, values[2] - available);
                recorded++;
            }
            head = (head + 1) % RING;
            count--;
        }
        return recorded;
    }
}
