package com.limelight.utils;

import android.os.CombinedVibration;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the vibrator is told by {@link RumblePlayback}: a host rumble nothing clears must end by itself, and a
 * residual below the dead zone must never start. Time is the Robolectric main looper's clock.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class RumblePlaybackGuardTest {
    private static final short HALF = (short) 0x8000;
    private static final short RESIDUAL = (short) 0x0400;

    private Vibrator vibrator;
    private RumblePlayback playback;

    @Before
    public void setUp() {
        vibrator = mock(Vibrator.class);
        when(vibrator.hasAmplitudeControl()).thenReturn(true);
        playback = new RumblePlayback(RumbleTranslator.ORIGINAL);
    }

    @After
    public void tearDown() {
        playback.stop();
    }

    private static void idle(long ms) {
        ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS);
    }

    private static long durationOf(VibrationEffect effect) throws Exception {
        return (Long) VibrationEffect.class.getMethod("getDuration").invoke(effect);
    }

    @Test
    public void everyEffectIsBoundedByTheHoldLimit() throws Exception {
        playback.single(vibrator, HALF, (short) 0);
        ArgumentCaptor<VibrationEffect> effect = ArgumentCaptor.forClass(VibrationEffect.class);
        verify(vibrator).vibrate(effect.capture(), any(VibrationAttributes.class));
        assertEquals(RumbleGuard.HOLD_MS, durationOf(effect.getValue()));
    }

    @Test
    public void aRumbleNothingClearsIsCancelledWhenTheHoldLimitPasses() {
        playback.single(vibrator, HALF, (short) 0);
        idle(RumbleGuard.HOLD_MS - 1);
        verify(vibrator, never()).cancel();
        idle(1);
        verify(vibrator, times(1)).cancel();
        assertEquals(0, playback.getLastAmplitude());
    }

    @Test
    public void everyHostEventRenewsTheHold() {
        playback.single(vibrator, HALF, (short) 0);
        idle(4_000);
        playback.single(vibrator, HALF, (short) 0);
        idle(4_000);
        verify(vibrator, never()).cancel();
        idle(1_000);
        verify(vibrator, times(1)).cancel();
    }

    @Test
    public void aCancelledRumbleStartsAgainWithTheNextHostEvent() {
        playback.single(vibrator, HALF, (short) 0);
        idle(RumbleGuard.HOLD_MS);
        playback.single(vibrator, HALF, (short) 0);
        verify(vibrator, times(2)).vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
        idle(RumbleGuard.HOLD_MS);
        verify(vibrator, times(2)).cancel();
    }

    @Test
    public void aHostZeroCancelsAtOnceAndLeavesNothingToWatch() {
        playback.single(vibrator, HALF, (short) 0);
        playback.single(vibrator, (short) 0, (short) 0);
        verify(vibrator, times(1)).cancel();
        idle(RumbleGuard.HOLD_MS * 3);
        verify(vibrator, times(1)).cancel();
    }

    @Test
    public void aResidualBelowTheDeadZoneNeverStartsAVibration() {
        playback.single(vibrator, RESIDUAL, (short) 0);
        playback.single(vibrator, (short) 0, RESIDUAL);
        verify(vibrator, never()).vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
        idle(RumbleGuard.HOLD_MS * 3);
        verify(vibrator, never()).vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @Test
    public void aResidualAfterARealRumbleCancelsTheRumble() {
        playback.single(vibrator, HALF, (short) 0);
        playback.single(vibrator, RESIDUAL, (short) 0);
        verify(vibrator, times(1)).cancel();
        idle(RumbleGuard.HOLD_MS * 3);
        verify(vibrator, times(1)).cancel();
    }

    @Test
    public void aFloorPresetDoesNotTurnAResidualIntoAPulse() {
        RumblePlayback pulsing = new RumblePlayback(RumblePresets.PULSE_25.profile);
        try {
            pulsing.single(vibrator, RESIDUAL, (short) 0);
            verify(vibrator, never()).vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
        }
        finally {
            pulsing.stop();
        }
    }

    @Test
    public void aRepeatingPulseWaveformEndsAtTheHoldLimitToo() {
        RumblePlayback pulsing = new RumblePlayback(RumblePresets.PULSE_25.profile);
        try {
            pulsing.single(vibrator, HALF, (short) 0);
            verify(vibrator).vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
            idle(RumbleGuard.HOLD_MS - 1);
            verify(vibrator, never()).cancel();
            idle(1);
            verify(vibrator, times(1)).cancel();
        }
        finally {
            pulsing.stop();
        }
    }

    @Test
    public void aRumbleEventAfterStopDoesNothing() {
        playback.single(vibrator, HALF, (short) 0);
        playback.stop();
        playback.single(vibrator, HALF, (short) 0);
        verify(vibrator, times(1)).vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
        idle(RumbleGuard.HOLD_MS * 3);
        verify(vibrator, times(1)).cancel();
    }

    @Test
    public void aDualVibratorRumbleIsCancelledAtTheHoldLimit() {
        VibratorManager manager = mock(VibratorManager.class);
        when(manager.getVibratorIds()).thenReturn(new int[] {1, 2});
        playback.dual(manager, HALF, HALF);
        verify(manager).vibrate(any(CombinedVibration.class), any(VibrationAttributes.class));
        idle(RumbleGuard.HOLD_MS - 1);
        verify(manager, never()).cancel();
        idle(1);
        verify(manager, times(1)).cancel();
    }

    @Test
    public void aDualResidualIsSilence() {
        VibratorManager manager = mock(VibratorManager.class);
        when(manager.getVibratorIds()).thenReturn(new int[] {1, 2});
        playback.dual(manager, RESIDUAL, RESIDUAL);
        verify(manager, never()).vibrate(any(CombinedVibration.class), any(VibrationAttributes.class));
    }
}
