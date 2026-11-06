package com.limelight.utils;

/** TEMPORARY (#66): how a translated amplitude is turned into vibration. */
public enum RumbleTechnique {
    /** The original path: a one-shot at the translated amplitude. */
    AMPLITUDE,
    /** Bursts of manufacturer THUD primitives, scaled by the translated amplitude. */
    PRIMITIVE_THUD,
    /** Bursts of manufacturer LOW_TICK primitives, scaled by the translated amplitude. */
    PRIMITIVE_LOW_TICK,
    /** A repeating on/off amplitude waveform with a 40 ms period (25 Hz). */
    PULSE_25,
    /** The same with a 25 ms period (40 Hz). */
    PULSE_40,
    /** The same with a 16 ms period (about 60 Hz). */
    PULSE_60;

    public boolean isPrimitive() {
        return this == PRIMITIVE_THUD || this == PRIMITIVE_LOW_TICK;
    }

    public boolean isPulse() {
        return this == PULSE_25 || this == PULSE_40 || this == PULSE_60;
    }

    /** Unknown or missing names (including the removed haptic-audio technique) are the original path. */
    public static RumbleTechnique fromName(String name) {
        if (name != null) {
            for (RumbleTechnique technique : values()) {
                if (technique.name().equals(name)) {
                    return technique;
                }
            }
        }
        return AMPLITUDE;
    }
}
