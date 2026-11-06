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
    public static final String PULSE_ON_MS = "pref_rumble_pulse_on_ms";
    public static final String PULSE_OFF_MS = "pref_rumble_pulse_off_ms";
    public static final String PRESET = "pref_rumble_preset";
    private static final String CUSTOM = "CUSTOM";

    private RumblePreferences() {}

    public static boolean isEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(ENABLED, false);
    }

    public static RumbleTranslator.Profile readProfile(SharedPreferences prefs) {
        RumbleTechnique technique = RumbleTechnique.fromName(prefs.getString(TECHNIQUE, null));
        int[] pulse = pulseTimings(prefs, technique);
        return new RumbleTranslator.Profile(
                prefs.getFloat(GAIN, 1f), prefs.getFloat(CURVE, 1f),
                prefs.getInt(MIN_AMPLITUDE, 0), prefs.getInt(KICK_MS, 0),
                prefs.getFloat(LOW_WEIGHT, .80f), prefs.getFloat(HIGH_WEIGHT, .33f),
                prefs.getFloat(SMOOTHING, 0f), technique, pulse[0], pulse[1]);
    }

    /** {on, off} of the saved pulse; earlier builds saved only a fixed-period technique name, which maps to its timings. */
    private static int[] pulseTimings(SharedPreferences prefs, RumbleTechnique technique) {
        if (!technique.isPulse()) {
            return new int[] {0, 0};
        }
        if (!prefs.contains(PULSE_ON_MS) || !prefs.contains(PULSE_OFF_MS)) {
            int[] legacy = RumbleHaptics.legacyPulse(prefs.getString(TECHNIQUE, null));
            if (legacy != null) {
                return legacy;
            }
        }
        return new int[] {prefs.getInt(PULSE_ON_MS, 0), prefs.getInt(PULSE_OFF_MS, 0)};
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
                .putInt(PULSE_ON_MS, profile.pulseOnMs)
                .putInt(PULSE_OFF_MS, profile.pulseOffMs)
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
        RumbleTranslator.Profile saved = readProfile(prefs);
        return saved.gain == expected.gain
                && saved.curve == expected.curve
                && saved.minAmplitude == expected.minAmplitude
                && saved.kickMs == expected.kickMs
                && saved.lowWeight == expected.lowWeight
                && saved.highWeight == expected.highWeight
                && saved.smoothing == expected.smoothing
                && saved.technique == expected.technique
                && saved.pulseOnMs == expected.pulseOnMs
                && saved.pulseOffMs == expected.pulseOffMs ? preset : null;
    }
}
