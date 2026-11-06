package com.limelight.utils;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class RumbleHapticsTest {
    @Test
    public void firstTenPresetsKeepTheAmplitudeTechniqueAndTheNewOnesSelectTheirOwn() {
        RumblePresets[] all = RumblePresets.values();
        assertEquals(15, all.length);
        for (int i = 0; i < 10; i++) {
            assertSame(RumbleTechnique.AMPLITUDE, all[i].profile.technique);
        }
        assertSame(RumbleTechnique.PRIMITIVE_THUD, RumblePresets.THUD.profile.technique);
        assertSame(RumbleTechnique.PRIMITIVE_LOW_TICK, RumblePresets.LOW_TICK.profile.technique);
        assertSame(RumbleTechnique.PULSE_25, RumblePresets.PULSE_25.profile.technique);
        assertSame(RumbleTechnique.PULSE_40, RumblePresets.PULSE_40.profile.technique);
        assertSame(RumbleTechnique.PULSE_60, RumblePresets.PULSE_60.profile.technique);
        assertSame(RumbleTechnique.AMPLITUDE, RumbleTechnique.fromName(null));
        assertSame(RumbleTechnique.AMPLITUDE, RumbleTechnique.fromName("nonsense"));
        assertSame(RumbleTechnique.AMPLITUDE, RumbleTechnique.fromName("HAPTIC_WAVE"));
    }

    @Test
    public void unsupportedTechniquesReportAReasonAndSupportedOnesDoNot() {
        RumbleCapabilities weak = new RumbleCapabilities(1, false, false, false, "CLICK");
        assertNull(weak.unavailableReason(RumbleTechnique.AMPLITUDE));
        assertNotNull(weak.unavailableReason(RumbleTechnique.PRIMITIVE_THUD));
        assertNotNull(weak.unavailableReason(RumbleTechnique.PRIMITIVE_LOW_TICK));
        for (RumbleTechnique pulse : new RumbleTechnique[] {RumbleTechnique.PULSE_25, RumbleTechnique.PULSE_40,
                RumbleTechnique.PULSE_60}) {
            assertNotNull(weak.unavailableReason(pulse));
        }
        RumbleCapabilities odin = new RumbleCapabilities(1, true, true, true, "all");
        for (RumbleTechnique technique : RumbleTechnique.values()) {
            assertNull(odin.unavailableReason(technique));
        }
    }

    @Test
    public void burstIsTwelvePrimitivesWithTheTechniqueDelayAndAmplitudeScale() {
        assertEquals(12, RumbleHaptics.BURST_COUNT);
        assertEquals(200, RumbleHaptics.BURST_REISSUE_MS);
        assertEquals(12, RumbleHaptics.burstDelayMs(RumbleTechnique.PRIMITIVE_THUD));
        assertEquals(10, RumbleHaptics.burstDelayMs(RumbleTechnique.PRIMITIVE_LOW_TICK));
        assertEquals(0, RumbleHaptics.burstDelayMs(RumbleTechnique.AMPLITUDE));
        assertEquals(0, RumbleHaptics.burstDelayMs(RumbleTechnique.PULSE_40));
        assertEquals(0f, RumbleHaptics.primitiveScale(0), 0f);
        assertEquals(1f, RumbleHaptics.primitiveScale(255), 0f);
        assertEquals(100 / 255f, RumbleHaptics.primitiveScale(100), 0f);
        assertEquals(0f, RumbleHaptics.primitiveScale(-3), 0f);
        assertEquals(1f, RumbleHaptics.primitiveScale(300), 0f);
    }

    @Test
    public void pulseTimingsFollowThePeriodAtHalfDuty() {
        assertEquals(40, RumbleHaptics.pulsePeriodMs(RumbleTechnique.PULSE_25));
        assertEquals(25, RumbleHaptics.pulsePeriodMs(RumbleTechnique.PULSE_40));
        assertEquals(16, RumbleHaptics.pulsePeriodMs(RumbleTechnique.PULSE_60));
        assertEquals(0, RumbleHaptics.pulsePeriodMs(RumbleTechnique.PRIMITIVE_THUD));
        assertArrayEquals(new long[] {20, 20}, RumbleHaptics.pulseTimings(40));
        assertArrayEquals(new long[] {13, 12}, RumbleHaptics.pulseTimings(25));
        assertArrayEquals(new long[] {8, 8}, RumbleHaptics.pulseTimings(16));
    }

    @Test
    public void pulseAmplitudesUseTheTranslatedAmplitudeWithTheFloorThenSilence() {
        assertArrayEquals(new int[] {255, 0}, RumbleHaptics.pulseAmplitudes(255, 50));
        assertArrayEquals(new int[] {200, 0}, RumbleHaptics.pulseAmplitudes(200, 50));
        assertArrayEquals(new int[] {50, 0}, RumbleHaptics.pulseAmplitudes(10, 50));
        assertArrayEquals(new int[] {1, 0}, RumbleHaptics.pulseAmplitudes(1, 0));
        assertArrayEquals(new int[] {0, 0}, RumbleHaptics.pulseAmplitudes(0, 0));
        assertArrayEquals(new int[] {255, 0}, RumbleHaptics.pulseAmplitudes(300, 50));
    }
}
