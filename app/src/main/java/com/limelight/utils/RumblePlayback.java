package com.limelight.utils;

import android.annotation.TargetApi;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.limelight.LimeLog;

import java.util.ArrayList;

/**
 * Shared playback for streaming and the rumble lab. State belongs to hardware identities,
 * never to host events. Public operations and main-thread settling ticks share one lock.
 * {@link #stop()} is terminal; use a new helper for a new playback session.
 *
 * <p>Every decision is logged as one line that starts with {@code RumblePlayback} (see docs/RUMBLE.md, "Rumble
 * diagnostics"): the host event that came in, the amplitude per channel it was translated to, and the call made on
 * which vibrator; every cancel also says who asked for it. Nothing else ends an effect: a vibration lasts until the
 * host sends the next value (zero included) or the playback is stopped.
 */
public final class RumblePlayback {
    private static final long EFFECT_DURATION_MS = 60000;
    // Why a cancel was issued, as the diagnostic lines show it
    private static final String REASON_HOST_ZERO = "host zero";
    private static final String REASON_TRANSLATED_ZERO = "host value translated to zero";
    private static final String REASON_TICK_ZERO = "settling tick reached zero";
    private static final String REASON_STOP = "stop";
    private static final String REASON_DISCARDED = "output discarded (device context destroyed)";

    private final RumbleTranslator.Profile profile;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Output> outputs = new ArrayList<>();
    private final AudioAttributes audioAttributes;
    private final VibrationAttributes vibrationAttributes;
    // What the diagnostic lines name as the attributes of a vibrate call
    private final String attributesLabel;
    private final Runnable tick = this::tick;
    private long scheduledAt = Long.MAX_VALUE;
    private boolean stopped;
    private volatile int lastAmplitude;
    // Why the selected technique could not run (the plain amplitude path is used instead), or null.
    private volatile RumbleProblem problem;

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
        attributesLabel = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? "VibrationAttributes usage MEDIA"
                : Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? "VibrationAttributes usage UNKNOWN"
                : "AudioAttributes USAGE_GAME";
    }

    /** Maximum translated amplitude of the most recently updated hardware output. */
    public int getLastAmplitude() {
        return lastAmplitude;
    }

    /** Null while the profile's technique runs; otherwise why it could not and amplitude is used. */
    public RumbleProblem getProblem() {
        return problem;
    }

    public synchronized void single(Vibrator vibrator, short low, short high) {
        if (stopped) {
            logIgnored(low, high, "single#" + identity(vibrator));
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
            logIgnored(low, high, "manager#" + identity(manager));
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
                output.cancel(REASON_DISCARDED);
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
                output.cancel(REASON_DISCARDED);
                outputs.remove(i);
            }
        }
        refreshAfterRemoval();
    }

    public void stop() {
        stop(REASON_STOP);
    }

    /** Stops for good and cancels every output; [reason] is what the diagnostic lines say asked for it. */
    public synchronized void stop(String reason) {
        LimeLog.info("RumblePlayback stop requested by " + reason + ": cancelling " + outputs.size() + " output(s)");
        stopped = true;
        handler.removeCallbacks(tick);
        scheduledAt = Long.MAX_VALUE;
        for (int i = 0; i < outputs.size(); i++) {
            outputs.get(i).cancel(reason);
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

    // --- Diagnostics: one line per decision, all starting with "RumblePlayback" ---

    private static String identity(Object vibratorOrManager) {
        return Integer.toHexString(System.identityHashCode(vibratorOrManager));
    }

    private static void hex4(StringBuilder out, int value) {
        String hex = Integer.toHexString(value & 0xFFFF);
        for (int i = hex.length(); i < 4; i++) {
            out.append('0');
        }
        out.append(hex);
    }

    private static void logIgnored(short low, short high, String target) {
        StringBuilder line = new StringBuilder(96).append("RumblePlayback host ");
        hex4(line, low);
        line.append(' ');
        hex4(line, high);
        LimeLog.info(line.append(" -> ").append(target).append(": ignored, the playback is stopped").toString());
    }

    /** The vibrator's own id (the Odin's only vibrator is 0), or -1 when the platform has none to report. */
    private static int vibratorId(Vibrator vibrator) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return vibrator.getId();
            }
            catch (RuntimeException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private abstract static class Output {
        int maximum;

        abstract void update(long nowMs, boolean hostEvent);
        abstract long delay(long nowMs);
        abstract void cancel(String reason);
    }

    private final class SingleOutput extends Output {
        final Vibrator vibrator;
        final RumbleTranslator.State state = new RumbleTranslator.State();
        final long[] pwm = new long[3];
        final boolean amplitudeControl;
        // How the diagnostic lines name this output: object identity (a cancel only reaches effects started
        // through the same object) and the vibrator's own id
        final String label;
        // The technique in effect; the requested one until it cannot run, then AMPLITUDE (with a problem recorded).
        RumbleTechnique technique;
        // Amplitude of the last pulse effect that was issued, 0 while none is playing
        int issuedAmplitude;
        int low;
        int high;

        SingleOutput(Vibrator vibrator) {
            this.vibrator = vibrator;
            amplitudeControl = vibrator.hasAmplitudeControl();
            label = "single#" + identity(vibrator) + " id=" + vibratorId(vibrator);
            technique = profile.technique;
            if (technique != RumbleTechnique.AMPLITUDE) {
                if (RumbleCapabilities.unavailableReason(technique, amplitudeControl) != null) {
                    fallBack(RumbleProblem.noAmplitudeControl(technique));
                }
            }
            LimeLog.info("RumblePlayback output " + label + " created: amplitudeControl=" + amplitudeControl
                    + " requested=" + profile.technique + " using=" + technique);
        }

        private void fallBack(RumbleProblem reason) {
            problem = reason;
            technique = RumbleTechnique.AMPLITUDE;
            LimeLog.info("RumblePlayback output " + label + " falls back from " + reason.technique
                    + " to AMPLITUDE: " + reason.kind + (reason.detail == null ? "" : " " + reason.detail));
        }

        @Override
        void update(long nowMs, boolean hostEvent) {
            int amplitude = RumbleTranslator.single(low, high, profile, state, nowMs);
            if (technique != RumbleTechnique.AMPLITUDE) {
                try {
                    technique(amplitude, hostEvent);
                    return;
                }
                catch (RuntimeException e) {
                    cancelVibrator(hostEvent, amplitude, "technique " + technique + " failed: " + e);
                    fallBack(RumbleProblem.failed(technique, e));
                }
            }
            if (!hostEvent && amplitude == maximum) {
                return;
            }
            maximum = amplitude;
            if (amplitude == 0) {
                cancelVibrator(hostEvent, amplitude,
                        !hostEvent ? REASON_TICK_ZERO : low == 0 && high == 0 ? REASON_HOST_ZERO : REASON_TRANSLATED_ZERO);
            }
            else if (amplitudeControl) {
                play(VibrationEffect.createOneShot(EFFECT_DURATION_MS, amplitude));
                logDecision(hostEvent, amplitude, "vibrate one-shot " + EFFECT_DURATION_MS + " ms amp " + amplitude
                        + " via " + vibrateCall());
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
                logDecision(hostEvent, amplitude, "vibrate PWM waveform on/off " + pwm[1] + "/" + pwm[2]
                        + " ms repeating until cancelled via " + vibrateCall());
            }
        }

        private void play(VibrationEffect effect) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, vibrationAttributes);
            }
            else {
                vibrator.vibrate(effect, audioAttributes);
            }
        }

        private String vibrateCall() {
            return "Vibrator.vibrate(" + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? "VibrationEffect, VibrationAttributes" : "VibrationEffect, AudioAttributes") + ") [" + attributesLabel + "]";
        }

        private void cancelVibrator(boolean hostEvent, int amplitude, String reason) {
            vibrator.cancel();
            logDecision(hostEvent, amplitude, "cancel via Vibrator.cancel() requested by " + reason);
        }

        /**
         * Pulse output. The waveform repeats by itself; it is replaced when the amplitude changes
         * (including the end of a start kick) and cancelled at zero.
         */
        private void technique(int amplitude, boolean hostEvent) {
            maximum = amplitude;
            if (amplitude == 0) {
                cancelVibrator(hostEvent, amplitude,
                        !hostEvent ? REASON_TICK_ZERO : low == 0 && high == 0 ? REASON_HOST_ZERO : REASON_TRANSLATED_ZERO);
                issuedAmplitude = 0;
                return;
            }
            if (amplitude == issuedAmplitude) {
                if (hostEvent) {
                    logDecision(true, amplitude, "no vibrator call, the pulse waveform at amp " + amplitude
                            + " is already playing and repeats until cancelled");
                }
                return;
            }
            play(VibrationEffect.createWaveform(RumbleHaptics.pulseTimings(profile.pulseOnMs, profile.pulseOffMs),
                    RumbleHaptics.pulseAmplitudes(amplitude, profile.minAmplitude), 0));
            issuedAmplitude = amplitude;
            logDecision(hostEvent, amplitude, "vibrate pulse waveform on/off " + Math.max(1, profile.pulseOnMs) + "/"
                    + Math.max(1, profile.pulseOffMs) + " ms amp " + Math.max(profile.minAmplitude, amplitude)
                    + " repeating until cancelled via " + vibrateCall());
        }

        private void logDecision(boolean hostEvent, int amplitude, String action) {
            StringBuilder line = new StringBuilder(192).append("RumblePlayback ");
            if (hostEvent) {
                line.append("host ");
                hex4(line, low);
                line.append(' ');
                hex4(line, high);
            }
            else {
                line.append("tick");
            }
            LimeLog.info(line.append(" -> ").append(label).append(" translated amp ").append(amplitude)
                    .append(": ").append(action).toString());
        }

        @Override
        long delay(long nowMs) {
            return state.nextUpdateDelay(nowMs);
        }

        @Override
        void cancel(String reason) {
            state.reset();
            vibrator.cancel();
            issuedAmplitude = 0;
            maximum = 0;
            LimeLog.info("RumblePlayback cancel " + label + " via Vibrator.cancel() requested by " + reason);
        }
    }

    @TargetApi(31)
    private final class ManagerOutput extends Output {
        final VibratorManager manager;
        final int[] ids;
        final int[] raw = new int[4];
        final int[] amplitudes = new int[4];
        final String label;
        final RumbleTranslator.State[] states = {
                new RumbleTranslator.State(), new RumbleTranslator.State(),
                new RumbleTranslator.State(), new RumbleTranslator.State()
        };

        ManagerOutput(VibratorManager manager) {
            this.manager = manager;
            ids = manager.getVibratorIds();
            StringBuilder name = new StringBuilder("manager#").append(identity(manager)).append(" ids=[");
            for (int i = 0; i < ids.length; i++) {
                name.append(i == 0 ? "" : ",").append(ids[i]);
            }
            label = name.append(']').toString();
            if (profile.technique != RumbleTechnique.AMPLITUDE) {
                problem = RumbleProblem.singleVibratorOnly(profile.technique);
            }
            LimeLog.info("RumblePlayback output " + label + " created: requested=" + profile.technique
                    + " using=AMPLITUDE per channel");
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
                boolean rawZero = raw[0] == 0 && raw[1] == 0 && raw[2] == 0 && raw[3] == 0;
                logDecision(hostEvent, "cancel via VibratorManager.cancel() requested by "
                        + (!hostEvent ? REASON_TICK_ZERO : rawZero ? REASON_HOST_ZERO : REASON_TRANSLATED_ZERO));
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
            logDecision(hostEvent, "vibrate parallel one-shots " + EFFECT_DURATION_MS
                    + " ms (a channel at 0 is left out) via VibratorManager.vibrate(CombinedVibration, VibrationAttributes) ["
                    + attributesLabel + "]");
        }

        private void logDecision(boolean hostEvent, String action) {
            StringBuilder line = new StringBuilder(224).append("RumblePlayback ");
            if (hostEvent) {
                line.append("host ");
                hex4(line, raw[1]);
                line.append(' ');
                hex4(line, raw[0]);
                if (raw[2] != 0 || raw[3] != 0) {
                    line.append(" triggers ");
                    hex4(line, raw[2]);
                    line.append(' ');
                    hex4(line, raw[3]);
                }
            }
            else {
                line.append("tick");
            }
            line.append(" -> ").append(label).append(" translated amps [");
            for (int i = 0; i < Math.min(ids.length, amplitudes.length); i++) {
                line.append(i == 0 ? "" : ",").append(amplitudes[i]);
            }
            LimeLog.info(line.append("]: ").append(action).toString());
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
        void cancel(String reason) {
            for (int i = 0; i < states.length; i++) {
                states[i].reset();
                amplitudes[i] = 0;
            }
            manager.cancel();
            maximum = 0;
            LimeLog.info("RumblePlayback cancel " + label + " via VibratorManager.cancel() requested by " + reason);
        }
    }
}
