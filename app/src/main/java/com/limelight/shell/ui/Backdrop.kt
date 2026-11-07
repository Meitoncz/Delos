package com.limelight.shell.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.BackgroundKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Art of a game: two hues and an icon for the procedural placeholder, and the [image] for the game
 * background once it is loaded (the PC's cover for now; SteamGridDB hero images come in step 6.8).
 * [animated]: the file of the hero when it is an animated WebP; [image] is its first frame, which stays the
 * still background (and the one the lens blur is cached for) while the animation is off or not loaded.
 */
@Immutable
data class GameArt(val id: String, val hue: Float, val hue2: Float, val icon: String, val image: ImageBitmap? = null, val animated: java.io.File? = null)

/**
 * The layered background of every screen, as in the prototype (#stage, #heroBg, canvas#bg, #vignette):
 * theme color → game background ("hero", blurred and faded) → animated background → vignette.
 * In dark mode the animated background is blended with "screen", so it lights the game background up
 * instead of covering it.
 */
@Composable
fun Backdrop(appearance: Appearance, colors: DelosColors, hero: GameArt?, modifier: Modifier = Modifier, animate: Boolean = true) {
    val heroOn = appearance.heroBackground && hero != null && appearance.heroOpacity > 0 && !PerfFlags.has('f')
    // The tilt is the still background's only motion; it also stops when the system removes animations.
    val context = LocalContext.current
    val tiltReduced = appearance.reducedMotion || remember(context) { HeroMotion.animatorsOff(context) }
    val heroAlpha by animateFloatAsState(if (heroOn) appearance.heroOpacity / 100f else 0f, tween(800), label = "hero")
    Box(modifier.fillMaxSize().background(colors.bg)) {
        if (heroAlpha > 0f || heroOn) {
            // The game background's opacity multiplies each draw of the hero (ModulateAlpha) instead of putting the whole hero into
            // an offscreen layer, which the tilt would render again on every display frame. The fade into the theme color below is
            // drawn over it at its own opacity: the theme color is also what lies under the hero, so this is the same picture.
            val fadeIntoTheme: @Composable () -> Unit = {
                // Fade the game background into the theme color at the bottom and on the left (behind the texts)
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = .9f }.drawBehind {
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, .42f to Color.Transparent, 1f to colors.bg))
                    drawRect(Brush.horizontalGradient(0f to colors.bg, .38f to colors.bg.copy(alpha = 0f)))
                })
            }
            val groupLayer = PerfFlags.has('a')
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = heroAlpha
                compositingStrategy = if (PerfFlags.has('a')) CompositingStrategy.Auto else CompositingStrategy.ModulateAlpha
            }) {
                val heroArt: @Composable (GameArt) -> Unit = { art ->
                    HeroArt(art, appearance.heroBlur, tiltReduced, heroOn && art == hero && !PerfFlags.has('j'), animate && heroOn && art == hero)
                }
                if (PerfFlags.has('b')) Crossfade(hero, animationSpec = tween(900), label = "heroArt") { art -> if (art != null) heroArt(art) }
                else HeroCrossfade(hero, heroArt)
                if (groupLayer) fadeIntoTheme()
            }
            if (!groupLayer) fadeIntoTheme()
        }
        // The animated background takes on the main color of the game's background image (with the setting on)
        val matching = heroOn && appearance.matchGameColors
        // The game's colors come from its cover (the game background's while the cover's are unknown)
        val known = matching && (HeroBitmaps.coverKnown(hero.id) || HeroBitmaps.huesKnown(hero.id))
        val gameHues = if (!matching) null else if (HeroBitmaps.coverKnown(hero.id)) HeroBitmaps.coverHues(hero.id) else HeroBitmaps.hues(hero.id)
        if (!PerfFlags.has('e')) AnimatedBackground(
            appearance, colors, gameHues = gameHues,
            // A game whose colors are not worked out yet keeps the previous game's, so nothing flashes in between
            gamePending = matching && !known, heroActive = heroOn,
        )
        Box(Modifier.fillMaxSize().drawBehind { drawVignette(colors.scrim) })
    }
}

/** One hero of a [HeroCrossfade] and how visible it is. */
private class FadingHero(val art: GameArt, val alpha: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>)

/**
 * Cross-fades the game background when the focus moves to another game: the new hero fades in over 900 ms while the old one
 * fades out and leaves. Like `Crossfade`, but each hero's opacity multiplies its own draws ([CompositingStrategy.ModulateAlpha])
 * instead of making every one of them an offscreen layer (a full-screen layer each for the old and the new hero, redone on every
 * display frame while the tilt moves, for as long as the fade runs).
 */
@Composable
private fun HeroCrossfade(hero: GameArt?, content: @Composable (GameArt) -> Unit) {
    val heroes = remember { mutableStateListOf<FadingHero>().also { list -> hero?.let { list += FadingHero(it, Animatable(1f)) } } }
    LaunchedEffect(hero) {
        if (heroes.lastOrNull()?.art == hero) return@LaunchedEffect
        val incoming = hero?.let { FadingHero(it, Animatable(0f)) }
        if (incoming != null) heroes += incoming
        coroutineScope {
            heroes.filter { it !== incoming }.forEach { old ->
                launch {
                    old.alpha.animateTo(0f, tween(900))
                    heroes.remove(old)
                }
            }
            if (incoming != null) launch { incoming.alpha.animateTo(1f, tween(900)) }
        }
    }
    heroes.forEach { fading ->
        key(fading) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = fading.alpha.value; compositingStrategy = CompositingStrategy.ModulateAlpha }) { content(fading.art) }
        }
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

/**
 * The hero alone: cached lens blur, then a very slow centered image-plane tilt. An animated hero plays over its
 * still first frame ([HeroLive]) while [animate] allows it. Every path draws the same composition ([HeroGeometry]):
 * a wide hero fitted to the width at the top, fading into its blurred extension, any other image cropped to fill.
 */
@Composable
private fun HeroArt(art: GameArt, blurPx: Int, reducedMotion: Boolean, active: Boolean, animate: Boolean) {
    val image = art.image
    var live by remember { mutableStateOf(false) }
    // The still hero leaves once the animation has faded in over it (its soft diagonal edge must not show the first frame)
    val stillAlpha by animateFloatAsState(if (live) 0f else 1f, tween(Motion.FADE, delayMillis = if (live) Motion.FADE else 0), label = "stillHero")
    // Once the animation has faded in over it, the still leaves composition for good (it only held a second tilt clock
    // and an invisible layer); stopping the animation brings it back, from the cache, in the same pose
    val stillOn by remember { derivedStateOf { !live || stillAlpha > 0f } }
    Box(Modifier.fillMaxSize()) {
        if (stillOn) Box(Modifier.fillMaxSize().graphicsLayer { alpha = stillAlpha }) { HeroStill(art, blurPx, reducedMotion, active) }
        // The animation needs the live blur (Android 12+); below it the first frame stays
        if (image != null && art.animated != null && !PerfFlags.has('g') && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            HeroLive(art.id, image, art.animated, blurPx, reducedMotion, active, animate && !reducedMotion) { live = it }
        }
    }
}

@Composable
private fun HeroStill(art: GameArt, blurPx: Int, reducedMotion: Boolean, active: Boolean) {
    val image = art.image
    if (image != null && blurPx > 0) {
        // Only this bounded cache fill uses the GPU lens shader. The orbit scales its result like an
        // ordinary image, with no shader work, bitmap allocations or recomposition on motion frames.
        val hero by produceState(HeroBitmaps.cachedLens(art.id, image, blurPx), art.id, image, blurPx) {
            value = HeroBitmaps.cachedLens(art.id, image, blurPx) ?: withContext(Dispatchers.Default) { HeroBitmaps.renderLens(art.id, image, blurPx) }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            hero?.let {
                Image(
                    it, contentDescription = null, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low,
                    modifier = Modifier.requiredSize(2100.px, 1260.px).heroTilt(active, reducedMotion),
                )
            }
        }
        return
    }
    // A sharp image (blur 0) or the placeholder art: drawn live, the placeholder without blur
    val stage = LocalStage.current
    val density = LocalDensity.current.density
    val effect = remember(blurPx, stage, density, image != null) { if (image != null) heroEffect(blurPx * stage.dpPerPx * density) else null }
    Box(Modifier.fillMaxSize().heroFade(), contentAlignment = Alignment.Center) {
        val layer = Modifier.requiredSize(2100.px, 1260.px)
            .then(if (image != null) Modifier.heroTilt(active, reducedMotion) else Modifier)
            .graphicsLayer { renderEffect = effect }
        if (image != null) {
            val layout = remember(image) { HeroGeometry.layout(image.width, image.height) }
            if (!layout.extended) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = layer)
            } else {
                val extension = rememberHeroExtension(art.id, image)
                if (extension != null) Canvas(layer) { drawHeroBand(layout, extension, image) }
            }
        } else {
            PlaceholderArt(art.hue, art.hue2, art.icon, glyphSize = 1050f, glyphRight = 84f, glyphTop = -.14f, glyphAlpha = .14f, modifier = layer)
        }
    }
}

/** The blurred, darkened extension under a wide hero ([HeroBitmaps.extension]), built off the main thread; null while it is. */
@Composable
private fun rememberHeroExtension(id: String, image: ImageBitmap): ImageBitmap? {
    val extension by produceState(HeroBitmaps.cachedExtension(id, image), id, image) {
        value = HeroBitmaps.cachedExtension(id, image) ?: withContext(Dispatchers.Default) { HeroBitmaps.extension(id, image) }
    }
    return extension
}

/**
 * A wide hero at full resolution: the extension over the whole area, then the image fitted to the width at the top with its
 * bottom edge fading into it, at the same place as the cached bitmap ([HeroBitmaps]) puts them.
 */
private fun DrawScope.drawHeroBand(layout: HeroGeometry.Layout, extension: ImageBitmap, image: ImageBitmap) {
    val k = size.width / HeroGeometry.AREA_WIDTH
    drawImage(extension, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
    val bounds = androidx.compose.ui.geometry.Rect(layout.left * k, layout.top * k, layout.right * k, layout.bottom * k)
    drawContext.canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
    drawImage(
        image, dstOffset = IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()),
        dstSize = IntSize(bounds.width.roundToInt(), bounds.height.roundToInt()), filterQuality = FilterQuality.Low,
    )
    drawRect(heroBandMask(layout.fade * k, bounds.bottom), topLeft = bounds.topLeft, size = bounds.size, blendMode = BlendMode.DstIn)
    drawContext.canvas.restore()
}

/** An opacity mask: opaque down to [fade] above [bottom], then fading out (the shape of [HeroGeometry.FADE_STOPS]) to nothing at [bottom]. */
private fun heroBandMask(fade: Float, bottom: Float): Brush = Brush.verticalGradient(
    colorStops = HeroGeometry.FADE_STOPS.map { (at, opacity) -> at to Color.Black.copy(alpha = opacity) }.toTypedArray(),
    startY = bottom - fade, endY = bottom,
)

/**
 * The playing animated hero. Its frames are composed in a small layer, never at the 2100 × 1260 stage-pixel area: the picture is
 * blurred anyway, so the band, its soft bottom edge, the extension, the blur ([heroEffect], saturation included) and the
 * diagonal fade are all drawn at [HeroGeometry.liveScale] of the area's size (a quarter with the default blur) into an offscreen
 * layer that HWUI keeps as a texture. It is rendered again only when a new animation frame arrives (the animation's own rate);
 * every display frame the tilt just draws that texture, scaled up, rotated by the orbit, with no effect on the rotating layer.
 * The diagonal fade is part of the layer (it tilts with the image, as the cached still hero's does). It fades in over the still
 * hero once decoded (the still leaves then), and exists only while it plays: paused (hidden, reduced motion, streaming,
 * backgrounded) the drawable is released and the still hero shows. The extension under a wide hero is the still first frame's.
 */
@Composable
private fun HeroLive(id: String, image: ImageBitmap, file: java.io.File, blurPx: Int, reducedMotion: Boolean, active: Boolean, play: Boolean, onLive: (Boolean) -> Unit) {
    val stage = LocalStage.current
    val density = LocalDensity.current.density
    // Physical pixels per stage pixel at full size, and the live layer's resolution as a fraction of it
    val k = stage.dpPerPx * density
    val scale = if (PerfFlags.has('i')) 1f else remember(blurPx, k) { HeroGeometry.liveScale(blurPx * k) }
    val unit = scale * k
    val layout = remember(image) { HeroGeometry.layout(image.width, image.height) }
    val ticker = rememberAnimationTicker(play)
    val source = remember(file) { AnimatedSource.OfFile(file) }
    // Frames are decoded no wider than they are drawn (and never above the hero's own cap)
    val decodeWidth = minOf(AnimatedPlayback.HERO_WIDTH, (layout.width * unit).roundToInt().coerceAtLeast(1))
    val drawable = rememberAnimatedDrawable(source, play, decodeWidth, ticker)
    val extension = if (layout.extended && drawable != null) rememberHeroExtension(id, image) else null
    val ready = drawable != null && (!layout.extended || extension != null)
    DisposableEffect(ready) {
        onLive(ready)
        onDispose { onLive(false) }
    }
    if (drawable == null || !ready) return
    val shown = remember(drawable) { Animatable(0f) }
    LaunchedEffect(shown) { shown.animateTo(1f, tween(Motion.FADE, easing = Motion.Ease)) }
    val effect = remember(blurPx, unit) { heroEffect(blurPx * unit) }
    val widthPx = (HeroGeometry.AREA_WIDTH * unit).roundToInt().coerceAtLeast(1)
    val heightPx = (HeroGeometry.AREA_HEIGHT * unit).roundToInt().coerceAtLeast(1)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The area, tilted: only this layer's transform changes every display frame
        Box(Modifier.requiredSize(2100.px, 1260.px).heroTilt(active, reducedMotion)) {
            Box(
                Modifier.requiredSize((widthPx / density).dp, (heightPx / density).dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        // Up to the area's size in pixels, from the size the layer was really given
                        scaleX = HeroGeometry.AREA_WIDTH * k / size.width
                        scaleY = HeroGeometry.AREA_HEIGHT * k / size.height
                        alpha = shown.value
                        // A texture of the small size, drawn again only when its content changes
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(heroDiagonalFade(size), blendMode = BlendMode.DstIn)
                    },
            ) {
                // The blur sits on this child, so it runs inside the small layer, not on the way to the screen
                Canvas(Modifier.fillMaxSize().graphicsLayer { renderEffect = effect }) {
                    // Reading the frame is what redraws the layer for the animation's next image
                    if (ticker.frame.intValue == Int.MIN_VALUE) return@Canvas
                    val u = size.width / HeroGeometry.AREA_WIDTH
                    if (extension != null) drawImage(extension, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
                    val bounds = androidx.compose.ui.geometry.Rect(layout.left * u, layout.top * u, layout.right * u, layout.bottom * u)
                    if (layout.extended) drawContext.canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
                    drawIntoCanvas { canvas ->
                        drawable.setBounds(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
                        drawable.draw(canvas.nativeCanvas)
                    }
                    if (layout.extended) {
                        drawRect(heroBandMask(layout.fade * u, bounds.bottom), topLeft = bounds.topLeft, size = bounds.size, blendMode = BlendMode.DstIn)
                        drawContext.canvas.restore()
                    }
                }
            }
        }
    }
}

/** Over the whole [size], fading out diagonally from the top-right corner to the bottom-left one (use with DstIn). */
private fun heroDiagonalFade(size: Size): Brush = Brush.linearGradient(
    0f to Color.Black, .3f to Color.Black, .95f to Color.Transparent,
    start = Offset(size.width, 0f), end = Offset(0f, size.height),
)

/** Over the whole screen, fading out diagonally from the top-right corner to the bottom-left one. */
private fun Modifier.heroFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(heroDiagonalFade(size), blendMode = BlendMode.DstIn)
    }

/** The clock exists only for a visible, loaded image, and stops when its Activity is not resumed. */
@Composable
private fun Modifier.heroTilt(active: Boolean, reducedMotion: Boolean): Modifier {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // System.nanoTime is the frame clock's timebase: a copy composed later starts in phase on its first frame.
    var turn by remember { mutableFloatStateOf(HeroMotion.turnAt(System.nanoTime())) }
    LaunchedEffect(active, reducedMotion, lifecycle) {
        if (!active || reducedMotion) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                // The phase is a pure function of the frame clock: every Backdrop (the stream-exit cover,
                // a new game's hero) shows the same pose, and nothing restarts at phase 0.
                withFrameNanos { now -> turn = HeroMotion.turnAt(now) }
            }
        }
    }
    return graphicsLayer {
        rotationX = HeroMotion.pitch(turn, reducedMotion)
        rotationY = HeroMotion.yaw(turn, reducedMotion)
        // The existing 90-stage-pixel inset plus this fixed safety margin covers all four edges.
        scaleX = if (reducedMotion) 1f else HeroMotion.OVERSCAN
        scaleY = scaleX
        cameraDistance = maxOf(size.width, size.height) * HeroMotion.CAMERA_DISTANCE_FACTOR
        // Default TransformOrigin.Center: the center stays stationary at every point of the orbit.
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
    /** The focused game's three colors as they follow it (main, secondary, accent). */
    val tints = FloatArray(3) { Float.NaN }
    /** How saturated each of them is (1: as the palette's). */
    val sats = FloatArray(3) { 1f }
    var baseAlpha = Float.NaN
    var tick by mutableIntStateOf(0)
}

private const val MIN_REDRAW_NANOS = 15_000_000L

/** With a game background over it the animated background is a faint light under the hero: it repaints at 30 Hz at most (a 4th of the 120 Hz frames). */
private const val MIN_REDRAW_NANOS_UNDER_HERO = 31_000_000L

/** Per-frame smoothing factor k (the prototype's per-frame lerps at 60 fps) converted to elapsed time. */
private fun smoothing(k: Float, dt: Float) = 1f - (1f - k).pow(dt * 60f)

@Composable
private fun AnimatedBackground(appearance: Appearance, colors: DelosColors, gameHues: GameColors?, gamePending: Boolean, heroActive: Boolean) {
    val clock = remember { BackgroundClock() }
    val speed by rememberUpdatedState(if (appearance.reducedMotion) .15f else 1f)
    val tintTarget by rememberUpdatedState(gameHues)
    val baseTarget by rememberUpdatedState(if (heroActive) .12f else 1f)
    val redrawNanos by rememberUpdatedState(if (heroActive && !PerfFlags.has('c')) MIN_REDRAW_NANOS_UNDER_HERO else MIN_REDRAW_NANOS)
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
                    // All three follow the focused game's colors smoothly
                    for (i in 0 until 3) {
                        val goal = target.hues.getOrNull(i) ?: continue
                        val sat = target.saturations.getOrNull(i) ?: 1f
                        clock.tints[i] = if (clock.tints[i].isNaN()) goal else lerpHue(clock.tints[i], goal, smoothing(.04f, dt))
                        clock.sats[i] += (sat - clock.sats[i]) * smoothing(.04f, dt)
                    }
                }
                clock.baseAlpha = if (clock.baseAlpha.isNaN()) baseTarget else clock.baseAlpha + (baseTarget - clock.baseAlpha) * smoothing(.05f, dt)
                if (now - lastDrawn >= redrawNanos) {
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
        // Without Material You the effects take the focused game's three main colors (from its background)
        // in place of the palette's main, secondary and accent ones, on every background
        val game = !colors.materialYou && (gameHues != null || gamePending) && clock.tints.none { it.isNaN() }
        val tint = if (game) clock.tints[1] else colors.accent2Hue
        val p = BgParams(
            clock.t, dark, colors.materialYou, tint, colors.baseHue,
            acc = if (game) clock.tints[0] else colors.accentHue,
            acc2 = tint, acc3 = if (game) clock.tints[2] else colors.accent3Hue,
            sat1 = if (game) clock.sats[0] else 1f, sat2 = if (game) clock.sats[1] else 1f, sat3 = if (game) clock.sats[2] else 1f,
        )
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
                else -> {
                    val (c1, c2, c3) = lights(p)
                    val drawn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        BackgroundShaders.draw(this, appearance.background, W, H, p.t, c1, c2, c3, if (dark) .62f else .5f, if (dark) BlendMode.Plus else BlendMode.SrcOver, dark)
                    // Before Android 13, or if a shader does not compile on this GPU: Aurora instead
                    if (!drawn) drawAurora(p)
                }
            }
        }
    }
}

/** The three lights of the newer backgrounds: main, secondary (or the focused game's color) and accent. */
private fun lights(p: BgParams): Triple<Color, Color, Color> {
    val second = if (p.my) p.acc2 else p.tint
    return if (p.dark) Triple(hsl(p.acc, 85f * p.sat1, 56f), hsl(second, 85f * p.sat2, 56f), hsl(p.acc3, 85f * p.sat3, 62f))
    else Triple(hsl(p.acc, 80f * p.sat1, 58f), hsl(second, 80f * p.sat2, 58f), hsl(p.acc3, 80f * p.sat3, 62f))
}

// The prototype draws on a 960×540 canvas stretched over the stage; the same coordinates are used here
private const val W = 960f
private const val H = 540f

private class BgParams(
    val t: Float, val dark: Boolean, val my: Boolean, val tint: Float,
    val base: Float, val acc: Float, val acc2: Float, val acc3: Float,
    /** How saturated the main, secondary and accent colors are (below 1 for a game's muted colors). */
    val sat1: Float = 1f, val sat2: Float = 1f, val sat3: Float = 1f,
) {
    /** The saturation factor of hue [h] when it is one of the three colors, else 1. */
    fun s(h: Float): Float = when (h) {
        acc -> sat1
        acc2, tint -> sat2
        acc3 -> sat3
        else -> 1f
    }
}

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
    // The main color twice, the secondary and the accent once, the accent fainter: about 50 / 35 / 15
    val hues = if (p.my) floatArrayOf(p.acc, p.acc2, p.base, p.acc3) else floatArrayOf(p.acc, p.tint, p.acc, p.acc3)
    val t = p.t
    for (i in 0 until 4) {
        val h = hues[i]
        val x = W * (.5f + .38f * sin(t * .11f + i * 1.9f))
        val y = H * (.45f + .32f * cos(t * .09f + i * 1.3f))
        val r = W * (.42f + .08f * sin(t * .2f + i))
        val sat = p.s(h)
        val w = if (i == 3) .6f else 1f
        val inner = if (p.dark) hsl(h, 85f * sat, 52f, .32f * w) else hsl(h, 80f * sat, 70f, .45f * w)
        drawRect(
            Brush.radialGradient(0f to inner, 1f to hsl(h, 80f, 50f, 0f), center = Offset(x, y), radius = r),
            size = Size(W, H), blendMode = blend,
        )
    }
}

private fun DrawScope.drawTide(p: BgParams, paths: BackgroundPaths) {
    val t = p.t
    for (i in 0 until 6) {
        // From a third of the height down: the waves fill the lower two thirds
        val y0 = H * (.34f + i * .11f)
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
        // Three of the six waves in the main color, two in the secondary one, one in the accent
        val h = when (i) { 1, 3 -> p.tint; 4 -> p.acc3; else -> p.acc }
        val sat = 70f * p.s(h)
        drawPath(path, if (p.dark) hsl(h, sat, 30f + i * 5f, .2f + i * .04f) else hsl(h, sat, 70f - i * 4f, .22f + i * .05f))
    }
}

private fun DrawScope.drawSilk(p: BgParams, paths: BackgroundPaths) {
    // Flowing satin ribbons; a band twists where its width crosses zero
    val blend = if (p.dark) BlendMode.Plus else BlendMode.SrcOver
    val ribbons = if (p.my) floatArrayOf(p.acc, p.acc2, p.base, p.acc3) else floatArrayOf(p.acc, p.tint, p.acc, p.acc3)
    val t = p.t
    withTransform({ rotateRad(-.21f, pivot = Offset(W / 2f, H / 2f)) }) {
        for (i in 0 until 4) {
            val h = ribbons[i]
            val sat = p.s(h)
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
            val a = (if (p.dark) .26f else .3f) * (if (i == 3) .6f else 1f)
            val l = if (p.dark) 60f else 62f
            val brush = Brush.horizontalGradient(
                0f to hsl(h, 85f * sat, l, 0f), .3f to hsl(h, 85f * sat, l, a), .7f to hsl(h + 30f, 85f * sat, l + 8f, a), 1f to hsl(h, 85f * sat, l, 0f),
                startX = 0f, endX = W,
            )
            drawPath(fill, brush, blendMode = blend)
            // Sheen along one edge of the ribbon
            drawPath(sheen, hsl(h + 30f, 90f * sat, if (p.dark) 80f else 45f, if (p.dark) .18f else .22f), style = Stroke(1.2f), blendMode = blend)
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
    val lineBlend = if (p.dark) BlendMode.Plus else BlendMode.SrcOver
    val r0 = if (p.my) p.acc2 else p.acc
    val r1 = if (p.my) p.acc else p.tint
    // Android 13+: the shader draws the same lines smoothly (see BackgroundShaders); the glow stays here
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        drawRect(
            Brush.radialGradient(
                0f to hsl(p.acc, 80f * p.sat1, if (p.dark) 50f else 70f, if (p.dark) .14f else .2f), 1f to hsl(p.acc, 80f, 50f, 0f),
                center = Offset(W * .66f, H * .5f), radius = W * .55f,
            ),
            size = Size(W, H), blendMode = lineBlend,
        )
        val l = if (p.dark) 68f else 40f
        if (BackgroundShaders.draw(this, BackgroundKind.CONTOUR, W, H, t, hsl(r0, 80f * p.s(r0), l), hsl(r1, 80f * p.s(r1), l), Color.Black, 1f, lineBlend, p.dark)) return
    }
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
            0f to hsl(p.acc, 80f * p.sat1, if (p.dark) 50f else 70f, if (p.dark) .14f else .2f), 1f to hsl(p.acc, 80f, 50f, 0f),
            center = glowCenter, radius = W * .55f,
        ),
        size = Size(W, H), blendMode = blend,
    )
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
        // The lines lean to the main color: the secondary one takes over only in the upper levels (like the shader)
        val u = k / 16f
        val h = lerpHue(r0, r1, u * (.4f + .6f * u))
        paint.color = hsl(h, 80f * (p.s(r0) + p.s(r1)) / 2f, if (p.dark) 68f else 40f, (if (major) .46f else .16f) + k / 16f * (if (p.dark) .18f else .14f)).toArgb()
        paint.strokeWidth = if (major) 1.7f else 1f
        // One batched line draw per level: stroking a path of hundreds of pieces every frame is what made
        // the home screen miss frames. Butt caps: neighbouring segments share their end points exactly, so
        // nothing overlaps (round caps would add up to bright dots at every joint)
        drawIntoCanvas { it.nativeCanvas.drawLines(lines, 0, count, paint) }
    }
}
