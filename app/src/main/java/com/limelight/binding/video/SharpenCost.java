package com.limelight.binding.video;

/**
 * What the Sharpen only mode cost per frame in the last statistics window (about a second), for the full
 * statistics panel and the logs. The pass is the final shader that sharpens and writes the output; the
 * preparation is the work the mode needs before it: the copy of the decoder's frame into a texture (OpenGL
 * ES) or the YCbCr to RGB conversion (PyroWave), which the direct output does not have.
 */
public final class SharpenCost {
    /** True when the times are GPU timestamps, false when only the CPU time of submitting the draw is known. */
    public final boolean gpuTimed;
    public final float passAvgMs, passMaxMs;
    /** Negative when the preparation was not measured. */
    public final float prepareAvgMs, prepareMaxMs;
    /** What the preparation is, for the panel ("copy", "conversion"). */
    public final String prepareName;
    /** Frames the averages are over. */
    public final int samples;

    public SharpenCost(boolean gpuTimed, float passAvgMs, float passMaxMs, float prepareAvgMs, float prepareMaxMs,
                       String prepareName, int samples) {
        this.gpuTimed = gpuTimed;
        this.passAvgMs = passAvgMs;
        this.passMaxMs = passMaxMs;
        this.prepareAvgMs = prepareAvgMs;
        this.prepareMaxMs = prepareMaxMs;
        this.prepareName = prepareName;
        this.samples = samples;
    }

    /** The cost of a PyroWave window from its native statistics, or null when no frame was sharpened. */
    public static SharpenCost fromPyroWave(long[] stats) {
        long frames = stats[PyroWaveRenderer.STAT_SHARPEN_FRAMES];
        if (frames <= 0) return null;
        return new SharpenCost(true,
                stats[PyroWaveRenderer.STAT_SHARPEN_TOTAL_US] / 1000f / frames,
                stats[PyroWaveRenderer.STAT_SHARPEN_MAX_US] / 1000f,
                stats[PyroWaveRenderer.STAT_SHARPEN_PREPARE_TOTAL_US] / 1000f / frames,
                stats[PyroWaveRenderer.STAT_SHARPEN_PREPARE_MAX_US] / 1000f,
                "conversion", (int) frames);
    }
}
