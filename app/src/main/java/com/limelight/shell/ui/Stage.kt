package com.limelight.shell.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The new UI is laid out on a "stage" of 1920×1080 stage pixels at 16:9, like the prototype (docs/prototype/delos-gui.html),
 * so sizes can be taken from it 1:1. One stage pixel scales with the screen. Other window shapes get a stage that holds more
 * stage pixels along one axis, or fewer when the legibility floor applies: [StagePlanner] decides.
 */
@Immutable
data class StageMetrics(
    /** Dp per stage pixel. */
    val dpPerPx: Float,
    /** Extra factor for text: Settings → Appearance → Interface scale. */
    val textScale: Float = 1f,
    /** Width and height of the window in stage pixels (1920 × 1080 at 16:9 while nothing is floored). */
    val stageWidth: Float = StagePlanner.STAGE_WIDTH,
    val stageHeight: Float = StagePlanner.STAGE_HEIGHT,
    /** The layout family of this window shape. */
    val shape: StageShape = StageShape.STANDARD,
    /** The display cutout in stage pixels on each edge; zero where the screen has none. */
    val safe: StageInsets = StageInsets.None,
)

val LocalStage = staticCompositionLocalOf { StageMetrics(dpPerPx = 1f) }

/**
 * Debug builds only: the display cutout (insets in pixels of the density in force, left/top/right/bottom) that [Stage] uses
 * instead of the window's own, for the shape simulator whose box is not the window. `null`: read the window.
 */
val LocalCutoutPx = staticCompositionLocalOf<IntRect?> { null }

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
 * [fadeEdges] of a scrolling area whose edges fade only as far as content continues behind them, so the focus ring and glow of an item
 * resting at the very start or end are not dimmed. [topPx] / [bottomPx] get the full fade in raw pixels and return how much of it shows
 * (0: nothing behind that edge); they are read while drawing. The layer still ends at the element's bounds: give the items room inside it
 * ([focusRoom]).
 */
fun Modifier.fadeEdges(stage: StageMetrics, top: Float, bottom: Float, topPx: (Float) -> Float, bottomPx: (Float) -> Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val height = size.height.coerceAtLeast(1f)
            val fullTop = stagePx(top, stage)
            val fullBottom = stagePx(bottom, stage)
            val start = topPx(fullTop).coerceIn(0f, fullTop)
            val end = bottomPx(fullBottom).coerceIn(0f, fullBottom)
            if (start <= 0f && end <= 0f) return@drawWithContent
            drawRect(
                Brush.verticalGradient(
                    0f to if (start > 0f) Color.Transparent else Color.Black, (start / height).coerceIn(0f, .5f) to Color.Black,
                    (1f - end / height).coerceIn(.5f, 1f) to Color.Black, 1f to if (end > 0f) Color.Transparent else Color.Black,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

/** [fadeEdges] following [scroll]: each fade grows with the first pixels scrolled away behind its edge and is gone at that end of the content. */
fun Modifier.fadeEdges(stage: StageMetrics, scroll: ScrollState, top: Float, bottom: Float): Modifier =
    fadeEdges(
        stage, top, bottom,
        topPx = { scroll.value.toFloat() },
        bottomPx = { full -> if (scroll.maxValue == Int.MAX_VALUE) full else (scroll.maxValue - scroll.value).toFloat() },
    )

/**
 * Makes the element's own box [horizontal] / [vertical] larger on each side without moving it or its neighbours: room inside a viewport
 * that clips or fades (a scrolling list, row or grid) for the focus scale, ring and glow of the items at its edges. Pad the content
 * inside by the same amount and it stays where it was.
 */
fun Modifier.focusRoom(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val h = horizontal.roundToPx()
    val v = vertical.roundToPx()
    val placeable = measurable.measure(constraints.offset(2 * h, 2 * v))
    layout((placeable.width - 2 * h).coerceAtLeast(0), (placeable.height - 2 * v).coerceAtLeast(0)) { placeable.place(-h, -v) }
}

/**
 * Brings the focused element into view with a margin of [before] / [after] pixels, so it never sits in a faded edge
 * (see [fadeEdges]), and only when it would leave that safe area.
 *
 * The animation is driven here, not by the framework: Foundation 1.12 deprecated `BringIntoViewSpec.scrollAnimationSpec`
 * ("customization is no longer supported") and on the device a custom spec had no visible effect, the scroll stayed a
 * ~150 ms default spring. So this spec only *measures*: [calculateScrollDistance] receives the focused element's bounds,
 * hands the needed distance to a [FocusScroller] and returns 0, which leaves the framework nothing to scroll (like
 * `NoAutoScroll`). Create it with [rememberMarginBringIntoView].
 */
@OptIn(ExperimentalFoundationApi::class)
class MarginBringIntoView internal constructor(
    private val before: Float,
    private val after: Float,
    private val scroller: FocusScroller,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Where the element will be once the running scroll has arrived, so a retarget is measured against its destination.
        scroller.request(distance(offset - scroller.remaining, size, containerSize, before, after))
        return 0f
    }

    companion object {
        /** How far to scroll forward (negative: back) so an element at [offset] of [size] sits inside the margins; 0 when it already does. */
        fun distance(offset: Float, size: Float, containerSize: Float, before: Float, after: Float): Float {
            val start = offset - before
            val end = offset + size + after - containerSize
            return when {
                start < 0f -> start
                end > 0f -> minOf(end, start)
                else -> 0f
            }
        }
    }
}

/**
 * Runs the scrolls of a [MarginBringIntoView] on [state]: one [ListScrollDriver] spring per list, started by the first
 * request and fed by later ones (a new focus move adds its distance to what is left and keeps the velocity, so there is
 * no restart and holding the D-pad glides). A drag or any other scroll of [state] takes over and ends it.
 * With [reducedMotion] the scroll is applied at once.
 */
class FocusScroller(
    private val state: ScrollableState,
    private val scope: CoroutineScope,
    stagePx: Float,
    private val reducedMotion: Boolean,
) {
    private val driver = ListScrollDriver(stagePx)
    private var running = false

    /** Pixels the running scroll still has to go. */
    val remaining: Float get() = driver.remaining

    fun request(distance: Float) {
        if (distance == 0f) return
        if (reducedMotion) {
            state.dispatchRawDelta(distance)
            return
        }
        driver.retarget(distance)
        if (running) return
        running = true
        scope.launch {
            try {
                state.scroll { driver.run({ scrollBy(it) }, { withFrameNanos { it } }) }
            } finally {
                driver.stop()
                running = false
            }
        }
    }
}

/** A [MarginBringIntoView] for the scrollable [state]; provide it as `LocalBringIntoViewSpec`. */
@Composable
fun rememberMarginBringIntoView(state: ScrollableState, before: Float, after: Float, stagePx: Float): MarginBringIntoView {
    val reducedMotion = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    return remember(state, before, after, stagePx, reducedMotion) {
        MarginBringIntoView(before, after, FocusScroller(state, scope, stagePx, reducedMotion))
    }
}

/** Plans the stage for the available space ([StagePlanner]) and provides [LocalStage]. */
@Composable
fun Stage(textScale: Float, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val plan = StagePlanner.plan(maxWidth.value, maxHeight.value)
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val cutout = LocalCutoutPx.current
        val cutoutInsets = WindowInsets.displayCutout
        fun toStage(pixels: Int) = StagePlanner.insetToStage(pixels, density.density, plan.dpPerPx)
        val safe = if (cutout != null) {
            StageInsets(toStage(cutout.left), toStage(cutout.top), toStage(cutout.right), toStage(cutout.bottom))
        } else {
            StageInsets(
                toStage(cutoutInsets.getLeft(density, direction)), toStage(cutoutInsets.getTop(density)),
                toStage(cutoutInsets.getRight(density, direction)), toStage(cutoutInsets.getBottom(density)),
            )
        }
        val metrics = StageMetrics(plan.dpPerPx, textScale, plan.stageWidth, plan.stageHeight, plan.shape, safe)
        CompositionLocalProvider(LocalStage provides metrics) {
            content()
        }
    }
}
