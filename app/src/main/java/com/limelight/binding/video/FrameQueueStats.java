package com.limelight.binding.video;

import java.util.Locale;

/**
 * What the SGSR output stage saw in its last statistics window (about a second): how many frames were waiting
 * when it started a draw, how old the newest of them already was, and how many older frames it skipped because
 * a newer one was ready. Immutable; published by {@link FrameQueue}.
 */
public final class FrameQueueStats {
    /** Draws in the window, and the frames skipped (never drawn) because a newer frame was already waiting. */
    public final int draws, skipped;
    /** Frames waiting at the start of a draw, including the one drawn: 1 means the output kept up. */
    public final float pendingAvg;
    public final int pendingMax;
    /** Frame-available to draw-start, in milliseconds. */
    public final float ageAvgMs, ageMaxMs;
    /** Skipped frames since the renderer started. */
    public final long skippedTotal;
    /** Length of the window in seconds, for rates. */
    public final float windowSeconds;
    /** System.nanoTime() when the window closed. */
    public final long endNs;

    public FrameQueueStats(int draws, int skipped, float pendingAvg, int pendingMax, float ageAvgMs, float ageMaxMs,
                           long skippedTotal, float windowSeconds, long endNs) {
        this.draws = draws;
        this.skipped = skipped;
        this.pendingAvg = pendingAvg;
        this.pendingMax = pendingMax;
        this.ageAvgMs = ageAvgMs;
        this.ageMaxMs = ageMaxMs;
        this.skippedTotal = skippedTotal;
        this.windowSeconds = windowSeconds;
        this.endNs = endNs;
    }

    /** Skipped frames per second over the window. */
    public float skippedPerSecond() {
        return windowSeconds > 0f ? skipped / windowSeconds : 0f;
    }

    /** Whether a window that closed at {@code endNs} still describes the stream at {@code nowNs}. */
    public boolean isFresh(long nowNs) {
        return nowNs - endNs < 3_000_000_000L;
    }

    /** The one-line form for the log. */
    public String describe() {
        return String.format(Locale.US, "queue pending avg=%.1f max=%d | age avg=%.2f max=%.2f ms | skipped=%d (%.1f/s, %d total)",
                pendingAvg, pendingMax, ageAvgMs, ageMaxMs, skipped, skippedPerSecond(), skippedTotal);
    }
}
