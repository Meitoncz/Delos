package com.limelight.binding.video;

import java.util.Arrays;

/**
 * Per-frame stage times of the MediaCodec video path, for measuring where the time between a frame's arrival and the
 * display goes. Every stage has a preallocated ring of the last {@value #CAPACITY} samples of the current window and
 * running sums, so recording a sample allocates nothing and the summary (average, 95th percentile, maximum) is built
 * once a second by {@link #summarize()}. Stage times come from several threads (the decoder's render thread, the
 * SGSR callback and GL threads), so recording and summarising are synchronised; the lock is held for a few
 * assignments.
 *
 * Nothing here reads pixels or claims input-to-photon latency: the stages start when the client has the frame (the
 * codec submit, or the SGSR frame-available callback) and end at the display's present time where the platform
 * reports it. The controller, network and host are not included.
 */
public final class LatencyStages {
    /** Submit to the codec until its output buffer is ready (decode, as far as the client can see). */
    public static final int DECODE = 0;
    /** Output buffer ready until the pacer released it (the pacing wait). */
    public static final int PACING = 1;
    /** SGSR: decoder frame-available callback until the GL thread starts the draw (queue wait, coalescing). */
    public static final int AVAILABLE_TO_DRAW = 2;
    /** SGSR: draw start until the swap call returned (CPU submit; HDR includes the buffer hand-over). */
    public static final int DRAW = 3;
    /** SGSR: frame-available until the GPU finished the frame (EGL rendering-complete time, HDR: native fence). */
    public static final int AVAILABLE_TO_GPU = 4;
    /** SGSR: frame-available until SurfaceFlinger latched the frame. */
    public static final int AVAILABLE_TO_LATCH = 5;
    /** SGSR: frame-available until the display presented the frame ("Render latency"). */
    public static final int AVAILABLE_TO_PRESENT = 6;
    public static final int STAGES = 7;
    static final String[] NAMES = {"decode", "pacing", "avail->draw", "draw", "avail->gpu", "avail->latch", "avail->present"};
    static final String TAG = "DelosLatency";

    private static final int CAPACITY = 512;
    private final long[][] rings = new long[STAGES][CAPACITY];
    private final int[] counts = new int[STAGES];
    private final long[] sums = new long[STAGES];
    private final long[] maxes = new long[STAGES];
    private final long[] scratch = new long[CAPACITY];
    private volatile Summary last;

    /** The stages of one window. */
    public static final class Summary {
        private final StageSummary[] stages;

        Summary(StageSummary[] stages) {
            this.stages = stages;
        }

        /** The stage's figures, null when no sample was recorded in the window. */
        public StageSummary stage(int stage) {
            return stages[stage];
        }

        /** The log line: every stage with samples as name avg/p95/max in milliseconds (n = samples). */
        String describe() {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < STAGES; i++) {
                StageSummary s = stages[i];
                if (s == null) continue;
                if (line.length() > 0) line.append(" | ");
                line.append(NAMES[i]).append(' ').append(s.triple()).append(" ms (n=").append(s.count).append(')');
            }
            return line.length() > 0 ? line.toString() : "no samples";
        }
    }

    /** Adds one sample in nanoseconds; negative values (an unavailable timestamp) are ignored. */
    public synchronized void record(int stage, long ns) {
        if (ns < 0) return;
        rings[stage][counts[stage]++ % CAPACITY] = ns;
        sums[stage] += ns;
        if (ns > maxes[stage]) maxes[stage] = ns;
    }

    /** Closes the window: its summary becomes {@link #lastSummary()} and every stage starts empty. */
    public Summary summarize() {
        StageSummary[] stages = new StageSummary[STAGES];
        synchronized (this) {
            for (int i = 0; i < STAGES; i++) {
                int count = counts[i];
                if (count == 0) continue;
                int kept = Math.min(count, CAPACITY);
                System.arraycopy(rings[i], 0, scratch, 0, kept);
                Arrays.sort(scratch, 0, kept);
                stages[i] = new StageSummary(count, sums[i] / (float) count / 1e6f,
                        scratch[percentileIndex(kept, 0.95)] / 1e6f, maxes[i] / 1e6f);
                counts[i] = 0;
                sums[i] = 0;
                maxes[i] = 0;
            }
        }
        Summary summary = new Summary(stages);
        last = summary;
        return summary;
    }

    /** The window closed last, null before the first. Readable from any thread. */
    public Summary lastSummary() {
        return last;
    }

    /** Index of the nearest-rank percentile in a sorted array of {@code size} samples. */
    static int percentileIndex(int size, double fraction) {
        return Math.max(0, Math.min(size - 1, (int) Math.ceil(fraction * size) - 1));
    }
}
