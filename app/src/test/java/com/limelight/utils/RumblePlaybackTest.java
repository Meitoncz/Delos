package com.limelight.utils;

import android.os.CombinedVibration;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.SparseArray;

import com.limelight.LimeLog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the vibrator service is told by {@link RumblePlayback}, against a model of what Android 13's
 * VibratorManagerService does with it: a cancel only reaches an effect started through the same vibrator object, a
 * one-shot ends by itself after its duration, a repeating waveform only ends when it is cancelled, and a one-shot is
 * ignored while a repeating effect plays. Time is the Robolectric main looper's clock.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class RumblePlaybackTest {
    private static final short TAIL = (short) 0x0400;
    private static final short HALF = (short) 0x8000;

    private RumblePlayback playback;
    private final Service service = new Service();
    private final Capture log = new Capture();
    private final Logger limeLog = Logger.getLogger(LimeLog.class.getName());

    @Before
    public void setUp() {
        limeLog.setLevel(Level.ALL);
        limeLog.addHandler(log);
        playback = new RumblePlayback(RumbleTranslator.ORIGINAL);
    }

    @After
    public void tearDown() {
        playback.stop();
        limeLog.removeHandler(log);
    }

    // --- The vibrator model ---

    /** The parts of a VibrationEffect the tests look at. */
    private static final class Fx {
        final long durationMs;
        final boolean repeating;
        /** The highest step amplitude, 0..255. */
        final int amplitude;
        /** Per vibrator id for a combined vibration, else null. */
        SparseArray<Fx> channels;

        Fx(long durationMs, boolean repeating, int amplitude) {
            this.durationMs = durationMs;
            this.repeating = repeating;
            this.amplitude = amplitude;
        }

        static Fx of(VibrationEffect effect) throws Exception {
            Class<?> type = effect.getClass();
            long duration = (Long) type.getMethod("getDuration").invoke(effect);
            int repeat = (Integer) type.getMethod("getRepeatIndex").invoke(effect);
            List<?> segments = (List<?>) type.getMethod("getSegments").invoke(effect);
            float highest = 0;
            for (Object segment : segments) {
                highest = Math.max(highest, (Float) segment.getClass().getMethod("getAmplitude").invoke(segment));
            }
            return new Fx(duration, repeat >= 0, Math.round(highest * 255));
        }

        static Fx of(CombinedVibration combined) throws Exception {
            Method getEffects = combined.getClass().getMethod("getEffects");
            @SuppressWarnings("unchecked")
            SparseArray<VibrationEffect> effects = (SparseArray<VibrationEffect>) getEffects.invoke(combined);
            Fx whole = null;
            SparseArray<Fx> channels = new SparseArray<>();
            for (int i = 0; i < effects.size(); i++) {
                Fx channel = of(effects.valueAt(i));
                channels.put(effects.keyAt(i), channel);
                if (whole == null || channel.amplitude > whole.amplitude) {
                    whole = channel;
                }
            }
            Fx result = new Fx(whole.durationMs, whole.repeating, whole.amplitude);
            result.channels = channels;
            return result;
        }
    }

    /** One physical vibrator service: at most one running vibration, owned by the object that started it. */
    private static final class Service {
        private Object token;
        private Fx fx;
        private long startedAt;
        private boolean any;
        int vibrateCalls;
        int cancelCalls;
        int ignored;

        void vibrate(Object caller, Fx next) {
            vibrateCalls++;
            if (vibrating() && fx.repeating && !next.repeating) {
                ignored++; // IGNORED_FOR_ONGOING: a repeating effect is not replaced by a one-shot
                return;
            }
            token = caller;
            fx = next;
            startedAt = SystemClock.uptimeMillis();
            any = true;
        }

        void cancel(Object caller) {
            cancelCalls++;
            if (any && token == caller) {
                any = false;
            }
        }

        boolean vibrating() {
            return any && (fx.repeating || SystemClock.uptimeMillis() < startedAt + fx.durationMs);
        }

        Fx running() {
            assertTrue("nothing vibrates", vibrating());
            return fx;
        }
    }

    private Vibrator vibrator(boolean amplitudeControl) {
        final Vibrator vibrator = mock(Vibrator.class);
        when(vibrator.hasAmplitudeControl()).thenReturn(amplitudeControl);
        when(vibrator.getId()).thenReturn(0);
        doAnswer(invocation -> {
            service.vibrate(vibrator, Fx.of((VibrationEffect) invocation.getArgument(0)));
            return null;
        }).when(vibrator).vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
        doAnswer(invocation -> {
            service.cancel(vibrator);
            return null;
        }).when(vibrator).cancel();
        return vibrator;
    }

    private VibratorManager manager(int... ids) {
        final VibratorManager manager = mock(VibratorManager.class);
        when(manager.getVibratorIds()).thenReturn(ids);
        doAnswer(invocation -> {
            service.vibrate(manager, Fx.of((CombinedVibration) invocation.getArgument(0)));
            return null;
        }).when(manager).vibrate(any(CombinedVibration.class), any(VibrationAttributes.class));
        doAnswer(invocation -> {
            service.cancel(manager);
            return null;
        }).when(manager).cancel();
        return manager;
    }

    private static void idle(long ms) {
        if (ms > 0) {
            ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS);
        }
    }

    // --- Capturing the diagnostic lines ---

    private static final class Capture extends java.util.logging.Handler {
        final List<String> lines = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            lines.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        List<String> rumble() {
            List<String> mine = new ArrayList<>();
            for (String line : lines) {
                if (line.startsWith("RumblePlayback")) {
                    mine.add(line);
                }
            }
            return mine;
        }

        /** The one line that contains [part]; fails when there is none or several. */
        String only(String part) {
            String found = null;
            for (String line : rumble()) {
                if (line.contains(part)) {
                    assertEquals("two lines contain: " + part, null, found);
                    found = line;
                }
            }
            assertNotNull("no line contains: " + part + " in " + rumble(), found);
            return found;
        }
    }

    // --- A host zero ends what the last host value started ---

    @Test
    public void aHostZeroEndsAnAmplitudeEffectOnTheVibratorThatPlayedIt() throws Exception {
        Vibrator vibrator = vibrator(true);
        playback.single(vibrator, (short) 0x1400, (short) 0);
        Fx running = service.running();
        assertEquals(60000, running.durationMs);
        assertFalse(running.repeating);
        assertEquals(16, running.amplitude);
        playback.single(vibrator, (short) 0, (short) 0);
        assertFalse(service.vibrating());
    }

    @Test
    public void aHostZeroEndsARepeatingPulseWaveform() {
        playback.stop();
        playback = new RumblePlayback(RumblePresets.PULSE_25.profile);
        Vibrator vibrator = vibrator(true);
        playback.single(vibrator, HALF, (short) 0);
        assertTrue(service.running().repeating);
        playback.single(vibrator, (short) 0, (short) 0);
        assertFalse(service.vibrating());
        idle(60_000);
        assertFalse(service.vibrating());
    }

    @Test
    public void aHostZeroEndsTheRepeatingPwmWaveformOfAVibratorWithoutAmplitudeControl() {
        Vibrator vibrator = vibrator(false);
        playback.single(vibrator, HALF, (short) 0);
        assertTrue(service.running().repeating);
        playback.single(vibrator, (short) 0, (short) 0);
        assertFalse(service.vibrating());
    }

    @Test
    public void aHostZeroEndsADualVibratorEffect() {
        VibratorManager manager = manager(1, 2);
        playback.dual(manager, HALF, (short) 0x4000);
        Fx running = service.running();
        assertEquals(2, running.channels.size());
        // Channel order is the legacy FF_RUMBLE one: high motor first, then the low one
        assertEquals(0x40, running.channels.get(1).amplitude);
        assertEquals(0x80, running.channels.get(2).amplitude);
        playback.dual(manager, (short) 0, (short) 0);
        assertFalse(service.vibrating());
    }

    @Test
    public void aQuadVibratorEffectLeavesZeroChannelsOutAndEndsWithTheZero() {
        VibratorManager manager = manager(1, 2, 3, 4);
        playback.quad(manager, (short) 0, (short) 0, HALF, (short) 0);
        assertEquals(1, service.running().channels.size());
        playback.quad(manager, (short) 0, (short) 0, (short) 0, (short) 0);
        assertFalse(service.vibrating());
    }

    @Test
    public void everyOutputIsCancelledByItsOwnZero() {
        Service second = new Service();
        Vibrator first = vibrator(true);
        final Vibrator other = mock(Vibrator.class);
        when(other.hasAmplitudeControl()).thenReturn(true);
        doAnswer(invocation -> {
            second.vibrate(other, Fx.of((VibrationEffect) invocation.getArgument(0)));
            return null;
        }).when(other).vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
        doAnswer(invocation -> {
            second.cancel(other);
            return null;
        }).when(other).cancel();
        playback.single(first, HALF, (short) 0);
        playback.single(other, HALF, (short) 0);
        assertTrue(service.vibrating() && second.vibrating());
        playback.single(first, (short) 0, (short) 0);
        assertFalse(service.vibrating());
        assertTrue("a zero for one vibrator must not touch the other", second.vibrating());
        playback.single(other, (short) 0, (short) 0);
        assertFalse(second.vibrating());
    }

    @Test
    public void aZeroThroughAnotherVibratorObjectDoesNotReachTheEffect() {
        // The reason RumblePlayback keeps one output per vibrator object and cancels through the object that played
        Vibrator played = vibrator(true);
        Vibrator other = vibrator(true);
        playback.single(played, HALF, (short) 0);
        playback.single(other, (short) 0, (short) 0);
        assertTrue(service.vibrating());
        playback.single(played, (short) 0, (short) 0);
        assertFalse(service.vibrating());
    }

    // --- Every shape of host input ends with its zero (seeded, any profile, any timing) ---

    private static final RumbleTranslator.Profile SMOOTH = new RumbleTranslator.Profile(1f, 1f, 0, 0, .8f, .33f, .5f);
    private static final RumbleTranslator.Profile KICK_SMOOTH = new RumbleTranslator.Profile(1f, 1f, 30, 80, .8f, .33f, .3f);
    private static final RumbleTranslator.Profile PULSE_KICK = new RumbleTranslator.Profile(1f, .8f, 50, 100, 1f, .35f, .2f,
            RumbleTechnique.PULSE, 20, 20);

    private static RumbleTranslator.Profile[] profiles() {
        return new RumbleTranslator.Profile[] {
                RumbleTranslator.ORIGINAL, RumblePresets.GENTLE.profile, RumblePresets.KICK_ONLY.profile,
                RumblePresets.PULSE_25.profile, SMOOTH, KICK_SMOOTH, PULSE_KICK
        };
    }

    private static short randomMotor(Random random) {
        return random.nextInt(8) == 0 ? 0 : (short) random.nextInt(65536);
    }

    @Test
    public void anyHostSequenceEndsWithItsZeroOnASingleVibrator() {
        for (RumbleTranslator.Profile profile : profiles()) {
            for (int seed = 0; seed < 60; seed++) {
                Random random = new Random(seed);
                Service local = new Service();
                playback.stop();
                playback = new RumblePlayback(profile);
                Vibrator vibrator = mock(Vibrator.class);
                when(vibrator.hasAmplitudeControl()).thenReturn(true);
                doAnswer(invocation -> {
                    local.vibrate(vibrator, Fx.of((VibrationEffect) invocation.getArgument(0)));
                    return null;
                }).when(vibrator).vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
                doAnswer(invocation -> {
                    local.cancel(vibrator);
                    return null;
                }).when(vibrator).cancel();
                int events = 1 + random.nextInt(40);
                for (int i = 0; i < events; i++) {
                    playback.single(vibrator, randomMotor(random), randomMotor(random));
                    idle(random.nextInt(40));
                }
                playback.single(vibrator, (short) 0, (short) 0);
                idle(5_000);
                assertFalse("profile " + profile + " seed " + seed, local.vibrating());
                int calls = local.vibrateCalls;
                idle(300_000);
                assertFalse("profile " + profile + " seed " + seed + " restarted", local.vibrating());
                assertEquals("a timer restarted the vibrator after the zero, profile " + profile + " seed " + seed,
                        calls, local.vibrateCalls);
            }
        }
    }

    @Test
    public void anyHostSequenceEndsWithItsZeroOnDualAndQuadVibrators() {
        for (RumbleTranslator.Profile profile : profiles()) {
            for (int seed = 0; seed < 40; seed++) {
                Random random = new Random(1000 + seed);
                boolean quad = seed % 2 == 0;
                Service local = new Service();
                playback.stop();
                playback = new RumblePlayback(profile);
                final VibratorManager manager = mock(VibratorManager.class);
                when(manager.getVibratorIds()).thenReturn(quad ? new int[] {1, 2, 3, 4} : new int[] {1, 2});
                doAnswer(invocation -> {
                    local.vibrate(manager, Fx.of((CombinedVibration) invocation.getArgument(0)));
                    return null;
                }).when(manager).vibrate(any(CombinedVibration.class), any(VibrationAttributes.class));
                doAnswer(invocation -> {
                    local.cancel(manager);
                    return null;
                }).when(manager).cancel();
                int events = 1 + random.nextInt(40);
                for (int i = 0; i < events; i++) {
                    if (quad) {
                        playback.quad(manager, randomMotor(random), randomMotor(random),
                                randomMotor(random), randomMotor(random));
                    }
                    else {
                        playback.dual(manager, randomMotor(random), randomMotor(random));
                    }
                    idle(random.nextInt(40));
                }
                if (quad) {
                    playback.quad(manager, (short) 0, (short) 0, (short) 0, (short) 0);
                }
                else {
                    playback.dual(manager, (short) 0, (short) 0);
                }
                idle(5_000);
                assertFalse("profile " + profile + " seed " + seed, local.vibrating());
                int calls = local.vibrateCalls;
                idle(300_000);
                assertEquals("profile " + profile + " seed " + seed, calls, local.vibrateCalls);
                assertFalse(local.vibrating());
            }
        }
    }

    // --- What ends an effect: the host's next value or stop, nothing else ---

    @Test
    public void aHostValueNothingClearsPlaysForTheWholeEffectAndIsNeverCancelledByTheClient() {
        // The mechanism behind a vibration that "lasts much longer": the client has no end of its own for a value
        // the host leaves behind (here a decay tail of 4 of 255), only the effect's own 60 s. A real pad's motor does
        // not spin at that value; the vibrator plays it.
        Vibrator vibrator = vibrator(true);
        playback.single(vibrator, TAIL, (short) 0);
        assertEquals(3, service.running().amplitude);
        idle(59_000);
        assertTrue(service.vibrating());
        assertEquals("the client never cancels by itself", 0, service.cancelCalls);
        idle(1_000);
        assertFalse("the 60 s of the one-shot", service.vibrating());
    }

    @Test
    public void theFloorOfThePulsePresetTurnsATailIntoABuzzThatNothingEndsButTheHost() {
        // Pulsing has a floor of 50: a tail of 4 of 255 becomes a repeating waveform at 50 that never ends by itself
        playback.stop();
        playback = new RumblePlayback(RumblePresets.PULSE_25.profile);
        Vibrator vibrator = vibrator(true);
        playback.single(vibrator, TAIL, (short) 0);
        Fx running = service.running();
        assertTrue(running.repeating);
        assertEquals(50, running.amplitude);
        idle(3_600_000);
        assertTrue("an hour later", service.vibrating());
        assertEquals(0, service.cancelCalls);
        playback.single(vibrator, (short) 0, (short) 0);
        assertFalse(service.vibrating());
    }

    @Test
    public void everyHostEventIsAFreshEffectOfTheFullDurationAndNoTimerRenewsOrCutsIt() {
        Vibrator vibrator = vibrator(true);
        playback.single(vibrator, (short) 0x1400, (short) 0);
        idle(30);
        playback.single(vibrator, (short) 0x1500, (short) 0);
        assertEquals(2, service.vibrateCalls);
        idle(59_000);
        assertTrue(service.vibrating());
        assertEquals(0, service.cancelCalls);
        assertEquals(2, service.vibrateCalls);
    }

    @Test
    public void stopCancelsEveryOutputAndLaterEventsDoNothing() {
        Vibrator vibrator = vibrator(true);
        playback.single(vibrator, HALF, (short) 0);
        playback.stop("stream teardown");
        assertFalse(service.vibrating());
        int calls = service.vibrateCalls;
        playback.single(vibrator, HALF, (short) 0);
        assertEquals(calls, service.vibrateCalls);
        assertFalse(service.vibrating());
    }

    @Test
    public void discardingAnOutputCancelsItsEffect() {
        Vibrator vibrator = vibrator(true);
        playback.single(vibrator, HALF, (short) 0);
        playback.cancel(vibrator);
        assertFalse(service.vibrating());
    }

    // --- Diagnostics: one line per decision, all matched by the measurement's "RumblePlayback" ---

    @Test
    public void aHostEventLogsTheRawValueTheTranslatedAmplitudeAndTheCallMade() {
        playback.single(vibrator(true), (short) 0x1400, (short) 0);
        String decision = log.only("host 1400 0000");
        assertTrue(decision, decision.startsWith("RumblePlayback host 1400 0000 -> single#"));
        assertTrue(decision, decision.contains(" id=0 translated amp 16: vibrate one-shot 60000 ms amp 16 via Vibrator.vibrate("));
        assertTrue(decision, decision.contains("VibrationEffect, VibrationAttributes) [VibrationAttributes usage MEDIA]"));
        assertTrue(log.only("output single#").contains("created: amplitudeControl=true requested=AMPLITUDE using=AMPLITUDE"));
    }

    @Test
    public void aHostZeroLogsTheCancelAndWhoAskedForIt() {
        Vibrator vibrator = vibrator(true);
        playback.single(vibrator, HALF, (short) 0);
        playback.single(vibrator, (short) 0, (short) 0);
        String cancel = log.only("host 0000 0000");
        assertTrue(cancel, cancel.contains("translated amp 0: cancel via Vibrator.cancel() requested by host zero"));
    }

    @Test
    public void aNonZeroValueThatTranslatesToZeroIsToldApartFromAHostZero() {
        Vibrator vibrator = vibrator(true);
        playback.single(vibrator, HALF, (short) 0);
        playback.single(vibrator, (short) 0x00FF, (short) 0);
        assertFalse(service.vibrating());
        String cancel = log.only("host 00ff 0000");
        assertTrue(cancel, cancel.contains("cancel via Vibrator.cancel() requested by host value translated to zero"));
    }

    @Test
    public void stopLogsItsReasonAndEveryCancelItMakes() {
        Vibrator vibrator = vibrator(true);
        playback.single(vibrator, HALF, (short) 0);
        playback.stop("stream teardown (ControllerHandler.stop)");
        assertEquals("RumblePlayback stop requested by stream teardown (ControllerHandler.stop): cancelling 1 output(s)",
                log.only("RumblePlayback stop requested by"));
        assertTrue(log.only("RumblePlayback cancel single#").endsWith(
                "via Vibrator.cancel() requested by stream teardown (ControllerHandler.stop)"));
    }

    @Test
    public void anEventAfterStopIsLoggedAsIgnored() {
        Vibrator vibrator = vibrator(true);
        playback.stop();
        playback.single(vibrator, HALF, (short) 0);
        assertTrue(log.only("host 8000 0000").endsWith(": ignored, the playback is stopped"));
    }

    @Test
    public void aPulseLogsItsWaveformAndALaterEventAtTheSameAmplitudeSaysNothingWasCalled() {
        playback.stop();
        playback = new RumblePlayback(RumblePresets.PULSE_25.profile);
        Vibrator vibrator = vibrator(true);
        playback.single(vibrator, TAIL, (short) 0);
        String first = log.only("host 0400 0000");
        assertTrue(first, first.contains("translated amp 50: vibrate pulse waveform on/off 20/20 ms amp 50 repeating until cancelled via"));
        log.lines.clear();
        playback.single(vibrator, TAIL, (short) 0);
        assertTrue(log.only("host 0400 0000").contains("no vibrator call, the pulse waveform at amp 50 is already playing"));
    }

    @Test
    public void aDualEventLogsTheAmplitudeOfEveryChannel() {
        VibratorManager manager = manager(1, 2);
        playback.dual(manager, HALF, (short) 0x4000);
        String decision = log.only("host 8000 4000");
        assertTrue(decision, decision.contains("-> manager#"));
        assertTrue(decision, decision.contains(" ids=[1,2] translated amps [64,128]: vibrate parallel one-shots 60000 ms"));
        playback.dual(manager, (short) 0, (short) 0);
        assertTrue(log.only("host 0000 0000").contains("cancel via VibratorManager.cancel() requested by host zero"));
    }

    @Test
    public void everyDiagnosticLineStartsWithTheNameTheMeasurementSearchesFor() {
        Vibrator vibrator = vibrator(true);
        playback.single(vibrator, HALF, (short) 0);
        playback.single(vibrator, (short) 0, (short) 0);
        playback.cancel(vibrator);
        playback.stop();
        assertFalse(log.lines.isEmpty());
        for (String line : log.lines) {
            assertTrue(line, line.startsWith("RumblePlayback"));
        }
    }
}
