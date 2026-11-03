package com.limelight.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.limelight.shell.ui.EaseOut
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.Glyph
import com.limelight.shell.ui.GlyphButton
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.glass
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.delay

/** Called with the help key of the popover row that gets the focus; the popover's footer shows its text. */
internal val LocalStreamHelp = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * One circle of the bar. [label] is also its help key. [on] is a toggle's state (filled with the accent and
 * "On" / "Off" under the label), null for actions; [state] replaces "On" / "Off" for toggles with more steps.
 */
@Immutable
internal class BarCircleSpec(
    val item: BarItem,
    val icon: String,
    val label: String,
    val on: Boolean? = null,
    val state: String? = null,
    val onClick: () -> Unit,
)

/** The soft legibility gradient under the bar: transparent at the top, a little over half black at the bottom edge. */
@Composable
internal fun StreamMenuGradient(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(StreamMenuLayout.GRADIENT_HEIGHT.px).background(
            Brush.verticalGradient(
                0f to Color.Transparent,
                .5f to Color.Black.copy(alpha = StreamMenuLayout.GRADIENT_MID_ALPHA),
                1f to Color.Black.copy(alpha = StreamMenuLayout.GRADIENT_BOTTOM_ALPHA),
            )
        )
    )
}

/**
 * The glass of every surface of the stream menu (the bar and the popover): a soft shadow, the palette's
 * background tint ([StreamMenuLayout.GLASS_ALPHA]) and a hairline border with a light top edge, all following [shape].
 * The stream is a separate window (a `SurfaceView`), so the shell's background blur cannot reach it: this is the
 * one place to change when the menu gets a blurring window of its own.
 */
@Composable
internal fun Modifier.menuGlass(shape: Shape): Modifier {
    val colors = LocalDelosColors.current
    val k = LocalStage.current.dpPerPx * LocalDensity.current.density
    return this
        .dropShadow(shape) {
            radius = 70f * k
            spread = -10f * k
            offset = Offset(0f, 18f * k)
            color = Color.Black
            alpha = .47f
        }
        .clip(shape)
        .glass(colors.bg.copy(alpha = StreamMenuLayout.GLASS_ALPHA))
        .border(1.px, Brush.verticalGradient(listOf(Color.White.copy(alpha = .28f), colors.line)), shape)
}

/**
 * The bottom bar (docs/GUI.md, "Stream menu"): the game's name and the button hints on the left, one row of
 * circles with one focus ring that glides between them, and under them the help area, which shows the complete
 * help of the circle. Always [StreamMenuLayout.BAR_HEIGHT] high. [entrance] runs the circles' staggered entrance.
 */
@Composable
internal fun StreamMenuBar(
    title: String,
    backLabel: String,
    circles: List<BarCircleSpec>,
    requesters: Map<BarItem, FocusRequester>,
    selected: BarItem?,
    initialItem: BarItem,
    entrance: Boolean,
    helpTitle: String,
    helpText: String,
    width: Float,
    onCircleFocused: (BarItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDelosColors.current
    var focusedItem by remember { mutableStateOf<BarItem?>(null) }
    Box(
        modifier
            .width(width.px)
            .height(StreamMenuLayout.BAR_HEIGHT.px)
            .menuGlass(RoundedCornerShape(StreamMenuLayout.BAR_CORNER.px))
            // A tap between the circles is not "outside"
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        FocusGlider(width, focusedItem ?: selected, focusedItem != null, initialItem, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = StreamMenuLayout.BAR_PADDING_X.px, end = StreamMenuLayout.BAR_PADDING_X.px, top = StreamMenuLayout.BAR_PADDING_TOP.px).height(StreamMenuLayout.CIRCLE_ROW_HEIGHT.px)) {
                Column(Modifier.width(StreamMenuLayout.TITLE_WIDTH.px).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    BasicText(
                        title,
                        style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.px)) {
                        BarHint(Glyph.A, "Select")
                        BarHint(Glyph.B, backLabel)
                    }
                }
                Box(Modifier.padding(horizontal = StreamMenuLayout.SEPARATOR_GAP.px).width(1.px).fillMaxHeight().background(colors.line))
                Row(Modifier.weight(1f)) {
                    circles.forEach { spec ->
                        BarCircle(
                            spec, requesters.getValue(spec.item), spec.item == selected, entrance,
                            onFocused = { item, focused ->
                                if (focused) {
                                    focusedItem = item
                                    onCircleFocused(item)
                                } else if (focusedItem == item) focusedItem = null
                            },
                            Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(StreamMenuLayout.HELP_GAP.px))
            Box(Modifier.padding(horizontal = StreamMenuLayout.BAR_PADDING_X.px).fillMaxWidth().height(1.px).background(colors.line))
            HelpArea(
                helpTitle, helpText,
                Modifier.fillMaxWidth().padding(horizontal = StreamMenuLayout.BAR_PADDING_X.px, vertical = StreamMenuLayout.HELP_PADDING_Y.px),
            )
        }
    }
}

@Composable
private fun BarHint(glyph: Glyph, label: String) {
    val colors = LocalDelosColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.px)) {
        GlyphButton(glyph, size = StreamMenuLayout.HINT_GLYPH_SIZE)
        BasicText(label, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx), maxLines = 1)
    }
}

/** Reports whether a pointer is down on the element, without consuming anything (taps still reach the element). */
private fun Modifier.trackPress(onChange: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onChange(true)
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
        } finally {
            onChange(false)
        }
    }
}

/**
 * A circle of the bar: a [StreamMenuLayout.CIRCLE_SIZE] glass circle, its label and, for toggles, the state under
 * it. The focused circle lifts and its label brightens (the ring is the bar's one [FocusGlider]); a press shrinks
 * it a little; on entrance it fades and scales in, each [StreamMenuLayout] circle 35 ms after the one before.
 * A label wider than its cell reaches into the free space beside it instead of being cut off.
 */
@Composable
private fun BarCircle(
    spec: BarCircleSpec,
    requester: FocusRequester,
    selected: Boolean,
    entrance: Boolean,
    onFocused: (BarItem, Boolean) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalDelosColors.current
    val reducedMotion = LocalReducedMotion.current
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val on = spec.on == true
    val press by animateFloatAsState(if (pressed) .95f else 1f, spring(dampingRatio = .6f, stiffness = Spring.StiffnessMedium), label = "circlePress")
    val appear = remember { Animatable(if (entrance) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (entrance) {
            delay(80L + spec.item.ordinal * 35L)
            appear.animateTo(1f, tween(320, easing = EaseOut))
        }
    }
    val labelColor by animateColorAsState(if (focused || selected) colors.text else colors.text2, tween(200), label = "circleLabel")
    Column(
        modifier.graphicsLayer {
            val s = if (reducedMotion) 1f else .85f + .15f * appear.value
            scaleX = s
            scaleY = s
            alpha = appear.value
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                }
                .trackPress { pressed = it }
                .focusRequester(requester)
                .focusEffects(spec.onClick, corner = 999f, focusScale = StreamMenuLayout.FOCUS_LIFT, ring = null, glow = null, onFocusChange = {
                    focused = it
                    onFocused(spec.item, it)
                })
                .size(StreamMenuLayout.CIRCLE_SIZE.px)
                .clip(CircleShape)
                .background(if (on) colors.accent else colors.surface3)
                .border(1.px, colors.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(spec.icon, 50.tpx, if (on) colors.onAccent else colors.text, filled = focused || on)
        }
        BasicText(
            spec.label,
            style = TextStyle(color = labelColor, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = StreamMenuLayout.CIRCLE_LABEL_SIZE.tpx, textAlign = TextAlign.Center),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(top = 14.px).wrapContentWidth(unbounded = true),
        )
        if (spec.on != null) {
            BasicText(
                spec.state ?: if (on) "On" else "Off",
                style = TextStyle(color = if (on) colors.accent else colors.text3, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = StreamMenuLayout.CIRCLE_STATE_SIZE.tpx, textAlign = TextAlign.Center),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(top = 2.px).wrapContentWidth(unbounded = true),
            )
        }
    }
}

/**
 * The bar's one focus ring: an accent ring with a gap and a soft glow that glides (a spring) to whichever circle
 * is [target] instead of popping up on it. While the focus is inside a popover ([active] false) it stays on the
 * circle that opened it, thinner in effect and without the lift; it is hidden when there is no [target]. With
 * reduced motion it only fades. Drawn behind the circles, so only the part outside them shows.
 */
@Composable
private fun FocusGlider(barWidth: Float, target: BarItem?, active: Boolean, initial: BarItem, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val k = stage.dpPerPx * LocalDensity.current.density
    val reducedMotion = LocalReducedMotion.current
    val frame = remember(barWidth) { StageRect(0f, 0f, barWidth, 0f) }
    val x = remember { Animatable(circleCenterX(frame, initial)) }
    // The ring stays on the circle it left when the focus is nowhere (it only fades)
    val last = remember { arrayOf(initial) }
    if (target != null) last[0] = target
    val resting = last[0]
    LaunchedEffect(resting, frame) {
        val goal = circleCenterX(frame, resting)
        if (reducedMotion) x.snapTo(goal) else x.animateTo(goal, spring(dampingRatio = .72f, stiffness = Spring.StiffnessMediumLow))
    }
    val visible = animateFloatAsState(if (target == null) 0f else if (active) 1f else .7f, tween(220, easing = EaseOut), label = "ringAlpha")
    val lift = animateFloatAsState(if (active) StreamMenuLayout.FOCUS_LIFT else 1f, tween(300, easing = EaseOut), label = "ringLift")
    Canvas(modifier) {
        val alpha = visible.value
        if (alpha <= 0.01f) return@Canvas
        val center = Offset(x.value * k, (StreamMenuLayout.BAR_PADDING_TOP + StreamMenuLayout.CIRCLE_SIZE / 2f) * k)
        val inner = StreamMenuLayout.CIRCLE_SIZE / 2f * lift.value * k
        val gap = StreamMenuLayout.RING_GAP * k
        val width = StreamMenuLayout.RING_WIDTH * k
        val glow = 44f * k
        val outer = inner + gap + width
        // The soft glow: strongest at the ring, fading outward
        drawCircle(
            Brush.radialGradient(
                0f to Color.Transparent,
                (outer - width) / (outer + glow) to colors.accent.copy(alpha = .30f * alpha),
                1f to Color.Transparent,
                center = center,
                radius = outer + glow,
            ),
            radius = outer + glow,
            center = center,
        )
        drawCircle(colors.bg.copy(alpha = .9f * alpha), radius = inner + gap / 2f, center = center, style = Stroke(gap))
        drawCircle(colors.accent.copy(alpha = alpha), radius = inner + gap + width / 2f, center = center, style = Stroke(width))
    }
}

/**
 * The help area of the bar: the focused circle's name and its complete help, wrapped over at most
 * [StreamMenuLayout.HELP_LINES] lines in a box that is always as tall as that many lines of the largest text
 * scale (the texts are checked against it: nothing scrolls, nothing is cut). A new text fades in.
 */
@Composable
private fun HelpArea(title: String, text: String, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val body = remember(text) { helpLineText(text) }
    AnimatedContent(
        targetState = title to body,
        modifier = modifier.height(StreamMenuLayout.HELP_TEXT_HEIGHT.px),
        contentAlignment = Alignment.CenterStart,
        transitionSpec = { fadeIn(tween(180, delayMillis = 50)) togetherWith fadeOut(tween(100)) },
        label = "barHelp",
    ) { (name, description) ->
        Row(Modifier.fillMaxWidth()) {
            BasicText(
                name,
                style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = StreamMenuLayout.HELP_TEXT_SIZE.tpx, lineHeight = StreamMenuLayout.HELP_LINE_HEIGHT.em),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width((StreamMenuLayout.TITLE_WIDTH + 2 * StreamMenuLayout.SEPARATOR_GAP + 1f).px),
            )
            BasicText(
                description,
                style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = StreamMenuLayout.HELP_TEXT_SIZE.tpx, lineHeight = StreamMenuLayout.HELP_LINE_HEIGHT.em),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
