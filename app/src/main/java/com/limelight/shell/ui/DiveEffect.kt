package com.limelight.shell.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Flying through the ring into the game (GUI.md, step 6.5): everything around the opening rushes
 * outwards – a zoom blur from the center, a light chromatic aberration that grows towards the edges and
 * a few wind streaks in the accent color. Every pixel is sampled from inside the screen, so it always
 * stays filled; transparency is kept, so the stream stays visible through the ring's opening.
 * Android 13+ (AGSL); older versions just open the ring.
 */
private const val DIVE_SHADER = """
uniform shader content;
uniform float2 size;
uniform float progress;
uniform half3 tint;

float hash(float n) { return fract(sin(n * 12.9898) * 43758.5453); }

half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 d = p - c;
    float radius = length(d) / length(c);
    float e = progress * progress;

    // Zoom blur towards the viewer; the color channels are scaled apart (inwards only), more towards the edges
    float zoom = 1.0 + e * 1.8;
    float streak = progress * 0.5;
    float split = progress * (0.008 + 0.03 * radius);
    half4 col = half4(0.0);
    for (int i = 0; i < 8; i++) {
        float t = float(i) / 7.0;
        float2 q = d / (zoom * (1.0 + streak * t));
        half4 g = content.eval(c + q * (1.0 - split * 0.5));
        col.r += content.eval(c + q).r;
        col.g += g.g;
        col.b += content.eval(c + q * (1.0 - split)).b;
        col.a += g.a;
    }
    col /= 8.0;

    // Wind: a few thin streaks rushing outwards, towards the edges
    float angle = atan(d.y, d.x);
    float lane = floor((angle + 3.14159) * 70.0);
    float gust = step(0.92, hash(lane)) * smoothstep(0.25, 0.9, radius);
    float travel = fract(radius * 1.6 - progress * 3.5 + hash(lane + 7.0));
    float wind = gust * smoothstep(0.0, 0.25, travel) * (1.0 - smoothstep(0.25, 0.6, travel)) * progress * 0.3;
    col.rgb += tint * wind * col.a;
    return col;
}
"""

/**
 * Applies the dive to everything drawn inside. [progress] is read while drawing, so the animation does
 * not recompose anything; at 0 the effect is off.
 */
@Composable
fun Modifier.diveEffect(progress: () -> Float, tint: Color): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    val shader = remember { DiveShader() }
    return graphicsLayer {
        val p = progress()
        renderEffect = if (p <= 0f) null else shader.effect(size.width, size.height, p, tint)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class DiveShader {
    private val shader = RuntimeShader(DIVE_SHADER)

    fun effect(width: Float, height: Float, progress: Float, tint: Color): androidx.compose.ui.graphics.RenderEffect {
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("progress", progress)
        shader.setFloatUniform("tint", tint.red, tint.green, tint.blue)
        return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}
