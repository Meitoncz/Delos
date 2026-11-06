package com.limelight.binding.video;

/**
 * The backlog numbers of one PyroWave statistics window, derived from PyroWaveRenderer.takeStats():
 * how long the video thread was held per frame, how old frames were when the renderer started on them
 * and when they were presented, and the per-stage waits of the presented frames. All times in ms.
 */
public final class PyroWaveBacklog {
    /** Time inside the submit call per received frame: the socket is not read meanwhile. */
    public final float stallAvgMs, stallMaxMs;
    /** Age (since the first packet arrived) when the renderer starts on the frame. */
    public final float submitAgeAvgMs, submitAgeMaxMs;
    /** Age when the frame has been handed to the presentation queue. */
    public final float presentAgeAvgMs, presentAgeMaxMs;
    public final float partialPerSecond, skippedPerSecond;
    /** Averages per presented frame: previous frame fence, swapchain image (or P010 pack), submit + present. */
    public final float fenceWaitAvgMs, acquireWaitAvgMs, submitPresentAvgMs;

    public PyroWaveBacklog(long[] stats, float seconds) {
        long received = stats[PyroWaveRenderer.STAT_RECEIVED_FRAMES];
        long rendered = stats[PyroWaveRenderer.STAT_RENDERED_FRAMES];
        stallAvgMs = averageMs(stats[PyroWaveRenderer.STAT_STALL_TOTAL_US], received);
        stallMaxMs = stats[PyroWaveRenderer.STAT_STALL_MAX_US] / 1000f;
        submitAgeAvgMs = averageMs(stats[PyroWaveRenderer.STAT_SUBMIT_AGE_TOTAL_US],
                stats[PyroWaveRenderer.STAT_SUBMIT_AGE_FRAMES]);
        submitAgeMaxMs = stats[PyroWaveRenderer.STAT_SUBMIT_AGE_MAX_US] / 1000f;
        presentAgeAvgMs = averageMs(stats[PyroWaveRenderer.STAT_PRESENT_AGE_TOTAL_US],
                stats[PyroWaveRenderer.STAT_PRESENT_AGE_FRAMES]);
        presentAgeMaxMs = stats[PyroWaveRenderer.STAT_PRESENT_AGE_MAX_US] / 1000f;
        float window = Math.max(seconds, 0.001f);
        partialPerSecond = stats[PyroWaveRenderer.STAT_PARTIAL_FRAMES] / window;
        skippedPerSecond = stats[PyroWaveRenderer.STAT_SKIPPED_FRAMES] / window;
        fenceWaitAvgMs = averageMs(stats[PyroWaveRenderer.STAT_FENCE_WAIT_TOTAL_US], rendered);
        acquireWaitAvgMs = averageMs(stats[PyroWaveRenderer.STAT_ACQUIRE_WAIT_TOTAL_US], rendered);
        submitPresentAvgMs = averageMs(stats[PyroWaveRenderer.STAT_SUBMIT_PRESENT_TOTAL_US], rendered);
    }

    private static float averageMs(long totalUs, long count) {
        return count > 0 ? totalUs / 1000f / count : 0f;
    }
}
