package com.limelight.utils;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;

/**
 * TEMPORARY (#66): drives the vibrator through the haptic channel of an AudioTrack (EXTERNAL_CONTROL), playing our own
 * low-frequency sine. The audio channels carry only zeros ({@link RumbleHaptics.WaveSynth}), so nothing is audible.
 * The track and its writer thread are created together, the thread parks (track paused) while the rumble is zero, and
 * {@link #close()} ends the thread, which releases the track. Constructor throws {@link IllegalStateException} with a
 * readable reason when no haptic track can be created.
 */
final class HapticWaveOutput {
    /** AudioFormat.CHANNEL_OUT_HAPTIC_A: @hide in the public SDK, a stable AOSP value (frameworks/base AudioFormat). */
    private static final int CHANNEL_OUT_HAPTIC_A = 0x20000000;
    private static final int CHUNK_FRAMES = 480; // 10 ms at 48 kHz
    private static final int BUFFER_CHUNKS = 4;

    private final Object lock = new Object();
    private final AudioTrack track;
    private final RumbleHaptics.WaveSynth synth;
    private final short[] chunk;
    private final Thread thread;
    private volatile int target;
    private volatile boolean closed;
    private volatile boolean failed;

    HapticWaveOutput(int hz) {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setHapticChannelsMuted(false)
                .build();
        // Mono audio + haptic first, stereo + haptic as a fallback for devices that want a stereo base.
        int[] audioChannelCounts = {1, 2};
        AudioTrack created = null;
        int usedAudioChannels = 1;
        String reason = "no AudioTrack with a haptic channel could be created";
        for (int audioChannels : audioChannelCounts) {
            int mask = (audioChannels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO)
                    | CHANNEL_OUT_HAPTIC_A;
            AudioTrack candidate = null;
            try {
                int frameBytes = (audioChannels + 1) * 2;
                int minBytes = AudioTrack.getMinBufferSize(RumbleHaptics.WAVE_SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT);
                int bytes = Math.max(minBytes, CHUNK_FRAMES * frameBytes * BUFFER_CHUNKS);
                candidate = new AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(RumbleHaptics.WAVE_SAMPLE_RATE)
                                .setChannelMask(mask)
                                .build())
                        .setBufferSizeInBytes(bytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                if (candidate.getState() == AudioTrack.STATE_INITIALIZED) {
                    created = candidate;
                    usedAudioChannels = audioChannels;
                    break;
                }
                candidate.release();
            }
            catch (RuntimeException e) {
                reason = "AudioTrack failed: " + e.getMessage();
                if (candidate != null) {
                    candidate.release();
                }
            }
        }
        if (created == null) {
            throw new IllegalStateException(reason);
        }
        track = created;
        synth = new RumbleHaptics.WaveSynth(hz, RumbleHaptics.WAVE_SAMPLE_RATE, usedAudioChannels);
        chunk = new short[CHUNK_FRAMES * synth.frameSize()];
        thread = new Thread(this::run, "RumbleHapticWave");
        thread.start();
    }

    /** Amplitude 0..255 of the translated rumble; 0 lets the envelope decay and parks the thread. */
    void setAmplitude(int amplitude) {
        synchronized (lock) {
            target = amplitude;
            lock.notifyAll();
        }
    }

    /** True once the writer thread hit an error; the caller should fall back to the amplitude path. */
    boolean isFailed() {
        return failed;
    }

    void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    private void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        // Chunks of silence written after the envelope is gone, so the buffered tail plays before the track pauses.
        int idleChunksBeforePause = BUFFER_CHUNKS + 1;
        int idleChunks = 0;
        boolean playing = false;
        try {
            while (!closed) {
                int amplitude = target;
                if (amplitude == 0 && synth.isSilent() && idleChunks >= idleChunksBeforePause) {
                    if (playing) {
                        track.pause();
                        track.flush();
                        playing = false;
                    }
                    synchronized (lock) {
                        while (!closed && target == 0) {
                            lock.wait();
                        }
                    }
                    idleChunks = 0;
                    continue;
                }
                idleChunks = amplitude == 0 && synth.isSilent() ? idleChunks + 1 : 0;
                if (!playing) {
                    track.play();
                    playing = true;
                }
                synth.fill(chunk, CHUNK_FRAMES, amplitude / 255f);
                int written = track.write(chunk, 0, chunk.length, AudioTrack.WRITE_BLOCKING);
                if (written < 0) {
                    failed = true;
                    return;
                }
            }
        }
        catch (InterruptedException | RuntimeException e) {
            failed = !closed;
        }
        finally {
            try {
                track.stop();
            }
            catch (RuntimeException ignored) {
            }
            track.release();
        }
    }
}
