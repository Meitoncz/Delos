package com.limelight.shell.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import com.limelight.shell.ContextMenu

/** The value side of a settings row. */
@Immutable
sealed interface RowValue {
    data class Toggle(val on: Boolean, val onChange: (Boolean) -> Unit) : RowValue

    /** Left/right (or A) cycles through the options. */
    data class Choice(val label: String, val onStep: (Int) -> Unit, val swatch: Color? = null) : RowValue

    data class Slider(val value: Int, val min: Int, val max: Int, val step: Int, val unit: String, val onChange: (Int) -> Unit) : RowValue

    /** Opens something (a screen, a dialog). */
    data class Action(val onClick: () -> Unit) : RowValue

    /** Read-only text. */
    data class Info(val text: String) : RowValue
}

/**
 * A settings row (`.row`): icon, label with an optional description, and the value. Used by the side
 * menu and the settings screen. Choice and slider rows are adjusted with left/right instead of moving
 * the focus, like in the prototype.
 */
@Composable
fun SettingRow(
    icon: String,
    label: String,
    value: RowValue,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    contextMenu: ContextMenu? = null,
    onFocused: (() -> Unit)? = null,
) {
    val colors = LocalDelosColors.current
    val feedback = LocalFeedback.current
    var focused by remember { mutableStateOf(false) }
    val adjust: ((Int) -> Unit)? = when (value) {
        is RowValue.Choice -> value.onStep
        is RowValue.Slider -> { dir -> value.onChange((value.value + dir * value.step).coerceIn(value.min, value.max)) }
        else -> null
    }
    val activate: () -> Unit = when (value) {
        is RowValue.Toggle -> ({ value.onChange(!value.on) })
        is RowValue.Choice -> ({ value.onStep(1) })
        is RowValue.Slider -> ({ adjust?.invoke(1) })
        is RowValue.Action -> value.onClick
        is RowValue.Info -> ({})
    }
    val shape = RoundedCornerShape(24.px)
    Row(
        modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                val dir = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> 0
                }
                if (dir == 0 || adjust == null || !enabled) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    feedback.play(Cue.MOVE)
                    adjust(dir)
                }
                true
            }
            .focusEffects(
                onClick = { if (enabled) activate() else feedback.play(Cue.ERROR) },
                corner = 24f,
                focusScale = 1.02f,
                ring = FocusRing(width = 3f, gap = 0f),
                glow = FocusGlow(offsetY = 20f, blur = 50f, spread = -24f),
                clickCue = if (enabled && value !is RowValue.Info) (if (value is RowValue.Action) Cue.OPEN else Cue.SELECT) else null,
                contextMenu = contextMenu,
                adjustable = adjust != null && enabled,
                onFocusChange = {
                    focused = it
                    if (it) onFocused?.invoke()
                },
            )
            .clip(shape)
            .background(if (focused) colors.surface2 else Color.Transparent)
            .defaultMinSize(minHeight = if (compact) 80.px else 92.px)
            .padding(horizontal = 26.px, vertical = 14.px)
            .alpha(if (enabled) 1f else .45f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.px),
    ) {
        Box(Modifier.width(if (compact) 44.px else 52.px), contentAlignment = Alignment.CenterStart) {
            Symbol(icon, 34.tpx, if (focused) colors.accent else colors.text3, filled = focused)
        }
        Column(Modifier.weight(1f)) {
            BasicText(label, style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 26.tpx))
            if (description != null) {
                BasicText(
                    description,
                    style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 19.tpx, lineHeight = 1.35.em),
                    modifier = Modifier.padding(top = 4.px),
                )
            }
        }
        RowValueView(value, focused)
    }
}

@Composable
private fun RowValueView(value: RowValue, focused: Boolean) {
    val colors = LocalDelosColors.current
    val text = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 23.tpx)
    when (value) {
        is RowValue.Toggle -> Switch(value.on)
        // The arrows are always shown (muted when not focused), so all values end at the same edge
        is RowValue.Choice -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
            val arrows = if (focused) colors.accent else colors.text3
            Symbol(Symbols.CHEVRON_LEFT, 30.tpx, arrows)
            if (value.swatch != null) Box(Modifier.size(26.px).clip(CircleShape).background(value.swatch))
            BasicText(value.label, style = text)
            // The glyph has empty space on its right; shift it so its visible edge lines up with switches and values
            Symbol(Symbols.CHEVRON_RIGHT, 30.tpx, arrows, Modifier.offset(x = 9.px))
        }
        is RowValue.Slider -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
            val fraction by animateFloatAsState(
                (value.value - value.min) / (value.max - value.min).coerceAtLeast(1).toFloat(), tween(250, easing = EaseOut), label = "slider",
            )
            Box(Modifier.size(220.px, 10.px).clip(CircleShape).background(colors.surface3)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(CircleShape).background(colors.accent))
            }
            BasicText("${value.value}${value.unit}", style = text.copy(textAlign = TextAlign.End), modifier = Modifier.width(120.px))
        }
        is RowValue.Action -> Symbol(Symbols.CHEVRON_RIGHT, 30.tpx, colors.text2)
        is RowValue.Info -> BasicText(value.text, style = text)
    }
}

/** The on/off switch (`.switch`). */
@Composable
fun Switch(on: Boolean) {
    val colors = LocalDelosColors.current
    val knob by animateFloatAsState(if (on) 1f else 0f, tween(380, easing = EaseSpring), label = "switch")
    Box(
        Modifier
            .size(74.px, 42.px)
            .clip(CircleShape)
            .background(if (on) colors.accent else colors.surface3)
            .border(1.px, colors.line, CircleShape),
    ) {
        Box(
            Modifier
                .offset(x = (6f + 32f * knob).px, y = 5.px)
                .size(30.px)
                .clip(CircleShape)
                .background(if (on) colors.onAccent else colors.text2),
        )
    }
}
