package com.limelight.shell.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** Whether the interface avoids movement (the Animations setting); provided by every window that draws Delos UI. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Passive help: pause at either end, travel at a constant reading speed, and restart on selection. With
 * reduced motion it stays still and is scrolled by touch instead.
 */
@Composable
fun AutoScrollingHelp(
    identity: Any?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val scroll = rememberScrollState()
    val stage = LocalStage.current
    val pixelsPerStagePixel = stage.dpPerPx * LocalDensity.current.density
    val maximum = scroll.maxValue
    val overflow = maximum in 1 until Int.MAX_VALUE
    LaunchedEffect(identity, maximum, pixelsPerStagePixel, reducedMotion) {
        scroll.scrollTo(0)
        if (!overflow || reducedMotion) return@LaunchedEffect
        val duration = (maximum * 1000f / (20f * pixelsPerStagePixel)).roundToInt().coerceIn(1000, 120000)
        while (isActive) {
            delay(3000)
            scroll.animateScrollTo(maximum, tween(duration, easing = LinearEasing))
            delay(2500)
            scroll.animateScrollTo(0, tween(duration, easing = LinearEasing))
        }
    }
    Column(modifier.focusProperties { canFocus = false }
        .fadeEdges(stage, top = if (overflow) 24f else 0f, bottom = if (overflow) 24f else 0f)
        .verticalScroll(scroll, enabled = reducedMotion)
        // The first and last lines can rest fully outside the fades at the two endpoints.
        .padding(vertical = 24.px), verticalArrangement = Arrangement.spacedBy(18.px), content = content)
}
