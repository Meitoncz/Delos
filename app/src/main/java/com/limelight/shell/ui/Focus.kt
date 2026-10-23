package com.limelight.shell.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.zIndex

/** saturate(.86) brightness(.92): unfocused covers are slightly muted (Android 12+). */
private val idleEffect by lazy {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) null
    else RenderEffect.createColorFilterEffect(
        ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(.86f)
            postConcat(ColorMatrix().apply { setScale(.92f, .92f, .92f, 1f) })
        })
    ).asComposeRenderEffect()
}

/**
 * The focus language of the prototype (`.f`, `.tile.is-focus`, `.sweep`): a spring scale, a two-tone
 * ring (a gap in the background color, then the accent), an accent glow and optionally a sweep of light.
 * Makes the element focusable (always, also in touch mode); A, Enter, D-pad center and taps activate it.
 *
 * Sizes are in stage pixels (see [px]).
 */
fun Modifier.focusEffects(
    onClick: () -> Unit,
    corner: Float,
    focusScale: Float = 1.055f,
    ring: Boolean = true,
    glow: Boolean = true,
    sweep: Boolean = false,
    dimWhenIdle: Boolean = false,
    clickCue: Cue? = Cue.SELECT,
    onFocusChange: ((Boolean) -> Unit)? = null,
): Modifier = composed {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val feedback = LocalFeedback.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, tween(380, easing = EaseSpring), label = "focusScale")
    val ringAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(380, easing = EaseOut), label = "focusRing")
    val sweepPos = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        if (focused && sweep) {
            sweepPos.snapTo(0f)
            sweepPos.animateTo(1f, tween(1250, delayMillis = 80, easing = EaseOut))
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val requester = remember { FocusRequester() }
    val activate = {
        clickCue?.let(feedback::play)
        onClick()
    }
    val shape = RoundedCornerShape(corner.px)
    val glowShadow = Shadow(
        radius = 80.px, spread = (-18).px, offset = DpOffset(0.px, 30.px),
        color = colors.glow.copy(alpha = colors.glow.alpha * ringAlpha),
    )

    this
        // The focused element is drawn above its neighbours, so its ring and scale are never covered
        .zIndex(if (focused || scale > 1f) 1f else 0f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            renderEffect = if (dimWhenIdle && !focused) idleEffect else null
        }
        .then(if (glow && ringAlpha > 0f) Modifier.dropShadow(shape, glowShadow) else Modifier)
        .drawWithContent {
            drawContent()
            val r = min(stagePx(corner, stage), size.minDimension / 2f)
            if (sweep && focused) drawSweep(sweepPos.value, r)
            if (ring && ringAlpha > 0f) {
                val band = stagePx(5f, stage)
                drawRing(colors.accent.copy(alpha = ringAlpha), r, band * 1.5f, band)
                drawRing(colors.bg.copy(alpha = ringAlpha), r, band * .5f, band)
            }
        }
        .onFocusChanged {
            if (it.isFocused != focused) {
                focused = it.isFocused
                if (focused) feedback.focusMoved()
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

private fun min(a: Float, b: Float) = if (a < b) a else b

/** A rounded stroke [offset] outside the element's bounds (box-shadow 0 0 0 spread). */
private fun DrawScope.drawRing(color: Color, radius: Float, offset: Float, width: Float) {
    drawRoundRect(
        color = color,
        topLeft = Offset(-offset, -offset),
        size = Size(size.width + offset * 2f, size.height + offset * 2f),
        cornerRadius = CornerRadius(radius + offset),
        style = Stroke(width),
    )
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
