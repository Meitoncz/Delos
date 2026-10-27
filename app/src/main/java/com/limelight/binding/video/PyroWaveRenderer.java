package com.limelight.binding.video;

import android.view.Surface;

import com.limelight.LimeLog;

/**
 * Delos: the PyroWave renderer (native, app/src/main/jni/pyrowave-renderer). PyroWave is an intra-only
 * wavelet codec decoded with Vulkan compute, so it cannot go through MediaCodec: the native renderer owns
 * a Vulkan device and a swapchain on the stream's surface, and moonlight-common-c feeds it the frames
 * directly. Java only checks whether the device can decode it, creates the renderer for a stream and
 * destroys it.
 */
public final class PyroWaveRenderer {
    /** Colour matrices of the stream (MoonBridge.COLORSPACE_*), as the renderer's shader takes them. */
    public static final int MATRIX_BT601 = 0;
    public static final int MATRIX_BT709 = 1;
    public static final int MATRIX_BT2020 = 2;

    private static Boolean available;

    private long handle;

    private PyroWaveRenderer(long handle) {
        this.handle = handle;
    }

    /** Whether this device can decode PyroWave (Vulkan 1.3 with the features it needs); probed once. */
    public static synchronized boolean isAvailable() {
        if (available == null) {
            try {
                // Settings may ask before a stream has loaded the library
                System.loadLibrary("moonlight-core");
                available = nativeIsAvailable();
            } catch (UnsatisfiedLinkError e) {
                available = false;
            }
            LimeLog.info("PyroWave available: " + available);
        }
        return available;
    }

    /**
     * The renderer for a stream on [surface], or null when it cannot be created. A 10-bit stream shows in
     * HDR10 whenever the host is in HDR mode (setHdrMode), in SDR otherwise.
     */
    public static PyroWaveRenderer create(Surface surface, int width, int height, int frameRate,
                                          int outputWidth, int outputHeight, boolean chroma444,
                                          boolean fullRange, int matrix, boolean tenBit, boolean enableSgsr2) {
        long handle = nativeCreate(surface, width, height, frameRate, outputWidth, outputHeight, chroma444,
                fullRange, matrix, tenBit, enableSgsr2);
        return handle != 0 ? new PyroWaveRenderer(handle) : null;
    }

    /** The GPU time of the last decoded frame in microseconds, 0 when unknown. */
    public int lastGpuDecodeUs() {
        return handle != 0 ? nativeGetLastGpuDecodeUs(handle) : 0;
    }

    /** The host switched HDR on or off. */
    public void setHdrMode(boolean enabled) {
        if (handle != 0) {
            nativeSetHdrMode(handle, enabled);
        }
    }

    public void setUpscalingEnabled(boolean enabled) {
        if (handle != 0) nativeSetUpscalingEnabled(handle, enabled);
    }

    public boolean isUpscalingEnabled() {
        return handle != 0 && nativeIsUpscalingEnabled(handle);
    }

    /** The frame statistics since the last call, in the order of the STAT_* indices. */
    public static final int STAT_TOTAL_FRAMES = 0;
    public static final int STAT_RECEIVED_FRAMES = 1;
    public static final int STAT_RENDERED_FRAMES = 2;
    public static final int STAT_LOST_FRAMES = 3;
    public static final int STAT_PARTIAL_FRAMES = 4;
    public static final int STAT_HOST_LATENCY_FRAMES = 5;
    public static final int STAT_HOST_LATENCY_MIN = 6;
    public static final int STAT_HOST_LATENCY_MAX = 7;
    public static final int STAT_HOST_LATENCY_TOTAL = 8;
    public static final int STAT_GPU_DECODE_FRAMES = 9;
    public static final int STAT_GPU_DECODE_TOTAL_US = 10;
    // 1% low of the presented frames x 100, -100 while too few frames
    public static final int STAT_ONE_PERCENT_LOW_FPS_X100 = 11;
    public static final int STAT_GPU_DRAW_FRAMES = 12;
    public static final int STAT_GPU_DRAW_TOTAL_US = 13;
    public static final int STAT_COUNT = 14;

    public long[] takeStats() {
        long[] stats = new long[STAT_COUNT];
        nativeTakeStats(stats);
        return stats;
    }

    public synchronized void destroy() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    private static native boolean nativeIsAvailable();

    private static native long nativeCreate(Surface surface, int width, int height, int frameRate,
                                            int outputWidth, int outputHeight, boolean chroma444,
                                            boolean fullRange, int matrix, boolean tenBit, boolean enableSgsr2);

    private static native void nativeSetHdrMode(long handle, boolean enabled);
    private static native void nativeSetUpscalingEnabled(long handle, boolean enabled);
    private static native boolean nativeIsUpscalingEnabled(long handle);

    private static native int nativeGetLastGpuDecodeUs(long handle);

    private static native void nativeTakeStats(long[] out);

    private static native void nativeDestroy(long handle);
}
