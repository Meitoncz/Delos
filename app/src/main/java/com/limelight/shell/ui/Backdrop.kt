package com.limelight.shell.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.BackgroundKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Art of a game: two hues and an icon for the procedural placeholder, and the [image] for the game
 * background once it is loaded (the PC's cover for now; SteamGridDB hero images come in step 6.8).
 */
@Immutable
data class GameArt(val id: String, val hue: Float, val hue2: Float, val icon: String, val image: ImageBitmap? = null)

/**
 * The layered background of every screen, as in the prototype (#stage, #heroBg, canvas#bg, #vignette):
 * theme color → game background ("hero", blurred and faded) → animated background → vignette.
 * In dark mode the animated background is blended with "screen", so it lights the game background up
 * instead of covering it.
 */
@Composable
fun Backdrop(appearance: Appearance, colors: DelosColors, hero: GameArt?, modifier: Modifier = Modifier) {
    val heroOn = appearance.heroBackground && hero != null && appearance.heroOpacity > 0
    val heroAlpha by animateFloatAsState(if (heroOn) appearance.heroOpacity / 100f else 0f, tween(800), label = "hero")
    Box(modifier.fillMaxSize().background(colors.bg)) {
        if (heroAlpha > 0f || heroOn) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = heroAlpha }) {
                Crossfade(hero, animationSpec = tween(900), label = "heroArt") { art ->
                    if (art != null) HeroArt(art, appearance.heroBlur)
                }
                // Fade the game background into the theme color at the bottom and on the left (behind the texts)
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = .9f }.drawBehind {
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, .42f to Color.Transparent, 1f to colors.bg))
                    drawRect(Brush.horizontalGradient(0f to colors.bg, .38f to colors.bg.copy(alpha = 0f)))
                })
            }
        }
        AnimatedBackground(appearance, colors, tintHue = if (heroOn) hero.hue else null, heroActive = heroOn)
        Box(Modifier.fillMaxSize().drawBehind { drawVignette(colors.scrim) })
    }
}

/** radial-gradient(120% 90% at 50% 40%, transparent 55%, scrim 100%) */
private fun DrawScope.drawVignette(scrim: Color) {
    val center = Offset(size.width * .5f, size.height * .4f)
    val rx = size.width * 1.2f
    val ry = size.height * .9f
    scale(1f, ry / rx, pivot = center) {
        drawCircle(
            Brush.radialGradient(.55f to scrim.copy(alpha = 0f), 1f to scrim, center = center, radius = rx),
            radius = rx * 2f, center = center,
        )
    }
}

/** The blurred game background (prototype `.hero`: inset -90px, blur, saturate 1.15). */
@Composable
private fun HeroArt(art: GameArt, blurPx: Int) {
    val image = art.image
    if (image != null && blurPx > 0) {
        // Pre-rendered once per game and blur (see HeroBitmaps): the screen only scales a small bitmap up
        val hero by produceState(HeroBitmaps.cached(art.id, blurPx), art.id, image, blurPx) {
            value = HeroBitmaps.cached(art.id, blurPx) ?: withContext(Dispatchers.Default) { HeroBitmaps.render(art.id, image, blurPx) }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            hero?.let {
                Image(
                    it, contentDescription = null, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low,
                    modifier = Modifier.requiredSize(2100.px, 1260.px),
                )
            }
        }
        return
    }
    // A sharp image (blur 0) or the placeholder art: drawn live, the placeholder without blur
    val stage = LocalStage.current
    val density = LocalDensity.current.density
    val effect = remember(blurPx, stage, density, image != null) { if (image != null) heroEffect(blurPx * stage.dpPerPx * density) else null }
    // Over the whole screen, fading out diagonally from the top-right corner to the bottom-left one
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.linearGradient(
                        0f to Color.Black, .3f to Color.Black, .95f to Color.Transparent,
                        start = Offset(size.width, 0f), end = Offset(0f, size.height),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val layer = Modifier.requiredSize(2100.px, 1260.px).graphicsLayer { renderEffect = effect }
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = layer)
        } else {
            PlaceholderArt(art.hue, art.hue2, art.icon, glyphSize = 1050f, glyphRight = 84f, glyphTop = -.14f, glyphAlpha = .14f, modifier = layer)
        }
    }
}

/** CSS blur(σ) uses σ directly; Android blur radii map to σ ≈ 0.577·r + 0.5. */
private fun heroEffect(sigmaPx: Float): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val saturate = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.15f) }))
    if (sigmaPx <= .5f) return saturate.asComposeRenderEffect()
    val radius = (sigmaPx - .5f) / .57735f
    return RenderEffect.createChainEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP), saturate).asComposeRenderEffect()
}

/**
 * Time and smoothed values of the animated background, advanced every frame. [tick] triggers a redraw
 * at most ~60 times a second: the background moves slowly, so on 120 Hz screens drawing every other
 * frame halves its cost without a visible difference (scrolling and focus still run at full rate).
 */
private class BackgroundClock {
    var t = 0f
    var tint = Float.NaN
    var baseAlpha = Float.NaN
    var tick by mutableIntStateOf(0)
}

private const val MIN_REDRAW_NANOS = 15_000_000L

/** Per-frame smoothing factor k (the prototype's per-frame lerps at 60 fps) converted to elapsed time. */
private fun smoothing(k: Float, dt: Float) = 1f - (1f - k).pow(dt * 60f)

@Composable
private fun AnimatedBackground(appearance: Appearance, colors: DelosColors, tintHue: Float?, heroActive: Boolean) {
    val clock = remember { BackgroundClock() }
    val speed by rememberUpdatedState(if (appearance.reducedMotion) .15f else 1f)
    val tintTarget by rememberUpdatedState(tintHue)
    val baseTarget by rememberUpdatedState(if (heroActive) .12f else 1f)
    LaunchedEffect(Unit) {
        var last = 0L
        var lastDrawn = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else min((now - last) / 1e9f, .1f)
                last = now
                // The prototype advances t by 0.016 per 60 Hz frame
                clock.t += dt * .96f * speed
                tintTarget?.let { target ->
                    clock.tint = if (clock.tint.isNaN()) target else lerpHue(clock.tint, target, smoothing(.04f, dt))
                }
                clock.baseAlpha = if (clock.baseAlpha.isNaN()) baseTarget else clock.baseAlpha + (baseTarget - clock.baseAlpha) * smoothing(.05f, dt)
                if (now - lastDrawn >= MIN_REDRAW_NANOS) {
                    lastDrawn = now
                    clock.tick++
                }
            }
        }
    }
    val paths = remember { BackgroundPaths() }
    val dark = colors.dark
    Canvas(
        Modifier.fillMaxSize().graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
            blendMode = if (dark) BlendMode.Screen else BlendMode.SrcOver
        }
    ) {
        @Suppress("UNUSED_VARIABLE")
        val redraw = clock.tick // subscribes the drawing to the throttled clock
        // Without Material You the effects follow the color of the focused game
        val tint = if (!colors.materialYou && tintHue != null && !clock.tint.isNaN()) clock.tint else colors.accent2Hue
        val p = BgParams(clock.t, dark, colors.materialYou, tint, colors.baseHue, colors.accentHue, colors.accent2Hue)
        scale(size.width / W, size.height / H, pivot = Offset.Zero) {
            val base = if (dark) Brush.verticalGradient(0f to hsl(p.base, 40f, 8f), 1f to hsl(p.base, 38f, 5f), endY = H)
            else Brush.verticalGradient(0f to hsl(p.base, 45f, 96f), 1f to hsl(p.base, 38f, 90f), endY = H)
            drawRect(base, size = Size(W, H), alpha = clock.baseAlpha.takeUnless { it.isNaN() } ?: 1f)
            when (appearance.background) {
                BackgroundKind.AURORA -> drawAurora(p)
                BackgroundKind.TIDE -> drawTide(p, paths)
                BackgroundKind.SILK -> drawSilk(p, paths)
                BackgroundKind.CONTOUR -> drawContour(p, paths)
                BackgroundKind.STILL -> Unit
            }
        }
    }
}

// The prototype draws on a 960×540 canvas stretched over the stage; the same coordinates are used here
private const val W = 960f
private const val H = 540f

private class BgParams(
    val t: Float, val dark: Boolean, val my: Boolean, val tint: Float,
    val base: Float, val acc: Float, val acc2: Float,
)

/** Paths reused between frames. */
private class BackgroundPaths {
    val tide = List(6) { Path() }
    val silk = List(4) { Path().apply { fillType = PathFillType.EvenOdd } }
    val sheen = List(4) { Path() }
    val silkBottom = FloatArray(SILK_POINTS * 2)
    val field = FloatArray(CONTOUR_NX * CONTOUR_NY)

    /** Line segments of one contour level: at most two per cell, four floats each. */
    val contourLines = FloatArray((CONTOUR_NX - 1) * (CONTOUR_NY - 1) * 8)
    val contourPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.BUTT
    }
}

private fun DrawScope.drawAurora(p: BgParams) {
    val blend = if (p.dark) BlendMode.Plus else BlendMode.SrcOver
    val hues = if (p.my) floatArrayOf(p.acc, p.acc2, p.base, (p.acc + 24f) % 360f) else floatArrayOf(p.tint, p.acc, p.base + 30f, p.acc2)
    val t = p.t
    for (i in 0 until 4) {
        val h = hues[i]
        val x = W * (.5f + .38f * sin(t * .11f + i * 1.9f))
        val y = H * (.45f + .32f * cos(t * .09f + i * 1.3f))
        val r = W * (.42f + .08f * sin(t * .2f + i))
        val inner = if (p.dark) hsl(h, 85f, 52f, .32f) else hsl(h, 80f, 70f, .45f)
        drawRect(
            Brush.radialGradient(0f to inner, 1f to hsl(h, 80f, 50f, 0f), center = Offset(x, y), radius = r),
            size = Size(W, H), blendMode = blend,
        )
    }
}

private fun DrawScope.drawTide(p: BgParams, paths: BackgroundPaths) {
    val t = p.t
    for (i in 0 until 6) {
        val y0 = H * (.5f + i * .085f)
        val amp = 14f + i * 4f
        val k = .006f + i * .0015f
        val sp = .35f + i * .12f
        val path = paths.tide[i].apply { rewind(); moveTo(0f, H) }
        var x = 0f
        while (x <= W) {
            path.lineTo(x, y0 + sin(x * k + t * sp + i) * amp + sin(x * k * 2.3f - t * sp * .7f) * amp * .35f)
            x += 12f
        }
        path.lineTo(W, H)
        path.close()
        val h = if (i % 2 == 1) p.tint else p.base + 10f
        drawPath(path, if (p.dark) hsl(h, 70f, 30f + i * 5f, .2f + i * .04f) else hsl(h, 70f, 70f - i * 4f, .22f + i * .05f))
    }
}

private fun DrawScope.drawSilk(p: BgParams, paths: BackgroundPaths) {
    // Flowing satin ribbons; a band twists where its width crosses zero
    val blend = if (p.dark) BlendMode.Plus else BlendMode.SrcOver
    val ribbons = if (p.my) floatArrayOf(p.acc, p.acc2, p.base, (p.acc + 24f) % 360f) else floatArrayOf(p.tint, p.acc, p.acc2, (p.base + 40f) % 360f)
    val t = p.t
    withTransform({ rotateRad(-.21f, pivot = Offset(W / 2f, H / 2f)) }) {
        for (i in 0 until 4) {
            val h = ribbons[i]
            val y0 = H * (.22f + i * .18f)
            val ph = i * 1.7f
            val sp = .18f + i * .05f
            val fill = paths.silk[i].apply { rewind() }
            val sheen = paths.sheen[i].apply { rewind() }
            val count = SILK_POINTS
            val bottom = paths.silkBottom
            for (j in 0 until count) {
                val x = -80f + j * 16f
                val y = y0 + sin(x * .006f + t * sp + ph) * 46f + sin(x * .013f - t * sp * 1.3f + ph * 2f) * 18f
                val w = 64f * sin(x * .0042f + t * .22f + ph) + 10f * sin(x * .02f + t)
                if (j == 0) { fill.moveTo(x, y - w / 2f); sheen.moveTo(x, y - w / 2f) } else { fill.lineTo(x, y - w / 2f); sheen.lineTo(x, y - w / 2f) }
                bottom[j * 2] = x; bottom[j * 2 + 1] = y + w / 2f
            }
            for (j in count - 1 downTo 0) fill.lineTo(bottom[j * 2], bottom[j * 2 + 1])
            fill.close()
            val a = if (p.dark) .26f else .3f
            val l = if (p.dark) 60f else 62f
            val brush = Brush.horizontalGradient(
                0f to hsl(h, 85f, l, 0f), .3f to hsl(h, 85f, l, a), .7f to hsl(h + 30f, 85f, l + 8f, a), 1f to hsl(h, 85f, l, 0f),
                startX = 0f, endX = W,
            )
            drawPath(fill, brush, blendMode = blend)
            // Sheen along one edge of the ribbon
            drawPath(sheen, hsl(h + 30f, 90f, if (p.dark) 80f else 45f, if (p.dark) .18f else .22f), style = Stroke(1.2f), blendMode = blend)
        }
    }
}

/** Silk samples every 16 px from -80 to W + 80. */
private const val SILK_POINTS = ((W + 160f) / 16f).toInt() + 1

private const val CONTOUR_STEP = 20f
private const val CONTOUR_NX = (W / CONTOUR_STEP).toInt() + 1
private const val CONTOUR_NY = 28 // ceil(540 / 20) + 1
private const val CONTOUR_LEVELS = 17

private fun DrawScope.drawContour(p: BgParams, paths: BackgroundPaths) {
    // Topographic contour lines over slowly shifting terrain; every fourth line is stronger, like on a map
    val t = p.t
    val f = paths.field
    for (j in 0 until CONTOUR_NY) for (i in 0 until CONTOUR_NX) {
        val x = i * CONTOUR_STEP
        val y = j * CONTOUR_STEP
        f[j * CONTOUR_NX + i] = sin(x * .0062f + t * .07f) + sin(y * .0081f - t * .05f + x * .002f) + .7f * sin((x - y) * .0047f + t * .09f) +
            .5f * cos(hypot(x - W * .66f, y - H * .5f) * .011f - t * .12f)
    }
    val blend = if (p.dark) BlendMode.Plus else BlendMode.SrcOver
    val glowCenter = Offset(W * .66f, H * .5f)
    drawRect(
        Brush.radialGradient(
            0f to hsl(p.acc, 80f, if (p.dark) 50f else 70f, if (p.dark) .14f else .2f), 1f to hsl(p.acc, 80f, 50f, 0f),
            center = glowCenter, radius = W * .55f,
        ),
        size = Size(W, H), blendMode = blend,
    )
    val r0 = if (p.my) p.acc2 else p.acc
    val r1 = if (p.my) p.acc else p.tint
    val s = CONTOUR_STEP
    val pts = FloatArray(8)
    val lines = paths.contourLines
    val paint = paths.contourPaint.apply { blendMode = if (p.dark) android.graphics.BlendMode.PLUS else android.graphics.BlendMode.SRC_OVER }
    for (k in 0 until CONTOUR_LEVELS) {
        val level = -2.8f + k * .35f
        val major = k % 4 == 0
        var count = 0
        for (j in 0 until CONTOUR_NY - 1) for (i in 0 until CONTOUR_NX - 1) {
            val a = f[j * CONTOUR_NX + i]
            val b = f[j * CONTOUR_NX + i + 1]
            val d = f[(j + 1) * CONTOUR_NX + i + 1]
            val e = f[(j + 1) * CONTOUR_NX + i]
            val x = i * s
            val y = j * s
            var n = 0
            // Marching squares: where the level crosses a cell edge, interpolate the crossing point
            if ((a > level) != (b > level)) { pts[n++] = x + s * (level - a) / (b - a); pts[n++] = y }
            if ((b > level) != (d > level)) { pts[n++] = x + s; pts[n++] = y + s * (level - b) / (d - b) }
            if ((e > level) != (d > level)) { pts[n++] = x + s * (level - e) / (d - e); pts[n++] = y + s }
            if ((a > level) != (e > level)) { pts[n++] = x; pts[n++] = y + s * (level - a) / (e - a) }
            // Every crossing pair is one segment (two segments in a saddle cell)
            for (q in 0 until (n / 4) * 4) lines[count++] = pts[q]
        }
        val h = lerpHue(r0, r1, k / 16f)
        paint.color = hsl(h, 80f, if (p.dark) 68f else 40f, (if (major) .46f else .16f) + k / 16f * (if (p.dark) .18f else .14f)).toArgb()
        paint.strokeWidth = if (major) 1.7f else 1f
        // One batched line draw per level: stroking a path of hundreds of pieces every frame is what made
        // the home screen miss frames. Butt caps: neighbouring segments share their end points exactly, so
        // nothing overlaps (round caps would add up to bright dots at every joint)
        drawIntoCanvas { it.nativeCanvas.drawLines(lines, 0, count, paint) }
    }
}
