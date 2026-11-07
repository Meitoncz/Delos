package com.limelight.shell.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import com.limelight.shell.ContextMenu
import com.limelight.shell.settings.SettingImpact

/** The value side of a settings row. */
@Immutable
sealed interface RowValue {
    data class Toggle(val on: Boolean, val onChange: (Boolean) -> Unit) : RowValue

    /** Left/right (or A) cycles through the options. */
    data class Choice(val label: String, val onStep: (Int) -> Unit, val swatch: Color? = null) : RowValue

    data class Slider(val value: Int, val min: Int, val max: Int, val step: Int, val unit: String, val divisor: Int = 1, val onChange: (Int) -> Unit) : RowValue

    data class Text(val text: String, val onClick: () -> Unit) : RowValue

    /** Opens something (a screen, a dialog). */
    data class Action(val onClick: () -> Unit) : RowValue

    /** Read-only text. */
    data class Info(val text: String) : RowValue
}

/**
 * How many steps one key repeat moves a slider: one at first, then more the longer the key is held,
 * up to a fortieth of the slider's range (so the bitrate's 1000 steps cross in about a second of
 * holding, while a slider of a few steps never jumps).
 */
internal fun sliderAcceleration(slider: RowValue.Slider, repeatCount: Int): Int {
    val steps = (slider.max - slider.min) / slider.step
    val wanted = when {
        repeatCount < 8 -> 1
        repeatCount < 20 -> 5
        repeatCount < 35 -> 10
        else -> 50
    }
    return wanted.coerceAtMost(maxOf(1, steps / 40))
}

/**
 * A settings row (`.row`): icon, label with an optional description, and the value. Used by the side
 * menu and the settings screen. Choice and slider rows are adjusted with left/right instead of moving
 * the focus, like in the prototype. [impact] (cost-bearing settings only) adds three small GPU / latency / network chips
 * after the name, without changing the row's height.
 */
@Composable
fun SettingRow(
    icon: String,
    label: String,
    value: RowValue,
    modifier: Modifier = Modifier,
    description: String? = null,
    descriptionColor: Color? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    contextMenu: ContextMenu? = null,
    override: RowOverride? = null,
    onFocused: (() -> Unit)? = null,
    impact: SettingImpact? = null,
) {
    val colors = LocalDelosColors.current
    val feedback = LocalFeedback.current
    // Inside a RowGlideHost the entire focus plate (tint, ring and glow) belongs to the host, never to a row.
    // Outside a list host, an isolated row keeps its own quick ring, glow and immediate frosted glass.
    val glide = LocalRowGlide.current
    val reduced = LocalReducedMotion.current
    val calm = glide != null && !reduced
    var focused by remember { mutableStateOf(false) }
    val choice = rememberChoiceMotion()
    val adjust: ((Int) -> Unit)? = when (value) {
        is RowValue.Choice -> { dir ->
            choice.step(dir)
            value.onStep(dir)
        }
        // Steps left or right ([dir] may be several while a key is held), landing on the slider's grid
        // (a stored value between steps snaps to it)
        is RowValue.Slider -> { dir ->
            val next = value.value + dir * value.step
            val snapped = value.min + Math.round((next - value.min).toFloat() / value.step) * value.step
            value.onChange(snapped.coerceIn(value.min, value.max))
        }
        else -> null
    }
    val activate: () -> Unit = when (value) {
        is RowValue.Toggle -> ({ value.onChange(!value.on) })
        is RowValue.Choice -> ({ adjust?.invoke(1) })
        is RowValue.Slider -> ({ adjust?.invoke(1) })
        is RowValue.Action -> value.onClick
        is RowValue.Text -> value.onClick
        is RowValue.Info -> ({})
    }
    val controllerAdjust: ((Int, Int) -> Unit)? = if (adjust == null || !enabled) null else { direction, repeatCount ->
        feedback.play(Cue.MOVE)
        adjust(direction * if (value is RowValue.Slider) sliderAcceleration(value, repeatCount) else 1)
    }
    val shape = RoundedCornerShape(ROW_CORNER.px)
    // The entrance is the content's (icon, label, value), not the row's: the focus ring and the glass of the row stay put
    // and fully visible from the first frame (the glass is a blur that would not fade with the row)
    val stagger = LocalEntranceStagger.current
    val entrance = remember { stagger.take() }
    Box(
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
                    adjust(dir * if (value is RowValue.Slider) sliderAcceleration(value, event.nativeKeyEvent.repeatCount) else 1)
                }
                true
            }
            .rowGlideTarget(focused)
            .focusEffects(
                onClick = { if (enabled) activate() else feedback.play(Cue.ERROR) },
                corner = ROW_CORNER,
                focusScale = ROW_FOCUS_SCALE,
                ring = if (glide != null) null else ROW_RING,
                glow = if (glide != null) null else FocusGlow(offsetY = 20f, blur = 50f, spread = -24f),
                clickCue = if (enabled && value !is RowValue.Info) (if (value is RowValue.Action) Cue.OPEN else Cue.SELECT) else null,
                contextMenu = contextMenu,
                adjustable = adjust != null && enabled,
                controllerAdjust = controllerAdjust,
                focusMillis = if (glide != null) Motion.ROW_FOCUS else Motion.FOCUS,
                onFocusChange = {
                    focused = it
                    if (it) onFocused?.invoke()
                },
            )
            .clip(shape),
    ) {
        // Hosted rows never create a glass layer, including while their content animates out of focus.
        // Outside a host the glass still appears/disappears immediately, without a blur-layer alpha fade.
        if (glide == null && focused) {
            Box(
                Modifier.matchParentSize()
                    .then(LocalBackdropHaze.current?.let { Modifier.backdropBlur(it, 8f, colors.surface2) } ?: Modifier.background(colors.surface2))
            )
        }
        // A game setting that differs from the profile: a violet tint with a faint ring (under the content, over the glide plate)
        if (override != null) {
            val k = stageUnit()
            val tint = colors.accent3
            Box(
                Modifier.matchParentSize().drawBehind {
                    drawRect(Brush.horizontalGradient(0f to tint.copy(alpha = .20f), .7f to tint.copy(alpha = .05f), 1f to tint.copy(alpha = .05f)))
                    val line = 1f * k
                    drawRoundRect(
                        tint.copy(alpha = .28f), topLeft = Offset(line / 2f, line / 2f), size = androidx.compose.ui.geometry.Size(size.width - line, size.height - line),
                        cornerRadius = CornerRadius(ROW_CORNER * k), style = Stroke(line),
                    )
                }
            )
        }
        Row(
            Modifier.fillMaxWidth()
                .defaultMinSize(minHeight = if (compact) 80.px else if (impact != null) IMPACT_ROW_MIN_HEIGHT.px else 92.px)
                .padding(horizontal = 26.px, vertical = 14.px)
                .alpha(if (enabled) 1f else .45f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.px),
        ) {
            Box(Modifier.width(if (compact) 44.px else 52.px).staggerEntrance(entrance), contentAlignment = Alignment.CenterStart) {
                RowIcon(icon, focused, calm)
            }
            Column(Modifier.weight(1f).staggerEntrance(entrance)) {
                // Cost-bearing rows: the chips on their own line above the title, on the same left edge; the title keeps the full width
                if (impact != null) ImpactChips(impact, focused, Modifier.padding(bottom = 2.px))
                BasicText(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 26.tpx))
                if (override != null) {
                    Row(Modifier.padding(top = 4.px), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.px)) {
                        CustomBadge()
                        BasicText(
                            override.profileLine, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 19.tpx, lineHeight = 1.35.em),
                        )
                    }
                } else if (description != null) {
                    BasicText(
                        description,
                        style = TextStyle(color = descriptionColor ?: colors.text3, fontFamily = Figtree, fontSize = 19.tpx, lineHeight = 1.35.em),
                        modifier = Modifier.padding(top = 4.px),
                    )
                }
            }
            Row(Modifier.staggerEntrance(entrance), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.px)) {
                // Touch: a round button that is always there and always takes its room (52 px + the gap)
                if (override != null && override.touch) RowResetButton(override, focused && enabled)
                Box {
                    RowValueView(value, focused, choice, calm)
                    // Controller: the pill takes no room at all. It is drawn over the free space left of the value, only while the row is focused,
                    // so the title column never changes its width (and never re-wraps) when focus arrives
                    if (override != null && !override.touch) {
                        val resetGap = 14.px
                        Box(Modifier.align(Alignment.CenterStart).layout { measurable, constraints ->
                            val pill = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                            layout(0, pill.height) { pill.place(-pill.width - resetGap.roundToPx(), 0) }
                        }) { RowResetButton(override, focused && enabled) }
                    }
                }
            }
        }
    }
}

/**
 * The marker of a per-game setting that differs from the profile: [SettingRow] tints the row, shows a CUSTOM badge and [profileLine]
 * (what the profile has) instead of its description, and offers a reset to the profile value. [touch]: the reset is a round button
 * on every such row; otherwise a pill with the X glyph that fades in on the focused row (the controller's X resets it too).
 */
@Immutable
data class RowOverride(val profileLine: String, val touch: Boolean, val onReset: () -> Unit)

@Composable
private fun CustomBadge() {
    val colors = LocalDelosColors.current
    Row(
        Modifier.height(26.px).clip(CircleShape).background(colors.accent3).padding(start = 7.px, end = 10.px),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.px),
    ) {
        Symbol(Symbols.TUNE, 17.tpx, colors.onAccent3, filled = true)
        BasicText("CUSTOM", style = TextStyle(color = colors.onAccent3, fontFamily = Figtree, fontWeight = FontWeight.ExtraBold, fontSize = 14.tpx, letterSpacing = .06.em))
    }
}

/** The reset of a custom row: fades and pops in (a spring) while the row is focused, always shown for touch; reduced motion only fades. */
@Composable
private fun RowResetButton(override: RowOverride, focused: Boolean) {
    val colors = LocalDelosColors.current
    val reduced = LocalReducedMotion.current
    val visible = override.touch || focused
    val shown by animateFloatAsState(if (visible) 1f else 0f, tween(Motion.FADE, easing = Motion.Ease), label = "rowReset")
    val pop by animateFloatAsState(if (visible) 1f else 0f, if (reduced) snap() else tween(Motion.PILL_POP, easing = EaseSpring), label = "rowResetPop")
    val reset by rememberUpdatedState(override.onReset)
    val tint = colors.accent3
    val label = "Reset to profile value"
    Row(
        Modifier.graphicsLayer { alpha = shown; (.9f + .1f * pop).let { if (reduced) 1f else it }.let { scaleX = it; scaleY = it } }
            .clip(CircleShape).background(tint.copy(alpha = .22f)).border(1.px, tint.copy(alpha = .5f), CircleShape)
            .then(
                if (visible) Modifier.pointerInput(Unit) { detectTapGestures(onTap = { reset() }) }
                    .semantics { role = Role.Button; onClick(label) { reset(); true } }
                else Modifier
            )
            .then(if (override.touch) Modifier.size(52.px) else Modifier.height(46.px).padding(start = 16.px, end = 8.px)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (override.touch) Arrangement.Center else Arrangement.spacedBy(10.px),
    ) {
        if (override.touch) Symbol(Symbols.RESET, 28.tpx, tint)
        else {
            Symbol(Symbols.RESET, 24.tpx, tint)
            BasicText("Reset", style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 19.tpx))
            GlyphButton(Glyph.Letter("X"), 30)
        }
    }
}

/** Every cost-bearing row reserves the chips line (about 28 px) on top of the 92 px of a row with a second line, so a list's rows with chips are one height. */
private const val IMPACT_ROW_MIN_HEIGHT = 120

/** The look of a focused settings row, shared by [SettingRow] and the focus plate of a [SettingRowList]. */
private const val ROW_CORNER = 24f
private const val ROW_FOCUS_SCALE = 1.02f
private val ROW_RING = FocusRing(width = 3f, gap = 0f)

/**
 * A scrolling list of [SettingRow]s (settings, per-game settings, debug info): one focus plate (tint, ring and glow)
 * glides from row to row ([RowGlideHost], [Motion.ROW_FOCUS]) and follows the scrolling list. [modifier] is the list's:
 * its size and [fadeEdges] (the plate fades with the rows). The list inside should fill it.
 */
@Composable
fun SettingRowList(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    RowGlideHost(ROW_RING, ROW_CORNER, ROW_FOCUS_SCALE, modifier, content = content)
}

/** The row's icon: full accent on the focused row, softer on the others; the color cross-fades with the row's other focus effects while [calm]. */
@Composable
private fun RowIcon(icon: String, focused: Boolean, calm: Boolean) {
    val colors = LocalDelosColors.current
    // The palette's accent: full on the focused row, softer on the others
    val color by animateColorAsState(
        if (focused) colors.accent2 else colors.accent2.copy(alpha = .62f),
        if (calm) tween(Motion.ROW_FOCUS, easing = Motion.Ease) else snap(),
        label = "rowIcon",
    )
    Symbol(icon, 34.tpx, color, filled = focused)
}

@Composable
private fun RowValueView(value: RowValue, focused: Boolean, choice: ChoiceMotion, calm: Boolean) {
    val colors = LocalDelosColors.current
    val reduced = LocalReducedMotion.current
    val text = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 23.tpx)
    // The arrows cross-fade with the rest of the row's focus effects while it is calm; a choice's arrows always fade
    val arrowColor = animateColorAsState(if (focused) colors.accent2 else colors.text3, tween(if (calm) Motion.ROW_FOCUS else Motion.FADE, easing = Motion.Ease), label = "chevron")
    val staticArrows = if (focused) colors.accent2 else colors.text3
    when (value) {
        is RowValue.Toggle -> Switch(value.on)
        // The arrows are always shown (muted when not focused), so all values end at the same edge
        is RowValue.Choice -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
            val arrows by arrowColor
            Symbol(Symbols.CHEVRON_LEFT, 30.tpx, arrows, Modifier.chevronBump(choice, -1))
            // A new value slides in from the side the key came from
            Row(Modifier.valueSlide(choice), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
                if (value.swatch != null) Box(Modifier.size(26.px).clip(CircleShape).background(value.swatch))
                MarqueeText(value.label, style = text, active = focused, modifier = Modifier.widthIn(max = 360.px))
            }
            // The glyph has empty space on its right; shift it so its visible edge lines up with switches and values
            Symbol(Symbols.CHEVRON_RIGHT, 30.tpx, arrows, Modifier.offset(x = 9.px).chevronBump(choice, 1))
        }
        is RowValue.Slider -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
            val onChange by rememberUpdatedState(value.onChange)
            var dragging by remember { mutableStateOf<Int?>(null) }
            val number = dragging ?: value.value
            val fraction by animateFloatAsState(
                ((number - value.min) / (value.max - value.min).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f), tween(if (dragging == null && !reduced) Motion.SLIDER_FILL else 0, easing = EaseOut), label = "slider",
            )
            val knobScale by animateKnobScale(focused)
            val k = stageUnit()
            Box(Modifier.size(220.px, 48.px).drawWithContent {
                drawContent()
                // The knob of the slider grows while its row is focused, inside a soft halo
                val radius = 14f * k * knobScale
                val center = Offset(size.width * fraction, size.height / 2f)
                if (focused) drawCircle(colors.accent3.copy(alpha = .40f), radius = radius + 6f * k * knobScale, center = center)
                drawCircle(Color.Black.copy(alpha = .30f), radius = radius, center = center + Offset(0f, 3f * k))
                drawCircle(colors.text, radius = radius, center = center)
            }.pointerInput(value.min, value.max, value.step) {
                awaitEachGesture {
                    fun setAt(x: Float) {
                        val raw = value.min + (value.max - value.min) * (x / size.width).coerceIn(0f, 1f)
                        val snapped = kotlin.math.round((raw - value.min) / value.step).toInt() * value.step + value.min
                        dragging = snapped.coerceIn(value.min, value.max)
                    }
                    val down = awaitFirstDown()
                    down.consume()
                    try {
                        setAt(down.position.x)
                        drag(down.id) { change -> change.consume(); setAt(change.position.x) }
                        dragging?.let(onChange)
                    } finally { dragging = null }
                }
            }, contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().size(height = 10.px, width = 220.px).clip(CircleShape).background(colors.surface3)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(CircleShape).background(Brush.horizontalGradient(listOf(colors.accent, colors.accent2))))
                }
            }
            val label = if (number % value.divisor == 0) (number / value.divisor).toString() else String.format(java.util.Locale.US, "%.1f", number.toDouble() / value.divisor)
            BasicText("$label${value.unit}", style = text.copy(textAlign = TextAlign.End), maxLines = 1, modifier = Modifier.width(160.px))
        }
        is RowValue.Action -> Symbol(Symbols.CHEVRON_RIGHT, 30.tpx, if (calm) arrowColor.value else staticArrows)
        is RowValue.Text -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
            MarqueeText(value.text.ifEmpty { "Not set" }, style = text, active = focused, modifier = Modifier.widthIn(max = 220.px))
            Symbol(Symbols.CHEVRON_RIGHT, 30.tpx, if (calm) arrowColor.value else staticArrows)
        }
        is RowValue.Info -> BasicText(value.text, style = text)
    }
}

/** The on/off switch (`.switch`): the shared animated switch of the stream menu, with the settings' veil and outline. */
@Composable
fun Switch(on: Boolean) {
    val colors = LocalDelosColors.current
    MotionSwitch(on, 74f, 42f, colors.surface3, border = colors.line)
}
