package com.limelight.shell.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
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
import kotlinx.coroutines.delay
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
    val heroOn = appearance.heroBackground && hero != null && appearance.heroOpacity > 0
    // The tilt is the still background's only motion; it also stops when the system removes animations.
    val context = LocalContext.current
    val tiltReduced = appearance.reducedMotion || remember(context) { HeroMotion.animatorsOff(context) }
    val heroAlpha by animateFloatAsState(if (heroOn) appearance.heroOpacity / 100f else 0f, tween(800), label = "hero")
    Box(modifier.fillMaxSize().background(colors.bg)) {
        if (heroAlpha > 0f || heroOn) {
            // The game background's opacity multiplies each draw of the hero (ModulateAlpha) instead of putting the whole hero into
            // an offscreen layer, which the tilt would render again on every display frame. The fade into the theme color below is
            // drawn over it at its own opacity: the theme color is also what lies under the hero, so this is the same picture.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = heroAlpha; compositingStrategy = CompositingStrategy.ModulateAlpha }) {
                HeroCrossfade(hero) { art ->
                    if (art != null) HeroArt(art, appearance.heroBlur, tiltReduced, heroOn && art == hero, animate && heroOn && art == hero)
                }
            }
            // Fade the game background into the theme color at the bottom and on the left (behind the texts), at 90 %. The two
            // fades are composed in one shader and drawn once: the picture an offscreen layer of both at 90 % would hold, without
            // the full-screen layer (and its extra render pass on every frame, in every glass pill that blurs the background too)
            val fade = remember(colors.bg) {
                Brush.composite(
                    Brush.verticalGradient(0f to Color.Transparent, .42f to Color.Transparent, 1f to colors.bg),
                    Brush.horizontalGradient(0f to colors.bg, .38f to colors.bg.copy(alpha = 0f)),
                    BlendMode.SrcOver,
                )
            }
            Box(Modifier.fillMaxSize().drawBehind { drawRect(fade, alpha = .9f) })
        }
        // The animated background takes on the main color of the game's background image (with the setting on)
        val matching = heroOn && appearance.matchGameColors
        // The game's colors come from its cover (the game background's while the cover's are unknown)
        val known = matching && (HeroBitmaps.coverKnown(hero.id) || HeroBitmaps.huesKnown(hero.id))
        val gameHues = if (matching) HeroBitmaps.gameColors(hero.id) else null
        AnimatedBackground(
            appearance, colors, gameHues = gameHues,
            // A game whose colors are not worked out yet keeps the previous game's, so nothing flashes in between
            gamePending = matching && !known, heroActive = heroOn,
        )
        Box(Modifier.fillMaxSize().drawBehind { drawVignette(colors.scrim) })
    }
}

/**
 * Compose's `Crossfade` (the same states, opacities and 900 ms) for the game background, whose two pictures are full-screen:
 * `Crossfade` gives each an offscreen layer while it fades, 8 MB of render target apiece on a 1080p screen, which together
 * with an opening menu filled the renderer's texture budget, so it dropped the covers' textures and uploaded them again on
 * every frame (measured on the Odin). A still background is one image, so its opacity can multiply that one draw instead
 * (ModulateAlpha), which is the same picture. An animated one draws its still frame and the animation over each other and
 * keeps the layer.
 */
@Composable
private fun HeroCrossfade(hero: GameArt?, content: @Composable (GameArt?) -> Unit) {
    val transition = updateTransition(hero, "heroArt")
    val visible = remember { mutableStateListOf<GameArt?>().apply { add(transition.currentState) } }
    val contents = remember { HashMap<GameArt?, @Composable () -> Unit>() }
    if (transition.currentState == transition.targetState) {
        // Not animating: only the current background stays
        if (visible.size != 1 || visible[0] != transition.targetState) {
            visible.removeAll { it != transition.targetState }
            contents.clear()
        }
    }
    if (transition.targetState !in contents) {
        if (transition.targetState !in visible) visible.add(transition.targetState)
        contents.clear()
        visible.forEach { art ->
            contents[art] = {
                val alpha by transition.animateFloat(transitionSpec = { tween(900) }, label = "heroArtAlpha") { if (it == art) 1f else 0f }
                Box(
                    Modifier.graphicsLayer {
                        this.alpha = alpha
                        compositingStrategy = if (art?.animated == null) CompositingStrategy.ModulateAlpha else CompositingStrategy.Auto
                    },
                ) { content(art) }
            }
        }
    }
    Box { visible.forEach { art -> key(art) { contents[art]?.invoke() } } }
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
    val file = art.animated
    var live by remember { mutableStateOf(false) }
    // The animation starts only after the game has stayed in focus for a moment, so holding or tapping the D-pad does not create
    // and destroy a decoder and its layers per step (the still hero shows meanwhile); losing the focus ends it at once
    var dwelled by remember { mutableLongStateOf(0L) }
    LaunchedEffect(animate) {
        dwelled = 0L
        if (animate) {
            delay(AnimatedPlayback.HERO_DWELL_MS)
            dwelled = AnimatedPlayback.HERO_DWELL_MS
        }
    }
    // The animation needs the live blur (Android 12+); below it the first frame stays
    val playing = image != null && file != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !reducedMotion && AnimatedPlayback.heroMayPlay(animate, dwelled)
    // When the animation stops while the hero stays on screen (the focus moved on and the hero cross-fades out, a launch begins, motion
    // is reduced), it is held: paused on the frame it showed, and released after [AnimatedPlayback.HERO_HOLD_MS], when nothing else
    // (the cross-fade or the fade-out ending, which remove the hero) has done it. The still hero would show its first frame instead.
    // A game that is focused again lets go of it at once and starts fresh.
    var released by remember { mutableStateOf(false) }
    LaunchedEffect(playing, live) {
        if (playing) {
            released = false
        } else if (live) {
            delay(AnimatedPlayback.HERO_HOLD_MS)
            released = true
        }
    }
    val held = live && !playing && !released && (!animate || reducedMotion)
    // Decided in the very composition that adds or removes the animation (not by its callback, a frame later): the hero is never
    // without both, so a hero that was superseded and is cross-fading out never shows a gap or a still that fades back in on its way out
    val liveOn = playing || held
    val liveShown = live && liveOn
    // The still hero leaves once the animation has faded in over it (its soft diagonal edge must not show the first frame)
    val stillFade = remember { Animatable(1f) }
    LaunchedEffect(liveShown) {
        if (liveShown) {
            delay(Motion.FADE.toLong())
            stillFade.animateTo(0f, tween(Motion.FADE))
        } else {
            stillFade.snapTo(1f)
        }
    }
    // Once the animation has faded in over it, the still leaves composition for good (it only held a second tilt clock
    // and an invisible layer); releasing the animation brings it back, from the cache, in the same pose
    val stillOn by remember(liveShown) { derivedStateOf { !liveShown || stillFade.value > 0f } }
    Box(Modifier.fillMaxSize()) {
        if (stillOn) Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (liveShown) stillFade.value else 1f }) { HeroStill(art, blurPx, reducedMotion, active) }
        if (liveOn && image != null && file != null) {
            HeroLive(art.id, image, file, blurPx, reducedMotion, active, playing) { live = it }
        }
    }
}

@Composable
private fun HeroStill(art: GameArt, blurPx: Int, reducedMotion: Boolean, active: Boolean) {
    val image = art.image
    val stage = LocalStage.current
    val area = remember(stage.stageWidth, stage.stageHeight) { HeroArea.of(stage) }
    if (image != null && blurPx > 0) {
        // The blurred bitmap is cached per game and strength (HeroBitmaps.render: three box blurs, a Gaussian). The orbit scales it
        // like an ordinary image, with no shader work, bitmap allocations or recomposition on motion frames.
        val hero by produceState(HeroBitmaps.cached(art.id, image, blurPx, area), art.id, image, blurPx, area) {
            value = HeroBitmaps.cached(art.id, image, blurPx, area) ?: withContext(Dispatchers.Default) { HeroBitmaps.render(art.id, image, blurPx, area) }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            hero?.let {
                Image(
                    it, contentDescription = null, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low,
                    modifier = Modifier.requiredSize(area.width.px, area.height.px).heroTilt(active, reducedMotion),
                )
            }
        }
        return
    }
    // A sharp image (blur 0) or the placeholder art: drawn live, the placeholder without blur
    val density = LocalDensity.current.density
    val effect = remember(blurPx, stage, density, image != null) { if (image != null) heroEffect(blurPx * stage.dpPerPx * density) else null }
    Box(Modifier.fillMaxSize().heroFade(), contentAlignment = Alignment.Center) {
        val layer = Modifier.requiredSize(area.width.px, area.height.px)
            .then(if (image != null) Modifier.heroTilt(active, reducedMotion) else Modifier)
            .graphicsLayer { renderEffect = effect }
        if (image != null) {
            val layout = remember(image, area) { HeroGeometry.layout(area, image.width, image.height) }
            if (!layout.extended) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = layer)
            } else {
                val extension = rememberHeroExtension(art.id, image, area)
                if (extension != null) Canvas(layer) { drawHeroBand(area, layout, extension, image) }
            }
        } else {
            PlaceholderArt(art.hue, art.hue2, art.icon, glyphSize = 1050f, glyphRight = 84f, glyphTop = -.14f, glyphAlpha = .14f, modifier = layer)
        }
    }
}

/** The blurred, darkened extension under a wide hero ([HeroBitmaps.extension]), built off the main thread; null while it is. */
@Composable
private fun rememberHeroExtension(id: String, image: ImageBitmap, area: HeroArea): ImageBitmap? {
    val extension by produceState(HeroBitmaps.cachedExtension(id, image, area), id, image, area) {
        value = HeroBitmaps.cachedExtension(id, image, area) ?: withContext(Dispatchers.Default) { HeroBitmaps.extension(id, image, area) }
    }
    return extension
}

/**
 * A wide hero at full resolution: the extension over the whole area, then the image fitted to the width at the top with its
 * bottom edge fading into it, at the same place as the cached bitmap ([HeroBitmaps]) puts them.
 */
private fun DrawScope.drawHeroBand(area: HeroArea, layout: HeroGeometry.Layout, extension: ImageBitmap, image: ImageBitmap) {
    val k = size.width / area.width
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
 * The playing animated hero. Its frames are composed in a small layer, never at the full stage-pixel area ([HeroArea], 2100 × 1260 at 16:9): the picture is
 * blurred anyway, so the band, its soft bottom edge, the extension, the blur ([heroEffect], saturation included) and the
 * diagonal fade are all drawn at [HeroGeometry.liveScale] of the area's size (a quarter with the default blur) into an offscreen
 * layer that HWUI keeps as a texture. It is rendered again only when a new animation frame arrives (the animation's own rate);
 * every display frame the tilt just draws that texture, scaled up, rotated by the orbit, with no effect on the rotating layer.
 * The diagonal fade is part of the layer (it tilts with the image, as the cached still hero's does). It fades in over the still
 * hero once decoded (the still leaves then). The animation loops without a jump ([LoopedAnimation]: the end of a pass fades into the
 * start of the next one, both drawn in this same small layer). It exists while composed; [play] false (the game lost the focus, a launch
 * began) pauses it on the frame it shows, and the caller leaves composition to release it. The extension under a wide hero is the still first frame's.
 */
@Composable
private fun HeroLive(id: String, image: ImageBitmap, file: java.io.File, blurPx: Int, reducedMotion: Boolean, active: Boolean, play: Boolean, onLive: (Boolean) -> Unit) {
    val stage = LocalStage.current
    val density = LocalDensity.current.density
    // Physical pixels per stage pixel at full size, and the live layer's resolution as a fraction of it
    val k = stage.dpPerPx * density
    val area = remember(stage.stageWidth, stage.stageHeight) { HeroArea.of(stage) }
    val scale = remember(blurPx, k) { HeroGeometry.liveScale(blurPx * k) }
    val unit = scale * k
    val layout = remember(image, area) { HeroGeometry.layout(area, image.width, image.height) }
    val ticker = rememberAnimationTicker(play)
    val source = remember(file) { AnimatedSource.OfFile(file) }
    // Frames are decoded no wider than they are drawn (and never above the hero's own cap)
    val decodeWidth = minOf(AnimatedPlayback.HERO_WIDTH, (layout.width * unit).roundToInt().coerceAtLeast(1))
    val animation = rememberLoopedAnimation(source, decodeWidth, ticker)
    PlayLoopedAnimation(animation, play)
    val extension = if (layout.extended && animation != null) rememberHeroExtension(id, image, area) else null
    val ready = animation != null && (!layout.extended || extension != null)
    DisposableEffect(ready) {
        onLive(ready)
        onDispose { onLive(false) }
    }
    if (animation == null || !ready) return
    val shown = remember(animation) { Animatable(0f) }
    LaunchedEffect(shown) { shown.animateTo(1f, tween(Motion.FADE, easing = Motion.Ease)) }
    val effect = remember(blurPx, unit) { heroEffect(blurPx * unit) }
    val widthPx = (area.width * unit).roundToInt().coerceAtLeast(1)
    val heightPx = (area.height * unit).roundToInt().coerceAtLeast(1)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The area, tilted: only this layer's transform changes every display frame
        Box(Modifier.requiredSize(area.width.px, area.height.px).heroTilt(active, reducedMotion)) {
            Box(
                Modifier.requiredSize((widthPx / density).dp, (heightPx / density).dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        // Up to the area's size in pixels, from the size the layer was really given
                        scaleX = area.width * k / size.width
                        scaleY = area.height * k / size.height
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
                    val u = size.width / area.width
                    if (extension != null) drawImage(extension, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
                    val bounds = androidx.compose.ui.geometry.Rect(layout.left * u, layout.top * u, layout.right * u, layout.bottom * u)
                    if (layout.extended) drawContext.canvas.saveLayer(bounds, androidx.compose.ui.graphics.Paint())
                    drawIntoCanvas { canvas ->
                        animation.draw(canvas.nativeCanvas, bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
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
        // The area's inset (HeroArea, 90 stage pixels per side at 16:9) plus this fixed safety margin covers all four edges.
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
    val redrawNanos by rememberUpdatedState(if (heroActive) MIN_REDRAW_NANOS_UNDER_HERO else MIN_REDRAW_NANOS)
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
        // One scale for both axes (the shapes keep their proportions); the canvas grows along the axis that has more room
        val unitX = size.width / W
        val unitY = size.height / H
        val unit = min(unitX, unitY)
        val canvasW = if (unitX <= unitY) W else size.width / unit
        val canvasH = if (unitY <= unitX) H else size.height / unit
        // Without Material You the effects take the focused game's three main colors (from its background)
        // in place of the palette's main, secondary and accent ones, on every background
        val game = !colors.materialYou && (gameHues != null || gamePending) && clock.tints.none { it.isNaN() }
        val tint = if (game) clock.tints[1] else colors.accent2Hue
        val p = BgParams(
            canvasW, canvasH, clock.t, dark, colors.materialYou, tint, colors.baseHue,
            acc = if (game) clock.tints[0] else colors.accentHue,
            acc2 = tint, acc3 = if (game) clock.tints[2] else colors.accent3Hue,
            sat1 = if (game) clock.sats[0] else 1f, sat2 = if (game) clock.sats[1] else 1f, sat3 = if (game) clock.sats[2] else 1f,
        )
        scale(unit, unit, pivot = Offset.Zero) {
            val base = if (dark) Brush.verticalGradient(0f to hsl(p.base, 40f, 8f), 1f to hsl(p.base, 38f, 5f), endY = canvasH)
            else Brush.verticalGradient(0f to hsl(p.base, 45f, 96f), 1f to hsl(p.base, 38f, 90f), endY = canvasH)
            drawRect(base, size = Size(canvasW, canvasH), alpha = clock.baseAlpha.takeUnless { it.isNaN() } ?: 1f)
            when (appearance.background) {
                BackgroundKind.AURORA -> drawAurora(p)
                BackgroundKind.TIDE -> drawTide(p, paths)
                BackgroundKind.SILK -> drawSilk(p, paths)
                BackgroundKind.CONTOUR -> drawContour(p, paths)
                BackgroundKind.STILL -> Unit
                else -> {
                    val (c1, c2, c3) = lights(p)
                    val drawn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        BackgroundShaders.draw(this, appearance.background, canvasW, canvasH, p.t, c1, c2, c3, if (dark) .62f else .5f, if (dark) BlendMode.Plus else BlendMode.SrcOver, dark)
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

// The prototype draws on a 960×540 canvas stretched over the 16:9 stage; the same coordinates are used here, scaled
// uniformly. A wider or taller stage extends the canvas (BgParams.w / h, at least W × H) instead of stretching it.
private const val W = 960f
private const val H = 540f

/** Silk samples every 16 px from -80 to w + 80. */
private fun silkPoints(w: Float) = ((w + 160f) / 16f).toInt() + 1

private fun contourColumns(w: Float) = (w / CONTOUR_STEP).toInt() + 1

/** ceil(h / 20) + 1: 28 rows for the 540-pixel canvas. */
private fun contourRows(h: Float) = kotlin.math.ceil(h / CONTOUR_STEP).toInt() + 1

private class BgParams(
    /** The canvas the shapes are drawn on, in prototype pixels: [W] × [H] at 16:9, extended along the longer axis of another shape. */
    val w: Float, val h: Float,
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
    private var silkBottom = FloatArray(0)
    private var field = FloatArray(0)
    private var contourLines = FloatArray(0)

    /** The lower edge of a ribbon of [count] points (x, y pairs). */
    fun silkBottom(count: Int): FloatArray {
        if (silkBottom.size < count * 2) silkBottom = FloatArray(count * 2)
        return silkBottom
    }

    /** The terrain samples of a [nx] × [ny] grid. */
    fun field(nx: Int, ny: Int): FloatArray {
        if (field.size < nx * ny) field = FloatArray(nx * ny)
        return field
    }

    /** Line segments of one contour level on a [nx] × [ny] grid: at most two per cell, four floats each. */
    fun contourLines(nx: Int, ny: Int): FloatArray {
        if (contourLines.size < (nx - 1) * (ny - 1) * 8) contourLines = FloatArray((nx - 1) * (ny - 1) * 8)
        return contourLines
    }
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
        val x = p.w * (.5f + .38f * sin(t * .11f + i * 1.9f))
        val y = p.h * (.45f + .32f * cos(t * .09f + i * 1.3f))
        val r = p.w * (.42f + .08f * sin(t * .2f + i))
        val sat = p.s(h)
        val w = if (i == 3) .6f else 1f
        val inner = if (p.dark) hsl(h, 85f * sat, 52f, .32f * w) else hsl(h, 80f * sat, 70f, .45f * w)
        drawRect(
            Brush.radialGradient(0f to inner, 1f to hsl(h, 80f, 50f, 0f), center = Offset(x, y), radius = r),
            size = Size(p.w, p.h), blendMode = blend,
        )
    }
}

private fun DrawScope.drawTide(p: BgParams, paths: BackgroundPaths) {
    val t = p.t
    for (i in 0 until 6) {
        // From a third of the height down: the waves fill the lower two thirds
        val y0 = p.h * (.34f + i * .11f)
        val amp = 14f + i * 4f
        val k = .006f + i * .0015f
        val sp = .35f + i * .12f
        val path = paths.tide[i].apply { rewind(); moveTo(0f, p.h) }
        var x = 0f
        while (x <= p.w) {
            path.lineTo(x, y0 + sin(x * k + t * sp + i) * amp + sin(x * k * 2.3f - t * sp * .7f) * amp * .35f)
            x += 12f
        }
        path.lineTo(p.w, p.h)
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
    withTransform({ rotateRad(-.21f, pivot = Offset(p.w / 2f, p.h / 2f)) }) {
        for (i in 0 until 4) {
            val h = ribbons[i]
            val sat = p.s(h)
            val y0 = p.h * (.22f + i * .18f)
            val ph = i * 1.7f
            val sp = .18f + i * .05f
            val fill = paths.silk[i].apply { rewind() }
            val sheen = paths.sheen[i].apply { rewind() }
            val count = silkPoints(p.w)
            val bottom = paths.silkBottom(count)
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
                startX = 0f, endX = p.w,
            )
            drawPath(fill, brush, blendMode = blend)
            // Sheen along one edge of the ribbon
            drawPath(sheen, hsl(h + 30f, 90f * sat, if (p.dark) 80f else 45f, if (p.dark) .18f else .22f), style = Stroke(1.2f), blendMode = blend)
        }
    }
}

private const val CONTOUR_STEP = 20f
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
                center = Offset(p.w * .66f, p.h * .5f), radius = p.w * .55f,
            ),
            size = Size(p.w, p.h), blendMode = lineBlend,
        )
        val l = if (p.dark) 68f else 40f
        if (BackgroundShaders.draw(this, BackgroundKind.CONTOUR, p.w, p.h, t, hsl(r0, 80f * p.s(r0), l), hsl(r1, 80f * p.s(r1), l), Color.Black, 1f, lineBlend, p.dark)) return
    }
    val nx = contourColumns(p.w)
    val ny = contourRows(p.h)
    val f = paths.field(nx, ny)
    for (j in 0 until ny) for (i in 0 until nx) {
        val x = i * CONTOUR_STEP
        val y = j * CONTOUR_STEP
        f[j * nx + i] = sin(x * .0062f + t * .07f) + sin(y * .0081f - t * .05f + x * .002f) + .7f * sin((x - y) * .0047f + t * .09f) +
            .5f * cos(hypot(x - p.w * .66f, y - p.h * .5f) * .011f - t * .12f)
    }
    val blend = if (p.dark) BlendMode.Plus else BlendMode.SrcOver
    val glowCenter = Offset(p.w * .66f, p.h * .5f)
    drawRect(
        Brush.radialGradient(
            0f to hsl(p.acc, 80f * p.sat1, if (p.dark) 50f else 70f, if (p.dark) .14f else .2f), 1f to hsl(p.acc, 80f, 50f, 0f),
            center = glowCenter, radius = p.w * .55f,
        ),
        size = Size(p.w, p.h), blendMode = blend,
    )
    val s = CONTOUR_STEP
    val pts = FloatArray(8)
    val lines = paths.contourLines(nx, ny)
    val paint = paths.contourPaint.apply { blendMode = if (p.dark) android.graphics.BlendMode.PLUS else android.graphics.BlendMode.SRC_OVER }
    for (k in 0 until CONTOUR_LEVELS) {
        val level = -2.8f + k * .35f
        val major = k % 4 == 0
        var count = 0
        for (j in 0 until ny - 1) for (i in 0 until nx - 1) {
            val a = f[j * nx + i]
            val b = f[j * nx + i + 1]
            val d = f[(j + 1) * nx + i + 1]
            val e = f[(j + 1) * nx + i]
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
