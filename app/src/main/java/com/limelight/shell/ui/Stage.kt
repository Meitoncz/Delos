package com.limelight.shell.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
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
