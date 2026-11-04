package com.limelight.shell.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.limelight.shell.settings.BackgroundKind

/**
 * The animated backgrounds drawn by a shader on the GPU (Android 13+), on the same 960 × 540 canvas as
 * the others. Each one mixes the palette's main color ([c1]), the secondary one or the focused game's
 * ([c2]) and the accent ([c3]); [glow] scales how strongly they light the theme color.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object BackgroundShaders {
    private val cache = HashMap<BackgroundKind, RuntimeShader>()
    private val broken = HashSet<BackgroundKind>()

    /** Draws [kind]; false if it has no shader or its shader does not compile here (the caller falls back). */
    fun draw(scope: DrawScope, kind: BackgroundKind, w: Float, h: Float, t: Float, c1: Color, c2: Color, c3: Color, glow: Float, blend: BlendMode, dark: Boolean = true): Boolean {
        val shader = shader(kind) ?: return false
        shader.setFloatUniform("res", w, h)
        shader.setFloatUniform("t", t)
        shader.setFloatUniform("c1", c1.red, c1.green, c1.blue)
        shader.setFloatUniform("c2", c2.red, c2.green, c2.blue)
        shader.setFloatUniform("c3", c3.red, c3.green, c3.blue)
        shader.setFloatUniform("glow", glow)
        shader.setFloatUniform("dark", if (dark) 1f else 0f)
        scope.drawRect(ShaderBrush(shader), size = Size(w, h), blendMode = blend)
        return true
    }

    private fun shader(kind: BackgroundKind): RuntimeShader? {
        cache[kind]?.let { return it }
        if (kind in broken) return null
        val source = SOURCES[kind] ?: return null
        return try {
            RuntimeShader(PRELUDE + source).also { cache[kind] = it }
        } catch (e: IllegalArgumentException) {
            com.limelight.LimeLog.warning("Background ${kind.label} does not compile: ${e.message}")
            broken += kind
            null
        }
    }

    /** Compiles every background once and logs the ones that fail (debug builds, at start). */
    fun verify() {
        SOURCES.keys.forEach { kind -> if (shader(kind) != null) com.limelight.LimeLog.info("Background ${kind.label} compiles") }
    }

    private const val PRELUDE = """
uniform float2 res;
uniform float t;
uniform float3 c1;
uniform float3 c2;
uniform float3 c3;
uniform float glow;
uniform float dark;

float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * noise(p);
        p = p * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}
// The three colors by weight: 60 / 25 / 15 (main, secondary, accent). tri(): k around 0.5 is the main color,
// lower values lean to the secondary one, only the high end brings in the accent
float3 tri(float k) { return mix(mix(c2, c1, clamp(k * 3.0 - 0.3, 0.0, 1.0)), c3, clamp(k * 2.5 - 1.55, 0.0, 1.0)); }
// pick(): the same weights for u in 0..1; (i + 0.5) * 0.618 keeps the colors of neighbouring items well spread
float3 pick(float u) { return u < 0.6 ? c1 : (u < 0.85 ? c2 : c3); }
half4 emit(float3 col, float a) { a = clamp(a * glow, 0.0, 1.0); return half4(half3(col * a), half(a)); }
"""

    private val SOURCES = mapOf(
        // Contour: the topographic lines of the canvas version, but worked out for every pixel, so they are
        // smooth curves with soft edges instead of straight pieces joined every 20 px. c1 → c2 from the lowest
        // to the highest level; every fourth line is stronger, like on a map.
        BackgroundKind.CONTOUR to """
float field(float2 p) {
    return sin(p.x * 0.0062 + t * 0.07) + sin(p.y * 0.0081 - t * 0.05 + p.x * 0.002) + 0.7 * sin((p.x - p.y) * 0.0047 + t * 0.09)
        + 0.5 * cos(length(p - float2(res.x * 0.66, res.y * 0.5)) * 0.011 - t * 0.12);
}
half4 main(float2 fc) {
    float f = field(fc);
    float2 grad = float2(field(fc + float2(1.0, 0.0)) - f, field(fc + float2(0.0, 1.0)) - f);
    float k = (f + 2.8) / 0.35;
    float level = floor(k + 0.5);
    if (level < 0.0 || level > 16.0) return half4(0.0);
    // Distance to the nearest line in canvas pixels: the field's distance divided by its slope
    float dist = abs(k - level) * 0.35 / max(length(grad), 0.0001);
    bool major = mod(level, 4.0) < 0.5;
    float width = major ? 1.7 : 1.0;
    float line = 1.0 - smoothstep(width * 0.5 - 0.45, width * 0.5 + 0.45, dist);
    float u = level / 16.0;
    float a = ((major ? 0.46 : 0.16) + u * (dark > 0.5 ? 0.18 : 0.14)) * line;
    float3 col = mix(c1, c2, u * (0.4 + 0.6 * u));
    return half4(half3(col * a), half(a));
}""",
        // Soft clouds of the three colors drifting through each other
        BackgroundKind.NEBULA to """
half4 main(float2 fc) {
    float2 uv = fc / res.y;
    float2 q = float2(fbm(uv * 2.2 + t * 0.03), fbm(uv * 2.2 + float2(5.2, 1.3) - t * 0.025));
    float n = fbm(uv * 2.0 + q * 1.6 + float2(t * 0.02, 0.0));
    float3 col = mix(c1, c2, smoothstep(0.3, 0.9, q.x));
    col = mix(col, c3, smoothstep(0.45, 0.85, q.y) * 0.8);
    return emit(col, smoothstep(0.3, 0.95, n) * 0.9);
}""",
        // Interfering sine waves mixing the three colors
        BackgroundKind.PLASMA to """
half4 main(float2 fc) {
    float2 uv = fc / res.y * 3.0;
    float v = sin(uv.x + t * 0.4) + sin(uv.y * 1.3 - t * 0.3) + sin((uv.x + uv.y) * 0.7 + t * 0.25)
        + sin(length(uv - float2(2.6 + sin(t * 0.2), 1.5 + cos(t * 0.17))) * 1.6 - t * 0.5);
    v = v * 0.25 * 0.5 + 0.5;
    return emit(tri(v), 0.4 + 0.3 * sin(v * 12.566 + t * 0.5));
}""",
        // A lava lamp: blobs that merge and part again
        BackgroundKind.LAVA to """
half4 main(float2 fc) {
    float2 uv = fc / res.y;
    float ax = res.x / res.y;
    float f = 0.0;
    float3 acc = float3(0.0);
    for (int i = 0; i < 7; i++) {
        float fi = float(i);
        float2 c = float2(ax * (0.5 + 0.42 * sin(t * (0.07 + fi * 0.013) + fi * 1.7)), 0.5 + 0.4 * cos(t * (0.06 + fi * 0.011) + fi * 2.3));
        float r = 0.09 + 0.04 * sin(fi * 3.1);
        float d = r * r / max(dot(uv - c, uv - c), 0.0001);
        f += d;
        float3 cc = pick(fract((fi + 0.5) * 0.618034));
        acc += cc * d;
    }
    float3 col = acc / max(f, 0.0001);
    return emit(col, smoothstep(0.9, 1.5, f) * 0.85 + smoothstep(0.25, 1.0, f) * 0.22);
}""",
        // Light dancing on the bottom of a pool
        BackgroundKind.CAUSTICS to """
half4 main(float2 fc) {
    float2 uv = fc / res;
    float2 p = mod(fc / res.y * 4.0, 6.28318) - 250.0;
    float2 i = p;
    float c = 1.0;
    float inten = 0.005;
    for (int n = 0; n < 4; n++) {
        float tt = t * 0.22 * (1.0 - (3.5 / float(n + 1)));
        i = p + float2(cos(tt - i.x) + sin(tt + i.y), sin(tt - i.y) + cos(tt + i.x));
        c += 1.0 / length(float2(p.x / (sin(i.x + tt) / inten), p.y / (cos(i.y + tt) / inten)));
    }
    c /= 4.0;
    c = 1.17 - pow(c, 1.4);
    float v = clamp(pow(abs(c), 8.0), 0.0, 1.0);
    float3 col = mix(mix(c2, c1, smoothstep(0.0, 0.7, uv.y)), c3, v * 0.5);
    return emit(col, v * 0.9 + 0.12 * (1.0 - uv.y));
}""",
        // Out-of-focus lights drifting slowly
        BackgroundKind.BOKEH to """
half4 main(float2 fc) {
    float2 uv = fc / res.y;
    float ax = res.x / res.y;
    float3 acc = float3(0.0);
    float aa = 0.0;
    for (int i = 0; i < 20; i++) {
        float fi = float(i);
        float s = hash(float2(fi, 1.3));
        float2 c = float2(fract(hash(float2(fi, 7.1)) + t * (0.003 + 0.008 * s)) * (ax + 0.3) - 0.15,
                          fract(hash(float2(fi, 3.7)) - t * (0.002 + 0.006 * s)) * 1.3 - 0.15);
        float r = 0.04 + 0.11 * s;
        float d = length(uv - c);
        float m = (1.0 - smoothstep(r * 0.8, r, d)) * (0.3 + 0.7 * (1.0 - s));
        m += smoothstep(r * 0.75, r * 0.96, d) * (1.0 - smoothstep(r * 0.96, r, d)) * 0.35;
        float3 cc = pick(fract((fi + 0.5) * 0.618034));
        acc += cc * m * 0.4;
        aa += m * 0.4;
    }
    float a = clamp(aa, 0.0, 1.0);
    return emit(acc / max(aa, 0.0001), a);
}""",
        // Thin orbits around a glowing center, with a light travelling each of them
        BackgroundKind.ORBIT to """
half4 main(float2 fc) {
    float2 uv = (fc - res * float2(0.64, 0.5)) / res.y;
    float2 e = float2(uv.x * 0.94 + uv.y * 0.34, (uv.y * 0.94 - uv.x * 0.34) * 2.1);
    float r = length(e);
    float ang = atan(e.y, e.x);
    float3 col = c1 * exp(-r * 7.0) * 0.8;
    float a = exp(-r * 7.0) * 0.5;
    for (int k = 1; k < 8; k++) {
        float fk = float(k);
        float rr = 0.1 * fk;
        float line = 1.0 - smoothstep(0.0, 0.0035 + 0.001 * fk, abs(r - rr));
        float pa = mod(t * (0.35 / fk) + fk * 1.3, 6.28318) - 3.14159;
        float da = abs(mod(ang - pa + 3.14159, 6.28318) - 3.14159);
        float trail = exp(-da * 3.0) * (1.0 - smoothstep(0.0, 0.01, abs(r - rr)));
        float spark = exp(-da * da * 300.0) * exp(-pow((r - rr) / 0.01, 2.0));
        float3 cc = pick(fract((fk + 0.5) * 0.618034));
        float v = line * 0.28 + trail * 0.45 + spark * 1.4;
        col += cc * v;
        a += v;
    }
    return emit(col / max(a, 0.0001), a);
}""",
    )
}
