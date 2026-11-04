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

    public static final int UPSCALING_OFF = 0;
    public static final int UPSCALING_SGSR1_STANDARD = 1;
    public static final int UPSCALING_SGSR1_HIGH = 2;
    public static final int UPSCALING_SGSR2_VIDEO = 3;
    public static final int UPSCALING_SGSR2_VIDEO_QUALITY = 4;

    private static Boolean available;

    // Written by destroy() on one thread, read by the setters and getters of others; the native entry points
    // check the handle against the live renderer, so a stale value is harmless.
    private volatile long handle;
    private final float[] launchArrivalPose = new float[LaunchArrival.POSE_FLOATS];
    private boolean launchArrivalPrepared;

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
     * HDR10 whenever the host is in HDR mode (setHdrMode), in SDR otherwise. The upscaler's sizing limits are
     * SgsrDimensions', so the native planner and the MediaCodec path cannot drift apart.
     */
    public static PyroWaveRenderer create(Surface surface, int width, int height, int frameRate,
                                          int outputWidth, int outputHeight, boolean chroma444,
                                          boolean fullRange, int matrix, boolean tenBit, int upscalingMode,
                                          int supersamplingLevel, float sharpness) {
        long handle = nativeCreate(surface, width, height, frameRate, outputWidth, outputHeight, chroma444,
                fullRange, matrix, tenBit, upscalingMode, supersamplingLevel, sharpness,
                SgsrDimensions.MAX_INTERNAL_WIDTH, SgsrDimensions.MAX_INTERNAL_HEIGHT,
                SgsrDimensions.MAX_INTERNAL_PIXELS, SgsrDimensions.NATIVE_TOLERANCE);
        return handle != 0 ? new PyroWaveRenderer(handle) : null;
    }

    /** The GPU time of the last decoded frame in microseconds, 0 when unknown. */
    public int lastGpuDecodeUs() {
        final long h = handle;
        return h != 0 ? nativeGetLastGpuDecodeUs(h) : 0;
    }

    /** The host switched HDR on or off. */
    public void setHdrMode(boolean enabled) {
        final long h = handle;
        if (h != 0) {
            nativeSetHdrMode(h, enabled);
        }
    }

    public void setUpscalingMode(int mode) {
        final long h = handle;
        if (h != 0) nativeSetUpscalingMode(h, mode);
    }

    /** The mode actually applied by the renderer, not the requested setting. */
    public int getUpscalingMode() {
        final long h = handle;
        return h != 0 ? nativeGetUpscalingMode(h) : UPSCALING_OFF;
    }

    /**
     * The supersampling level in effect (1 or 2): the one last requested, or 1 once the renderer found that 2
     * does not fit. It follows a request at once; it does not wait for the renderer's next frame.
     */
    public int getSupersamplingLevel() {
        final long h = handle;
        return h != 0 ? nativeGetSupersamplingLevel(h) : 1;
    }

    public void setSharpness(float sharpness) {
        final long h = handle;
        if (h != 0) nativeSetSharpness(h, Math.max(0f, Math.min(1f, sharpness)));
    }

    public void setSupersamplingLevel(int level) {
        final long h = handle;
        if (h != 0) nativeSetSupersamplingLevel(h, level >= 2 ? 2 : 1);
    }

    public boolean isSupersampling() {
        final long h = handle;
        return h != 0 && nativeIsSupersampling(h);
    }

    public String getScaleDescription() {
        final long h = handle;
        return h != 0 ? nativeGetScaleDescription(h) : null;
    }

    /** Arms GPU preparation on the owning video thread, without blocking decoder startup. */
    public synchronized void prepareLaunchArrival() {
        if (launchArrivalPrepared || handle == 0) return;
        launchArrivalPrepared = true;
        sendLaunchArrivalPose(-1f, true);
    }

    public boolean isLaunchArrivalReady() {
        final long h = handle;
        return h != 0 && nativeIsLaunchArrivalReady(h);
    }

    public synchronized void setLaunchArrivalProgress(float progress) {
        if (handle != 0 && launchArrivalPrepared) sendLaunchArrivalPose(progress, false);
    }

    private void sendLaunchArrivalPose(float progress, boolean prepare) {
        LaunchArrival.writePose(progress, launchArrivalPose);
        nativeSetLaunchArrivalPose(handle, prepare, progress,
                launchArrivalPose[0], launchArrivalPose[1], launchArrivalPose[2],
                launchArrivalPose[3], launchArrivalPose[4], launchArrivalPose[5]);
    }

    public boolean isLaunchArrivalComplete() {
        final long h = handle;
        return h != 0 && nativeIsLaunchArrivalComplete(h);
    }

    public String getLaunchArrivalError() {
        final long h = handle;
        return h != 0 ? nativeGetLaunchArrivalError(h) : "PyroWave renderer has been destroyed";
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
                                            boolean fullRange, int matrix, boolean tenBit, int upscalingMode,
                                            int supersamplingLevel, float sharpness, int maxInternalWidth,
                                            int maxInternalHeight, long maxInternalPixels, int nativeTolerance);

    private static native void nativeSetHdrMode(long handle, boolean enabled);
    private static native void nativeSetUpscalingMode(long handle, int mode);
    private static native int nativeGetUpscalingMode(long handle);
    private static native void nativeSetSharpness(long handle, float sharpness);
    private static native void nativeSetSupersamplingLevel(long handle, int level);
    private static native boolean nativeIsSupersampling(long handle);
    private static native int nativeGetSupersamplingLevel(long handle);
    private static native String nativeGetScaleDescription(long handle);
    private static native void nativeSetLaunchArrivalPose(long handle, boolean prepare, float progress,
                                                          float scaleX, float scaleY, float skew,
                                                          float warp, float blur, float chromatic);
    private static native boolean nativeIsLaunchArrivalReady(long handle);
    private static native boolean nativeIsLaunchArrivalComplete(long handle);
    private static native String nativeGetLaunchArrivalError(long handle);

    private static native int nativeGetLastGpuDecodeUs(long handle);

    private static native void nativeTakeStats(long[] out);

    private static native void nativeDestroy(long handle);
}
