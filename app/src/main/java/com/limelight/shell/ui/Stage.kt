package com.limelight.shell.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * The new UI is laid out on a 1920×1080 "stage", like the prototype (docs/prototype/delos-gui.html),
 * so sizes can be taken from it 1:1. One stage pixel scales with the screen.
 */
@Immutable
data class StageMetrics(
    /** Dp per stage pixel. */
    val dpPerPx: Float,
    /** Extra factor for text: Settings → Appearance → Interface scale. */
    val textScale: Float = 1f,
)

val LocalStage = staticCompositionLocalOf { StageMetrics(dpPerPx = 1f) }

/** Stage pixels → Dp. */
val Number.px: Dp
    @Composable @ReadOnlyComposable
    get() = (toFloat() * LocalStage.current.dpPerPx).dp

/**
 * Stage pixels → text size, including the interface scale. The system font scale is cancelled out:
 * text size is controlled by the interface scale, so the layout stays identical to the prototype.
 */
val Number.tpx: TextUnit
    @Composable @ReadOnlyComposable
    get() {
        val stage = LocalStage.current
        return (toFloat() * stage.dpPerPx * stage.textScale / LocalDensity.current.fontScale).sp
    }

/** Stage pixels → raw pixels inside a draw scope. */
fun DrawScope.stagePx(value: Float, stage: StageMetrics): Float = value * stage.dpPerPx * density

/**
 * Fades the content out at the top and bottom edge ([top] / [bottom] stage pixels), like the
 * prototype's mask-image on scrolling lists.
 */
fun Modifier.fadeEdges(stage: StageMetrics, top: Float, bottom: Float): Modifier =
    fadeMask(stage, vertical = true, startClear = 0f, startSolid = top, endSolid = bottom, endClear = 0f)

/**
 * A CSS-like mask-image gradient along one axis, distances in stage pixels from each edge: transparent
 * up to [startClear], opaque from [startSolid] until [endSolid] before the end, transparent again from
 * [endClear] before the end.
 */
fun Modifier.fadeMask(stage: StageMetrics, vertical: Boolean, startClear: Float, startSolid: Float, endSolid: Float, endClear: Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val length = if (vertical) size.height else size.width
            fun at(px: Float) = (stagePx(px, stage) / length).coerceIn(0f, 1f)
            val stops = arrayOf(
                0f to Color.Transparent, at(startClear) to Color.Transparent, at(startSolid) to Color.Black,
                (1f - at(endSolid)) to Color.Black, (1f - at(endClear)) to Color.Transparent, 1f to Color.Transparent,
            )
            drawRect(
                if (vertical) Brush.verticalGradient(*stops) else Brush.horizontalGradient(*stops),
                blendMode = BlendMode.DstIn,
            )
        }

/**
 * Scrolls the focused element into view with a margin of [before] / [after] pixels, so it never sits
 * in a faded edge (see [fadeEdges]), and only when it would leave that safe area. The scroll is animated by
 * [ListScrollMotion] (calm, slightly longer for longer jumps); a new focus move retargets the running scroll and keeps
 * its velocity, so holding the D-pad glides. [stagePx] is one stage pixel in device pixels; with [reducedMotion] the
 * scroll snaps.
 */
@OptIn(ExperimentalFoundationApi::class)
class MarginBringIntoView(
    private val before: Float,
    private val after: Float,
    stagePx: Float = 1f,
    reducedMotion: Boolean = false,
) : BringIntoViewSpec {
    // Marked deprecated ("customization is no longer supported") in foundation 1.12, yet ContentInViewNode still reads it (checked in 1.12.1).
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> = if (reducedMotion) snap() else ListScrollSpec(stagePx)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = offset - before
        val end = offset + size + after - containerSize
        return when {
            start < 0f -> start
            end > 0f -> minOf(end, start)
            else -> 0f
        }
    }
}

/** The spring of [ListScrollMotion] as an [AnimationSpec]; [stagePx] converts device pixels to stage pixels. */
private class ListScrollSpec(private val stagePx: Float) : AnimationSpec<Float> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<Float, V>): VectorizedAnimationSpec<V> =
        object : VectorizedAnimationSpec<V> {
            override val isInfinite: Boolean get() = false

            private fun omega(x0: Float, v0: Float): Float {
                val toward = if (x0 > 0f) -v0 else v0
                return ListScrollMotion.omega(kotlin.math.abs(x0) / stagePx, toward / stagePx)
            }

            override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long {
                val x0 = converter.convertFromVector(initialValue) - converter.convertFromVector(targetValue)
                val v0 = converter.convertFromVector(initialVelocity)
                return (ListScrollMotion.durationSeconds(x0 / stagePx, v0 / stagePx, omega(x0, v0), .5f) * 1e9f).toLong()
            }

            override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
                val target = converter.convertFromVector(targetValue)
                val x0 = converter.convertFromVector(initialValue) - target
                val v0 = converter.convertFromVector(initialVelocity)
                return converter.convertToVector(target + ListScrollMotion.offset(x0, v0, omega(x0, v0), playTimeNanos / 1e9f))
            }

            override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
                val x0 = converter.convertFromVector(initialValue) - converter.convertFromVector(targetValue)
                val v0 = converter.convertFromVector(initialVelocity)
                return converter.convertToVector(ListScrollMotion.velocity(x0, v0, omega(x0, v0), playTimeNanos / 1e9f))
            }
        }
}

/** Fits the 1920×1080 stage into the available space and provides [LocalStage]. */
@Composable
fun Stage(textScale: Float, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val dpPerPx = min(maxWidth.value / 1920f, maxHeight.value / 1080f)
        CompositionLocalProvider(LocalStage provides StageMetrics(dpPerPx, textScale)) {
            content()
        }
    }
}
