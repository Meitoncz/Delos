package com.limelight.utils;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class RumblePreferencesPresetTest {
    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = context.getSharedPreferences("rumble-presets", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }

    @Test
    public void defaultAndLegacyOffAreOriginalButLegacyOnIsCustom() {
        assertSame(RumblePresets.ORIGINAL, RumblePreferences.activePreset(prefs));
        assertFalse(RumblePreferences.isEnabled(prefs));
        assertProfile(RumbleTranslator.ORIGINAL, RumblePreferences.readProfile(prefs));
        prefs.edit().putFloat(RumblePreferences.GAIN, 2f).commit();
        assertSame(RumblePresets.ORIGINAL, RumblePreferences.activePreset(prefs));
        prefs.edit().putBoolean(RumblePreferences.ENABLED, true).commit();
        assertNull(RumblePreferences.activePreset(prefs));
        // Even legacy values identical to a named profile do not imply a selection.
        prefs.edit().putFloat(RumblePreferences.GAIN, 1.5f).commit();
        assertNull(RumblePreferences.activePreset(prefs));
        prefs.edit().putFloat(RumblePreferences.GAIN, 1f).commit();
        assertNull(RumblePreferences.activePreset(prefs));
    }

    @Test
    public void everyPresetRoundTripsItsIdentityEnabledStateAndAllParameters() {
        for (RumblePresets preset : RumblePresets.values()) {
            RumblePreferences.applyPreset(prefs, preset);
            assertSelection(prefs, preset);
            assertEquals(preset.name(), prefs.getString(RumblePreferences.PRESET, null));
            assertSelection(reloadSavedValues(), preset);
        }
    }

    @Test
    public void presetAndCustomListenersOnlyObserveCompleteSelections() {
        RumblePresets preset = RumblePresets.MAX_IMMERSION;
        int[] notifications = {0};
        SharedPreferences.OnSharedPreferenceChangeListener presetListener = (saved, key) -> {
            notifications[0]++;
            assertSelection(saved, preset);
        };
        prefs.registerOnSharedPreferenceChangeListener(presetListener);
        RumblePreferences.applyPreset(prefs, preset);
        prefs.unregisterOnSharedPreferenceChangeListener(presetListener);
        assertTrue(notifications[0] > 0);

        RumbleTranslator.Profile custom = new RumbleTranslator.Profile(2f, 2f, 50, 20, .5f, .6f, .4f);
        notifications[0] = 0;
        SharedPreferences.OnSharedPreferenceChangeListener customListener = (saved, key) -> {
            notifications[0]++;
            assertNull(RumblePreferences.activePreset(saved));
            assertFalse(RumblePreferences.isEnabled(saved));
            assertProfile(custom, RumblePreferences.readProfile(saved));
        };
        prefs.registerOnSharedPreferenceChangeListener(customListener);
        RumblePreferences.saveCustom(prefs, custom, false);
        prefs.unregisterOnSharedPreferenceChangeListener(customListener);
        assertTrue(notifications[0] > 0);
    }

    @Test
    public void savingIdenticalValuesExplicitlyKeepsEveryPresetCustomAfterReload() {
        for (RumblePresets preset : RumblePresets.values()) {
            RumblePreferences.applyPreset(prefs, preset);
            RumblePreferences.saveCustom(prefs, RumblePreferences.readProfile(prefs), preset.enabled);
            assertNull(RumblePreferences.activePreset(prefs));
            assertNull(RumblePreferences.activePreset(reloadSavedValues()));
            assertEquals(preset.enabled, RumblePreferences.isEnabled(prefs));
            assertProfile(preset.profile, RumblePreferences.readProfile(prefs));
            RumblePreferences.applyPreset(prefs, preset);
            assertSame(preset, RumblePreferences.activePreset(prefs));
        }
    }

    @Test
    public void techniqueAndWaveHzPersistAndAStaleTechniqueIsRejected() {
        RumblePreferences.applyPreset(prefs, RumblePresets.DEEP_WAVE_60);
        SharedPreferences reloaded = reloadSavedValues();
        assertSame(RumblePresets.DEEP_WAVE_60, RumblePreferences.activePreset(reloaded));
        assertEquals(RumbleTechnique.HAPTIC_WAVE, RumblePreferences.readProfile(reloaded).technique);
        assertEquals(60, RumblePreferences.readProfile(reloaded).waveHz);
        prefs.edit().putInt(RumblePreferences.WAVE_HZ, 40).commit();
        assertNull(RumblePreferences.activePreset(prefs));
        prefs.edit().putInt(RumblePreferences.WAVE_HZ, 60)
                .putString(RumblePreferences.TECHNIQUE, RumbleTechnique.AMPLITUDE.name()).commit();
        assertNull(RumblePreferences.activePreset(prefs));
    }

    @Test
    public void profilesSavedBeforeTechniquesExistStayOnTheAmplitudePath() {
        RumblePreferences.applyPreset(prefs, RumblePresets.STRONGER);
        prefs.edit().remove(RumblePreferences.TECHNIQUE).remove(RumblePreferences.WAVE_HZ).commit();
        assertSame(RumblePresets.STRONGER, RumblePreferences.activePreset(prefs));
        assertEquals(RumbleTechnique.AMPLITUDE, RumblePreferences.readProfile(prefs).technique);
    }

    @Test
    public void everyAdvancedParameterEditAndEnableEditPersistsAsCustom() {
        RumbleTranslator.Profile p = RumblePresets.STRONGER.profile;
        RumbleTranslator.Profile[] edits = {
                new RumbleTranslator.Profile(2f, p.curve, p.minAmplitude, p.kickMs, p.lowWeight, p.highWeight, p.smoothing),
                new RumbleTranslator.Profile(p.gain, 2f, p.minAmplitude, p.kickMs, p.lowWeight, p.highWeight, p.smoothing),
                new RumbleTranslator.Profile(p.gain, p.curve, 140, p.kickMs, p.lowWeight, p.highWeight, p.smoothing),
                new RumbleTranslator.Profile(p.gain, p.curve, p.minAmplitude, 50, p.lowWeight, p.highWeight, p.smoothing),
                new RumbleTranslator.Profile(p.gain, p.curve, p.minAmplitude, p.kickMs, 1f, p.highWeight, p.smoothing),
                new RumbleTranslator.Profile(p.gain, p.curve, p.minAmplitude, p.kickMs, p.lowWeight, .5f, p.smoothing),
                new RumbleTranslator.Profile(p.gain, p.curve, p.minAmplitude, p.kickMs, p.lowWeight, p.highWeight, .5f)
        };
        for (RumbleTranslator.Profile edit : edits) {
            RumblePreferences.applyPreset(prefs, RumblePresets.STRONGER);
            RumblePreferences.saveCustom(prefs, edit, true);
            SharedPreferences reloaded = reloadSavedValues();
            assertNull(RumblePreferences.activePreset(reloaded));
            assertTrue(RumblePreferences.isEnabled(reloaded));
            assertProfile(edit, RumblePreferences.readProfile(reloaded));
        }
        RumblePreferences.applyPreset(prefs, RumblePresets.STRONGER);
        RumblePreferences.saveCustom(prefs, p, false);
        SharedPreferences reloaded = reloadSavedValues();
        assertNull(RumblePreferences.activePreset(reloaded));
        assertFalse(RumblePreferences.isEnabled(reloaded));
        assertProfile(p, RumblePreferences.readProfile(reloaded));
    }

    @Test
    public void staleIdentityIsRejectedForEachParameterAndEnableMismatch() {
        String[] keys = {RumblePreferences.GAIN, RumblePreferences.CURVE,
                RumblePreferences.MIN_AMPLITUDE, RumblePreferences.KICK_MS,
                RumblePreferences.LOW_WEIGHT, RumblePreferences.HIGH_WEIGHT,
                RumblePreferences.SMOOTHING, RumblePreferences.ENABLED};
        for (String key : keys) {
            RumblePreferences.applyPreset(prefs, RumblePresets.STRONGER);
            SharedPreferences.Editor edit = prefs.edit();
            if (key.equals(RumblePreferences.MIN_AMPLITUDE) || key.equals(RumblePreferences.KICK_MS)) {
                edit.putInt(key, 1);
            }
            else if (key.equals(RumblePreferences.ENABLED)) {
                edit.putBoolean(key, false);
            }
            else {
                edit.putFloat(key, .7f);
            }
            edit.commit();
            assertNull(key, RumblePreferences.activePreset(prefs));
            assertNull(key, RumblePreferences.activePreset(reloadSavedValues()));
        }
        RumblePreferences.applyPreset(prefs, RumblePresets.ORIGINAL);
        prefs.edit().putBoolean(RumblePreferences.ENABLED, true).commit();
        assertNull(RumblePreferences.activePreset(prefs));
    }

    @Test
    public void unknownOrEmptyIdentityIsCustomRatherThanGuessingFromValues() {
        for (String identity : new String[] {"NOT_A_PRESET", "", "CUSTOM"}) {
            RumblePreferences.applyPreset(prefs, RumblePresets.ORIGINAL);
            prefs.edit().putString(RumblePreferences.PRESET, identity).commit();
            assertNull(RumblePreferences.activePreset(prefs));
            assertNull(RumblePreferences.activePreset(reloadSavedValues()));
        }
    }

    /** Rebuild a separate preference store from stored values, without sharing selection state. */
    private SharedPreferences reloadSavedValues() {
        SharedPreferences reloaded = context.getSharedPreferences("rumble-presets-reloaded", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = reloaded.edit().clear();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Float) {
                editor.putFloat(entry.getKey(), (Float) value);
            }
            else if (value instanceof Integer) {
                editor.putInt(entry.getKey(), (Integer) value);
            }
            else if (value instanceof Boolean) {
                editor.putBoolean(entry.getKey(), (Boolean) value);
            }
            else if (value instanceof String) {
                editor.putString(entry.getKey(), (String) value);
            }
            else {
                throw new AssertionError("Unexpected preference type: " + value);
            }
        }
        assertTrue(editor.commit());
        return reloaded;
    }

    private static void assertSelection(SharedPreferences saved, RumblePresets preset) {
        assertSame(preset, RumblePreferences.activePreset(saved));
        assertEquals(preset.enabled, RumblePreferences.isEnabled(saved));
        assertProfile(preset.profile, RumblePreferences.readProfile(saved));
    }

    private static void assertProfile(RumbleTranslator.Profile expected, RumbleTranslator.Profile actual) {
        assertEquals(expected.gain, actual.gain, 0f);
        assertEquals(expected.curve, actual.curve, 0f);
        assertEquals(expected.minAmplitude, actual.minAmplitude);
        assertEquals(expected.kickMs, actual.kickMs);
        assertEquals(expected.lowWeight, actual.lowWeight, 0f);
        assertEquals(expected.highWeight, actual.highWeight, 0f);
        assertEquals(expected.smoothing, actual.smoothing, 0f);
        assertEquals(expected.technique, actual.technique);
        assertEquals(expected.waveHz, actual.waveHz);
    }
}
