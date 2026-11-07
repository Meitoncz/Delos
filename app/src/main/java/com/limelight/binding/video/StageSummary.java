package com.limelight.binding.video;

import java.util.Locale;

/** Average, 95th percentile and maximum of one latency stage over a statistics window, in milliseconds. */
public final class StageSummary {
    public final int count;
    public final float avgMs, p95Ms, maxMs;

    public StageSummary(int count, float avgMs, float p95Ms, float maxMs) {
        this.count = count;
        this.avgMs = avgMs;
        this.p95Ms = p95Ms;
        this.maxMs = maxMs;
    }

    /** "avg/p95/max" for the log, for example "1.20/2.10/3.40". */
    String triple() {
        return String.format(Locale.US, "%.2f/%.2f/%.2f", avgMs, p95Ms, maxMs);
    }
}
