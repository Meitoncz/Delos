package com.limelight.utils;

import android.content.SharedPreferences;

/** Debug-only tuning keys; values belong to the active user profile. */
public final class RumblePreferences {
    public static final String ENABLED = "checkbox_rumble_translator";
    public static final String GAIN = "pref_rumble_gain";
    public static final String CURVE = "pref_rumble_curve";
    public static final String MIN_AMPLITUDE = "pref_rumble_min_amplitude";
    public static final String KICK_MS = "pref_rumble_kick_ms";
    public static final String LOW_WEIGHT = "pref_rumble_low_weight";
    public static final String HIGH_WEIGHT = "pref_rumble_high_weight";
    public static final String SMOOTHING = "pref_rumble_smoothing";

    private RumblePreferences() {}

    public static boolean isEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(ENABLED, false);
    }

    public static RumbleTranslator.Profile readProfile(SharedPreferences prefs) {
        return new RumbleTranslator.Profile(
                prefs.getFloat(GAIN, 1f), prefs.getFloat(CURVE, 1f),
                prefs.getInt(MIN_AMPLITUDE, 0), prefs.getInt(KICK_MS, 0),
                prefs.getFloat(LOW_WEIGHT, .80f), prefs.getFloat(HIGH_WEIGHT, .33f),
                prefs.getFloat(SMOOTHING, 0f));
    }
}
