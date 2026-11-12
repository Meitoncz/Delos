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
 *
 * [edgeFade] (stage px) is the compact dock's look: no padding above or below, and each edge fades only as far as the content
 * continues behind it (nothing at rest at the top, growing with the first pixels scrolled), so the area is clipped clean at both ends.
 */
@Composable
fun AutoScrollingHelp(
    identity: Any?,
    modifier: Modifier = Modifier,
    /** The padding above and below the text, which is also the width of the fades at both ends (stage px). */
    inset: Float = 24f,
    /** The room between the paragraphs (stage px). */
    spacing: Float = 18f,
    edgeFade: Float? = null,
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
        // The compact dock gives the text a long first pause (time to read it whole, nothing half-cut under the header); the panel keeps 3 s
        var first = if (edgeFade != null) 8000L else 3000L
        while (isActive) {
            delay(first)
            first = 3000L
            scroll.animateScrollTo(maximum, tween(duration, easing = LinearEasing))
            delay(2500)
            scroll.animateScrollTo(0, tween(duration, easing = LinearEasing))
        }
    }
    Column(modifier.focusProperties { canFocus = false }
        .then(
            if (edgeFade != null) Modifier.fadeEdges(stage, scroll, top = edgeFade, bottom = edgeFade)
            else Modifier.fadeEdges(stage, top = if (overflow) inset else 0f, bottom = if (overflow) inset else 0f)
        )
        .verticalScroll(scroll, enabled = reducedMotion)
        // The first and last lines can rest fully outside the fades at the two endpoints.
        .padding(vertical = (if (edgeFade != null) 0f else inset).px), verticalArrangement = Arrangement.spacedBy(spacing.px), content = content)
}
