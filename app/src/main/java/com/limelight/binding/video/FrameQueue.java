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
 * The window also counts the frames dropped because the GPU still had too much work in flight
 * ({@link #recordBusySkip}) and the GPU time per frame ({@link #recordGpuWork}).
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
    private int windowArrivedStart;
    private int draws, skipped, busySkipped, pendingMax, ageCount, gpuCount;
    private long pendingSum, skippedTotal, busySkippedTotal;
    private double ageSumMs, ageMaxMs, gpuSumMs, gpuMaxMs;
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
        if (windowStartNs == 0) startWindow(nowNs);
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
        return closeWindowIfDue(nowNs);
    }

    /** Frames consumed together with a newer one and not drawn, when no draw follows (the GPU is busy). */
    void addCoalesced(int frames) {
        skipped += frames;
        skippedTotal += frames;
    }

    /**
     * A frame that was held back because the GPU was busy has been replaced by a newer one and will never be drawn.
     * Returns the finished window when this closed one, otherwise null.
     */
    FrameQueueStats recordBusySkip(long nowNs) {
        if (windowStartNs == 0) startWindow(nowNs);
        busySkipped++;
        busySkippedTotal++;
        return closeWindowIfDue(nowNs);
    }

    /** One frame's GPU execution time in nanoseconds (from its fences, a few frames after the draw). */
    void recordGpuWork(long ns) {
        double ms = ns / 1_000_000.0;
        gpuSumMs += ms;
        gpuMaxMs = Math.max(gpuMaxMs, ms);
        gpuCount++;
    }

    private void startWindow(long nowNs) {
        windowStartNs = nowNs;
        windowArrivedStart = arrived.get();
    }

    private FrameQueueStats closeWindowIfDue(long nowNs) {
        if (nowNs - windowStartNs < WINDOW_NS) return null;
        int arrivedNow = arrived.get();
        FrameQueueStats stats = new FrameQueueStats(draws, skipped, busySkipped, draws > 0 ? (float) pendingSum / draws : 0f,
                pendingMax, ageCount > 0 ? (float) (ageSumMs / ageCount) : -1f, ageCount > 0 ? (float) ageMaxMs : -1f,
                gpuCount > 0 ? (float) (gpuSumMs / gpuCount) : -1f, gpuCount > 0 ? (float) gpuMaxMs : -1f,
                skippedTotal, busySkippedTotal, arrivedNow - windowArrivedStart, (nowNs - windowStartNs) / 1e9f, nowNs);
        snapshot = stats;
        startWindow(nowNs);
        draws = skipped = busySkipped = pendingMax = ageCount = gpuCount = 0;
        pendingSum = 0;
        ageSumMs = ageMaxMs = gpuSumMs = gpuMaxMs = 0;
        return stats;
    }

    /** The last finished window, null before the first. Readable from any thread. */
    FrameQueueStats snapshot() {
        return snapshot;
    }
}
