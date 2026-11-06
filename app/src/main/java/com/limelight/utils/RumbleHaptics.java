package com.limelight.utils;

/**
 * TEMPORARY (#66): pure math of the experimental pulse technique (no Android types), so it can be unit tested.
 */
public final class RumbleHaptics {
    private RumbleHaptics() {
    }

    /** {on, off} timings saved by earlier builds under the fixed-period names, or null for any other name. */
    public static int[] legacyPulse(String name) {
        if ("PULSE_25".equals(name)) {
            return new int[] {20, 20};
        }
        if ("PULSE_40".equals(name)) {
            return new int[] {13, 12};
        }
        return "PULSE_60".equals(name) ? new int[] {8, 8} : null;
    }

    /** {on, off} durations of one pulse period; each phase lasts at least 1 ms (a waveform rejects 0). */
    public static long[] pulseTimings(int onMs, int offMs) {
        return new long[] {Math.max(1, onMs), Math.max(1, offMs)};
    }

    /** {on, off} amplitudes: the translated amplitude, never below {@code floor}, then silence. */
    public static int[] pulseAmplitudes(int amplitude, int floor) {
        int on = Math.max(Math.max(0, Math.min(255, floor)), Math.max(0, Math.min(255, amplitude)));
        return new int[] {on, 0};
    }
}
