package com.limelight.shell.ui

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The pure parts of the hero's bokeh lens (prototype variant B): which thresholds make a pixel a "light",
 * how many taps a given aperture needs and the AGSL source for that tap count. No Android types, so the
 * numbers and the shader text are unit-testable on the JVM.
 *
 * Every tap's weight is `w = 1 + K * smoothstep(t0, t1, luma)^1.5` times a rim profile; the sum is divided
 * by the weight sum, so brightness is conserved while the few brightest pixels (above the artwork's own
 * 97th percentile) pull their whole neighbourhood towards a flat, hard-edged disc.
 */
internal object HeroBokeh {
    /** Part of the lens cache key: bump whenever the look of the lens changes, so no stale entry is reused. */
    const val ALGORITHM_VERSION = 2

    const val HIGHLIGHT_GAIN = 120f
    const val LOW_PERCENTILE = 97.0
    const val HIGH_PERCENTILE = 99.4

    /** Smallest distance between the two thresholds (luma 0–1), so the smoothstep never degenerates. */
    const val MIN_THRESHOLD_GAP = 0.06f

    /** Kernel weight at the aperture centre is 1 - RIM and rises to 1 at the edge: the "onion ring". */
    const val RIM = 0.4f

    const val MIN_TAPS = 128
    const val MAX_TAPS = 1280
    private const val TAP_STEP = 32
    private const val AREA_PER_TAP = 0.6

    /** The aperture radius in px of a bitmap [width] px wide for an app blur of [blurPx] (0.4 px per unit at 420). */
    fun radiusPx(blurPx: Int, width: Int): Float = blurPx.coerceIn(0, 40) * (width / 2100f) * 2f

    /** One golden-angle tap per ~0.6 px² of aperture, a multiple of 32 so only a few shader variants exist. */
    fun sampleCount(radius: Float): Int {
        val ideal = PI * radius * radius / AREA_PER_TAP
        return ((ideal / TAP_STEP).roundToInt() * TAP_STEP).coerceIn(MIN_TAPS, MAX_TAPS)
    }

    /** Rec. 709 luma of an ARGB pixel in gamma space, as the shader computes it. */
    fun luma(argb: Int): Float =
        (0.2126f * ((argb shr 16) and 255) + 0.7152f * ((argb shr 8) and 255) + 0.0722f * (argb and 255)) / 255f

    /** [q] percent (0–100) of ascending [sorted], linearly interpolated between ranks like numpy's default. */
    fun percentile(sorted: FloatArray, q: Double): Float {
        require(sorted.isNotEmpty())
        val rank = q.coerceIn(0.0, 100.0) / 100.0 * (sorted.size - 1)
        val low = floor(rank).toInt()
        val high = minOf(low + 1, sorted.size - 1)
        return (sorted[low] + (sorted[high] - sorted[low]) * (rank - low)).toFloat()
    }

    /** The (t0, t1) highlight thresholds of an artwork's pixels: its p97 and p99.4 luma, at least 0.06 apart. */
    fun thresholds(pixels: IntArray): Pair<Float, Float> {
        val luma = FloatArray(pixels.size) { luma(pixels[it]) }
        luma.sort()
        var t0 = percentile(luma, LOW_PERCENTILE)
        val t1 = maxOf(minOf(percentile(luma, HIGH_PERCENTILE), 1f), t0 + MIN_THRESHOLD_GAP)
        t0 = minOf(t0, t1 - MIN_THRESHOLD_GAP)
        return t0 to t1
    }

    /**
     * AGSL needs a compile-time loop bound, so the tap count is baked into the source (at most 40 distinct
     * counts exist) instead of looping to the maximum with a uniform `break`.
     */
    fun shaderSource(taps: Int): String = """
uniform shader image;
uniform float2 resolution;
uniform float radius;
uniform float2 thresholds;

half4 main(float2 p) {
    float4 sum = float4(0.0);
    float weights = 0.0;
    for (int i = 0; i < $taps; ++i) {
        float fi = float(i);
        float rho = sqrt((fi + 0.5) / $taps.0);
        float angle = fi * 2.39996323;
        float4 s = float4(image.eval(p + radius * rho * float2(cos(angle), sin(angle))));
        float h = smoothstep(thresholds.x, thresholds.y, dot(s.rgb, float3(0.2126, 0.7152, 0.0722)));
        float w = (1.0 + $HIGHLIGHT_GAIN * h * sqrt(h)) * ((1.0 - $RIM) + $RIM * smoothstep(0.55, 0.97, rho));
        sum += s * w;
        weights += w;
    }
    half4 color = half4(sum / weights);
    // Same diagonal stops as HeroBitmaps' legacy snapshot, after (not inside) the blur.
    float2 direction = float2(-resolution.x, resolution.y);
    float t = dot(p - float2(resolution.x, 0.0), direction) / dot(direction, direction);
    float alpha = clamp((0.95 - t) / 0.65, 0.0, 1.0);
    return color * half(alpha);
}
"""
}
