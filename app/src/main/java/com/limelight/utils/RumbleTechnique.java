package com.limelight.utils;

/** How a translated amplitude is turned into vibration. */
public enum RumbleTechnique {
    /** The original path: a one-shot at the translated amplitude. */
    AMPLITUDE,
    /** A repeating on/off amplitude waveform; the profile carries the on and off durations. */
    PULSE;

    public boolean isPulse() {
        return this == PULSE;
    }

    /**
     * Unknown or missing names (including the removed haptic-audio and burst techniques) are the original path.
     * The fixed-period names saved by earlier builds are pulses; {@link RumbleHaptics#legacyPulse} gives their timings.
     */
    public static RumbleTechnique fromName(String name) {
        if (name != null) {
            if (name.equals(PULSE.name()) || RumbleHaptics.legacyPulse(name) != null) {
                return PULSE;
            }
        }
        return AMPLITUDE;
    }
}
