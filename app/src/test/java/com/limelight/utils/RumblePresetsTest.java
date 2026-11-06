package com.limelight.utils;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RumblePresetsTest {

    @Test
    public void thereAreExactlyTheFourOfferedFeels() {
        RumblePresets[] all = RumblePresets.values();
        assertArrayEquals(new RumblePresets[] {RumblePresets.ORIGINAL, RumblePresets.GENTLE,
                RumblePresets.KICK_ONLY, RumblePresets.PULSE_25}, all);
        assertEquals("Original", RumblePresets.ORIGINAL.label);
        assertEquals("Soft", RumblePresets.GENTLE.label);
        assertEquals("Kick-start", RumblePresets.KICK_ONLY.label);
        assertEquals("Pulsing", RumblePresets.PULSE_25.label);
        assertFalse(RumblePresets.ORIGINAL.enabled);
        assertProfile(RumblePresets.GENTLE, .7f, 1.3f, 0, 0, RumbleTechnique.AMPLITUDE, 0, 0);
        assertProfile(RumblePresets.KICK_ONLY, 1f, 1f, 0, 100, RumbleTechnique.AMPLITUDE, 0, 0);
        assertProfile(RumblePresets.PULSE_25, 1f, .8f, 50, 0, RumbleTechnique.PULSE, 20, 20);
        for (RumblePresets preset : all) {
            assertTrue(preset.name(), preset.enabled || preset == RumblePresets.ORIGINAL);
        }
    }

    private static void assertProfile(RumblePresets preset, float gain, float curve, int floor, int kickMs,
                                      RumbleTechnique technique, int onMs, int offMs) {
        RumbleTranslator.Profile p = preset.profile;
        assertEquals(gain, p.gain, 0f);
        assertEquals(curve, p.curve, 0f);
        assertEquals(floor, p.minAmplitude);
        assertEquals(kickMs, p.kickMs);
        assertEquals(technique, p.technique);
        assertEquals(onMs, p.pulseOnMs);
        assertEquals(offMs, p.pulseOffMs);
        boolean pulse = technique == RumbleTechnique.PULSE;
        assertEquals(pulse ? 1f : .80f, p.lowWeight, 0f);
        assertEquals(pulse ? .35f : .33f, p.highWeight, 0f);
        assertEquals(0f, p.smoothing, 0f);
    }

    @Test
    public void everyPresetFitsAllAdvancedControlBounds() {
        for (RumblePresets preset : RumblePresets.values()) {
            RumbleTranslator.Profile p = preset.profile;
            assertTrue(p.gain >= .5f && p.gain <= 3f);
            assertTrue(p.curve >= .5f && p.curve <= 3f);
            assertTrue(p.minAmplitude >= 0 && p.minAmplitude <= 255);
            assertTrue(p.kickMs >= 0 && p.kickMs <= 120);
            assertTrue(p.lowWeight >= 0f && p.lowWeight <= 1f);
            assertTrue(p.highWeight >= 0f && p.highWeight <= 1f);
            assertTrue(p.smoothing >= 0f && p.smoothing <= 1f);
        }
    }

    @Test
    public void everyPresetKeepsBoundaryInputsInRangeAndZeroSilent() {
        int[] boundaries = {0, 1, 255, 256, 257, 32767, 32768, 65280, 65535};
        for (RumblePresets preset : RumblePresets.values()) {
            for (int low : boundaries) {
                for (int high : boundaries) {
                    RumbleTranslator.State state = new RumbleTranslator.State();
                    assertAmplitude(RumbleTranslator.single(low, high, preset.profile, null, 0));
                    assertAmplitude(RumbleTranslator.motor(low, preset.profile, null, 0));
                    for (long now : new long[] {0, 1, 50, 60, 80, 100, 120, 1000}) {
                        assertAmplitude(RumbleTranslator.single(low, high, preset.profile, state, now));
                    }
                    assertEquals(0, RumbleTranslator.single(0, 0, preset.profile, state, 1001));
                    assertEquals(0, RumbleTranslator.motor(0, preset.profile,
                            new RumbleTranslator.State(), 0));
                }
            }
        }
    }

    @Test
    public void originalPresetMatchesLegacyGridAndEveryIndividualMotorInput() {
        assertFalse(RumblePresets.ORIGINAL.enabled);
        RumbleTranslator.Profile p = RumblePresets.ORIGINAL.profile;
        RumbleTranslator.State state = new RumbleTranslator.State();
        for (int low = 0; low <= 255; low++) {
            for (int high = 0; high <= 255; high++) {
                int expected = Math.min(255, (int) (low * .80 + high * .33));
                assertEquals(expected, RumbleTranslator.single(low << 8, high << 8, p, null, 0));
                assertEquals(expected, RumbleTranslator.single((low << 8) | 255,
                        (high << 8) | 255, p, state, 100));
            }
        }
        for (int raw = 0; raw <= 65535; raw++) {
            assertEquals(raw >> 8, RumbleTranslator.motor(raw, p, state, 100));
        }
    }

    private static void assertAmplitude(int amplitude) {
        assertTrue(amplitude >= 0 && amplitude <= 255);
    }
}
