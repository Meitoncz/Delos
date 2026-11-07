package com.limelight.binding.video;

import java.util.Locale;

/**
 * What the SGSR output stage saw in its last statistics window (about a second): how many frames were waiting when
 * it started a draw, how old the newest of them already was, how many older frames it skipped because a newer one
 * was ready, how many frames it dropped because the GPU still had too much work in flight, and how long the GPU
 * really took per frame. Immutable; published by {@link FrameQueue}.
 */
public final class FrameQueueStats {
    /** Frames drawn in the window, and the frames skipped (never drawn) because a newer frame was already waiting. */
    public final int draws, skipped;
    /** Frames that waited for a busy GPU and were replaced by a newer one without ever being drawn. */
    public final int busySkipped;
    /** Frames waiting at the start of a draw, including the one drawn: 1 means the output kept up. */
    public final float pendingAvg;
    public final int pendingMax;
    /** Frame-available to draw-start, in milliseconds. */
    public final float ageAvgMs, ageMaxMs;
    /** GPU execution time per frame (fence to fence, queueing behind the previous frame excluded), -1 if unknown. */
    public final float gpuAvgMs, gpuMaxMs;
    /** Skipped frames since the renderer started, by coalescing and because the GPU was busy. */
    public final long skippedTotal, busySkippedTotal;
    /** Frames the decoder delivered during the window. */
    public final int arrived;
    /** Length of the window in seconds, for rates. */
    public final float windowSeconds;
    /** System.nanoTime() when the window closed. */
    public final long endNs;

    public FrameQueueStats(int draws, int skipped, int busySkipped, float pendingAvg, int pendingMax,
                           float ageAvgMs, float ageMaxMs, float gpuAvgMs, float gpuMaxMs,
                           long skippedTotal, long busySkippedTotal, int arrived, float windowSeconds, long endNs) {
        this.draws = draws;
        this.skipped = skipped;
        this.busySkipped = busySkipped;
        this.pendingAvg = pendingAvg;
        this.pendingMax = pendingMax;
        this.ageAvgMs = ageAvgMs;
        this.ageMaxMs = ageMaxMs;
        this.gpuAvgMs = gpuAvgMs;
        this.gpuMaxMs = gpuMaxMs;
        this.skippedTotal = skippedTotal;
        this.busySkippedTotal = busySkippedTotal;
        this.arrived = arrived;
        this.windowSeconds = windowSeconds;
        this.endNs = endNs;
    }

    /** Skipped frames per second over the window. */
    public float skippedPerSecond() {
        return windowSeconds > 0f ? skipped / windowSeconds : 0f;
    }

    /** Frames per second dropped because the GPU was busy. */
    public float busySkippedPerSecond() {
        return windowSeconds > 0f ? busySkipped / windowSeconds : 0f;
    }

    /** Frames per second the decoder delivered. */
    public float arrivedPerSecond() {
        return windowSeconds > 0f ? arrived / windowSeconds : 0f;
    }

    /** Whether a window that closed at {@code endNs} still describes the stream at {@code nowNs}. */
    public boolean isFresh(long nowNs) {
        return nowNs - endNs < 3_000_000_000L;
    }

    /** The one-line form for the log. */
    public String describe() {
        return String.format(Locale.US,
                "queue pending avg=%.1f max=%d | age avg=%.2f max=%.2f ms | gpu avg=%.2f max=%.2f ms | in=%.0f/s drawn=%d "
                        + "skipped=%d (%.1f/s, %d total) gpuBusySkipped=%d (%.1f/s, %d total)",
                pendingAvg, pendingMax, ageAvgMs, ageMaxMs, gpuAvgMs, gpuMaxMs, arrivedPerSecond(), draws,
                skipped, skippedPerSecond(), skippedTotal, busySkipped, busySkippedPerSecond(), busySkippedTotal);
    }
}
