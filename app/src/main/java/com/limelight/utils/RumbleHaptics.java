package com.limelight.utils;

/**
 * TEMPORARY (#66): pure math of the experimental rumble techniques (no Android types), so it can be unit tested.
 */
public final class RumbleHaptics {
    /** Primitives per burst composition. */
    public static final int BURST_COUNT = 12;
    /** A held burst is issued again after this long, or at once when the amplitude changes. */
    public static final int BURST_REISSUE_MS = 200;
    static final int THUD_DELAY_MS = 12;
    static final int LOW_TICK_DELAY_MS = 10;
    /** On-phase share of a pulse period. */
    static final int PULSE_DUTY_PERCENT = 50;

    private RumbleHaptics() {
    }

    /** Delay before every primitive after the first in a burst; 0 for techniques that are no bursts. */
    public static int burstDelayMs(RumbleTechnique technique) {
        if (technique == RumbleTechnique.PRIMITIVE_THUD) {
            return THUD_DELAY_MS;
        }
        return technique == RumbleTechnique.PRIMITIVE_LOW_TICK ? LOW_TICK_DELAY_MS : 0;
    }

    /** Primitive scale 0..1 following the translated amplitude 0..255. */
    public static float primitiveScale(int amplitude) {
        return Math.max(0, Math.min(255, amplitude)) / 255f;
    }

    /** Pulse period in milliseconds; 0 for techniques that are no pulses. */
    public static int pulsePeriodMs(RumbleTechnique technique) {
        switch (technique) {
            case PULSE_25:
                return 40;
            case PULSE_40:
                return 25;
            case PULSE_60:
                return 16;
            default:
                return 0;
        }
    }

    /** {on, off} durations of one pulse period; the on-phase is rounded half up from the duty share. */
    public static long[] pulseTimings(int periodMs) {
        long on = (periodMs * PULSE_DUTY_PERCENT + 50) / 100;
        return new long[] {on, periodMs - on};
    }

    /** {on, off} amplitudes: the translated amplitude, never below {@code floor}, then silence. */
    public static int[] pulseAmplitudes(int amplitude, int floor) {
        int on = Math.max(Math.max(0, Math.min(255, floor)), Math.max(0, Math.min(255, amplitude)));
        return new int[] {on, 0};
    }
}
