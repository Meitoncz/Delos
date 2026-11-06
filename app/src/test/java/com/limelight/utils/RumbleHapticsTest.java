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
        // No preset combines a kick with a pulse any more, but Advanced edits in the Debug lab can
        RumbleTranslator.Profile profile = new RumbleTranslator.Profile(.9f, .8f, 50, 100, 1f, .35f, 0f,
                RumbleTechnique.PULSE, 20, 20);
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
