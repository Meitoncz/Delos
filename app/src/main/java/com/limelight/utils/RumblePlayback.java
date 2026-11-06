package com.limelight.utils;

import android.annotation.TargetApi;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import java.util.ArrayList;

/**
 * Shared playback for streaming and the rumble lab. State belongs to hardware identities,
 * never to host events. Public operations and main-thread settling ticks share one lock.
 * {@link #stop()} is terminal; use a new helper for a new playback session.
 */
public final class RumblePlayback {
    private static final long EFFECT_DURATION_MS = 60000;
    private final RumbleTranslator.Profile profile;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Output> outputs = new ArrayList<>();
    private final AudioAttributes audioAttributes;
    private final VibrationAttributes vibrationAttributes;
    private final Runnable tick = this::tick;
    private long scheduledAt = Long.MAX_VALUE;
    private boolean stopped;
    private volatile int lastAmplitude;
    // TEMPORARY (#66): why the selected technique fell back to the plain amplitude path, or null while it works.
    private volatile String fallbackReason;

    public RumblePlayback(RumbleTranslator.Profile profile) {
        this.profile = profile;
        audioAttributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibrationAttributes.Builder builder = new VibrationAttributes.Builder();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.setUsage(VibrationAttributes.USAGE_MEDIA);
            }
            vibrationAttributes = builder.build();
        }
        else {
            vibrationAttributes = null;
        }
    }

    /** Maximum translated amplitude of the most recently updated hardware output. */
    public int getLastAmplitude() {
        return lastAmplitude;
    }

    /** TEMPORARY (#66): null while the profile's technique runs; otherwise why the plain amplitude path is used. */
    public String getFallbackReason() {
        return fallbackReason;
    }

    public synchronized void single(Vibrator vibrator, short low, short high) {
        if (stopped) {
            return;
        }
        SingleOutput output = null;
        for (int i = 0; i < outputs.size(); i++) {
            Output candidate = outputs.get(i);
            if (candidate instanceof SingleOutput && ((SingleOutput) candidate).vibrator == vibrator) {
                output = (SingleOutput) candidate;
                break;
            }
        }
        if (output == null) {
            output = new SingleOutput(vibrator);
            outputs.add(output);
        }
        output.low = low & 0xFFFF;
        output.high = high & 0xFFFF;
        long nowMs = SystemClock.uptimeMillis();
        output.update(nowMs, true);
        lastAmplitude = output.maximum;
        schedule(nowMs);
    }

    @TargetApi(31)
    public synchronized void dual(VibratorManager manager, short low, short high) {
        manager(manager, low, high, (short) 0, (short) 0, 2);
    }

    @TargetApi(31)
    public synchronized void quad(VibratorManager manager, short low, short high,
                                  short leftTrigger, short rightTrigger) {
        manager(manager, low, high, leftTrigger, rightTrigger, 4);
    }

    @TargetApi(31)
    private void manager(VibratorManager manager, short low, short high,
                         short leftTrigger, short rightTrigger, int channelCount) {
        if (stopped) {
            return;
        }
        ManagerOutput output = null;
        for (int i = 0; i < outputs.size(); i++) {
            Output candidate = outputs.get(i);
            if (candidate instanceof ManagerOutput && ((ManagerOutput) candidate).manager == manager) {
                output = (ManagerOutput) candidate;
                break;
            }
        }
        if (output == null) {
            output = new ManagerOutput(manager);
            outputs.add(output);
        }
        // Match the legacy Android FF_RUMBLE enumeration, including trigger ordering.
        output.raw[0] = high & 0xFFFF;
        output.raw[1] = low & 0xFFFF;
        output.raw[2] = channelCount == 4 ? leftTrigger & 0xFFFF : 0;
        output.raw[3] = channelCount == 4 ? rightTrigger & 0xFFFF : 0;
        long nowMs = SystemClock.uptimeMillis();
        output.update(nowMs, true);
        lastAmplitude = output.maximum;
        schedule(nowMs);
    }

    /** Discards a disconnected output, including any outstanding kick or slew. */
    public synchronized void cancel(Vibrator vibrator) {
        for (int i = outputs.size() - 1; i >= 0; i--) {
            Output output = outputs.get(i);
            if (output instanceof SingleOutput && ((SingleOutput) output).vibrator == vibrator) {
                output.cancel();
                outputs.remove(i);
            }
        }
        refreshAfterRemoval();
    }

    @TargetApi(31)
    public synchronized void cancel(VibratorManager manager) {
        for (int i = outputs.size() - 1; i >= 0; i--) {
            Output output = outputs.get(i);
            if (output instanceof ManagerOutput && ((ManagerOutput) output).manager == manager) {
                output.cancel();
                outputs.remove(i);
            }
        }
        refreshAfterRemoval();
    }

    public synchronized void stop() {
        stopped = true;
        handler.removeCallbacks(tick);
        scheduledAt = Long.MAX_VALUE;
        for (int i = 0; i < outputs.size(); i++) {
            outputs.get(i).cancel();
        }
        outputs.clear();
        lastAmplitude = 0;
    }

    private void refreshAfterRemoval() {
        lastAmplitude = 0;
        for (int i = 0; i < outputs.size(); i++) {
            lastAmplitude = Math.max(lastAmplitude, outputs.get(i).maximum);
        }
        schedule(SystemClock.uptimeMillis());
    }

    private synchronized void tick() {
        scheduledAt = Long.MAX_VALUE;
        if (stopped) {
            return;
        }
        long nowMs = SystemClock.uptimeMillis();
        for (int i = 0; i < outputs.size(); i++) {
            Output output = outputs.get(i);
            if (output.delay(nowMs) != Long.MAX_VALUE) {
                output.update(nowMs, false);
                lastAmplitude = output.maximum;
            }
        }
        schedule(nowMs);
    }

    private void schedule(long nowMs) {
        long delay = Long.MAX_VALUE;
        for (int i = 0; i < outputs.size(); i++) {
            delay = Math.min(delay, outputs.get(i).delay(nowMs));
        }
        long next = delay == Long.MAX_VALUE ? Long.MAX_VALUE : nowMs + delay;
        if (next == scheduledAt) {
            return;
        }
        handler.removeCallbacks(tick);
        scheduledAt = next;
        if (next != Long.MAX_VALUE) {
            handler.postAtTime(tick, next);
        }
    }

    private abstract static class Output {
        int maximum;

        abstract void update(long nowMs, boolean hostEvent);
        abstract long delay(long nowMs);
        abstract void cancel();
    }

    private final class SingleOutput extends Output {
        final Vibrator vibrator;
        final RumbleTranslator.State state = new RumbleTranslator.State();
        final long[] pwm = new long[3];
        final boolean amplitudeControl;
        // TEMPORARY (#66): the technique in effect; the requested one, or AMPLITUDE when this device cannot do it.
        final RumbleTechnique technique;
        HapticWaveOutput wave;
        boolean waveBroken;
        long nextPulseMs;
        int low;
        int high;

        SingleOutput(Vibrator vibrator) {
            this.vibrator = vibrator;
            amplitudeControl = vibrator.hasAmplitudeControl();
            RumbleTechnique requested = profile.technique;
            technique = canRun(requested, vibrator) ? requested : RumbleTechnique.AMPLITUDE;
            if (technique != requested) {
                fallbackReason = "unsupported, using plain amplitude";
            }
        }

        private boolean canRun(RumbleTechnique requested, Vibrator vibrator) {
            if (requested.isPrimitive()) {
                return RumbleCapabilities.supportsPrimitive(vibrator, requested);
            }
            if (requested == RumbleTechnique.HAPTIC_WAVE) {
                return profile.waveHz > 0 && AudioManager.isHapticPlaybackSupported();
            }
            return true;
        }

        @Override
        void update(long nowMs, boolean hostEvent) {
            int amplitude = RumbleTranslator.single(low, high, profile, state, nowMs);
            if (technique.isPrimitive()) {
                pulse(amplitude, nowMs);
                return;
            }
            if (technique == RumbleTechnique.HAPTIC_WAVE && driveWave(amplitude)) {
                maximum = amplitude;
                return;
            }
            if (!hostEvent && amplitude == maximum) {
                return;
            }
            maximum = amplitude;
            if (amplitude == 0) {
                vibrator.cancel();
            }
            else if (amplitudeControl) {
                VibrationEffect effect = VibrationEffect.createOneShot(EFFECT_DURATION_MS, amplitude);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vibrator.vibrate(effect, vibrationAttributes);
                }
                else {
                    vibrator.vibrate(effect, audioAttributes);
                }
            }
            else {
                // Keep the legacy 20 ms PWM and truncation, without allocating a new array.
                pwm[1] = (long) ((amplitude / 255.0) * 20);
                pwm[2] = 20 - pwm[1];
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pwm, 0), vibrationAttributes);
                }
                else {
                    vibrator.vibrate(pwm, 0, audioAttributes);
                }
            }
        }

        /** TEMPORARY (#66): one primitive per interval while held; host events in between only update the amplitude. */
        @TargetApi(31)
        private void pulse(int amplitude, long nowMs) {
            maximum = amplitude;
            if (amplitude == 0) {
                vibrator.cancel();
                nextPulseMs = 0;
                return;
            }
            if (nowMs < nextPulseMs) {
                return;
            }
            VibrationEffect effect = VibrationEffect.startComposition()
                    .addPrimitive(RumbleCapabilities.primitiveId(technique), RumbleHaptics.primitiveScale(amplitude))
                    .compose();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, vibrationAttributes);
            }
            else {
                vibrator.vibrate(effect, audioAttributes);
            }
            nextPulseMs = nowMs + RumbleHaptics.primitiveIntervalMs(technique, amplitude);
        }

        /** TEMPORARY (#66): feeds the haptic wave; false means the plain amplitude path must handle this update. */
        private boolean driveWave(int amplitude) {
            if (wave != null && wave.isFailed()) {
                wave.close();
                wave = null;
                waveBroken = true;
                fallbackReason = "haptic track failed, using plain amplitude";
            }
            if (amplitude == 0) {
                if (wave == null) {
                    return false;
                }
                wave.setAmplitude(0);
                return true;
            }
            if (wave == null && !waveBroken) {
                try {
                    wave = new HapticWaveOutput(profile.waveHz);
                }
                catch (RuntimeException e) {
                    waveBroken = true;
                    fallbackReason = e.getMessage() + ", using plain amplitude";
                }
            }
            if (wave == null) {
                return false;
            }
            wave.setAmplitude(amplitude);
            return true;
        }

        @Override
        long delay(long nowMs) {
            long delay = state.nextUpdateDelay(nowMs);
            if (nextPulseMs != 0) {
                delay = Math.min(delay, Math.max(0, nextPulseMs - nowMs));
            }
            return delay;
        }

        @Override
        void cancel() {
            state.reset();
            vibrator.cancel();
            if (wave != null) {
                wave.close();
                wave = null;
            }
            nextPulseMs = 0;
            maximum = 0;
        }
    }

    @TargetApi(31)
    private final class ManagerOutput extends Output {
        final VibratorManager manager;
        final int[] ids;
        final int[] raw = new int[4];
        final int[] amplitudes = new int[4];
        final RumbleTranslator.State[] states = {
                new RumbleTranslator.State(), new RumbleTranslator.State(),
                new RumbleTranslator.State(), new RumbleTranslator.State()
        };

        ManagerOutput(VibratorManager manager) {
            this.manager = manager;
            ids = manager.getVibratorIds();
        }

        @Override
        void update(long nowMs, boolean hostEvent) {
            boolean changed = hostEvent;
            maximum = 0;
            for (int i = 0; i < Math.min(ids.length, states.length); i++) {
                int amplitude = RumbleTranslator.motor(raw[i], profile, states[i], nowMs);
                changed |= amplitude != amplitudes[i];
                amplitudes[i] = amplitude;
                maximum = Math.max(maximum, amplitude);
            }
            if (!changed) {
                return;
            }
            if (maximum == 0) {
                manager.cancel();
                return;
            }
            CombinedVibration.ParallelCombination combination = CombinedVibration.startParallel();
            for (int i = 0; i < Math.min(ids.length, amplitudes.length); i++) {
                // Omitting a zero channel turns it off; VibrationEffect rejects amplitude 0.
                if (amplitudes[i] != 0) {
                    combination.addVibrator(ids[i],
                            VibrationEffect.createOneShot(EFFECT_DURATION_MS, amplitudes[i]));
                }
            }
            manager.vibrate(combination.combine(), vibrationAttributes);
        }

        @Override
        long delay(long nowMs) {
            long delay = Long.MAX_VALUE;
            for (int i = 0; i < Math.min(ids.length, states.length); i++) {
                delay = Math.min(delay, states[i].nextUpdateDelay(nowMs));
            }
            return delay;
        }

        @Override
        void cancel() {
            for (int i = 0; i < states.length; i++) {
                states[i].reset();
                amplitudes[i] = 0;
            }
            manager.cancel();
            maximum = 0;
        }
    }
}
