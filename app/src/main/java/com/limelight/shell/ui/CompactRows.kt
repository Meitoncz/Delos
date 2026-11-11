package com.limelight.shell.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * The one scale factor of the non-text elements on a [StageShape.COMPACT] stage (issue #9, phase C): row heights and paddings, icons, chips,
 * tab pills, the banner, the dock of the description. Text is NOT scaled by it (the legibility floor of [StagePlanner] keeps text at least as big
 * as on the reference device), only the room around it.
 *
 * It is the stage's size relative to the 1920 x 1080 design, `min(width / 1920, height / 1080)`, never under [COMPACT_SCALE_FLOOR]; 1 on
 * every other shape, so those stay pixel-identical. The floor keeps a 92 px row at 64 stage px, the least a touch or controller target may have.
 */
const val COMPACT_SCALE_FLOOR = 0.7f

val StageMetrics.compactScale: Float
    get() = if (shape != StageShape.COMPACT) 1f else max(COMPACT_SCALE_FLOOR, min(stageWidth / StagePlanner.STAGE_WIDTH, stageHeight / StagePlanner.STAGE_HEIGHT))

/** Stage pixels of a non-text element, scaled by [compactScale] (the same as [px] on every shape but the compact one). */
val Number.cpx: Dp
    @Composable @ReadOnlyComposable
    get() = LocalStage.current.let { (toFloat() * it.compactScale * it.dpPerPx).dp }

/** Like [cpx] but never under 64 stage px: for a row or button that is touched or focused. */
@Composable
@ReadOnlyComposable
fun targetPx(value: Number): Dp = LocalStage.current.let { (max(value.toFloat() * it.compactScale, min(value.toFloat(), 64f)) * it.dpPerPx).dp }
