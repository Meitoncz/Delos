package com.limelight.binding.video;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts the decoder frames waiting in the SGSR output's SurfaceTexture so the GL thread can skip to the
 * newest one. The frame-available callback runs on its own thread and calls {@link #onFrameAvailable}; the GL
 * thread asks {@link #pending()} when a draw starts, consumes that many frames with one updateTexImage()
 * each (only the last one is drawn) and reports the draw to {@link #recordDraw}, which keeps the statistics
 * window. The callback counts a frame only after the codec has queued it, so the count never exceeds what the
 * SurfaceTexture really holds.
 *
 * Threading: {@code onFrameAvailable} has a single writer thread; everything else runs on the GL thread
 * except {@link #snapshot()}, which any thread may read. Nothing here allocates per frame except the
 * published window (about once a second).
 */
final class FrameQueue {
    /** Tag shared with the Sharpen only meter: the SGSR output stage's diagnostics. */
    static final String TAG = "DelosSharpen";
    // Far above what a BufferQueue holds; a wrapped slot would only make one frame age wrong
    private static final int RING = 64, MASK = RING - 1;
    private static final long WINDOW_NS = 1_000_000_000L;

    private final AtomicInteger arrived = new AtomicInteger();
    private final long[] arrivalNs = new long[RING];
    // GL thread only
    private int consumed;
    private long windowStartNs;
    private int draws, skipped, pendingMax, ageCount;
    private long pendingSum, skippedTotal;
    private double ageSumMs, ageMaxMs;
    private volatile FrameQueueStats snapshot;

    /** Callback thread: a frame was queued by the codec. */
    void onFrameAvailable(long nowNs) {
        int n = arrived.get();
        arrivalNs[n & MASK] = nowNs;
        arrived.set(n + 1);
    }

    /** Frames queued and not yet consumed. */
    int pending() {
        return arrived.get() - consumed;
    }

    /** Marks the oldest waiting frame as consumed and returns when it became available. */
    long consumeOne() {
        return arrivalNs[consumed++ & MASK];
    }

    /** When the most recently consumed frame became available. */
    long lastConsumedArrivalNs() {
        return arrivalNs[(consumed - 1) & MASK];
    }

    /**
     * Records one draw that started at {@code nowNs} after consuming {@code pending} frames (so pending - 1 were
     * skipped) whose newest frame became available {@code ageNs} earlier. Returns the finished window when this
     * draw closed one, otherwise null.
     */
    FrameQueueStats recordDraw(long nowNs, int pending, long ageNs) {
        if (windowStartNs == 0) windowStartNs = nowNs;
        draws++;
        skipped += pending - 1;
        skippedTotal += pending - 1;
        pendingSum += pending;
        pendingMax = Math.max(pendingMax, pending);
        if (ageNs >= 0) {
            double ms = ageNs / 1_000_000.0;
            ageSumMs += ms;
            ageMaxMs = Math.max(ageMaxMs, ms);
            ageCount++;
        }
        if (nowNs - windowStartNs < WINDOW_NS) return null;
        FrameQueueStats stats = new FrameQueueStats(draws, skipped, (float) pendingSum / draws, pendingMax,
                ageCount > 0 ? (float) (ageSumMs / ageCount) : -1f, ageCount > 0 ? (float) ageMaxMs : -1f,
                skippedTotal, (nowNs - windowStartNs) / 1e9f, nowNs);
        snapshot = stats;
        windowStartNs = nowNs;
        draws = skipped = pendingMax = ageCount = 0;
        pendingSum = 0;
        ageSumMs = ageMaxMs = 0;
        return stats;
    }

    /** The last finished window, null before the first. Readable from any thread. */
    FrameQueueStats snapshot() {
        return snapshot;
    }
}
