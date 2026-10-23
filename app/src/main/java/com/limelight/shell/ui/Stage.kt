package com.limelight.shell.ui

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
 * in a faded edge (see [fadeEdges]).
 */
@OptIn(ExperimentalFoundationApi::class)
class MarginBringIntoView(private val before: Float, private val after: Float) : BringIntoViewSpec {
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
