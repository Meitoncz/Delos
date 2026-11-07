package com.limelight.binding.video;

/**
 * Lightens SGSR 2's work when its GPU time takes most of the frame period: while the smoothed GPU time per frame is
 * above 70 % of the period, every second frame reuses the previous frame's optical flow instead of recomputing it (the
 * flow passes are about a quarter to a third of the temporal cost). Under load the picture can be slightly less sharp
 * on changing motion; nothing changes while the GPU keeps up. It leaves again below 45 % (the figure drops while it is
 * active, so a single threshold would flap). The decision uses the statistics windows of {@link FrameQueue}; no other
 * setting is touched.
 */
final class FlowGovernor {
    static final float ENTER_SHARE = 0.70f;
    static final float LEAVE_SHARE = 0.45f;

    private boolean reduced;
    private int frame;

    /** Feeds one finished window; returns true when the state changed. */
    boolean onWindow(FrameQueueStats window) {
        if (window.gpuAvgMs < 0f || window.arrivedPerSecond() <= 0f || window.arrived < OverloadDetector.MIN_FRAMES) return false;
        float share = window.gpuAvgMs / (1000f / window.arrivedPerSecond());
        if (!reduced && share > ENTER_SHARE) {
            reduced = true;
            frame = 0;
            return true;
        }
        if (reduced && share < LEAVE_SHARE) {
            reduced = false;
            return true;
        }
        return false;
    }

    boolean isReduced() {
        return reduced;
    }

    /** For the frame about to be drawn: true when it should reuse the previous flow (every second frame while reduced). */
    boolean reuseFlowThisFrame() {
        if (!reduced) return false;
        return (frame++ & 1) == 1;
    }

    /** Back to full quality (the mode changed). */
    void reset() {
        reduced = false;
        frame = 0;
    }
}
