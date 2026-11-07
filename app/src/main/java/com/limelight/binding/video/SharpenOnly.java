package com.limelight.binding.video;

/**
 * The pure maths of the Sharpen only mode: the FidelityFX CAS (contrast adaptive sharpening) formula on the
 * five-sample cross, at a scale factor of 1. The GLSL in assets/sgsr/sharpen_only.frag (OpenGL ES) and
 * app/src/main/jni/pyrowave-renderer/shaders/sgsr_sharpen.glsl (Vulkan) evaluate exactly these steps per
 * colour channel; this class is their CPU reference and the one place the constants are documented.
 *
 * <p>Per channel, with e the pixel and b, d, f, h its up, left, right and down neighbours:
 * <pre>
 *   mn, mx = min / max of the five samples
 *   amp    = sqrt(clamp(min(mn, 1 - mx) / mx, 0, 1))      // less where the signal is already near its limits
 *   w      = -amp * peak(sharpness)
 *   out    = clamp((b + d + f + h) * w + e) / (1 + 4 w)    // weights sum to 1: flat areas never change
 * </pre>
 * The values stay in the encoded domain (SDR gamma or PQ), like every other presentation filter of the app.
 */
public final class SharpenOnly {
    /** CAS's strongest peak weight (1/5); the divisor 1 + 4 w stays at 0.2 instead of reaching 0. */
    public static final float MAX_PEAK = 0.2f;
    /** Below this a maximum is treated as black: no division by zero, no NaN in an FP16 target. */
    public static final float MIN_MAX = 1e-4f;

    private SharpenOnly() {}

    /**
     * The negative-lobe size for a sharpness in 0..1: 0 turns the filter off, 1 is CAS's maximum. The square
     * root puts the middle of the slider (50 %) at 0.141, close to CAS's own default of 0.154, instead of
     * the weak 0.1 a linear ramp would give.
     */
    public static float peak(float sharpness) {
        float s = Math.max(0f, Math.min(1f, sharpness));
        return MAX_PEAK * (float) Math.sqrt(s);
    }

    /** CAS's adaptive amplitude from the neighbourhood's minimum and maximum (0..1). */
    public static float amplitude(float mn, float mx) {
        float a = Math.min(mn, 1f - mx) / Math.max(mx, MIN_MAX);
        return (float) Math.sqrt(Math.max(0f, Math.min(1f, a)));
    }

    /** The weight of each of the four neighbours (negative or zero) for one channel. */
    public static float weight(float mn, float mx, float sharpness) {
        return -amplitude(mn, mx) * peak(sharpness);
    }

    /** One channel of the filter: [e] centre, its up, left, right and down neighbours, sharpness in 0..1. */
    public static float channel(float e, float up, float left, float right, float down, float sharpness) {
        float mn = Math.min(Math.min(Math.min(left, e), Math.min(right, up)), down);
        float mx = Math.max(Math.max(Math.max(left, e), Math.max(right, up)), down);
        float w = weight(mn, mx, sharpness);
        float result = ((up + left + right + down) * w + e) / (1f + 4f * w);
        return Math.max(0f, Math.min(1f, result));
    }
}
