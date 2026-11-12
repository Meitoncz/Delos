package com.limelight.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The pure rules that keep a host rumble from outliving what the game meant. */
public class RumbleGuardTest {
    @Test
    public void motorValueBelowTheDeadZoneIsSilenceAtAnyLowByte() {
        for (int msb = 0; msb < RumbleGuard.DEAD_ZONE; msb++) {
            for (int lsb : new int[] {0, 1, 0x7F, 0xFF}) {
                assertEquals(0, RumbleGuard.gate((short) ((msb << 8) | lsb)));
            }
        }
    }

    @Test
    public void motorValueFromTheDeadZoneUpIsPassedThroughUnchanged() {
        for (int msb = RumbleGuard.DEAD_ZONE; msb <= 255; msb++) {
            for (int lsb : new int[] {0, 1, 0xFF}) {
                short raw = (short) ((msb << 8) | lsb);
                assertEquals(raw, RumbleGuard.gate(raw));
            }
        }
    }

    @Test
    public void unsignedValuesStoredInANegativeShortAreNotMistakenForSilence() {
        assertEquals((short) 0xFFFF, RumbleGuard.gate((short) 0xFFFF));
        assertEquals((short) 0x8000, RumbleGuard.gate((short) 0x8000));
    }

    @Test
    public void theStuckResidualFromTheOdinIsSilenceAndAFloorCannotRaiseIt() {
        // The last host value before the stuck vibration was 3/255 mapped, i.e. a motor MSB of 4
        short residual = RumbleGuard.gate((short) 0x0400);
        assertEquals(0, residual);
        RumbleTranslator.Profile floor = RumblePresets.PULSE_25.profile;
        assertEquals(0, RumbleTranslator.single(residual, 0, floor, null, 0));
        // Without the gate the floor of the Pulsing preset would play it for as long as nothing clears it
        assertTrue(RumbleTranslator.single(0x0400, 0, floor, null, 0) >= floor.minAmplitude);
    }

    @Test
    public void gatedZeroMotorsGiveTheSameResultAsRawZero() {
        assertEquals(0, RumbleTranslator.single(RumbleGuard.gate((short) 0x0F80), RumbleGuard.gate((short) 0x0A00),
                RumbleTranslator.ORIGINAL, new RumbleTranslator.State(), 0));
        assertEquals(0, RumbleTranslator.motor(RumbleGuard.gate((short) 0x0F80),
                RumbleTranslator.ORIGINAL, new RumbleTranslator.State(), 0));
    }

    @Test
    public void nothingIsWatchedWhileNothingVibrates() {
        assertEquals(Long.MAX_VALUE, RumbleGuard.holdDelay(1_000_000, 0, false));
        assertFalse(RumbleGuard.holdExpired(1_000_000, 0, false));
    }

    @Test
    public void aVibratingRumbleExpiresExactlyAfterTheHoldLimit() {
        long renewed = 12_345;
        assertEquals(RumbleGuard.HOLD_MS, RumbleGuard.holdDelay(renewed, renewed, true));
        assertEquals(1, RumbleGuard.holdDelay(renewed + RumbleGuard.HOLD_MS - 1, renewed, true));
        assertFalse(RumbleGuard.holdExpired(renewed + RumbleGuard.HOLD_MS - 1, renewed, true));
        assertEquals(0, RumbleGuard.holdDelay(renewed + RumbleGuard.HOLD_MS, renewed, true));
        assertTrue(RumbleGuard.holdExpired(renewed + RumbleGuard.HOLD_MS, renewed, true));
    }

    @Test
    public void anOverdueRumbleStaysExpiredAndNeverReportsANegativeDelay() {
        assertEquals(0, RumbleGuard.holdDelay(1_000_000, 0, true));
        assertTrue(RumbleGuard.holdExpired(1_000_000, 0, true));
    }

    @Test
    public void renewingRestartsTheHoldLimit() {
        long first = 0;
        long renewed = first + 4_000;
        assertTrue(RumbleGuard.holdExpired(first + RumbleGuard.HOLD_MS, first, true));
        assertFalse(RumbleGuard.holdExpired(first + RumbleGuard.HOLD_MS, renewed, true));
        assertEquals(RumbleGuard.HOLD_MS - 1_000, RumbleGuard.holdDelay(first + RumbleGuard.HOLD_MS, renewed, true));
    }

    @Test
    public void theHoldLimitIsAFewSecondsNotTheOldMinute() {
        assertTrue(RumbleGuard.HOLD_MS >= 2_000 && RumbleGuard.HOLD_MS <= 10_000);
    }
}
