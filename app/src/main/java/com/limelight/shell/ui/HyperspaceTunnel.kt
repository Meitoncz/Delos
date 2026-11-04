package com.limelight.shell.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Tunnel only: the soft aperture exposes the real surface below, without sampling or deforming it.
 * The caller owns the shared clock (seconds), first-frame gating and reduced-motion/API fallback.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun HyperspaceTunnel(
    progress: () -> Float,
    opening: () -> Float,
    clock: () -> Float,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDelosColors.current
    // Composition-scoped ownership: no global shader/brush cache survives overlay disposal.
    // Deliberately let AGSL compilation errors surface instead of hiding a broken port.
    val shader = remember { RuntimeShader(HYPERSPACE_SHADER) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val motion = remember(LaunchHandoff.startedAt) { LaunchHandoff.motion }
    Box(modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        Canvas(
            Modifier
                // Box does not propagate its minimum constraints: this is a genuinely smaller
                // raster (1152 x 648 at 1920 x 1080), not a full-resolution shader scaled afterward.
                .fillMaxSize(HyperspaceConfig.RENDER_SCALE)
                .graphicsLayer {
                    scaleX = 1f / HyperspaceConfig.RENDER_SCALE
                    scaleY = 1f / HyperspaceConfig.RENDER_SCALE
                    transformOrigin = TransformOrigin(0f, 0f)
                    compositingStrategy = CompositingStrategy.Offscreen
                },
        ) {
            val o = opening()
            if (o >= 1f || size.width <= 0f || size.height <= 0f) return@Canvas
            val seconds = clock()
            motion.advance(seconds, progress(), o)
            // State reads and every uniform mutation belong to drawing, not composition/layout.
            shader.setFloatUniform("uResolution", size.width, size.height)
            shader.setFloatUniform("uTime", seconds)
            shader.setFloatUniform("uTravel", motion.travel)
            shader.setFloatUniform("uProgress", motion.smoothedProgress)
            shader.setFloatUniform("uOpening", o)
            shader.setFloatUniform("uBg", colors.bg.red, colors.bg.green, colors.bg.blue)
            shader.setFloatUniform("uGold", colors.accent.red, colors.accent.green, colors.accent.blue)
            shader.setFloatUniform("uCyan", colors.accent2.red, colors.accent2.green, colors.accent2.blue)
            shader.setFloatUniform("uIndigo", colors.accent3.red, colors.accent3.green, colors.accent3.blue)
            drawRect(brush)
        }
    }
}

// The approved GLSL math, with top-left Canvas coordinates and premultiplied AGSL output.
// Immutable CONFIG values become shader constants, not redundant per-frame uniform updates.
private val HYPERSPACE_SHADER = """
uniform float2 uResolution;
uniform float uTime, uTravel, uProgress, uOpening;
uniform float3 uBg, uGold, uCyan, uIndigo;

const float2 uCenter = float2(${HyperspaceConfig.CENTER_X}, ${HyperspaceConfig.CENTER_Y});
const float uDepth = ${HyperspaceConfig.DEPTH};
const float uThroat = ${HyperspaceConfig.THROAT};
const float uWallGain = ${HyperspaceConfig.WALL_GAIN};
const float uSpokes = ${HyperspaceConfig.SPOKES};
const float uBandFrequency = ${HyperspaceConfig.BAND_FREQUENCY};
const float uSwirl = ${HyperspaceConfig.SWIRL};
const float uVelocity = ${HyperspaceConfig.VELOCITY};
const float uAcceleration = ${HyperspaceConfig.ACCELERATION};
const float uStreakLength = ${HyperspaceConfig.STREAK_LENGTH};
const float uAberration = ${HyperspaceConfig.ABERRATION};
const float uOpeningAcceleration = ${HyperspaceConfig.OPENING_ACCELERATION};
const float uCloudDensity = ${HyperspaceConfig.CLOUD_DENSITY};

float hash(float n) { return fract(sin(n * 127.1 + 311.7) * 43758.5453); }
float segment(float2 p, float2 a, float2 b) {
    float2 ab = b - a;
    return length(p - a - ab * clamp(dot(p - a, ab) / max(dot(ab, ab), .000001), 0., 1.));
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float aspect = uResolution.x / uResolution.y;
    float2 screen = (uv - uCenter) * float2(aspect, 1.);
    float o = clamp(uOpening, 0., 1.);
    // Revealed pixels skip all 48 streak/cloud iterations. There is no stream child shader.
    if (o >= 1.) return half4(0.);
    float iris = clamp((o - ${HyperspaceConfig.IRIS_START}) /
        (${HyperspaceConfig.IRIS_END} - ${HyperspaceConfig.IRIS_START}), 0., 1.);
    iris = iris * iris * iris * (iris * (iris * 6. - 15.) + 10.);
    float apertureRadius = mix(-(${HyperspaceConfig.IRIS_SOFTNESS} + .001),
        length(float2(aspect, 1.)) * .5 + ${HyperspaceConfig.IRIS_SOFTNESS} + .01, iris);
    float opacity = smoothstep(apertureRadius - ${HyperspaceConfig.IRIS_SOFTNESS},
        apertureRadius + ${HyperspaceConfig.IRIS_SOFTNESS}, length(screen));
    if (opacity <= 0.) return half4(0.);

    // Pure accelerating tunnel zoom; no spring or stream deformation belongs here.
    float zoomPhase = clamp((o - ${HyperspaceConfig.ZOOM_START}) /
        (${HyperspaceConfig.ZOOM_END} - ${HyperspaceConfig.ZOOM_START}), 0., 1.);
    float expansion = zoomPhase * zoomPhase;
    float zoom = 1. + (${HyperspaceConfig.ZOOM_SCALE} - 1.) * expansion;
    screen /= zoom;
    float baseSpeed = uVelocity + uProgress * uProgress * uAcceleration;
    float k = ${HyperspaceConfig.SPEED_DECAY};
    float t = uTime;
    // Integrated exponential acceleration/deceleration, continuous at exit takeover.
    float arrivalSeconds = ${HyperspaceConfig.OPENING_MS / 1000f};
    float travel = uTravel + arrivalSeconds *
        (baseSpeed * o + 3. * uOpeningAcceleration * (1. - (1. + k * o) * exp(-k * o)) / (k * k));

    float2 q = screen * (1. + ${HyperspaceConfig.LENS_PINCH} * float2(screen.y * screen.y, screen.x * screen.x));
    float theta = atan(abs(q.y), abs(q.x) + .000001);
    float r = length(q);
    float depth = uDepth / (r + .065);
    float wall = smoothstep(uThroat, .14, r) * (1. - smoothstep(1.12, 1.75, r));
    float turn = sin(depth * .8 + travel * .30) * uSwirl;
    float groove = .5 + .5 * cos(theta * uSpokes + turn + sin(depth * 1.7) * .55);
    float ribs = pow(max(groove, 0.), 32.);
    float wave = .5 + .5 * cos(depth * uBandFrequency + travel * 3.4);
    float bands = pow(max(wave, 0.), 12.);
    float glow = .035 + .14 * groove + .28 * bands;
    float3 tint = mix(uIndigo, uCyan, .5 + .5 * cos(theta * 2.));
    float3 color = uBg * .16 + tint * wall * glow * uWallGain;
    color += uCyan * wall * ribs * (.065 + .19 * uProgress);
    color += uGold * wall * pow(max(.5 + .5 * cos(depth * uBandFrequency + travel * 3.4 + .24), 0.), 28.) * .12;
    color += uIndigo * exp(-r * 4.) * .08;

    float2 mirrored = float2(abs(q.x), q.y);
    for (int i = 0; i < ${HyperspaceConfig.STREAK_COUNT}; i++) {
        float fi = float(i);
        float angle = hash(fi + 1.) * 6.2831853;
        float2 direction = float2(abs(cos(angle)), sin(angle));
        float z = fract(hash(fi + 19.) + travel * (.12 + hash(fi + 53.) * .10));
        float radius = .045 + pow(z, 2.8) * 2.1;
        float tail = radius * (.06 + uProgress * uProgress * uStreakLength + o * 1.1);
        float2 a = direction * radius, b = direction * (radius + tail);
        float width = .0005 + radius * .0008;
        float d = segment(mirrored, a, b);
        float core = exp(-d * d / (width * width));
        float bloom = exp(-d * 125.) * .07;
        // The prototype's fine streak accent, not RGB separation of the stream.
        float split = exp(-pow(segment(mirrored + float2(uAberration * radius, 0.), a, b) / (width * .8), 2.));
        float3 streak = mod(fi, 6.) < 1. ? uGold : (mod(fi, 6.) > 4. ? uIndigo : uCyan);
        float gain = smoothstep(.04, .16, radius) * (1. - smoothstep(1.8, 4., radius)) * (.45 + .8 * uProgress);
        color += (streak * (core + bloom) + float3(.08, .015, .16) * split) * gain;
    }

    // Unzoomed viewport-periphery light, before cloud occlusion.
    float2 framePoint = (uv - uCenter) * float2(aspect, 1.);
    float edge = smoothstep(.28, .93, length(framePoint));
    float edgeAngle = atan(framePoint.y, framePoint.x);
    float sweep = pow(.5 + .5 * sin(edgeAngle * 2. - t * .72), 6.);
    float counter = pow(.5 + .5 * cos(edgeAngle * 3. + t * .43 + 1.2), 8.);
    color += (uCyan * sweep + uIndigo * counter + uGold * counter * .18) * edge * ${HyperspaceConfig.EDGE_BLOOM};

    for (int j = 0; j < ${HyperspaceConfig.CLOUD_COUNT}; j++) {
        // Independent seeds, one jittered angular stratum per puff; deliberately not mirrored.
        float f = float(j), seed = hash(f + 231.), layer = mod(f, 3.);
        float z = fract(hash(f + 87.) + travel * ${HyperspaceConfig.CLOUD_DRIFT} * (.6 + layer * .22));
        float angle = (f + hash(f + 141.)) * 6.2831853 / ${HyperspaceConfig.CLOUD_COUNT.toFloat()};
        float radius = .13 + pow(z, 1.6) * (1.05 + layer * .10);
        float2 center = float2(cos(angle), sin(angle)) * radius;
        float size = (.025 + pow(z, 1.8) * ${HyperspaceConfig.CLOUD_SIZE}) * (.70 + layer * .45);
        float2 local = (q - center) / float2(size * (1.5 + seed * .8), size * (.7 + seed * .35));
        if (dot(local, local) > 12.) continue;
        local.x += .22 * sin(local.y * 2.6 + seed * 11.) + .10 * cos(local.y * 4.1 - seed * 7.);
        float body = exp(-dot(local, local) * 1.35);
        float2 lobe = local - float2(.65, -.24);
        body += .55 * exp(-dot(lobe, lobe) * 2.1);
        lobe = local + float2(.55, .28);
        body += .38 * exp(-dot(lobe, lobe) * 2.4);
        float detail = .72 + .28 * sin(local.x * 7.3 + sin(local.y * 6.2 + seed * 13.) + t * .14);
        float billow = (.52 + .48 * sin(local.x * 3.1 + sin(local.y * 3.5 + seed * 9.) + seed * 17.)) * detail;
        float pulse = pow(max(0., sin(t * (1.35 + seed * .55) + seed * 31.)), 5.);
        float envelope = pow(max(0., sin(z * 3.14159265)), 1.5);
        float density = body * billow * envelope;
        float3 shadow = mix(uBg * .075, uIndigo * .012, seed);
        float veil = clamp(density * uCloudDensity * (1.35 + pulse * ${HyperspaceConfig.CLOUD_FLASH_GAIN}), 0., .91);
        color = mix(color, shadow, veil);
        float rim = exp(-pow((body - .32) * 7., 2.)) * billow * envelope;
        color += mix(uIndigo, uCyan, seed) * rim * ${HyperspaceConfig.CLOUD_RIM} * (.5 + .5 * pulse);
    }

    float throat = 1. - smoothstep(.015, .075, r);
    color *= 1. - throat * .94;
    color *= 1. - smoothstep(.86, 1.5, r) * .30;
    // WebGL gl_FragCoord was bottom-up; preserve its grain seed without flipping the tunnel.
    float2 grainCoord = float2(fragCoord.x, uResolution.y - fragCoord.y);
    color += float3((hash(dot(grainCoord, float2(1., 31.))) - .5) * .002);
    color = pow(max(float3(0.), 1. - exp(-color * ${HyperspaceConfig.EXPOSURE})), float3(${HyperspaceConfig.CONTRAST}));
    return half4(half3(color * opacity), half(opacity));
}
"""
