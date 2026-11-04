package com.limelight.shell.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/*
 * The building blocks of the motion language (docs/GUI.md, "Motion"): modifiers, state holders, a switch, the gliding
 * ring and the transitions of layers and screens. The numbers are in [Motion]; everything here follows reduced motion
 * (LocalReducedMotion): transforms are dropped, fades remain.
 */

/** Pixels per stage pixel: what a distance in stage pixels is while drawing or inside a graphics layer. */
@Composable
fun stageUnit(): Float = LocalStage.current.dpPerPx * LocalDensity.current.density

/** This color at [fraction] of its own opacity. */
fun Color.fade(fraction: Float): Color = copy(alpha = alpha * fraction)

// ---- Entrance ----

/** The counter of the screen, menu or page that is being composed (see [EntranceStagger]); the default animates nothing. */
val LocalEntranceStagger = staticCompositionLocalOf { EntranceStagger(enabled = false) }

/**
 * A screen, a menu or a dialog whose elements enter one after the other: everything inside that takes
 * [staggerEntrance] without an index gets the next place, up to [cap]; elements that appear later than [lateAfterMs]
 * after this scope was composed (a row that shows up when data arrives, a lazy list that scrolls) just appear.
 */
@Composable
fun EntranceScope(
    enabled: Boolean = true,
    cap: Int = Motion.STAGGER_CAP,
    lateAfterMs: Long = Motion.STAGGER_WINDOW,
    content: @Composable () -> Unit,
) {
    val stagger = rememberEntranceStagger(enabled = enabled, cap = cap, lateAfterMs = lateAfterMs)
    CompositionLocalProvider(LocalEntranceStagger provides stagger, content = content)
}

/** [staggerEntrance] in the place the surrounding [EntranceScope] (or stream menu page) hands out next. */
@Composable
fun Modifier.staggerEntrance(): Modifier {
    val stagger = LocalEntranceStagger.current
    val index = remember { stagger.take() }
    return staggerEntrance(index)
}

/** A fresh [EntranceStagger] for a screen, remembered for as long as [key] stays the same (a tab that is switched starts a new entrance). */
@Composable
fun rememberEntranceStagger(
    key: Any? = Unit,
    enabled: Boolean = true,
    cap: Int = Motion.STAGGER_CAP,
    lateAfterMs: Long = Motion.STAGGER_WINDOW,
): EntranceStagger = remember(key) { EntranceStagger(enabled, cap, lateAfterMs) }

/**
 * The entrance of one element of a staggered list: it rises [rise] stage pixels and fades in ([duration] ms), starting
 * [baseDelay] ms after it is composed plus [step] ms for every place before it. With reduced motion it only fades;
 * a negative [index] (not part of the entrance) leaves the element alone. The element is usable all the time, the
 * entrance is only what it looks like.
 */
@Composable
fun Modifier.staggerEntrance(
    index: Int,
    baseDelay: Int = Motion.ROW_DELAY,
    step: Int = Motion.STAGGER_ROW,
    rise: Float = Motion.RISE,
    duration: Int = Motion.ROW_IN,
): Modifier {
    val reduced = LocalReducedMotion.current
    val appear = remember { Animatable(if (index < 0) 1f else 0f) }
    val distance = rise * stageUnit()
    LaunchedEffect(Unit) {
        if (index >= 0) {
            delay(Motion.staggerDelay(index, baseDelay, step).toLong())
            appear.animateTo(1f, tween(duration, easing = EaseOut))
        }
    }
    if (index < 0) return this
    return graphicsLayer {
        alpha = appear.value
        translationY = Motion.rise(distance, appear.value, reduced)
    }
}

// ---- Press and focus ----

/** The squeeze of an element that was activated: [pressed] is true for [Motion.PRESS_HOLD] after [fire]. */
@Stable
class PressPulse internal constructor(private val scope: CoroutineScope) {
    var pressed by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun fire() {
        job?.cancel()
        job = scope.launch {
            pressed = true
            delay(Motion.PRESS_HOLD)
            pressed = false
        }
    }
}

@Composable
fun rememberPressPulse(): PressPulse {
    val scope = rememberCoroutineScope()
    return remember { PressPulse(scope) }
}

/**
 * The scale of a focusable element: the focus lifts it to [focusScale] with the overshooting spring ([Motion.FOCUS]),
 * a press squeezes it to [pressScale] for a moment ([Motion.PRESS]). With reduced motion it jumps.
 */
@Composable
fun animateFocusScale(
    focused: Boolean,
    pressed: Boolean,
    focusScale: Float,
    pressScale: Float = Motion.PRESS_SCALE,
    label: String = "focusScale",
): State<Float> {
    val reduced = LocalReducedMotion.current
    return animateFloatAsState(
        Motion.scaleTarget(focused, pressed, focusScale, pressScale),
        if (reduced) snap() else tween(if (pressed) Motion.PRESS else Motion.FOCUS, easing = EaseSpring),
        label = label,
    )
}

/** The scale of a slider's knob: it grows to [Motion.KNOB_FOCUS_SCALE] while its row is focused. */
@Composable
fun animateKnobScale(focused: Boolean): State<Float> {
    val reduced = LocalReducedMotion.current
    return animateFloatAsState(
        if (focused) Motion.KNOB_FOCUS_SCALE else 1f,
        if (reduced) tween(0) else tween(Motion.FOCUS, easing = EaseSpring),
        label = "knobScale",
    )
}

// ---- A choice changing its value ----

/**
 * What a choice row shows when a key steps its value: the new value slides in from the side the key came from, the
 * chevron it went toward nudges. [step] starts it; [bump] and [slide] run from 0 to 1 (back to 0 for the bump).
 */
@Stable
class ChoiceMotion {
    var direction by mutableIntStateOf(0)
        private set
    var count by mutableIntStateOf(0)
        private set
    val bump = Animatable(0f)
    val slide = Animatable(1f)

    fun step(direction: Int) {
        this.direction = direction
        count++
    }
}

@Composable
fun rememberChoiceMotion(): ChoiceMotion {
    val motion = remember { ChoiceMotion() }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(motion.count) {
        if (motion.count == 0) return@LaunchedEffect
        coroutineScope {
            if (!reduced) launch {
                motion.bump.snapTo(0f)
                motion.bump.animateTo(1f, tween((Motion.CHOICE_BUMP * .4f).toInt(), easing = EaseSpring))
                motion.bump.animateTo(0f, tween((Motion.CHOICE_BUMP * .6f).toInt(), easing = EaseSpring))
            }
            motion.slide.snapTo(0f)
            motion.slide.animateTo(1f, tween(Motion.CHOICE_SLIDE, easing = EaseOut))
        }
    }
    return motion
}

/** The value of a choice: fades in and slides [Motion.VALUE_SLIDE] stage pixels in from the side [ChoiceMotion.direction] points to. */
@Composable
fun Modifier.valueSlide(motion: ChoiceMotion): Modifier {
    val reduced = LocalReducedMotion.current
    val k = stageUnit()
    return graphicsLayer {
        alpha = motion.slide.value
        translationX = Motion.slide(motion.direction, Motion.VALUE_SLIDE * k, motion.slide.value, reduced)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/** The nudge of the chevron on [side] (-1 left, 1 right) of a choice, toward the key that was pressed. */
@Composable
fun Modifier.chevronBump(motion: ChoiceMotion, side: Int): Modifier {
    val reduced = LocalReducedMotion.current
    val k = stageUnit()
    return graphicsLayer { translationX = Motion.chevronBump(side, motion.direction, motion.bump.value, reduced) * k }
}

// ---- Switch ----

/**
 * The on/off switch of the stream menu and of the settings: the knob springs across and turns from pale to dark as the
 * track fills with the gradient of the accents; [offTrack] is the veil the track wears while it is off, [border] an
 * optional 1 px outline. [width] x [height] are stage pixels; the knob is 12 smaller than the track is high, 6 from its edge.
 */
@Composable
fun MotionSwitch(on: Boolean, width: Float, height: Float, offTrack: Color, modifier: Modifier = Modifier, border: Color? = null) {
    val colors = LocalDelosColors.current
    val reduced = LocalReducedMotion.current
    val k = stageUnit()
    val knob by animateFloatAsState(if (on) 1f else 0f, if (reduced) tween(0) else tween(Motion.FOCUS, easing = EaseSpring), label = "switchKnob")
    val lit by animateFloatAsState(if (on) 1f else 0f, tween(Motion.FADE, easing = Motion.Ease), label = "switchTrack")
    val knobColor by animateColorAsState(if (on) colors.onAccent else colors.text2, tween(Motion.FADE, easing = Motion.Ease), label = "switchKnobColor")
    Box(
        modifier
            .size(width.px, height.px)
            .drawBehind {
                // Off: a light veil. On: the accent fading into the second accent, like the slider's fill
                drawRoundRect(offTrack.fade(1f - lit), cornerRadius = CornerRadius(size.height / 2f))
                if (lit > 0f) drawRoundRect(Brush.horizontalGradient(listOf(colors.accent.copy(alpha = lit), colors.accent2.copy(alpha = lit))), cornerRadius = CornerRadius(size.height / 2f))
                if (border != null) {
                    val stroke = k
                    drawRoundRect(
                        border, topLeft = Offset(stroke / 2f, stroke / 2f), size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius((size.height - stroke) / 2f), style = Stroke(stroke),
                    )
                }
                val d = (height - 12f) * k
                drawCircle(knobColor, radius = d / 2f, center = Offset(6f * k + d / 2f + (width - height) * k * knob, size.height / 2f))
            },
    )
}

// ---- The gliding ring ----

/** Where the ring of a [GlideRingHost] goes: the element that has the focus reports its bounds here. */
@Stable
class GlideRing {
    private var host: LayoutCoordinates? = null
    private var holder: Any? = null
    private var holderCoordinates: LayoutCoordinates? = null

    /** The focused element's bounds in the host's coordinates, null while nothing in the host has the focus. */
    var target by mutableStateOf<Rect?>(null)
        private set

    internal fun attach(coordinates: LayoutCoordinates) {
        host = coordinates
        update()
    }

    internal fun focus(key: Any, coordinates: LayoutCoordinates) {
        holder = key
        holderCoordinates = coordinates
        update()
    }

    internal fun blur(key: Any) {
        if (holder != key) return
        holder = null
        holderCoordinates = null
        target = null
    }

    internal fun moved(key: Any, coordinates: LayoutCoordinates) {
        if (holder == key) {
            holderCoordinates = coordinates
            update()
        }
    }

    private fun update() {
        val h = host ?: return
        val c = holderCoordinates ?: return
        if (!h.isAttached || !c.isAttached) return
        val bounds = h.localBoundingBoxOf(c, clipBounds = false)
        if (bounds != target) target = bounds
    }
}

/** The ring of the surrounding [GlideRingHost]; null outside one (an element then draws its own ring). */
val LocalGlideRing = staticCompositionLocalOf<GlideRing?> { null }

/**
 * One focus ring for a group of elements that glides to whichever has the focus ([Motion.RING] ms with the slight
 * overshoot of [Motion.Glide]) instead of every element fading its own in and out, like the dock of the stream menu.
 * The elements mark themselves with [glideTarget] and draw no ring of their own while [LocalGlideRing] is set. The ring
 * is drawn behind the host's content, [focusScale] times as big as the element's bounds (the scale the element grows to
 * when focused), so what sits above the elements (a badge) stays above it. With reduced motion it jumps.
 */
@Composable
fun GlideRingHost(ring: FocusRing, corner: Float, focusScale: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val reduced = LocalReducedMotion.current
    val state = remember { GlideRing() }
    val box = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    val alpha = remember { Animatable(0f) }
    val target = state.target
    LaunchedEffect(target) {
        if (target == null) {
            alpha.animateTo(0f, tween(Motion.FADE, easing = Motion.Ease))
            return@LaunchedEffect
        }
        val grown = target.grownBy(focusScale)
        if (alpha.value == 0f || reduced) {
            box.snapTo(grown)
            alpha.animateTo(1f, tween(Motion.FADE, easing = Motion.Ease))
        } else coroutineScope {
            launch { box.animateTo(grown, tween(Motion.RING, easing = Motion.Glide)) }
            launch { alpha.animateTo(1f, tween(Motion.FADE, easing = Motion.Ease)) }
        }
    }
    CompositionLocalProvider(LocalGlideRing provides state) {
        Box(
            modifier
                .onGloballyPositioned { state.attach(it) }
                .drawBehind {
                    val a = alpha.value
                    val r = box.value
                    if (a > 0f && r.width > 0f) {
                        inset(r.left, r.top, size.width - r.right, size.height - r.bottom) {
                            drawFocusRing(ring, min(stagePx(corner, stage), this.size.minDimension / 2f), a, colors, stage)
                        }
                    }
                },
            content = { content() },
        )
    }
}

private fun Rect.grownBy(scale: Float): Rect {
    val halfWidth = width / 2f * scale
    val halfHeight = height / 2f * scale
    return Rect(center.x - halfWidth, center.y - halfHeight, center.x + halfWidth, center.y + halfHeight)
}

/**
 * Marks an element of a [GlideRingHost]: while it is [focused] the ring glides to it. Put it before the element's
 * `focusEffects` (so its bounds are those of the layout, not of the focus scale). Does nothing outside a host.
 */
@Composable
fun Modifier.glideTarget(focused: Boolean): Modifier {
    val ring = LocalGlideRing.current ?: return this
    val key = remember { Any() }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    LaunchedEffect(focused) {
        if (focused) coordinates[0]?.let { ring.focus(key, it) } else ring.blur(key)
    }
    DisposableEffect(ring) { onDispose { ring.blur(key) } }
    return onGloballyPositioned {
        coordinates[0] = it
        if (focused) ring.moved(key, it)
    }
}

// ---- Layers and screens ----

/**
 * A menu or popover growing out of its anchor ([origin], fractions of the layer): it fades in a little faster than it
 * grows from [Motion.POP_FROM_SCALE] with the overshooting spring. Reduced motion: a fade.
 */
fun popEnter(origin: TransformOrigin, reduced: Boolean): EnterTransition =
    if (reduced) fadeIn(tween(Motion.FADE, easing = Motion.Ease))
    else fadeIn(tween(Motion.CARD_FADE_IN, easing = Motion.Ease)) + scaleIn(tween(Motion.POP_IN, easing = EaseSpring), Motion.POP_FROM_SCALE, origin)

/** It shrinks back into its anchor in [Motion.EXIT] ms, faster than it came. */
fun popExit(origin: TransformOrigin, reduced: Boolean): ExitTransition =
    if (reduced) fadeOut(tween(Motion.EXIT, easing = Motion.Ease))
    else fadeOut(tween(Motion.EXIT, easing = Motion.Ease)) + scaleOut(tween(Motion.EXIT, easing = Motion.In), Motion.POP_FROM_SCALE, origin)

/** A dialog or modal in the middle of the screen: it settles in from a slight scale, a little lower than its place. */
fun dialogEnter(reduced: Boolean): EnterTransition =
    if (reduced) fadeIn(tween(Motion.FADE, easing = Motion.Ease))
    else fadeIn(tween(Motion.FADE, easing = Motion.Ease)) +
        scaleIn(tween(Motion.POP_IN, easing = EaseOut), Motion.DIALOG_FROM_SCALE) +
        slideInVertically(tween(Motion.POP_IN, easing = EaseOut)) { it / 25 }

fun dialogExit(reduced: Boolean): ExitTransition =
    if (reduced) fadeOut(tween(Motion.EXIT, easing = Motion.Ease))
    else fadeOut(tween(Motion.EXIT, easing = Motion.Ease)) +
        scaleOut(tween(Motion.EXIT, easing = Motion.In), Motion.DIALOG_FROM_SCALE) +
        slideOutVertically(tween(Motion.EXIT, easing = Motion.In)) { it / 25 }

/** The side menu slides in from the left edge. */
fun sideEnter(reduced: Boolean): EnterTransition =
    if (reduced) fadeIn(tween(Motion.FADE, easing = Motion.Ease))
    else fadeIn(tween(Motion.FADE, easing = Motion.Ease)) + slideInHorizontally(tween(Motion.POP_IN, easing = EaseOut)) { -it / 10 }

fun sideExit(reduced: Boolean): ExitTransition =
    if (reduced) fadeOut(tween(Motion.EXIT, easing = Motion.Ease))
    else fadeOut(tween(Motion.EXIT, easing = Motion.Ease)) + slideOutHorizontally(tween(Motion.EXIT, easing = Motion.In)) { -it / 10 }

/**
 * One screen replaced by another (`.screen`): the new one fades in while it settles from a slight zoom, the old one
 * leaves quickly. Reduced motion: only the fades.
 */
fun screenTransition(reduced: Boolean): ContentTransform {
    val enter = if (reduced) fadeIn(tween(Motion.FADE, easing = Motion.Ease))
    else fadeIn(tween(Motion.FADE, easing = Motion.Ease)) + scaleIn(tween(Motion.SCREEN_IN, easing = EaseOut), Motion.SCREEN_FROM_SCALE)
    val exit = if (reduced) fadeOut(tween(Motion.EXIT, easing = Motion.Ease))
    else fadeOut(tween(Motion.EXIT, easing = Motion.Ease)) + scaleOut(tween(Motion.EXIT, easing = Motion.In), Motion.SCREEN_FROM_SCALE)
    return enter togetherWith exit
}
