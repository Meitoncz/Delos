package com.limelight.shell.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.em
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LowRed
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx

/*
 * The touch building blocks of the companion screen (variant A of the approved prototype): glass cards, big buttons, the label
 * and the slider. Everything is at least 64 stage px high and every text at least 28 stage px: a thumb on a small panel.
 */

/** The smallest text and the smallest touch target of the companion, in stage pixels. */
internal const val MIN_TEXT = 28
internal const val MIN_TARGET = 64

/** The look of a [CompanionButton]. */
internal enum class ButtonStyle { PRIMARY, SECONDARY, NORMAL, DANGER }

internal val Radius = 22

@Composable
internal fun Modifier.card(corner: Int = Radius): Modifier {
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(corner.px)
    return clip(shape).background(colors.surface).border(1.px, colors.line, shape)
}

/** A big touch button: [icon] and [label] centred (or at the start with [start]); [selected] gives it the accent outline. */
@Composable
internal fun CompanionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.NORMAL,
    icon: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    textSize: Int = 32,
    maxLines: Int = 1,
) {
    val colors = LocalDelosColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (fill, content, line) = when (style) {
        ButtonStyle.PRIMARY -> Triple(colors.accent, colors.onAccent, colors.accent)
        ButtonStyle.SECONDARY -> Triple(colors.accent2.copy(alpha = .14f), colors.accent2, colors.accent2.copy(alpha = .45f))
        ButtonStyle.DANGER -> Triple(LowRed.copy(alpha = .13f), LowRed, LowRed.copy(alpha = .5f))
        ButtonStyle.NORMAL -> Triple(colors.surface2, colors.text, colors.line)
    }
    val shape = RoundedCornerShape(18.px)
    Row(
        modifier
            .heightIn(min = MIN_TARGET.px)
            .padding(top = if (pressed) 2.px else 0.px)
            .clip(shape)
            .background(if (pressed) Brush.linearGradient(listOf(fill.copy(alpha = fill.alpha * .8f), fill.copy(alpha = fill.alpha * .8f))) else Brush.linearGradient(listOf(fill, fill)))
            .border((if (selected) 3 else 1).px, if (selected) colors.accent else line, shape)
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.px),
        horizontalArrangement = Arrangement.spacedBy(14.px, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Symbol(icon, (textSize + 6).tpx, content.copy(alpha = if (enabled) 1f else .4f))
        BasicText(
            label,
            style = TextStyle(color = content.copy(alpha = if (enabled) 1f else .4f), fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = textSize.tpx, textAlign = TextAlign.Center),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The small caps line over a block (`.eyebrow`): letter-spaced, [text3], never below [MIN_TEXT]. */
@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = LocalDelosColors.current.text3) {
    BasicText(
        text.uppercase(),
        modifier,
        style = TextStyle(color = color, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = MIN_TEXT.tpx, letterSpacing = .12.em),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun Body(text: String, modifier: Modifier = Modifier, color: Color = LocalDelosColors.current.text2, size: Int = MIN_TEXT, maxLines: Int = 1, weight: FontWeight = FontWeight.Normal, align: TextAlign = TextAlign.Start) {
    BasicText(
        text,
        modifier,
        style = TextStyle(color = color, fontFamily = Figtree, fontWeight = weight, fontSize = size.tpx, textAlign = align, fontFeatureSettings = "tnum"),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A rounded label (genre, HLTB, HDR): [icon] and [text] on a glass pill. */
@Composable
internal fun InfoPill(text: String, icon: String? = null, modifier: Modifier = Modifier, color: Color = LocalDelosColors.current.text2) {
    val colors = LocalDelosColors.current
    Row(
        modifier.clip(CircleShape).background(colors.surface).border(1.px, colors.line, CircleShape).padding(horizontal = 22.px, vertical = 10.px),
        horizontalArrangement = Arrangement.spacedBy(10.px),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Symbol(icon, 32.tpx, color)
        Body(text, color = color)
    }
}

/**
 * A horizontal slider for a thumb: a track [height] stage px high that follows a drag and a tap, with a round knob.
 * [onChange] gets every value while dragging (snapped to [step]); [onCommit] the final one.
 */
@Composable
internal fun TouchSlider(value: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit, onCommit: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = LocalDelosColors.current
    var size = remember { IntSize.Zero }
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val current by rememberUpdatedState(value)
    fun at(x: Float): Int = snapSliderValue(x / size.width.coerceAtLeast(1), min, max, step)
    val fraction = ((value - min).toFloat() / (max - min).coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(
        modifier
            .height(MIN_TARGET.px + 16.px)
            .fillMaxWidth()
            .onSizeChanged { size = it }
            .pointerInput(enabled, min, max, step) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val next = at(offset.x)
                    change(next)
                    commit(next)
                }
            }
            .pointerInput(enabled, min, max, step) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { change(at(it.x)) },
                    onDragEnd = { commit(current) },
                    onDragCancel = { commit(current) },
                ) { pointer, _ -> change(at(pointer.position.x)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(14.px).clip(CircleShape).background(colors.surface3))
        Box(Modifier.fillMaxWidth(fraction).height(14.px).clip(CircleShape).background(colors.accent))
        val knob = 52.px
        Box(
            Modifier
                .offset(x = with(androidx.compose.ui.platform.LocalDensity.current) { (size.width * fraction).toDp() } - knob / 2)
                .size(knob)
                .clip(CircleShape)
                .background(colors.accent)
                .border(5.px, colors.bg, CircleShape),
        )
    }
}

/** The value of a slider track a finger at [fraction] (0..1) of its width stands for, snapped to [step] and held in [min]..[max]. */
internal fun snapSliderValue(fraction: Float, min: Int, max: Int, step: Int): Int {
    val raw = min + fraction.coerceIn(0f, 1f) * (max - min)
    val snapped = min + Math.round((raw - min) / step) * step
    return snapped.coerceIn(min, max)
}

/** A thumb-sized stepper: [-] value [+]. */
@Composable
internal fun Stepper(onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.px), verticalAlignment = Alignment.CenterVertically) {
        CompanionButton("\u2212", onMinus, Modifier.size(MIN_TARGET.px + 16.px), textSize = 40)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
        CompanionButton("+", onPlus, Modifier.size(MIN_TARGET.px + 16.px), textSize = 40)
    }
}

/** A key that reports the finger going down and coming up (the host needs both). */
@Composable
internal fun PressKey(
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    accent: Boolean = false,
    content: @Composable (pressed: Boolean) -> Unit,
) {
    val colors = LocalDelosColors.current
    val pressedState = remember { androidx.compose.runtime.mutableStateOf(false) }
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    val shape = RoundedCornerShape(14.px)
    val pressed = pressedState.value
    Box(
        modifier
            .heightIn(min = MIN_TARGET.px)
            .clip(shape)
            .background(
                when {
                    pressed -> colors.accent
                    accent || selected -> colors.accent.copy(alpha = .22f)
                    else -> colors.surface2
                },
            )
            .border((if (selected) 3 else 1).px, if (selected) colors.accent else colors.line, shape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressedState.value = true
                    down()
                    try {
                        tryAwaitRelease()
                    } finally {
                        pressedState.value = false
                        up()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) { content(pressed) }
}
