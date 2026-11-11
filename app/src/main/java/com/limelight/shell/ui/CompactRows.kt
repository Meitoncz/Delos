package com.limelight.shell.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/*
 * Scaling helpers of the non-text elements on a [StageShape.COMPACT] stage (issue #9, phase C) for the settings family: row heights and paddings,
 * icons, chips, tab pills, the banner, the dock of the description. Text is NOT scaled (the legibility floor of [StagePlanner] keeps text at least
 * as big as on the reference device), only the room around it. The factor itself is [compactScale] (see Compact.kt): 1 on every other shape, so
 * those stay pixel-identical; [targetPx] keeps touched or focused rows at 64 stage px at least.
 */

/** Stage pixels of a non-text element, scaled by [compactScale] (the same as [px] on every shape but the compact one). */
val Number.cpx: Dp
    @Composable @ReadOnlyComposable
    get() = LocalStage.current.let { (toFloat() * it.compactScale * it.dpPerPx).dp }

/** Like [cpx] but never under 64 stage px: for a row or button that is touched or focused. */
@Composable
@ReadOnlyComposable
fun targetPx(value: Number): Dp = LocalStage.current.let { (max(value.toFloat() * it.compactScale, min(value.toFloat(), 64f)) * it.dpPerPx).dp }
