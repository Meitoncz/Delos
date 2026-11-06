package com.limelight.utils;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RumbleHapticsTest {
    @Test
    public void firstTenPresetsStayAmplitudeAndTheLastEightAreTheAgreedPulses() {
        RumblePresets[] all = RumblePresets.values();
        assertEquals(18, all.length);
        for (int i = 0; i < 10; i++) {
            assertSame(RumbleTechnique.AMPLITUDE, all[i].profile.technique);
            assertEquals(0, all[i].profile.pulseOnMs);
            assertEquals(0, all[i].profile.pulseOffMs);
        }
        assertPulse(RumblePresets.PULSE_25_KICK, "Pulse 25 + kick", 20, 20, .9f, .8f, 50, 100);
        assertPulse(RumblePresets.PULSE_20, "Pulse 20 Hz", 25, 25, 1f, .8f, 50, 0);
        assertPulse(RumblePresets.PULSE_15, "Pulse 15 Hz", 33, 33, 1f, .8f, 50, 0);
        assertPulse(RumblePresets.PULSE_25_GENTLE, "Pulse 25 gentle", 20, 20, .7f, 1.3f, 0, 0);
        assertPulse(RumblePresets.PULSE_25_HEAVY, "Pulse 25 heavy", 28, 12, 1f, .8f, 50, 0);
        assertPulse(RumblePresets.PULSE_25, "Pulse 25 Hz", 20, 20, 1f, .8f, 50, 0);
        assertPulse(RumblePresets.PULSE_40, "Pulse 40 Hz", 13, 12, 1f, .8f, 50, 0);
        assertPulse(RumblePresets.PULSE_60, "Pulse 60 Hz", 8, 8, 1f, .8f, 50, 0);
        for (int i = 10; i < 18; i++) {
            assertEquals("Pulse", all[i].label.substring(0, 5));
        }
        assertSame(RumblePresets.PULSE_25_KICK, all[10]);
        assertSame(RumblePresets.PULSE_25, all[15]);
        assertSame(RumblePresets.PULSE_60, all[17]);
    }

    private static void assertPulse(RumblePresets preset, String label, int on, int off,
                                    float gain, float curve, int floor, int kick) {
        RumbleTranslator.Profile p = preset.profile;
        assertEquals(label, preset.label);
        assertSame(RumbleTechnique.PULSE, p.technique);
        assertEquals(on, p.pulseOnMs);
        assertEquals(off, p.pulseOffMs);
        assertEquals(gain, p.gain, 0f);
        assertEquals(curve, p.curve, 0f);
        assertEquals(floor, p.minAmplitude);
        assertEquals(kick, p.kickMs);
    }

    @Test
    public void techniqueNamesFromRemovedAndOlderBuildsLoadSafely() {
        assertSame(RumbleTechnique.AMPLITUDE, RumbleTechnique.fromName(null));
        assertSame(RumbleTechnique.AMPLITUDE, RumbleTechnique.fromName("nonsense"));
        assertSame(RumbleTechnique.AMPLITUDE, RumbleTechnique.fromName("HAPTIC_WAVE"));
        assertSame(RumbleTechnique.AMPLITUDE, RumbleTechnique.fromName("PRIMITIVE_THUD"));
        assertSame(RumbleTechnique.AMPLITUDE, RumbleTechnique.fromName("PRIMITIVE_LOW_TICK"));
        assertSame(RumbleTechnique.PULSE, RumbleTechnique.fromName("PULSE"));
        assertSame(RumbleTechnique.PULSE, RumbleTechnique.fromName("PULSE_25"));
        assertArrayEquals(new int[] {20, 20}, RumbleHaptics.legacyPulse("PULSE_25"));
        assertArrayEquals(new int[] {13, 12}, RumbleHaptics.legacyPulse("PULSE_40"));
        assertArrayEquals(new int[] {8, 8}, RumbleHaptics.legacyPulse("PULSE_60"));
        assertNull(RumbleHaptics.legacyPulse("PULSE"));
        assertNull(RumbleHaptics.legacyPulse("AMPLITUDE"));
    }

    @Test
    public void pulsesNeedAmplitudeControlAndNothingElseDoes() {
        RumbleCapabilities weak = new RumbleCapabilities(1, false);
        assertNull(weak.unavailableReason(RumbleTechnique.AMPLITUDE));
        assertNotNull(weak.unavailableReason(RumbleTechnique.PULSE));
        RumbleCapabilities odin = new RumbleCapabilities(1, true);
        for (RumbleTechnique technique : RumbleTechnique.values()) {
            assertNull(odin.unavailableReason(technique));
        }
    }

    @Test
    public void pulseTimingsAreTheProfileOnAndOffAndNeverBelowOneMillisecond() {
        assertArrayEquals(new long[] {20, 20}, RumbleHaptics.pulseTimings(20, 20));
        assertArrayEquals(new long[] {28, 12}, RumbleHaptics.pulseTimings(28, 12));
        assertArrayEquals(new long[] {33, 33}, RumbleHaptics.pulseTimings(33, 33));
        assertArrayEquals(new long[] {1, 1}, RumbleHaptics.pulseTimings(0, -5));
    }

    @Test
    public void pulseAmplitudesFollowTheRequestWithTheFloorAndStayInRange() {
        assertArrayEquals(new int[] {255, 0}, RumbleHaptics.pulseAmplitudes(255, 50));
        assertArrayEquals(new int[] {200, 0}, RumbleHaptics.pulseAmplitudes(200, 50));
        assertArrayEquals(new int[] {50, 0}, RumbleHaptics.pulseAmplitudes(10, 50));
        assertArrayEquals(new int[] {1, 0}, RumbleHaptics.pulseAmplitudes(1, 0));
        assertArrayEquals(new int[] {0, 0}, RumbleHaptics.pulseAmplitudes(0, 0));
        assertArrayEquals(new int[] {255, 0}, RumbleHaptics.pulseAmplitudes(300, 50));
        assertArrayEquals(new int[] {255, 0}, RumbleHaptics.pulseAmplitudes(0, 255));
    }

    @Test
    public void aKickedPulseStartsAtFullAmplitudeThenFollowsTheRequestAfterTheKick() {
        RumbleTranslator.Profile profile = RumblePresets.PULSE_25_KICK.profile;
        RumbleTranslator.State state = new RumbleTranslator.State();
        // Raw 0x4000 on both motors: shaped below 255, so the kick is visible
        int start = RumbleTranslator.single(0x4000, 0x4000, profile, state, 1000);
        assertEquals(255, start);
        assertArrayEquals(new int[] {255, 0}, RumbleHaptics.pulseAmplitudes(start, profile.minAmplitude));
        assertEquals(255, RumbleTranslator.single(0x4000, 0x4000, profile, state, 1099));
        int after = RumbleTranslator.single(0x4000, 0x4000, profile, state, 1100);
        assertEquals(RumbleTranslator.single(0x4000, 0x4000, profile, null, 1100), after);
        assertArrayEquals(new int[] {after, 0}, RumbleHaptics.pulseAmplitudes(after, profile.minAmplitude));
        assertArrayEquals(new long[] {20, 20}, RumbleHaptics.pulseTimings(profile.pulseOnMs, profile.pulseOffMs));
        // Zero stops at once and the next request kicks again
        assertEquals(0, RumbleTranslator.single(0, 0, profile, state, 1200));
        assertEquals(255, RumbleTranslator.single(0x4000, 0x4000, profile, state, 1210));
    }

    @Test
    public void boundaryRequestsOfEveryPulsePresetStayWithinZeroAnd255() {
        for (RumblePresets preset : RumblePresets.values()) {
            if (!preset.profile.technique.isPulse()) {
                continue;
            }
            for (int raw : new int[] {0, 0x00FF, 0x0100, 0xFFFF}) {
                int amplitude = RumbleTranslator.single(raw, raw, preset.profile, null, 0);
                int[] amplitudes = RumbleHaptics.pulseAmplitudes(amplitude, preset.profile.minAmplitude);
                assertEquals(0, amplitudes[1]);
                if (raw == 0) {
                    assertEquals(0, amplitude);
                }
                else {
                    assertTrue(amplitudes[0] >= preset.profile.minAmplitude);
                    assertTrue(amplitudes[0] <= 255);
                }
            }
            assertEquals(255, RumbleHaptics.pulseAmplitudes(255, preset.profile.minAmplitude)[0]);
        }
    }
}
