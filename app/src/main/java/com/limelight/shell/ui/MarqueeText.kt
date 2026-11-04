package com.limelight.shell.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay

private const val START_DELAY_MS = 700L
private const val LOOP_DELAY_MS = 900L
private const val SPEED = 56f      // stage pixels per second
private const val GAP = 56f        // stage pixels between the end of the text and its repeat
private const val FADE_START = 28f // stage pixels of the left fade
private const val FADE_END = 36f   // stage pixels of the right fade

/**
 * One line of text that always stays readable, whatever its length (translations, large text scale). While it fits
 * nothing changes. When it is wider than the room it is given, its right end fades out, and while it is [active]
 * (usually: its row is focused) it scrolls around in a loop, with a pause before it starts and between laps.
 * The left end fades in only as the text moves away from its start (and out again as the lap ends), so a text at
 * rest always shows its first letter intact. Reduced motion keeps the text still. The caller limits the width with [modifier].
 */
@Composable
fun MarqueeText(text: String, style: TextStyle, modifier: Modifier = Modifier, active: Boolean = true) {
    val stage = LocalStage.current
    val rm = LocalReducedMotion.current
    val k = stage.dpPerPx * LocalDensity.current.density
    var boxWidth by remember { mutableIntStateOf(0) }
    var textWidth by remember { mutableIntStateOf(0) }
    val overflow = boxWidth > 0 && textWidth > boxWidth
    val scrolling = overflow && active && !rm
    // One lap is the text and the gap; at its end the repeat stands exactly where the text started
    val lap = textWidth + GAP * k
    val offset = remember { Animatable(0f) }
    LaunchedEffect(scrolling, text, lap) {
        offset.snapTo(0f)
        if (!scrolling) return@LaunchedEffect
        delay(START_DELAY_MS)
        while (true) {
            offset.animateTo(lap, tween((lap / (SPEED * k) * 1000f).toInt().coerceAtLeast(1), easing = LinearEasing))
            offset.snapTo(0f)
            delay(LOOP_DELAY_MS)
        }
    }
    val ramp = FADE_START * k
    val leftFade = if (scrolling) (minOf(offset.value, lap - offset.value) / ramp).coerceIn(0f, 1f) else 0f
    Box(
        modifier
            .onSizeChanged { boxWidth = it.width }
            .clipToBounds()
            .then(if (overflow) Modifier.fadeMask(stage, vertical = false, startClear = 0f, startSolid = FADE_START * leftFade, endSolid = FADE_END, endClear = 0f) else Modifier),
    ) {
        Row(
            Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .graphicsLayer { translationX = -offset.value },
        ) {
            BasicText(
                text, style = style, maxLines = 1, softWrap = false,
                onTextLayout = { textWidth = kotlin.math.ceil(it.multiParagraph.maxIntrinsicWidth).toInt() },
            )
            if (scrolling) {
                Spacer(Modifier.width(GAP.px))
                BasicText(text, style = style, maxLines = 1, softWrap = false)
            }
        }
    }
}
