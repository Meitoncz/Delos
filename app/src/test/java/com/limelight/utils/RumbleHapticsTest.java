package com.limelight.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RumbleHapticsTest {
    private static final int SR = RumbleHaptics.WAVE_SAMPLE_RATE;

    @Test
    public void firstTenPresetsKeepTheAmplitudeTechniqueAndNewOnesSelectTheirOwn() {
        RumblePresets[] all = RumblePresets.values();
        assertEquals(15, all.length);
        for (int i = 0; i < 10; i++) {
            assertSame(RumbleTechnique.AMPLITUDE, all[i].profile.technique);
            assertEquals(0, all[i].profile.waveHz);
        }
        assertSame(RumbleTechnique.PRIMITIVE_THUD, RumblePresets.THUD.profile.technique);
        assertSame(RumbleTechnique.PRIMITIVE_LOW_TICK, RumblePresets.LOW_TICK.profile.technique);
        assertSame(RumbleTechnique.HAPTIC_WAVE, RumblePresets.DEEP_WAVE_40.profile.technique);
        assertEquals(40, RumblePresets.DEEP_WAVE_40.profile.waveHz);
        assertEquals(60, RumblePresets.DEEP_WAVE_60.profile.waveHz);
        assertEquals(80, RumblePresets.DEEP_WAVE_80.profile.waveHz);
        assertSame(RumbleTechnique.AMPLITUDE, RumbleTechnique.fromName(null));
        assertSame(RumbleTechnique.AMPLITUDE, RumbleTechnique.fromName("nonsense"));
        assertSame(RumbleTechnique.HAPTIC_WAVE, RumbleTechnique.fromName("HAPTIC_WAVE"));
    }

    @Test
    public void unsupportedTechniquesReportAReasonAndSupportedOnesDoNot() {
        RumbleCapabilities none = new RumbleCapabilities(1, true, false, false, false, "CLICK");
        assertNull(none.unavailableReason(RumbleTechnique.AMPLITUDE));
        assertNotNull(none.unavailableReason(RumbleTechnique.PRIMITIVE_THUD));
        assertNotNull(none.unavailableReason(RumbleTechnique.PRIMITIVE_LOW_TICK));
        assertNotNull(none.unavailableReason(RumbleTechnique.HAPTIC_WAVE));
        RumbleCapabilities odin = new RumbleCapabilities(1, true, true, true, true, "all");
        for (RumbleTechnique technique : RumbleTechnique.values()) {
            assertNull(odin.unavailableReason(technique));
        }
    }

    @Test
    public void primitiveIntervalFallsWithAmplitudeInsideTheRequestedRanges() {
        assertEquals(100, RumbleHaptics.primitiveIntervalMs(RumbleTechnique.PRIMITIVE_THUD, 0));
        assertEquals(60, RumbleHaptics.primitiveIntervalMs(RumbleTechnique.PRIMITIVE_THUD, 255));
        assertEquals(70, RumbleHaptics.primitiveIntervalMs(RumbleTechnique.PRIMITIVE_LOW_TICK, 0));
        assertEquals(40, RumbleHaptics.primitiveIntervalMs(RumbleTechnique.PRIMITIVE_LOW_TICK, 255));
        assertEquals(0, RumbleHaptics.primitiveIntervalMs(RumbleTechnique.AMPLITUDE, 128));
        assertEquals(0, RumbleHaptics.primitiveIntervalMs(RumbleTechnique.HAPTIC_WAVE, 128));
        for (RumbleTechnique technique : new RumbleTechnique[] {RumbleTechnique.PRIMITIVE_THUD,
                RumbleTechnique.PRIMITIVE_LOW_TICK}) {
            int previous = Integer.MAX_VALUE;
            for (int amplitude = -5; amplitude <= 300; amplitude++) {
                int interval = RumbleHaptics.primitiveIntervalMs(technique, amplitude);
                assertTrue(interval <= previous);
                previous = interval;
            }
        }
    }

    @Test
    public void primitiveScaleFollowsAmplitudeAndStaysInUnitRange() {
        assertEquals(0f, RumbleHaptics.primitiveScale(0), 0f);
        assertEquals(1f, RumbleHaptics.primitiveScale(255), 0f);
        assertEquals(128 / 255f, RumbleHaptics.primitiveScale(128), 0f);
        assertEquals(0f, RumbleHaptics.primitiveScale(-4), 0f);
        assertEquals(1f, RumbleHaptics.primitiveScale(999), 0f);
    }

    @Test
    public void waveAudioChannelsAreAlwaysZeroAndHapticChannelCarriesTheSine() {
        for (int audioChannels = 1; audioChannels <= 2; audioChannels++) {
            RumbleHaptics.WaveSynth synth = new RumbleHaptics.WaveSynth(60, SR, audioChannels);
            int size = synth.frameSize();
            assertEquals(audioChannels + 1, size);
            short[] out = new short[size * 4800];
            synth.fill(out, 4800, 1f);
            int peak = 0;
            for (int f = 0; f < 4800; f++) {
                for (int c = 0; c < audioChannels; c++) {
                    assertEquals(0, out[f * size + c]);
                }
                peak = Math.max(peak, Math.abs(out[f * size + audioChannels]));
            }
            assertTrue(peak > 32000);
        }
    }

    @Test
    public void waveFrequencyMatchesRequestedHz() {
        for (int hz : new int[] {40, 60, 80}) {
            RumbleHaptics.WaveSynth synth = new RumbleHaptics.WaveSynth(hz, SR, 1);
            short[] out = new short[2 * SR];
            synth.fill(out, SR, 1f); // one second
            int crossings = 0;
            for (int f = 1; f < SR; f++) {
                if (out[(f - 1) * 2 + 1] < 0 && out[f * 2 + 1] >= 0) {
                    crossings++;
                }
            }
            assertEquals(hz, crossings, 1);
        }
    }

    @Test
    public void waveChunkBordersAreSeamlessAndPhaseContinues() {
        RumbleHaptics.WaveSynth whole = new RumbleHaptics.WaveSynth(60, SR, 1);
        RumbleHaptics.WaveSynth chunked = new RumbleHaptics.WaveSynth(60, SR, 1);
        short[] expected = new short[2 * 4800];
        whole.fill(expected, 4800, .7f);
        short[] actual = new short[2 * 4800];
        short[] chunk = new short[2 * 480];
        for (int i = 0; i < 10; i++) {
            chunked.fill(chunk, 480, .7f);
            System.arraycopy(chunk, 0, actual, i * 960, 960);
        }
        assertArrayEqualsShort(expected, actual);
        assertEquals(whole.phase(), chunked.phase(), 1e-9);
        assertTrue(chunked.phase() >= 0 && chunked.phase() < 1);
    }

    @Test
    public void envelopeAttacksAndDecaysWithoutJumpsAndGoesSilent() {
        RumbleHaptics.WaveSynth synth = new RumbleHaptics.WaveSynth(40, SR, 1);
        assertTrue(synth.isSilent());
        short[] out = new short[2 * 100];
        synth.fill(out, 100, 1f);
        // 4 ms attack = 192 frames: after 100 frames the level is about half-way, never at target yet
        assertTrue(synth.level() > .4f && synth.level() < .6f);
        assertFalse(synth.isSilent());
        short[] more = new short[2 * 480];
        synth.fill(more, 480, 1f);
        assertEquals(1f, synth.level(), 0f);
        // a held target stays put; a zero target decays over about 20 ms (960 frames) to exact silence
        synth.fill(more, 480, 0f);
        assertTrue(synth.level() > 0f && synth.level() < 1f);
        synth.fill(more, 480, 0f);
        synth.fill(more, 480, 0f);
        assertTrue(synth.isSilent());
        for (int f = 0; f < 480; f++) {
            assertEquals(0, more[f * 2 + 1]);
        }
        // never a sample step larger than the envelope slope allows
        RumbleHaptics.WaveSynth ramp = new RumbleHaptics.WaveSynth(40, SR, 1);
        short[] samples = new short[2 * 2000];
        ramp.fill(samples, 2000, 1f);
        for (int f = 1; f < 2000; f++) {
            assertTrue(Math.abs(samples[f * 2 + 1] - samples[(f - 1) * 2 + 1]) < 600);
        }
    }

    @Test
    public void waveAmplitudeFollowsTheTarget() {
        RumbleHaptics.WaveSynth synth = new RumbleHaptics.WaveSynth(60, SR, 1);
        short[] out = new short[2 * 4800];
        synth.fill(out, 4800, 100 / 255f);
        int peak = 0;
        for (int f = 2400; f < 4800; f++) {
            peak = Math.max(peak, Math.abs(out[f * 2 + 1]));
        }
        assertEquals(32767 * 100 / 255, peak, 400);
    }

    private static void assertArrayEqualsShort(short[] expected, short[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("index " + i, expected[i], actual[i]);
        }
    }
}
