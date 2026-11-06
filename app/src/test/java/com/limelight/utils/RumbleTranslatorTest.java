package com.limelight.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RumbleTranslatorTest {
    private static RumbleTranslator.Profile profile(float gain, float curve, int floor,
                                                     int kickMs, float smoothing) {
        return new RumbleTranslator.Profile(gain, curve, floor, kickMs, 0.80f, 0.33f, smoothing);
    }

    @Test
    public void originalMatchesLegacyForEveryPairOfMotorMsbs() {
        RumbleTranslator.State state = new RumbleTranslator.State();
        for (int low = 0; low <= 255; low++) {
            for (int high = 0; high <= 255; high++) {
                int expected = Math.min(255, (int) (low * 0.80 + high * 0.33));
                assertEquals(expected, RumbleTranslator.single(low << 8, high << 8,
                        RumbleTranslator.ORIGINAL, null, 0));
                assertEquals(expected, RumbleTranslator.single((low << 8) | 255,
                        (high << 8) | 255, RumbleTranslator.ORIGINAL, state, 100));
                assertEquals(Long.MAX_VALUE, state.nextUpdateDelay(100));
            }
        }
    }

    @Test
    public void originalMotorMatchesLegacyForAllUnsignedInputs() {
        RumbleTranslator.State state = new RumbleTranslator.State();
        for (int raw = 0; raw <= 65535; raw++) {
            assertEquals((raw >> 8) & 255, RumbleTranslator.motor(raw,
                    RumbleTranslator.ORIGINAL, state, 100));
            assertEquals(Long.MAX_VALUE, state.nextUpdateDelay(100));
        }
    }

    @Test
    public void originalBoundaryGridDropsLowByteExactly() {
        int[] boundaries = {0, 1, 255, 256, 257, 32767, 32768, 65280, 65535};
        for (int low : boundaries) {
            for (int high : boundaries) {
                int expected = Math.min(255,
                        (int) (((low >> 8) & 255) * 0.80 + ((high >> 8) & 255) * 0.33));
                assertEquals(expected, RumbleTranslator.single(low, high,
                        RumbleTranslator.ORIGINAL, null, 0));
            }
        }
    }

    @Test
    public void freshlyConstructedOriginalProfileAlsoUsesExactDoubleWeights() {
        RumbleTranslator.Profile original = profile(1f, 1f, 0, 0, 0f);
        for (int low = 0; low <= 255; low++) {
            for (int high = 0; high <= 255; high++) {
                assertEquals(Math.min(255, (int) (low * 0.80 + high * 0.33)),
                        RumbleTranslator.single(low << 8, high << 8, original, null, 0));
            }
        }
    }

    @Test
    public void floorAppliesEvenWhenOnlyDiscardedLowByteIsNonzero() {
        RumbleTranslator.Profile floor = profile(1f, 1f, 220, 0, 0f);
        for (int raw : new int[] {1, 255, 256, 65535}) {
            assertTrue(RumbleTranslator.motor(raw, floor, null, 0) >= 220);
            assertTrue(RumbleTranslator.single(raw, 0, floor, null, 0) >= 220);
            assertTrue(RumbleTranslator.single(0, raw, floor, null, 0) >= 220);
        }
        assertEquals(0, RumbleTranslator.motor(0, floor, null, 0));
        assertEquals(0, RumbleTranslator.single(0, 0, floor, null, 0));
    }

    @Test
    public void fullStrengthFloorDoesNotTurnRawZeroOn() {
        RumbleTranslator.Profile floor = profile(1f, 1f, 255, 0, 0f);
        assertEquals(255, RumbleTranslator.motor(1, floor, null, 0));
        assertEquals(255, RumbleTranslator.single(0, 1, floor, null, 0));
        assertEquals(0, RumbleTranslator.single(0, 0, floor, null, 0));
    }

    @Test
    public void gainAndCurveShapeTheLegacyAmplitudeAndClampAt255() {
        int raw = 64 << 8;
        assertEquals(128, RumbleTranslator.motor(raw, profile(2f, 1f, 0, 0, 0f), null, 0));
        assertEquals(32, RumbleTranslator.motor(raw, profile(0.5f, 1f, 0, 0, 0f), null, 0));
        assertEquals((int) (Math.pow(64 / 255.0, 0.5) * 255),
                RumbleTranslator.motor(raw, profile(1f, 0.5f, 0, 0, 0f), null, 0));
        assertEquals((int) (Math.pow(64 / 255.0, 3.0) * 255),
                RumbleTranslator.motor(raw, profile(1f, 3f, 0, 0, 0f), null, 0));
        assertEquals(255, RumbleTranslator.motor(65535,
                profile(3f, 0.5f, 0, 0, 0f), null, 0));
        assertEquals(255, RumbleTranslator.single(65535, 65535,
                profile(3f, 0.5f, 0, 0, 0f), null, 0));
    }

    @Test
    public void customSingleWeightsDoNotAffectIndividualMotors() {
        RumbleTranslator.Profile weights = new RumbleTranslator.Profile(1f, 1f, 0, 0, 0f, 1f, 0f);
        assertEquals(0, RumbleTranslator.single(65535, 0, weights, null, 0));
        assertEquals(255, RumbleTranslator.single(0, 65535, weights, null, 0));
        assertEquals(255, RumbleTranslator.motor(65535, weights, null, 0));
    }

    @Test
    public void kickHasExactDeadlineAndSettlesWithoutAnotherHostEvent() {
        RumbleTranslator.Profile kick = profile(1f, 1f, 0, 60, 0f);
        RumbleTranslator.State state = new RumbleTranslator.State();
        assertEquals(255, RumbleTranslator.motor(64 << 8, kick, state, 100));
        assertEquals(60, state.nextUpdateDelay(100));
        assertEquals(255, RumbleTranslator.motor(64 << 8, kick, state, 159));
        assertEquals(1, state.nextUpdateDelay(159));
        // Playback uses the retained raw request for this scheduled deadline call.
        assertEquals(64, RumbleTranslator.motor(64 << 8, kick, state, 160));
        assertEquals(Long.MAX_VALUE, state.nextUpdateDelay(160));
    }

    @Test
    public void nonzeroChangesDoNotRestartKickButRawZeroDoes() {
        RumbleTranslator.Profile kick = profile(1f, 1f, 0, 120, 0f);
        RumbleTranslator.State state = new RumbleTranslator.State();
        assertEquals(255, RumbleTranslator.motor(1, kick, state, 0));
        assertEquals(255, RumbleTranslator.motor(100 << 8, kick, state, 100));
        assertEquals(100, RumbleTranslator.motor(100 << 8, kick, state, 120));
        assertEquals(20, RumbleTranslator.motor(20 << 8, kick, state, 121));
        assertEquals(0, RumbleTranslator.motor(0, kick, state, 122));
        assertEquals(Long.MAX_VALUE, state.nextUpdateDelay(122));
        assertEquals(255, RumbleTranslator.motor(1, kick, state, 123));
        assertEquals(0, RumbleTranslator.motor(1, kick, state, 243));
        assertEquals(Long.MAX_VALUE, state.nextUpdateDelay(243));
    }

    @Test
    public void zeroStopsAndResetsImmediatelyDuringKickAndSmoothing() {
        RumbleTranslator.Profile settings = profile(1f, 1f, 220, 120, 1f);
        RumbleTranslator.State state = new RumbleTranslator.State();
        assertEquals(255, RumbleTranslator.single(1, 0, settings, state, 0));
        assertEquals(0, RumbleTranslator.single(0, 0, settings, state, 10));
        assertEquals(Long.MAX_VALUE, state.nextUpdateDelay(10));
        assertEquals(255, RumbleTranslator.single(0, 1, settings, state, 20));
        assertEquals(250, RumbleTranslator.single(0, 1, settings, state, 160));
        assertEquals(0, RumbleTranslator.single(0, 0, settings, state, 161));
        assertEquals(Long.MAX_VALUE, state.nextUpdateDelay(161));
    }

    @Test
    public void smoothingZeroIsImmediateAndOneIs255UnitsPerSecond() {
        RumbleTranslator.State immediate = new RumbleTranslator.State();
        RumbleTranslator.motor(65535, RumbleTranslator.ORIGINAL, immediate, 0);
        assertEquals(1, RumbleTranslator.motor(256, RumbleTranslator.ORIGINAL, immediate, 0));
        assertEquals(Long.MAX_VALUE, immediate.nextUpdateDelay(0));

        RumbleTranslator.Profile slow = profile(1f, 1f, 0, 0, 1f);
        RumbleTranslator.State state = new RumbleTranslator.State();
        assertEquals(255, RumbleTranslator.motor(65535, slow, state, 0));
        assertEquals(255, RumbleTranslator.motor(256, slow, state, 0));
        assertEquals(16, state.nextUpdateDelay(0));
        assertEquals(230, RumbleTranslator.motor(256, slow, state, 100));
        assertEquals(204, RumbleTranslator.motor(256, slow, state, 200));
        assertEquals(1, RumbleTranslator.motor(256, slow, state, 1000));
        assertEquals(Long.MAX_VALUE, state.nextUpdateDelay(1000));
    }

    @Test
    public void smoothingAccumulatesFractionalStepsIndependentlyOfTickFrequency() {
        RumbleTranslator.Profile slow = profile(1f, 1f, 0, 0, 1f);
        RumbleTranslator.State frequent = new RumbleTranslator.State();
        RumbleTranslator.State sparse = new RumbleTranslator.State();
        RumbleTranslator.motor(256, slow, frequent, 0);
        RumbleTranslator.motor(256, slow, sparse, 0);
        for (int now = 1; now < 100; now++) {
            RumbleTranslator.motor(65535, slow, frequent, now);
        }
        assertEquals(27, RumbleTranslator.motor(65535, slow, frequent, 100));
        assertEquals(27, RumbleTranslator.motor(65535, slow, sparse, 100));
        assertEquals(255, RumbleTranslator.motor(65535, slow, frequent, 1000));
        assertEquals(Long.MAX_VALUE, frequent.nextUpdateDelay(1000));
    }

    @Test
    public void halfSmoothingTraversesFullScaleInHalfASecond() {
        RumbleTranslator.Profile half = profile(1f, 1f, 0, 0, 0.5f);
        RumbleTranslator.State state = new RumbleTranslator.State();
        RumbleTranslator.motor(65535, half, state, 0);
        assertEquals(128, RumbleTranslator.motor(1, half, state, 250));
        assertEquals(0, RumbleTranslator.motor(1, half, state, 500));
        assertEquals(Long.MAX_VALUE, state.nextUpdateDelay(500));
    }

    @Test
    public void smoothingAfterKickCountsOnlyTimeSinceKickDeadline() {
        RumbleTranslator.Profile settings = profile(1f, 1f, 0, 100, 1f);
        RumbleTranslator.State state = new RumbleTranslator.State();
        assertEquals(255, RumbleTranslator.motor(256, settings, state, 0));
        assertEquals(255, RumbleTranslator.motor(256, settings, state, 100));
        assertEquals(230, RumbleTranslator.motor(256, settings, state, 200));
        assertEquals(1, RumbleTranslator.motor(256, settings, state, 1100));
        assertEquals(Long.MAX_VALUE, state.nextUpdateDelay(1100));
    }

    @Test
    public void independentlyAllocatedStatesNeverShareKicksOrHistory() {
        RumbleTranslator.Profile kick = profile(1f, 1f, 0, 60, 1f);
        RumbleTranslator.State first = new RumbleTranslator.State();
        RumbleTranslator.State second = new RumbleTranslator.State();
        RumbleTranslator.motor(256, kick, first, 0);
        assertEquals(255, RumbleTranslator.motor(256, kick, second, 1000));
        assertEquals(15, RumbleTranslator.motor(256, kick, first, 1000));
        assertEquals(0, RumbleTranslator.motor(0, kick, first, 1010));
        assertEquals(255, RumbleTranslator.motor(256, kick, second, 1010));
    }

    @Test
    public void nullStateIsStatelessAndDoesNotInventKickHistory() {
        RumbleTranslator.Profile settings = profile(1f, 1f, 86, 120, 1f);
        assertEquals(86, RumbleTranslator.motor(1, settings, null, 0));
        assertEquals(86, RumbleTranslator.single(0, 1, settings, null, 0));
        assertEquals(0, RumbleTranslator.motor(0, settings, null, 0));
    }
}
