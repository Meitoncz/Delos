package com.limelight.utils;

/** TEMPORARY (#66): how a translated amplitude is turned into vibration. */
public enum RumbleTechnique {
    /** The original path: a one-shot at the translated amplitude. */
    AMPLITUDE,
    /** Repeated manufacturer THUD primitives, scaled by the translated amplitude. */
    PRIMITIVE_THUD,
    /** Repeated manufacturer LOW_TICK primitives, scaled by the translated amplitude. */
    PRIMITIVE_LOW_TICK,
    /** A synthesized low-frequency sine on the haptic channel of an AudioTrack. */
    HAPTIC_WAVE;

    public boolean isPrimitive() {
        return this == PRIMITIVE_THUD || this == PRIMITIVE_LOW_TICK;
    }

    /** Unknown or missing names are the original path, so old saved profiles keep working. */
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
