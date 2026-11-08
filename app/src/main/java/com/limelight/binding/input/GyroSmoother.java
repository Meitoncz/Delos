package com.limelight.binding.input;

/**
 * Adaptive low-pass ("One Euro" filter) for gyroscope angular velocity, one instance per sensor listener
 * (that is, per controller and per report rate; a new report rate creates a new listener and so a fresh filter).
 * Each of the three axes is filtered on its own; the accelerometer is never filtered.
 *
 * <p>The cut-off of each axis is {@code minCutoff + beta * |d|}, where {@code d} is the low-pass filtered rate of
 * change of the signal. At rest and for slow movement the cut-off stays near {@code minCutoff} (jitter and noise
 * are removed); during a fast flick the rate of change grows, the cut-off opens and the filter lags very little.
 * The derivative itself is filtered at a fixed {@link #DERIVATIVE_CUTOFF_HZ}.
 *
 * <p><b>Units.</b> The filter runs on the values the host receives, degrees per second, so {@code beta} is in Hz
 * per (deg/s per second). Samples are timed with the sensor's own event timestamps (nanoseconds), not the arrival
 * time, so delivery jitter does not become filter noise. A repeated timestamp repeats the last output; a gap
 * above {@link #MAX_GAP_NS} or a timestamp that goes backwards restarts the filter from the new sample.
 *
 * <p><b>Slider mapping</b> ({@code 0} = off, then {@code t = slider / 100}), exponential so every slider step
 * feels alike:
 * <pre>
 *   minCutoff(slider) = 8 Hz * 0.025^t     (8 Hz at 0 down to 0.2 Hz at 100)
 *   beta(slider)      = 0.02 * 0.25^t      (0.02 at 0 down to 0.005 at 100)
 * </pre>
 * Slider 0 never creates a filter: callers bypass it and the values are sent untouched. docs/SETTINGS.md lists the
 * resulting numbers, and {@code GyroSmootherTest} measures lag and noise reduction.
 *
 * <p>No allocation per sample; not thread safe (one sensor callback thread per listener).
 */
public final class GyroSmoother {
    public static final int OFF = 0;
    public static final int MAX_SLIDER = 100;
    public static final float DERIVATIVE_CUTOFF_HZ = 1.0f;
    /** After a silence this long (ns) the old state is meaningless and the filter restarts. */
    public static final long MAX_GAP_NS = 250_000_000L;

    private static final double MIN_CUTOFF_LOOSE_HZ = 8.0;
    private static final double MIN_CUTOFF_STRONG_HZ = 0.2;
    private static final double BETA_LOOSE = 0.02;
    private static final double BETA_STRONG = 0.005;
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double NS = 1e-9;

    private final double minCutoffHz;
    private final double beta;
    private final double[] value = new double[3];
    private final double[] rate = new double[3];
    private long lastTimestampNs;
    private boolean primed;

    /** @param slider 1..100; use {@link #isActive} first, 0 means "do not filter". */
    public GyroSmoother(int slider) {
        this.minCutoffHz = minCutoffHz(slider);
        this.beta = beta(slider);
    }

    public static boolean isActive(int slider) {
        return slider > OFF;
    }

    private static double fraction(int slider) {
        return Math.min(MAX_SLIDER, Math.max(OFF, slider)) / (double) MAX_SLIDER;
    }

    /** Cut-off at rest in Hz; falls monotonically from 8 Hz (slider 0) to 0.2 Hz (slider 100). */
    public static float minCutoffHz(int slider) {
        return (float) (MIN_CUTOFF_LOOSE_HZ * Math.pow(MIN_CUTOFF_STRONG_HZ / MIN_CUTOFF_LOOSE_HZ, fraction(slider)));
    }

    /** Speed coefficient; falls monotonically from 0.02 (slider 0) to 0.005 (slider 100). */
    public static float beta(int slider) {
        return (float) (BETA_LOOSE * Math.pow(BETA_STRONG / BETA_LOOSE, fraction(slider)));
    }

    /** Forgets everything; the next sample passes through unchanged. */
    public void reset() {
        primed = false;
    }

    /**
     * Filters one sample into {@code out[0..2]}. {@code out} may be the array the input came from.
     *
     * @param timestampNs the sensor event timestamp in nanoseconds
     */
    public void filter(long timestampNs, float x, float y, float z, float[] out) {
        long dtNs = timestampNs - lastTimestampNs;
        if (!primed || dtNs > MAX_GAP_NS || dtNs < 0) {
            value[0] = x;
            value[1] = y;
            value[2] = z;
            rate[0] = rate[1] = rate[2] = 0.0;
            lastTimestampNs = timestampNs;
            primed = true;
        } else if (dtNs > 0) {
            double dt = dtNs * NS;
            lastTimestampNs = timestampNs;
            double derivativeAlpha = alpha(DERIVATIVE_CUTOFF_HZ, dt);
            step(0, x, dt, derivativeAlpha);
            step(1, y, dt, derivativeAlpha);
            step(2, z, dt, derivativeAlpha);
        }
        out[0] = (float) value[0];
        out[1] = (float) value[1];
        out[2] = (float) value[2];
    }

    private void step(int axis, float sample, double dt, double derivativeAlpha) {
        double previous = value[axis];
        double derivative = rate[axis] + derivativeAlpha * ((sample - previous) / dt - rate[axis]);
        rate[axis] = derivative;
        double cutoff = minCutoffHz + beta * Math.abs(derivative);
        value[axis] = previous + alpha(cutoff, dt) * (sample - previous);
    }

    private static double alpha(double cutoffHz, double dt) {
        double tau = 1.0 / (TWO_PI * cutoffHz);
        return dt / (dt + tau);
    }
}
