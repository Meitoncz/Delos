package com.limelight.binding.video;

/**
 * Decides from the SGSR output's statistics windows whether the upscaler is too heavy for the stream's frame rate. A
 * window counts as overloaded when the GPU time per frame is above 90 % of the frame period (the incoming frame rate
 * of that window) or more than 20 % of the frames that were drawn or waited for the GPU were dropped because it was
 * busy. Two overloaded windows in a row (about two seconds) report the overload once; the caller decides what to do
 * and how often. Windows with very few frames (a paused or static stream) neither count nor break a streak.
 */
final class OverloadDetector {
    static final float GPU_SHARE = 0.9f;
    static final float BUSY_SHARE = 0.2f;
    static final int WINDOWS_IN_A_ROW = 2;
    static final int MIN_FRAMES = 20;

    private int streak;

    /** Forgets the streak (the mode or the output size changed). */
    void reset() {
        streak = 0;
    }

    /** Feeds one finished window; returns true when the overload has just been established. */
    boolean onWindow(FrameQueueStats window) {
        if (window.arrived < MIN_FRAMES || window.arrivedPerSecond() <= 0f) return false;
        if (isOverloaded(window)) {
            if (++streak >= WINDOWS_IN_A_ROW) {
                streak = 0;
                return true;
            }
        } else {
            streak = 0;
        }
        return false;
    }

    /** Whether one window shows the GPU unable to keep up. */
    static boolean isOverloaded(FrameQueueStats window) {
        float periodMs = 1000f / window.arrivedPerSecond();
        boolean gpuBound = window.gpuAvgMs > GPU_SHARE * periodMs;
        int offered = window.draws + window.busySkipped;
        boolean dropping = offered > 0 && (float) window.busySkipped / offered > BUSY_SHARE;
        return gpuBound || dropping;
    }
}
