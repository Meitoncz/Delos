package com.limelight.shell.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.limelight.shell.Toast
import com.limelight.utils.ToastKind
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

// ---- The console toast (docs/GUI.md, "Toasts"; the approved prototype B) ----
// One card sliding in from the top right edge: a solid icon block in the colour of its kind, a small word (INFO / DONE /
// WARNING / ERROR / UNDO), the text (two lines at most) and, for an action, the Y chip. Everything is a pure function of one
// clock that ticks while a toast is on the stage; it is read in the draw phase (graphicsLayer / drawBehind), so nothing
// recomposes per frame, there is no blur (the stream is a SurfaceView Compose cannot sample) and no brush or path is built
// while it animates. With reduced motion only the fades remain.

private val CardTop = Color(0xFF17223C)
private val CardBottom = Color(0xFF0E1628)
private val CardText = Color(0xFFEEF3FB)
private val CardRing = Color(0x2EA0B9E6)

/** Success, warning and error are fixed semantic colours; info and Undo follow the palette (cyan, indigo). */
private class KindColors(val fill: Color, val on: Color)

private val Mint = KindColors(Color(0xFF5EE0A0), Color(0xFF04261A))
private val Orange = KindColors(Color(0xFFFF9D48), Color(0xFF2E1500))
private val Coral = KindColors(Color(0xFFFF6577), Color(0xFF33060D))

private fun kindColors(kind: ToastKind, colors: DelosColors): KindColors = when (kind) {
    ToastKind.INFO -> KindColors(colors.accent2, hsl(colors.accent2Hue, 85f, 9f))
    ToastKind.SUCCESS -> Mint
    ToastKind.WARNING -> Orange
    ToastKind.ERROR -> Coral
    ToastKind.ACTION -> KindColors(hsl(colors.accent3Hue, 100f, 80f), hsl(colors.accent3Hue, 60f, 15f))
}

/** Sizes of the toast: every number of the prototype is `N * interface scale` stage pixels. */
private class ToastMetrics(stage: StageMetrics, density: Float, val fontScale: Float) {
    val dpPerU = stage.dpPerPx * stage.textScale
    val pxPerU = dpPerU * density
    val pxPerStage = stage.dpPerPx * density
    val scale = stage.textScale
    fun dp(units: Float): Dp = (units * dpPerU).dp
    fun sp(units: Float) = (units * dpPerU / fontScale).sp
}

private const val CARD_RADIUS = 20f

/**
 * The toast: a console notification at the top right, 112 stage pixels from the top (below the shell's status bar) and 44 from
 * the right edge. A newer toast replaces the one on the stage (never a queue): the old card leaves at once, the new one enters
 * [Motion.ROW_DELAY] later. A toast leaves [Toast.durationMs] after it started to enter (the timer line along the bottom edge
 * shows what is left); one with an action stays for the Undo window and runs it on a tap. [actionPress] counts presses of the
 * action by the controller (Y), so the chip squeezes before the card leaves. [onHidden] is told when a toast's time is up.
 * The host never takes focus; it is also the stream overlay's content, where nothing can be pressed.
 */
@Composable
fun ToastHost(toast: Toast?, onHidden: (Int) -> Unit, onAction: () -> Unit, actionPress: Int = 0) {
    val reduced = LocalReducedMotion.current
    val logic = remember { ToastStageLogic<Toast>() }
    var slots by remember { mutableStateOf(emptyList<ToastSlot<Toast>>()) }
    val clock = remember { mutableLongStateOf(0L) }
    val hidden by rememberUpdatedState(onHidden)
    val currentId by rememberUpdatedState(toast?.id)
    val runAction by rememberUpdatedState(onAction)
    val seenPress = remember { intArrayOf(actionPress) }

    LaunchedEffect(toast?.id, actionPress) {
        if (actionPress != seenPress[0]) {
            seenPress[0] = actionPress
            logic.press(nowMs())
            slots = logic.slots()
            delay(Motion.PRESS_HOLD)
        }
        val now = nowMs()
        if (toast != null) logic.show(toast, toast.durationMs, now) else logic.dismiss(now)
        slots = logic.slots()
    }
    // The clock only runs while something is on the stage: an idle host costs nothing
    LaunchedEffect(slots.isNotEmpty()) {
        if (slots.isEmpty()) return@LaunchedEffect
        while (true) {
            val now = withFrameNanos { it } / 1_000_000L
            clock.longValue = now
            if (logic.advance(now) { hidden(it.payload.id) }) {
                slots = logic.slots()
                if (slots.isEmpty()) break
            }
        }
    }
    if (slots.isEmpty()) return

    val stage = LocalStage.current
    val density = LocalDensity.current
    val metrics = remember(stage, density.density, density.fontScale) { ToastMetrics(stage, density.density, density.fontScale) }
    val colors = LocalDelosColors.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = 112.px, end = 44.px), contentAlignment = Alignment.TopEnd) {
        val textMaxPx = toastTextMaxWidth(constraints.maxWidth / metrics.pxPerStage + 44f, metrics.scale) * metrics.pxPerStage
        val latest = slots.last()
        slots.forEach { slot ->
            key(slot) {
                ToastCard(slot, clock, metrics, colors, textMaxPx, reduced, isLatest = slot === latest) {
                    if (slot.payload.id == currentId && clock.longValue >= slot.startMs) runAction()
                }
            }
        }
    }
}

private fun nowMs(): Long = System.nanoTime() / 1_000_000L

@Composable
private fun ToastCard(
    slot: ToastSlot<Toast>,
    clock: LongState,
    m: ToastMetrics,
    colors: DelosColors,
    textMaxPx: Float,
    reduced: Boolean,
    isLatest: Boolean,
    onTap: () -> Unit,
) {
    val toast = slot.payload
    val kind = toast.kind
    val look = remember(kind, colors) { kindColors(kind, colors) }
    val action = toast.action
    val shape = remember(m) { RoundedCornerShape(m.dp(CARD_RADIUS)) }
    val gold = colors.accent
    val goldLabel = remember(gold) { lerp(gold, Color.White, .45f) }

    // Text: at most two lines, then an ellipsis; the card is as wide as the widest line, like the prototype
    val textStyle = remember(m) {
        TextStyle(color = CardText, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = m.sp(25f), lineHeight = 1.28.em, letterSpacing = .005.em, lineBreak = LineBreak.Heading)
    }
    val measurer = rememberTextMeasurer()
    val textWidthPx = remember(toast.id, textStyle, textMaxPx) {
        val layout = measurer.measure(
            AnnotatedString(toast.text), textStyle, TextOverflow.Ellipsis, softWrap = true, maxLines = TOAST_MAX_LINES,
            constraints = Constraints(maxWidth = textMaxPx.toInt().coerceAtLeast(1)),
        )
        var widest = 0f
        for (line in 0 until layout.lineCount) widest = max(widest, layout.getLineRight(line) - layout.getLineLeft(line))
        min(textMaxPx, ceil(widest) + 2f)
    }
    val eyebrowStyle = remember(m, look) {
        TextStyle(color = look.fill, fontFamily = Figtree, fontWeight = FontWeight.ExtraBold, fontSize = m.sp(16f), letterSpacing = .18.em)
    }
    val density = LocalDensity.current
    val cardBrush = remember { Brush.verticalGradient(listOf(CardTop, CardBottom)) }
    val sweepBrush = remember(m) {
        val band = 190f * m.pxPerStage
        Brush.linearGradient(
            0f to Color.Transparent, .45f to Color.White.copy(alpha = .20f), .5f to Color.White.copy(alpha = .34f),
            .55f to Color.White.copy(alpha = .20f), 1f to Color.Transparent,
            start = Offset.Zero, end = Offset(band, band * tan(Math.toRadians(12.0)).toFloat()),
        )
    }
    val lineBrush = remember(gold) { Brush.horizontalGradient(listOf(lerp(gold, Color(0xFFB87800), .6f), gold)) }
    val lineGlowBrush = remember(gold) { Brush.verticalGradient(listOf(Color.Transparent, gold.copy(alpha = .35f))) }
    val lineTrack = remember(gold) { gold.copy(alpha = .14f) }
    val hold = slot.holdMs

    fun ageOf(): Float = (clock.longValue - slot.startMs).toFloat()
    fun leaving(): Float = Motion.progress((clock.longValue - slot.exitMs).toFloat(), 0, Motion.EXIT, Motion.In)
    fun opacity(age: Float): Float = Motion.progress(age, 0, Motion.FADE, Motion.Ease) * (1f - leaving())

    val description = remember(toast) { "${kind.word.lowercase()}. ${toast.text}" + (action?.let { ". ${it.label}" } ?: "") }
    val live = if (kind == ToastKind.ERROR) LiveRegionMode.Assertive else LiveRegionMode.Polite

    Box(
        Modifier
            .graphicsLayer {
                if (ageOf() < 0f) {
                    alpha = 0f // still waiting for the card it replaces to leave
                } else {
                    val enter = Motion.progress(ageOf(), 0, Motion.ENTER, EaseOut)
                    translationX = if (reduced) 0f else (size.width + ToastTiming.SLIDE_EXTRA * m.pxPerStage) * (1f - enter) + ToastTiming.EXIT_SLIDE * m.pxPerStage * leaving()
                }
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .drawBehind {
                val age = ageOf()
                if (age < 0f) return@drawBehind
                val op = opacity(age)
                val glow = op * (ToastTiming.GLOW_REST + (1f - ToastTiming.GLOW_REST) * (1f - Motion.progress(age, ToastTiming.GLOW_AT, ToastTiming.GLOW_MS, Motion.Ease)))
                drawGlow(look.fill, glow, m)
                drawCardShadow(op, m)
            }
            .then(if (isLatest) Modifier.semantics(mergeDescendants = true) { liveRegion = live; contentDescription = description } else Modifier),
    ) {
        Row(
            Modifier
                .height(IntrinsicSize.Min)
                .graphicsLayer {
                    val age = ageOf()
                    alpha = if (age < 0f) 0f else opacity(age)
                    this.shape = shape
                    clip = true
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .then(if (action != null) Modifier.pointerInput(slot) { detectTapGestures { onTap() } } else Modifier)
                .drawWithContent {
                    val radius = CornerRadius(CARD_RADIUS * m.pxPerU)
                    drawRoundRect(cardBrush, cornerRadius = radius)
                    drawRoundRect(CardRing, topLeft = Offset(.5f, .5f), size = Size(size.width - 1f, size.height - 1f), cornerRadius = radius, style = Stroke(1f))
                    drawContent()
                    // The light sweep crosses the card once
                    if (!reduced) {
                        val sweep = Motion.progress(ageOf(), ToastTiming.SWEEP_AT, ToastTiming.SWEEP_MS, EaseOut)
                        val strength = sin(Math.PI.toFloat() * sweep)
                        if (strength > 0f) {
                            val band = 190f * m.pxPerStage
                            val x = size.width * Motion.lerp(-.4f, 1.4f, sweep)
                            translate(x, 0f) { drawRect(sweepBrush, size = Size(band + size.height * .25f, size.height), alpha = strength) }
                        }
                    }
                    // The timer: a gold line along the bottom edge that draws in and then drains
                    val line = ToastTiming.timerFraction(ageOf(), hold)
                    val lineHeight = 4f * m.pxPerU
                    val top = size.height - lineHeight
                    drawRect(lineTrack, Offset(0f, top), Size(size.width, lineHeight))
                    if (line > 0f) {
                        val glowHeight = 10f * m.pxPerStage
                        drawRect(lineGlowBrush, Offset(0f, top - glowHeight), Size(size.width * line, glowHeight))
                        drawRect(lineBrush, Offset(0f, top), Size(size.width * line, lineHeight))
                    }
                },
        ) {
            // The solid icon block
            Box(Modifier.width(m.dp(92f)).fillMaxHeight().background(look.fill), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.graphicsLayer {
                        val age = ageOf()
                        val pop = Motion.progress(age, ToastTiming.ICON_DELAY, Motion.PILL_POP, EaseSpring)
                        alpha = Motion.progress(age, ToastTiming.ICON_DELAY, Motion.FADE, Motion.Ease)
                        scaleX = Motion.scaleIn(Motion.POP_FROM_PILL, pop, reduced)
                        scaleY = scaleX
                        rotationZ = if (reduced) 0f else (1f - pop) * -50f
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                ) { Symbol(toast.icon ?: kind.defaultIcon, m.sp(44f), look.on, filled = true) }
            }
            Row(
                Modifier.padding(start = m.dp(24f), top = m.dp(16f), end = m.dp(if (action != null) 18f else 26f), bottom = m.dp(20f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.dp(22f)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(m.dp(3f))) {
                    BasicText(kind.word, style = eyebrowStyle, modifier = Modifier.rising(slot, clock, m, reduced, Motion.ROW_DELAY))
                    BasicText(
                        toast.text, style = textStyle,
                        modifier = Modifier.width(with(density) { textWidthPx.toDp() }).rising(slot, clock, m, reduced, Motion.ROW_DELAY + Motion.STAGGER_ROW),
                        overflow = TextOverflow.Ellipsis, maxLines = TOAST_MAX_LINES,
                    )
                }
                if (action != null) {
                    Row(
                        Modifier
                            .rising(slot, clock, m, reduced, Motion.ROW_DELAY + 2 * Motion.STAGGER_ROW, pressed = true)
                            .background(gold.copy(alpha = .16f), CircleShape)
                            .border(m.dp(1.5f), gold.copy(alpha = .6f), CircleShape)
                            .padding(start = m.dp(8f), top = m.dp(7f), bottom = m.dp(7f), end = m.dp(22f)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(m.dp(10f)),
                    ) {
                        Box(Modifier.size(m.dp(38f)).background(CardText, CircleShape), contentAlignment = Alignment.Center) {
                            BasicText("Y", style = TextStyle(color = CardBottom, fontFamily = Figtree, fontWeight = FontWeight.ExtraBold, fontSize = m.sp(21f)))
                        }
                        BasicText(action.label, style = TextStyle(color = goldLabel, fontFamily = Figtree, fontWeight = FontWeight.ExtraBold, fontSize = m.sp(24f), letterSpacing = .01.em))
                    }
                }
            }
        }
    }
}

/** An element of the card that fades in and rises [Motion.RISE] stage pixels from [delay] ms after the card started to enter (the chip also squeezes when pressed). */
private fun Modifier.rising(slot: ToastSlot<Toast>, clock: LongState, m: ToastMetrics, reduced: Boolean, delay: Int, pressed: Boolean = false): Modifier =
    graphicsLayer {
        val now = clock.longValue
        val appear = Motion.progress((now - slot.startMs).toFloat(), delay, Motion.ROW_IN, EaseOut)
        alpha = appear
        translationY = Motion.rise(Motion.RISE * m.pxPerStage, appear, reduced)
        if (pressed) {
            val scale = ToastTiming.pressScale(if (slot.pressMs == ToastSlot.NO_PRESS) -1f else (now - slot.pressMs).toFloat())
            scaleX = scale
            scaleY = scale
        }
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }

/** The kind-coloured edge glow: stacked rounded rectangles of low opacity stand in for a blur (nothing is blurred, nothing allocates). */
private fun DrawScope.drawGlow(color: Color, strength: Float, m: ToastMetrics) {
    if (strength <= 0f) return
    val radius = CARD_RADIUS * m.pxPerU
    val sp = m.pxPerStage
    for (i in 0 until FAR_STEPS) {
        val e = FAR_SPREAD * sp * (1f - i / FAR_STEPS.toFloat())
        drawRoundRect(color.copy(alpha = FAR_ALPHA * strength), Offset(-e, -e), Size(size.width + 2f * e, size.height + 2f * e), CornerRadius(radius + e))
    }
    for (i in 0 until NEAR_STEPS) {
        val e = NEAR_SPREAD * sp * (1f - i / NEAR_STEPS.toFloat())
        drawRoundRect(color.copy(alpha = NEAR_ALPHA * strength), Offset(-e, -e), Size(size.width + 2f * e, size.height + 2f * e), CornerRadius(radius + e))
    }
    drawRoundRect(color.copy(alpha = .4f * strength), Offset(-sp, -sp), Size(size.width + 2f * sp, size.height + 2f * sp), CornerRadius(radius + sp), style = Stroke(sp))
}

/** The soft drop shadow under the card (22 stage pixels down), built the same way. */
private fun DrawScope.drawCardShadow(opacity: Float, m: ToastMetrics) {
    val radius = CARD_RADIUS * m.pxPerU
    val sp = m.pxPerStage
    for (i in 0 until SHADOW_STEPS) {
        val e = (10f - 4f * i) * sp
        drawRoundRect(Color.Black.copy(alpha = SHADOW_ALPHA * opacity), Offset(-e, 22f * sp - e), Size(size.width + 2f * e, size.height + 2f * e), CornerRadius(max(0f, radius + e)))
    }
}

private const val FAR_STEPS = 8
private const val FAR_SPREAD = 110f
private const val FAR_ALPHA = .03f
private const val NEAR_STEPS = 10
private const val NEAR_SPREAD = 46f
private const val NEAR_ALPHA = .075f
private const val SHADOW_STEPS = 6
private const val SHADOW_ALPHA = .125f
