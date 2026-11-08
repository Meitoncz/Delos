package com.limelight.shell.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import com.limelight.shell.ContextMenu
import com.limelight.shell.LocalFocusTracker

/**
 * saturate(.86) brightness(.92): unfocused covers are slightly muted. Applied as the image's color
 * filter (cheap, drawn with the image) rather than as a layer effect on the whole tile.
 */
val IdleCoverFilter: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(.86f)
        timesAssign(ColorMatrix(floatArrayOf(.92f, 0f, 0f, 0f, 0f, 0f, .92f, 0f, 0f, 0f, 0f, 0f, .92f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    }
)

/**
 * The covers behind an open context menu (the prototype's `.menu-open .gt:not(.sel)`: saturate(.6) brightness(.5)):
 * [IdleCoverFilter] when [open] is 0, the dimmed look at 1.
 */
fun menuDimCoverFilter(open: Float): ColorFilter {
    if (open <= 0f) return IdleCoverFilter
    val saturation = .86f + (.6f - .86f) * open
    val brightness = .92f + (.5f - .92f) * open
    return ColorFilter.colorMatrix(
        ColorMatrix().apply {
            setToSaturation(saturation)
            timesAssign(ColorMatrix(floatArrayOf(brightness, 0f, 0f, 0f, 0f, 0f, brightness, 0f, 0f, 0f, 0f, 0f, brightness, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        }
    )
}

/**
 * The focus ring: an accent band of [width] stage pixels, [gap] pixels outside the element (the gap
 * is filled with the background color). Tiles use 5 / 5, list rows 3 / 0.
 */
@Immutable
data class FocusRing(val width: Float = 5f, val gap: Float = 5f)

/** The accent glow under a focused element: CSS box-shadow 0 [offsetY] [blur] [spread], stage pixels. */
@Immutable
data class FocusGlow(val offsetY: Float = 30f, val blur: Float = 80f, val spread: Float = -18f)

/**
 * The focus language of the prototype (`.f`, `.tile.is-focus`, `.row.is-focus`, `.sweep`): a spring
 * scale, a ring, an accent glow and optionally a sweep of light. Makes the element focusable (always,
 * also in touch mode); A, Enter, D-pad center and taps activate it. The focused element registers with
 * the [com.limelight.shell.FocusTracker], with its [contextMenu] and whether it is [adjustable] with
 * left/right.
 *
 * Sizes are in stage pixels (see [px]).
 */
fun Modifier.focusEffects(
    onClick: () -> Unit,
    corner: Float,
    focusScale: Float = 1.055f,
    ring: FocusRing? = FocusRing(),
    glow: FocusGlow? = FocusGlow(),
    sweep: Boolean = false,
    /** Where the scale grows from: the wide first cover grows to the left, so it never covers its neighbour. */
    origin: TransformOrigin = TransformOrigin.Center,
    clickCue: Cue? = Cue.SELECT,
    contextMenu: ContextMenu? = null,
    adjustable: Boolean = false,
    controllerAdjust: ((direction: Int, repeatCount: Int) -> Unit)? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    /** What a press squeezes the element to for a moment ([Motion.PRESS_SCALE]); 1 for an element that has no press feedback of its own kind. */
    pressScale: Float = Motion.PRESS_SCALE,
    /** How long the focus spring of the scale takes ([Motion.FOCUS]). */
    focusMillis: Int = Motion.FOCUS,
    /** Share the surrounding list's moving plate instead of drawing a separate ring and glow. */
    glide: Boolean = false,
): Modifier = composed {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val feedback = LocalFeedback.current
    val tracker = LocalFocusTracker.current
    val plate = if (glide) LocalRowGlide.current else null
    var focused by remember { mutableStateOf(false) }
    val pulse = rememberPressPulse()
    val scale by animateFocusScale(focused, pulse.pressed, focusScale, pressScale, focusMillis = if (plate != null) Motion.ROW_FOCUS else focusMillis)
    // Hosted rows never draw an independent ring or glow.
    val ringAlpha by animateFloatAsState(
        if (focused) 1f else 0f, tween(if (focused) 140 else 110, easing = EaseOut), label = "focusRing",
    )
    // The glow is a blurred shadow layer: only an element that is (or just was) focused has one. It stays attached for a while
    // after the focus has left, so a focus that moves back and forth between two elements never attaches and detaches it again.
    var glowHeld by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused) glowHeld = true else {
            delay(GLOW_HOLD_MS)
            glowHeld = false
        }
    }
    val glowVisible = focused || glowHeld
    val sweepPos = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        if (focused && sweep) {
            sweepPos.snapTo(0f)
            sweepPos.animateTo(1f, tween(1250, delayMillis = 80, easing = EaseOut))
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val requester = remember { FocusRequester() }
    val position = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val activate = {
        clickCue?.let(feedback::play)
        pulse.fire()
        onClick()
    }
    val currentActivate by rememberUpdatedState(activate)
    val currentAdjust by rememberUpdatedState(controllerAdjust)
    val shape = RoundedCornerShape(corner.px)
    val k = stage.dpPerPx * LocalDensity.current.density

    // The animated values (scale, glow and ring alpha) are only read while drawing, never while composing:
    // a focus change would otherwise rebuild both affected elements on every frame of the animation
    this
        .then(if (plate != null) Modifier.rowGlideTarget(focused) else Modifier)
        // The focused element is drawn above its neighbours, so its ring and scale are never covered
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            transformOrigin = origin
        }
        .then(
            if (plate != null || glow == null) Modifier
            // A round element draws its glow itself, centered on it: the blurred shadow of dropShadow is cut off at the element's square
            else if (corner >= 500f) Modifier.drawBehind {
                val a = ringAlpha
                if (a > 0f) {
                    val inner = (size.minDimension / 2f + glow.spread * k).coerceAtLeast(1f)
                    val outer = inner + glow.blur * k
                    val c = colors.glow
                    drawCircle(
                        Brush.radialGradient(
                            0f to c.copy(alpha = c.alpha * a),
                            inner / outer to c.copy(alpha = c.alpha * a),
                            (inner / outer + 1f) / 2f to c.copy(alpha = c.alpha * a * .32f),
                            1f to Color.Transparent,
                            center = Offset(size.width / 2f, size.height / 2f), radius = outer,
                        ),
                        radius = outer, center = Offset(size.width / 2f, size.height / 2f),
                    )
                }
            }
            else if (glowVisible) Modifier.dropShadow(shape) {
                radius = glow.blur * k
                spread = glow.spread * k
                offset = Offset(0f, glow.offsetY * k)
                color = colors.glow
                alpha = ringAlpha
            } else Modifier
        )
        .drawWithContent {
            drawContent()
            val r = min(stagePx(corner, stage), size.minDimension / 2f)
            if (sweep && focused) drawSweep(sweepPos.value, r)
            if (plate == null && ring != null && ringAlpha > 0f) drawFocusRing(ring, r, ringAlpha, colors, stage)
        }
        .onGloballyPositioned {
            position[0] = it
            if (focused) tracker.moved(requester, it)
        }
        .onFocusChanged {
            if (it.isFocused != focused) {
                focused = it.isFocused
                if (focused) {
                    feedback.focusMoved()
                    tracker.focused(
                        requester,
                        position[0],
                        contextMenu,
                        adjustable,
                        activate = { currentActivate() },
                        adjust = if (controllerAdjust == null) null else { direction, repeatCount ->
                            currentAdjust?.invoke(direction, repeatCount)
                        },
                    )
                }
                onFocusChange?.invoke(focused)
            }
        }
        .onKeyEvent {
            // Handled on both edges so Android does not synthesize a fallback key (A → D-pad center)
            if (it.key !in ACTIVATE_KEYS) return@onKeyEvent false
            if (it.type == KeyEventType.KeyUp) activate()
            true
        }
        // Always focusable, also in touch mode: `clickable` would only be focusable after a key press,
        // but a console UI always shows the focus (and the game background that goes with it)
        .focusRequester(requester)
        .focusable(interactionSource = interaction)
        .pointerInput(Unit) {
            detectTapGestures(onTap = {
                requester.requestFocus()
                activate()
            })
        }
        .semantics {
            role = Role.Button
            onClick { activate(); true }
        }
}

/** A on the gamepad, Enter, numpad Enter and D-pad center activate the focused element. */
private val ACTIVATE_KEYS = setOf(Key.ButtonA, Key.Enter, Key.NumPadEnter, Key.DirectionCenter)

/** How long an element keeps its glow layer after it lost the focus (its glow has faded out long before). */
private const val GLOW_HOLD_MS = 2500L

private fun min(a: Float, b: Float) = if (a < b) a else b

/** A rounded stroke [offset] outside the element's bounds (box-shadow 0 0 0 spread). */
private fun DrawScope.drawRing(color: Color, radius: Float, offset: Float, width: Float, stroke: Stroke? = null) {
    drawRoundRect(
        color = color,
        topLeft = Offset(-offset, -offset),
        size = Size(size.width + offset * 2f, size.height + offset * 2f),
        cornerRadius = CornerRadius(radius + offset),
        style = stroke ?: Stroke(width),
    )
}

/** The focus ring (`box-shadow: 0 0 0 gap bg, 0 0 0 gap+width accent`) around an element with corner [radius]. */
fun DrawScope.drawFocusRing(
    ring: FocusRing, radius: Float, alpha: Float, colors: DelosColors, stage: StageMetrics,
    stroke: Stroke? = null, gapStroke: Stroke? = null,
) {
    val width = stagePx(ring.width, stage)
    val gap = stagePx(ring.gap, stage)
    drawRing(colors.accent.copy(alpha = alpha), radius, gap + width / 2f, width, stroke)
    if (gap > 0f) drawRing(colors.bg.copy(alpha = alpha), radius, gap / 2f, gap, gapStroke)
}

/**
 * linear-gradient(115deg, transparent 30%, white .28 48%, transparent 66%) at background-size 260%,
 * moving from background-position 130% to -40%.
 */
private fun DrawScope.drawSweep(progress: Float, radius: Float) {
    val imageWidth = size.width * 2.6f
    val position = 1.3f - 1.7f * progress
    val left = (size.width - imageWidth) * position
    val brush = cssLinearGradient(
        115f, Size(imageWidth, size.height),
        .3f to Color.White.copy(alpha = 0f), .48f to Color.White.copy(alpha = .28f), .66f to Color.White.copy(alpha = 0f),
        origin = Offset(left, 0f),
    )
    val clip = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius))) }
    clipPath(clip) { drawRect(brush) }
}
