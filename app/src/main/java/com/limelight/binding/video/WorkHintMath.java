package com.limelight.binding.video;

/** Delos: the numbers behind the performance hint session of LatencyThreads. */
final class WorkHintMath {
    // The target is a share of the frame period: leaves slack for the compositor, and a target well below
    // the budget asks the governor for clocks that finish the frame early instead of just in time.
    private static final double TARGET_SHARE = 0.6;
    private static final int MIN_FPS = 1;
    private static final int MAX_FPS = 1000;
    /** Frames averaged into one report; keeps the binder call to once per 4 frames (30 per second at 120 fps). */
    static final int REPORT_EVERY_FRAMES = 4;

    private WorkHintMath() {
    }

    /** The target work duration for a stream of [fps] frames per second: 0.6 x the frame period, in ns. */
    static long targetNanos(int fps) {
        int clamped = Math.max(MIN_FPS, Math.min(MAX_FPS, fps));
        return Math.round(1_000_000_000d / clamped * TARGET_SHARE);
    }

    /**
     * Averages the work duration of consecutive frames. One thread feeds it (the one that finishes the frames),
     * so it needs no lock.
     */
    static final class Window {
        private final int frames;
        private long sumNanos;
        private int count;

        Window(int frames) {
            this.frames = Math.max(1, frames);
        }

        /** Adds one frame; the mean of the window in ns once it is full (and starts a new one), else 0. */
        long add(long nanos) {
            sumNanos += Math.max(0, nanos);
            if (++count < frames) return 0;
            // A mean of 0 would read as "no report"; a frame cannot take less than a nanosecond
            long mean = Math.max(1, sumNanos / count);
            sumNanos = 0;
            count = 0;
            return mean;
        }

        void reset() {
            sumNanos = 0;
            count = 0;
        }
    }
}
