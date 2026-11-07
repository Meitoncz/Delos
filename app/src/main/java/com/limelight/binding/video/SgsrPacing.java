package com.limelight.binding.video;

/**
 * Pure helpers for making Fast V-Sync aware of the SGSR output stage. The pacer times its release of a decoded
 * frame to a display target, but with SGSR the frame is only on screen after the GL thread has processed it, so
 * the release has to happen that much earlier. Nothing here touches a clock or a thread.
 */
final class SgsrPacing {
    /** Slack on top of the measured time: the estimate is smoothed, the next frame may be a little slower. */
    static final long MARGIN_NS = 500_000L;
    private static final float SMOOTHING = 0.2f;

    private SgsrPacing() { }

    /** Exponential moving average; the first sample (previous 0 or less) is taken as is. */
    static float smooth(float previous, float sample) {
        return previous <= 0f ? sample : previous * (1f - SMOOTHING) + sample * SMOOTHING;
    }

    /**
     * How much earlier than its target a frame should be released: the SGSR time plus a margin, between zero and
     * half a frame period. Zero (no lead, the behaviour without SGSR) when the time is unknown, not positive or not
     * a number. The cap bounds what a wrong estimate can do: a frame is never released more than half a period
     * early, and a large estimate is the worst case, not an unbounded shift.
     */
    static long leadNs(long sgsrWorkNs, long framePeriodNs) {
        if (sgsrWorkNs <= 0L || framePeriodNs <= 0L) return 0L;
        return Math.min(sgsrWorkNs + MARGIN_NS, framePeriodNs / 2);
    }

    /**
     * When to release a frame aimed at {@code targetNs}: {@code leadNs} earlier, but never before {@code nowNs}
     * (a frame already due is released as it is, and an early release is not turned into a wait that has passed).
     */
    static long releaseTimeNs(long targetNs, long nowNs, long leadNs) {
        if (leadNs <= 0L || targetNs <= nowNs) return targetNs;
        return Math.max(targetNs - leadNs, nowNs);
    }
}
