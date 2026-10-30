package com.limelight.binding.video;

import java.util.Arrays;

/**
 * Delos: the 1% low frame rate of the frames drawn recently, for the statistics panel. 1% low is the
 * frame rate of the slowest 1% of frames: 1000 over the average of the longest 1% of frame intervals.
 * Idea from Moonlight V+ (qiin2333) and Moonlight X Turnip (NoCrypt), via Starwire.
 *
 * <p>Every call site stamps right after the frame was handed to the display with
 * {@code releaseOutputBuffer}. The intervals therefore measure the cadence at which frames are
 * submitted for drawing, not the moment the panel shows them (no render callback is used). Under
 * Fast V-Sync the release is timed to the pacer's target, so the two stay close; frames dropped
 * before the release are not counted.
 */
public final class FrameTimes {
    // About ten seconds at 120 FPS
    private static final int CAPACITY = 1200;
    // Longer gaps are a still picture (the host sends nothing new), not a hitch
    private static final long IDLE_GAP_NS = 500_000_000L;

    private final long[] intervals = new long[CAPACITY];
    private int count;
    private int next;
    private long lastNs;

    /** A frame was drawn at [nowNs]. */
    public synchronized void add(long nowNs) {
        if (lastNs != 0) {
            long interval = nowNs - lastNs;
            if (interval > 0 && interval < IDLE_GAP_NS) {
                intervals[next] = interval;
                next = (next + 1) % CAPACITY;
                count = Math.min(count + 1, CAPACITY);
            }
        }
        lastNs = nowNs;
    }

    /** The 1% low frame rate, or -1 while there are too few frames to tell. */
    public synchronized float onePercentLowFps() {
        if (count < 100) {
            return -1f;
        }
        long[] sorted = Arrays.copyOf(intervals, count);
        Arrays.sort(sorted);
        int worst = Math.max(1, count / 100);
        long total = 0;
        for (int i = count - worst; i < count; i++) {
            total += sorted[i];
        }
        return 1e9f / (total / (float) worst);
    }
}
