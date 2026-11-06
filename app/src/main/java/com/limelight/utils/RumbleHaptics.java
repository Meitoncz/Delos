package com.limelight.utils;

/**
 * TEMPORARY (#66): pure math of the experimental rumble techniques (no Android types), so it can be unit tested.
 */
public final class RumbleHaptics {
    /** Primitive repeat interval at amplitude 0 and 255; stronger requests repeat faster. */
    static final int THUD_SLOW_MS = 100;
    static final int THUD_FAST_MS = 60;
    static final int LOW_TICK_SLOW_MS = 70;
    static final int LOW_TICK_FAST_MS = 40;

    public static final int WAVE_SAMPLE_RATE = 48000;
    public static final float WAVE_ATTACK_MS = 4f;
    public static final float WAVE_DECAY_MS = 20f;

    private RumbleHaptics() {
    }

    /** Milliseconds between primitive repeats while a rumble is held; 0 for techniques that do not repeat. */
    public static int primitiveIntervalMs(RumbleTechnique technique, int amplitude) {
        int slow;
        int fast;
        if (technique == RumbleTechnique.PRIMITIVE_THUD) {
            slow = THUD_SLOW_MS;
            fast = THUD_FAST_MS;
        }
        else if (technique == RumbleTechnique.PRIMITIVE_LOW_TICK) {
            slow = LOW_TICK_SLOW_MS;
            fast = LOW_TICK_FAST_MS;
        }
        else {
            return 0;
        }
        int a = Math.max(0, Math.min(255, amplitude));
        return slow - ((slow - fast) * a + 127) / 255;
    }

    /** Primitive scale 0..1 following the translated amplitude 0..255. */
    public static float primitiveScale(int amplitude) {
        return Math.max(0, Math.min(255, amplitude)) / 255f;
    }

    /**
     * Allocation-free sine generator for the haptic channel. Frames are interleaved: {@code audioChannels}
     * audio samples (always 0, so nothing audible plays) followed by one haptic sample. Phase and the
     * attack/decay envelope persist between {@link #fill} calls, so chunk borders are seamless.
     */
    public static final class WaveSynth {
        private final int audioChannels;
        private final double phaseStep;
        private final float attackStep;
        private final float decayStep;
        private double phase;
        private float level;

        public WaveSynth(int hz, int sampleRate, int audioChannels) {
            this.audioChannels = audioChannels;
            phaseStep = (double) hz / sampleRate;
            attackStep = 1000f / (WAVE_ATTACK_MS * sampleRate);
            decayStep = 1000f / (WAVE_DECAY_MS * sampleRate);
        }

        public int frameSize() {
            return audioChannels + 1;
        }

        /** True once the envelope has fully decayed, so output would be all zeros. */
        public boolean isSilent() {
            return level == 0f;
        }

        public float level() {
            return level;
        }

        public double phase() {
            return phase;
        }

        /** Writes {@code frames} frames into {@code out} (from index 0) with the envelope heading to {@code target} 0..1. */
        public void fill(short[] out, int frames, float target) {
            int size = audioChannels + 1;
            for (int f = 0; f < frames; f++) {
                if (level < target) {
                    level = Math.min(target, level + attackStep);
                }
                else if (level > target) {
                    level = Math.max(target, level - decayStep);
                }
                int base = f * size;
                for (int c = 0; c < audioChannels; c++) {
                    out[base + c] = 0;
                }
                out[base + audioChannels] = (short) Math.round(Math.sin(2 * Math.PI * phase) * level * 32767.0);
                phase += phaseStep;
                if (phase >= 1.0) {
                    phase -= 1.0;
                }
            }
        }
    }
}
