package com.limelight.binding.video;

import android.opengl.GLES30;
import android.opengl.GLES31;
import android.util.Log;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Measures what the Sharpen only mode costs the OpenGL ES output stage: the copy of the decoder's frame into
 * a texture (the direct MediaCodec output does not have it) and the sharpen pass itself. GPU time comes from
 * EXT_disjoint_timer_query when the driver has it and a probe query works; the CPU time of submitting each
 * draw is always recorded. Once a second it logs one line under the tag {@value #TAG} and publishes a
 * {@link SharpenCost} for the statistics panel. Everything runs on the SGSR GL thread, except
 * {@link #snapshot()}.
 */
final class SharpenMeter {
    static final String TAG = "DelosSharpen";
    private static final long WINDOW_NS = 1_000_000_000L;
    /** Queries in flight per span: the GPU runs a few frames behind the submitting thread. */
    private static final int RING = 4;
    // EXT_disjoint_timer_query: the ES 3.0 query entry points take its enums
    private static final int TIME_ELAPSED_EXT = 0x88BF;
    private static final int GPU_DISJOINT_EXT = 0x8FBB;

    private final boolean gpuTimer;
    private final Span copy = new Span();
    private final Span pass = new Span();
    private final int[] value = new int[1];
    private long windowStartNs;
    private volatile SharpenCost snapshot;

    /** One measured stretch of draw calls: a ring of timer queries plus the window's sums. */
    private static final class Span {
        final int[] ids = new int[RING];
        int head, tail, pending;
        boolean open;
        long cpuStartNs;
        // The window's results
        double gpuSumMs, gpuMaxMs, cpuSumMs, cpuMaxMs;
        int gpuCount, cpuCount;
        // The results of one poll, kept apart until the disjoint flag says they are valid
        double pollSumMs, pollMaxMs;
        int pollCount;

        void clearWindow() {
            gpuSumMs = gpuMaxMs = cpuSumMs = cpuMaxMs = 0;
            gpuCount = cpuCount = 0;
        }
    }

    private SharpenMeter(boolean gpuTimer) {
        this.gpuTimer = gpuTimer;
        if (gpuTimer) {
            GLES30.glGenQueries(RING, copy.ids, 0);
            GLES30.glGenQueries(RING, pass.ids, 0);
        }
    }

    /**
     * Builds the meter on the GL thread with its context current. [extensions] is the GL_EXTENSIONS string:
     * without EXT_disjoint_timer_query, or when a probe query raises an error, only CPU times are measured.
     */
    static SharpenMeter create(String extensions) {
        boolean timer = extensions != null && extensions.contains("GL_EXT_disjoint_timer_query");
        if (timer) {
            // A query the driver rejects would raise a GL error that the renderer treats as a failed frame
            int[] probe = new int[1];
            GLES30.glGenQueries(1, probe, 0);
            while (GLES31.glGetError() != GLES31.GL_NO_ERROR) { /* clear what came before */ }
            GLES30.glBeginQuery(TIME_ELAPSED_EXT, probe[0]);
            GLES30.glEndQuery(TIME_ELAPSED_EXT);
            timer = GLES31.glGetError() == GLES31.GL_NO_ERROR;
            while (GLES31.glGetError() != GLES31.GL_NO_ERROR) { /* the probe's own leftovers */ }
            GLES30.glDeleteQueries(1, probe, 0);
        }
        Log.i(TAG, "GPU timer queries " + (timer ? "available" : "unavailable; CPU submit time only"));
        return new SharpenMeter(timer);
    }

    void beginCopy() { begin(copy); }
    void endCopy() { end(copy); }
    void beginPass() { begin(pass); }
    void endPass() { end(pass); }

    private void begin(Span span) {
        span.cpuStartNs = System.nanoTime();
        if (gpuTimer && span.pending < RING) {
            GLES30.glBeginQuery(TIME_ELAPSED_EXT, span.ids[span.head]);
            span.open = true;
        }
    }

    private void end(Span span) {
        double cpuMs = (System.nanoTime() - span.cpuStartNs) / 1_000_000.0;
        span.cpuSumMs += cpuMs;
        span.cpuMaxMs = Math.max(span.cpuMaxMs, cpuMs);
        span.cpuCount++;
        if (span.open) {
            GLES30.glEndQuery(TIME_ELAPSED_EXT);
            span.open = false;
            span.head = (span.head + 1) % RING;
            span.pending++;
        }
    }

    private void poll(Span span) {
        span.pollSumMs = span.pollMaxMs = 0;
        span.pollCount = 0;
        while (span.pending > 0) {
            GLES30.glGetQueryObjectuiv(span.ids[span.tail], GLES30.GL_QUERY_RESULT_AVAILABLE, value, 0);
            if (value[0] == 0) break;
            GLES30.glGetQueryObjectuiv(span.ids[span.tail], GLES30.GL_QUERY_RESULT, value, 0);
            double ms = (value[0] & 0xFFFFFFFFL) / 1_000_000.0;
            span.pollSumMs += ms;
            span.pollMaxMs = Math.max(span.pollMaxMs, ms);
            span.pollCount++;
            span.tail = (span.tail + 1) % RING;
            span.pending--;
        }
    }

    /**
     * Called once per presented frame. Collects the finished GPU timings and, once a second, logs and publishes
     * the window. [active] is false while another mode is running: the window is then dropped and the snapshot
     * cleared. [describe] names the mode, sizes and sharpness for the log line.
     */
    void tick(long nowNs, boolean active, Supplier<String> describe) {
        if (gpuTimer) {
            poll(copy);
            poll(pass);
            // A disjoint event (power state change, context loss) invalidates the timings gathered since the last check
            GLES31.glGetIntegerv(GPU_DISJOINT_EXT, value, 0);
            if (value[0] == 0) {
                accept(copy);
                accept(pass);
            }
        }
        if (windowStartNs == 0) windowStartNs = nowNs;
        if (nowNs - windowStartNs < WINDOW_NS) return;
        windowStartNs = nowNs;
        if (active && pass.cpuCount > 0) publish(describe.get());
        else snapshot = null;
        copy.clearWindow();
        pass.clearWindow();
    }

    private static void accept(Span span) {
        span.gpuSumMs += span.pollSumMs;
        span.gpuMaxMs = Math.max(span.gpuMaxMs, span.pollMaxMs);
        span.gpuCount += span.pollCount;
    }

    private void publish(String description) {
        boolean gpu = gpuTimer && pass.gpuCount > 0;
        float passAvg, passMax, copyAvg, copyMax;
        if (gpu) {
            passAvg = (float) (pass.gpuSumMs / pass.gpuCount);
            passMax = (float) pass.gpuMaxMs;
            copyAvg = copy.gpuCount > 0 ? (float) (copy.gpuSumMs / copy.gpuCount) : -1f;
            copyMax = copy.gpuCount > 0 ? (float) copy.gpuMaxMs : -1f;
        } else {
            passAvg = (float) (pass.cpuSumMs / pass.cpuCount);
            passMax = (float) pass.cpuMaxMs;
            copyAvg = copy.cpuCount > 0 ? (float) (copy.cpuSumMs / copy.cpuCount) : -1f;
            copyMax = copy.cpuCount > 0 ? (float) copy.cpuMaxMs : -1f;
        }
        snapshot = new SharpenCost(gpu, passAvg, passMax, copyAvg, copyMax, "copy", gpu ? pass.gpuCount : pass.cpuCount);
        StringBuilder line = new StringBuilder(description);
        line.append(String.format(Locale.US, " timer=%s", gpu ? "gpu" : "cpu-submit"));
        if (gpu) {
            line.append(String.format(Locale.US, " | pass gpu avg=%.3f max=%.3f ms (n=%d)", passAvg, passMax, pass.gpuCount));
            if (copy.gpuCount > 0)
                line.append(String.format(Locale.US, " | copy gpu avg=%.3f max=%.3f ms", copyAvg, copyMax));
        }
        line.append(String.format(Locale.US, " | cpu submit pass avg=%.3f max=%.3f ms",
                pass.cpuSumMs / pass.cpuCount, pass.cpuMaxMs));
        if (copy.cpuCount > 0)
            line.append(String.format(Locale.US, " copy avg=%.3f max=%.3f ms", copy.cpuSumMs / copy.cpuCount, copy.cpuMaxMs));
        Log.d(TAG, line.toString());
    }

    /** The last published window, null while Sharpen only is not running. Readable from any thread. */
    SharpenCost snapshot() { return snapshot; }

    /** Deletes the queries; the GL context must be current. */
    void close() {
        if (gpuTimer) {
            GLES30.glDeleteQueries(RING, copy.ids, 0);
            GLES30.glDeleteQueries(RING, pass.ids, 0);
        }
        snapshot = null;
    }
}
