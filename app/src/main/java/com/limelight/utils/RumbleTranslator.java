package com.limelight.utils;

/** Pure host-rumble mapping. Keep one reusable {@link State} per physical output channel. */
public final class RumbleTranslator {
    public static final Profile ORIGINAL = new Profile(1f, 1f, 0, 0, 0.80f, 0.33f, 0f);

    private RumbleTranslator() {
    }

    /** Immutable settings; constructing profiles is not part of the rumble hot path. */
    public static final class Profile {
        public final float gain;
        public final float curve;
        public final int minAmplitude;
        public final int kickMs;
        public final float lowWeight;
        public final float highWeight;
        public final float smoothing;
        /** TEMPORARY (#66): how the translated amplitude becomes vibration; AMPLITUDE is the original path. */
        public final RumbleTechnique technique;
        /** TEMPORARY (#66): sine frequency of {@link RumbleTechnique#HAPTIC_WAVE}, otherwise 0. */
        public final int waveHz;
        private final double preciseLowWeight;
        private final double preciseHighWeight;

        public Profile(float gain, float curve, int minAmplitude, int kickMs,
                       float lowWeight, float highWeight, float smoothing) {
            this(gain, curve, minAmplitude, kickMs, lowWeight, highWeight, smoothing,
                    RumbleTechnique.AMPLITUDE, 0);
        }

        public Profile(float gain, float curve, int minAmplitude, int kickMs,
                       float lowWeight, float highWeight, float smoothing,
                       RumbleTechnique technique, int waveHz) {
            this.gain = gain;
            this.curve = curve;
            this.minAmplitude = minAmplitude;
            this.kickMs = kickMs;
            this.lowWeight = lowWeight;
            this.highWeight = highWeight;
            this.smoothing = smoothing;
            this.technique = technique;
            this.waveHz = waveHz;
            // A float preference for .80/.33 must still use the legacy double literals.
            preciseLowWeight = lowWeight == 0.80f ? 0.80 : lowWeight;
            preciseHighWeight = highWeight == 0.33f ? 0.33 : highWeight;
        }

        /** The same shaping with another technique (advanced edits keep the technique of the selected preset). */
        public Profile withTechnique(RumbleTechnique technique, int waveHz) {
            return new Profile(gain, curve, minAmplitude, kickMs, lowWeight, highWeight, smoothing,
                    technique, waveHz);
        }
    }

    /**
     * Mutable, allocation-free channel history. A raw zero resets it immediately.
     * Smoothing 0 is instantaneous; otherwise the amplitude can move at most
     * 255 / (1000 * smoothing) units per millisecond (1 = a full-scale step per second).
     * The first nonzero request starts immediately, with an optional full-strength kick.
     * Slew begins at the kick deadline, not at the next host event. Fractional movement
     * is retained between calls, then rounded to the nearest output amplitude.
     */
    public static final class State {
        private boolean active;
        private long lastMs;
        private long kickUntilMs;
        private double amplitude;
        private int target;

        public void reset() {
            active = false;
            lastMs = 0;
            kickUntilMs = 0;
            amplitude = 0;
            target = 0;
        }

        // Playback calls again only while a kick or unfinished slew requires it.
        long nextUpdateDelay(long nowMs) {
            if (!active) {
                return Long.MAX_VALUE;
            }
            if (nowMs < kickUntilMs) {
                return kickUntilMs - nowMs;
            }
            return amplitude != target ? 16 : Long.MAX_VALUE;
        }
    }

    /**
     * Mixes the legacy MSB-truncated low/high motors. Inputs are unsigned 16-bit values.
     * A null state performs stateless mapping, without kick or smoothing.
     */
    public static int single(int low, int high, Profile profile, State state, long nowMs) {
        boolean nonzero = low != 0 || high != 0;
        double mixed = ((low >> 8) & 0xFF) * profile.preciseLowWeight
                + ((high >> 8) & 0xFF) * profile.preciseHighWeight;
        return translate(shape(mixed, nonzero, profile), nonzero, profile, state, nowMs);
    }

    /** Maps an individual dual/quad motor, preserving the legacy MSB truncation. */
    public static int motor(int raw, Profile profile, State state, long nowMs) {
        boolean nonzero = raw != 0;
        return translate(shape((raw >> 8) & 0xFF, nonzero, profile),
                nonzero, profile, state, nowMs);
    }

    private static int shape(double amplitude, boolean nonzero, Profile profile) {
        if (!nonzero) {
            return 0;
        }
        // Do not divide and multiply the identity path: rounding at integer boundaries
        // must be exactly the same as the original double-weighted expression.
        double shaped = profile.curve == 1f ? amplitude * profile.gain
                : Math.pow(amplitude / 255.0, profile.curve) * profile.gain * 255.0;
        return Math.min(255, Math.max(profile.minAmplitude, (int) shaped));
    }

    private static int translate(int target, boolean nonzero, Profile profile,
                                 State state, long nowMs) {
        if (state == null) {
            return target;
        }
        if (!nonzero) {
            state.reset();
            return 0;
        }
        if (!state.active) {
            state.active = true;
            state.lastMs = nowMs;
            state.kickUntilMs = nowMs + profile.kickMs;
            state.target = target;
            state.amplitude = profile.kickMs > 0 ? 255 : target;
            return (int) state.amplitude;
        }

        long previousMs = state.lastMs;
        nowMs = Math.max(previousMs, nowMs);
        state.lastMs = nowMs;
        state.target = target;
        if (nowMs < state.kickUntilMs) {
            state.amplitude = 255;
        }
        else if (profile.smoothing == 0f) {
            state.amplitude = target;
        }
        else {
            long elapsedMs = nowMs - Math.max(previousMs, state.kickUntilMs);
            double maxDelta = elapsedMs * 255.0 / (1000.0 * profile.smoothing);
            double delta = target - state.amplitude;
            state.amplitude += Math.max(-maxDelta, Math.min(maxDelta, delta));
            // Assign the exact integer at completion so playback can stop ticking.
            if (Math.abs(target - state.amplitude) < 0.000001) {
                state.amplitude = target;
            }
        }
        // Repeated fractional ticks must round the same as one equivalent elapsed step.
        return Math.max(0, Math.min(255, (int) Math.round(state.amplitude + 1e-9)));
    }
}
