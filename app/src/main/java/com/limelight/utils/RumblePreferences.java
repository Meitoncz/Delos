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
    public static final String TECHNIQUE = "pref_rumble_technique";
    public static final String WAVE_HZ = "pref_rumble_wave_hz";
    public static final String PRESET = "pref_rumble_preset";
    private static final String CUSTOM = "CUSTOM";

    private RumblePreferences() {}

    public static boolean isEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(ENABLED, false);
    }

    public static RumbleTranslator.Profile readProfile(SharedPreferences prefs) {
        return new RumbleTranslator.Profile(
                prefs.getFloat(GAIN, 1f), prefs.getFloat(CURVE, 1f),
                prefs.getInt(MIN_AMPLITUDE, 0), prefs.getInt(KICK_MS, 0),
                prefs.getFloat(LOW_WEIGHT, .80f), prefs.getFloat(HIGH_WEIGHT, .33f),
                prefs.getFloat(SMOOTHING, 0f),
                RumbleTechnique.fromName(prefs.getString(TECHNIQUE, null)), prefs.getInt(WAVE_HZ, 0));
    }

    /** Saves the entire selection in one editor so listeners never see a partial preset. */
    public static void applyPreset(SharedPreferences prefs, RumblePresets preset) {
        save(prefs, preset.profile, preset.enabled, preset.name());
    }

    /** An advanced edit is Custom even when its values happen to equal a named preset. */
    public static void saveCustom(SharedPreferences prefs, RumbleTranslator.Profile profile,
                                  boolean enabled) {
        save(prefs, profile, enabled, CUSTOM);
    }

    private static void save(SharedPreferences prefs, RumbleTranslator.Profile profile,
                             boolean enabled, String identity) {
        prefs.edit()
                .putFloat(GAIN, profile.gain)
                .putFloat(CURVE, profile.curve)
                .putInt(MIN_AMPLITUDE, profile.minAmplitude)
                .putInt(KICK_MS, profile.kickMs)
                .putFloat(LOW_WEIGHT, profile.lowWeight)
                .putFloat(HIGH_WEIGHT, profile.highWeight)
                .putFloat(SMOOTHING, profile.smoothing)
                .putString(TECHNIQUE, profile.technique.name())
                .putInt(WAVE_HZ, profile.waveHz)
                .putBoolean(ENABLED, enabled)
                .putString(PRESET, identity)
                .apply();
    }

    /** Returns null for Custom, including stale identities whose settings no longer match. */
    public static RumblePresets activePreset(SharedPreferences prefs) {
        if (!prefs.contains(PRESET)) {
            // Before presets existed, off always used the legacy path regardless of tuning.
            return isEnabled(prefs) ? null : RumblePresets.ORIGINAL;
        }
        String identity = prefs.getString(PRESET, null);
        if (identity == null || CUSTOM.equals(identity)) {
            return null;
        }
        RumblePresets preset;
        try {
            preset = RumblePresets.valueOf(identity);
        }
        catch (IllegalArgumentException ignored) {
            return null;
        }
        if (isEnabled(prefs) != preset.enabled) {
            return null;
        }
        RumbleTranslator.Profile expected = preset.profile;
        return prefs.getFloat(GAIN, 1f) == expected.gain
                && prefs.getFloat(CURVE, 1f) == expected.curve
                && prefs.getInt(MIN_AMPLITUDE, 0) == expected.minAmplitude
                && prefs.getInt(KICK_MS, 0) == expected.kickMs
                && prefs.getFloat(LOW_WEIGHT, .80f) == expected.lowWeight
                && prefs.getFloat(HIGH_WEIGHT, .33f) == expected.highWeight
                && prefs.getFloat(SMOOTHING, 0f) == expected.smoothing
                && RumbleTechnique.fromName(prefs.getString(TECHNIQUE, null)) == expected.technique
                && prefs.getInt(WAVE_HZ, 0) == expected.waveHz ? preset : null;
    }
}
