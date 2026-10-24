package com.limelight.shell.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import com.limelight.shell.settings.BackgroundKind
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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
    fun draw(scope: DrawScope, kind: BackgroundKind, w: Float, h: Float, t: Float, c1: Color, c2: Color, c3: Color, glow: Float, blend: BlendMode): Boolean {
        val shader = shader(kind) ?: return false
        shader.setFloatUniform("res", w, h)
        shader.setFloatUniform("t", t)
        shader.setFloatUniform("c1", c1.red, c1.green, c1.blue)
        shader.setFloatUniform("c2", c2.red, c2.green, c2.blue)
        shader.setFloatUniform("c3", c3.red, c3.green, c3.blue)
        shader.setFloatUniform("glow", glow)
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
float3 tri(float k) { return mix(mix(c1, c2, clamp(k * 2.0, 0.0, 1.0)), c3, clamp(k * 2.0 - 1.0, 0.0, 1.0)); }
half4 emit(float3 col, float a) { a = clamp(a * glow, 0.0, 1.0); return half4(half3(col * a), half(a)); }
"""

    private val SOURCES = mapOf(
        // Soft clouds of the three colors drifting through each other
        BackgroundKind.NEBULA to """
half4 main(float2 fc) {
    float2 uv = fc / res.y;
    float2 q = float2(fbm(uv * 2.2 + t * 0.03), fbm(uv * 2.2 + float2(5.2, 1.3) - t * 0.025));
    float n = fbm(uv * 2.0 + q * 1.6 + float2(t * 0.02, 0.0));
    float3 col = mix(c1, c2, smoothstep(0.2, 0.8, q.x));
    col = mix(col, c3, smoothstep(0.45, 0.85, q.y) * 0.8);
    return emit(col, smoothstep(0.3, 0.95, n) * 0.9);
}""",
        // A smooth gradient between four slowly wandering color points
        BackgroundKind.MESH to """
half4 main(float2 fc) {
    float2 uv = fc / res.y;
    float ax = res.x / res.y;
    float2 p1 = float2(ax * (0.22 + 0.16 * sin(t * 0.13)), 0.3 + 0.2 * cos(t * 0.11));
    float2 p2 = float2(ax * (0.78 + 0.14 * cos(t * 0.09)), 0.32 + 0.22 * sin(t * 0.12));
    float2 p3 = float2(ax * (0.5 + 0.25 * sin(t * 0.07 + 2.0)), 0.78 + 0.14 * cos(t * 0.1 + 1.0));
    float2 p4 = float2(ax * (0.12 + 0.08 * cos(t * 0.08)), 0.85 + 0.08 * sin(t * 0.14));
    float w1 = 1.0 / (dot(uv - p1, uv - p1) + 0.03);
    float w2 = 1.0 / (dot(uv - p2, uv - p2) + 0.03);
    float w3 = 1.0 / (dot(uv - p3, uv - p3) + 0.03);
    float w4 = 0.6 / (dot(uv - p4, uv - p4) + 0.03);
    float3 col = (c1 * w1 + c2 * w2 + c3 * w3 + mix(c1, c3, 0.5) * w4) / (w1 + w2 + w3 + w4);
    float strength = clamp(max(max(w1, w2), max(w3, w4)) * 0.025, 0.25, 0.85);
    return emit(col, strength);
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
        float k = mod(fi, 3.0);
        float3 cc = k < 0.5 ? c1 : (k < 1.5 ? c2 : c3);
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
    float3 col = mix(mix(c2, c1, uv.y), c3, v * 0.5);
    return emit(col, v * 0.9 + 0.12 * (1.0 - uv.y));
}""",
        // A retro neon grid running to the horizon, under a striped sun
        BackgroundKind.GRID to """
half4 main(float2 fc) {
    float2 uv = fc / res;
    float hz = 0.6;
    float a = 0.0;
    float3 col = c1;
    if (uv.y > hz) {
        float y = uv.y - hz;
        float z = 0.32 / y;
        float x = (uv.x - 0.5) * z * 2.4;
        float fx = abs(fract(x) - 0.5);
        float fz = abs(fract(z * 0.8 - t * 0.35) - 0.5);
        float fade = smoothstep(0.0, 0.22, y);
        float lx = 1.0 - smoothstep(0.0, 0.035, fx);
        float lz = 1.0 - smoothstep(0.0, 0.05, fz);
        a = max(lx, lz) * fade * 0.8;
        col = mix(c2, c1, lz);
    }
    float horizon = exp(-abs(uv.y - hz) * 22.0);
    col = mix(col, c3, horizon * 0.6);
    a = max(a, horizon * 0.55);
    float2 sp = float2((uv.x - 0.5) * res.x / res.y, uv.y - (hz - 0.17));
    float sun = 1.0 - smoothstep(0.17, 0.175, length(sp));
    float stripes = step(0.35, fract((uv.y - hz) * 40.0 + t * 0.2)) + step(uv.y, hz - 0.2);
    sun *= clamp(stripes, 0.0, 1.0) * step(uv.y, hz);
    if (sun > 0.0) {
        col = mix(c3, c1, clamp((uv.y - (hz - 0.34)) / 0.34, 0.0, 1.0));
        a = max(a, sun * 0.8);
    }
    return emit(col, a);
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
        float k = mod(fi, 3.0);
        float3 cc = k < 0.5 ? c1 : (k < 1.5 ? c2 : c3);
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
        float m = mod(fk, 3.0);
        float3 cc = m < 0.5 ? c1 : (m < 1.5 ? c2 : c3);
        float v = line * 0.28 + trail * 0.45 + spark * 1.4;
        col += cc * v;
        a += v;
    }
    return emit(col / max(a, 0.0001), a);
}""",
        // Stars in three layers, twinkling, over a faint nebula
        BackgroundKind.STARFIELD to """
half4 main(float2 fc) {
    float2 uv = fc / res.y;
    float n = fbm(uv * 1.6 + float2(t * 0.01, 0.0));
    float neb = smoothstep(0.5, 0.9, n) * 0.3;
    float3 col = mix(c1, c2, n) * neb;
    float a = neb;
    for (int l = 0; l < 3; l++) {
        float fl = float(l);
        float sc = 14.0 + fl * 12.0;
        float2 p = uv * sc + float2(t * (0.08 + fl * 0.06), t * 0.015);
        float2 id = floor(p);
        float2 f = fract(p) - 0.5;
        float h = hash(id + fl * 13.0);
        if (h > 0.8) {
            float2 o = float2(hash(id * 1.7 + 0.3), hash(id * 2.3 + 0.7)) - 0.5;
            float d = length(f - o * 0.7);
            float tw = 0.55 + 0.45 * sin(t * (1.0 + h * 3.0) + h * 30.0);
            float s = (1.0 - smoothstep(0.0, 0.07 - fl * 0.015, d)) * tw * (1.0 - fl * 0.25);
            float3 cc = h > 0.95 ? c3 : (h > 0.88 ? c2 : mix(float3(1.0), c1, 0.3));
            col += cc * s;
            a += s;
        }
    }
    return emit(col / max(a, 0.0001), a);
}""",
    )
}

/** Constellation: drifting points linked by thin lines when they come close (drawn on the canvas). */
internal class ConstellationState {
    val lines = FloatArray(COUNT * COUNT * 2)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1f
    }
    val xs = FloatArray(COUNT)
    val ys = FloatArray(COUNT)

    companion object {
        const val COUNT = 46
    }
}

internal fun DrawScope.drawConstellation(state: ConstellationState, w: Float, h: Float, t: Float, colors: List<Color>, dark: Boolean, blend: BlendMode) {
    val n = ConstellationState.COUNT
    for (i in 0 until n) {
        val s = i * 12.9898f
        state.xs[i] = w * (.5f + .48f * sin(t * (.021f + (i % 7) * .004f) + s))
        state.ys[i] = h * (.5f + .46f * cos(t * (.017f + (i % 5) * .005f) + s * 1.7f))
    }
    val reach = 120f
    val nativeBlend = if (dark) android.graphics.BlendMode.PLUS else android.graphics.BlendMode.SRC_OVER
    drawIntoCanvas { canvas ->
        // One batch per color and strength band keeps it to a few draws per frame
        for ((ci, color) in colors.withIndex()) {
            for (band in 0 until 3) {
                var count = 0
                for (i in 0 until n) {
                    if (i % colors.size != ci) continue
                    for (j in i + 1 until n) {
                        val d = hypot(state.xs[i] - state.xs[j], state.ys[i] - state.ys[j])
                        if (d >= reach) continue
                        if (((d / reach) * 3f).toInt().coerceIn(0, 2) != band) continue
                        state.lines[count++] = state.xs[i]; state.lines[count++] = state.ys[i]
                        state.lines[count++] = state.xs[j]; state.lines[count++] = state.ys[j]
                    }
                }
                if (count == 0) continue
                state.paint.blendMode = nativeBlend
                state.paint.color = color.copy(alpha = (if (dark) .42f else .5f) * (1f - band / 3f)).toArgb()
                canvas.nativeCanvas.drawLines(state.lines, 0, count, state.paint)
            }
        }
    }
    for (i in 0 until n) {
        val c = colors[i % colors.size]
        val pulse = .6f + .4f * sin(t * (.8f + (i % 4) * .3f) + i)
        drawCircle(c.copy(alpha = (if (dark) .8f else .7f) * pulse), radius = 1.6f + (i % 3) * .6f, center = Offset(state.xs[i], state.ys[i]), blendMode = blend)
    }
}
