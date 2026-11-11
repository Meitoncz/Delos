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
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.shell.Toast
import com.limelight.utils.ToastKind
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.cos
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
fun ToastHost(
    toast: Toast?,
    onHidden: (Int) -> Unit,
    onAction: () -> Unit,
    actionPress: Int = 0,
    toastStage: ToastStage = remember { ToastStage() },
    active: Boolean = true,
) {
    val reduced = LocalReducedMotion.current
    val hidden by rememberUpdatedState(onHidden)
    val currentId by rememberUpdatedState(toast?.id)
    val runAction by rememberUpdatedState(onAction)

    // Only the active host drives the stage and draws it; a stage shared with another host (the stream menu's window) goes on
    // exactly where it was when the other host takes over (`ToastStage.sync` ignores a toast it already shows)
    if (active) {
        LaunchedEffect(toast?.id, actionPress) {
            if (toastStage.pressed(actionPress, nowMs())) delay(Motion.PRESS_HOLD)
            toastStage.sync(toast, nowMs())
        }
        // The clock only runs while something is on the stage: an idle host costs nothing
        LaunchedEffect(toastStage.slots.isNotEmpty()) {
            if (toastStage.slots.isEmpty()) return@LaunchedEffect
            while (true) {
                val now = withFrameNanos { it } / 1_000_000L
                if (toastStage.tick(now) { hidden(it.payload.id) }) break
            }
        }
    }
    if (!active || toastStage.slots.isEmpty()) return
    val slots = toastStage.slots
    val clock = toastStage.clock

    val stage = LocalStage.current
    val density = LocalDensity.current
    val metrics = remember(stage, density.density, density.fontScale) { ToastMetrics(stage, density.density, density.fontScale) }
    val colors = LocalDelosColors.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = 112.px, end = (if (stage.compact) 48 else 44).px), contentAlignment = Alignment.TopEnd) {
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
    // The sweep: a band whose opacity follows a raised cosine (zero, with zero slope, at both ends), so it has no edge; the rect around it is padded by what the 12 degree tilt shifts it
    val sweepBrush = remember(m) {
        val band = SWEEP_BAND * m.pxPerStage
        val pad = SWEEP_PAD * m.pxPerU
        val stops = Array(SWEEP_STOPS + 1) { i ->
            val t = i / SWEEP_STOPS.toFloat()
            t to Color.White.copy(alpha = SWEEP_PEAK * .5f * (1f - cos(2f * Math.PI.toFloat() * t)))
        }
        Brush.linearGradient(*stops, start = Offset(pad, 0f), end = Offset(pad + band, band * tan(Math.toRadians(12.0)).toFloat()))
    }
    val lineBrush = remember(gold) { Brush.horizontalGradient(listOf(lerp(gold, Color(0xFFB87800), .6f), gold)) }
    val shadowHalo = remember(m) {
        Halo(Color.Black, ToastTiming.SHADOW_PEAK, ToastTiming.SHADOW_REACH * m.pxPerStage, CARD_RADIUS * m.pxPerU - ToastTiming.SHADOW_INSET * m.pxPerStage)
    }
    val hold = slot.holdMs

    fun ageOf(): Float = (clock.longValue - slot.startMs).toFloat()
    fun leaving(): Float = Motion.progress((clock.longValue - slot.exitMs).toFloat(), 0, Motion.EXIT, Motion.In)
    fun opacity(age: Float): Float = Motion.progress(age, 0, Motion.FADE, Motion.Ease) * (1f - leaving())

    val kindWord = stringResource(kind.wordRes)
    val description = action?.let { stringResource(R.string.toast_description_action, kindWord, toast.text, it.label) }
        ?: stringResource(R.string.toast_description, kindWord, toast.text)
    val live = if (kind == ToastKind.ERROR) LiveRegionMode.Assertive else LiveRegionMode.Polite

    Box(
        Modifier
            .graphicsLayer {
                if (ageOf() < 0f) {
                    alpha = 0f // still waiting for the card it replaces to leave
                } else {
                    val step = ToastTiming.springStep(ageOf())
                    translationX = if (reduced) 0f else (size.width + ToastTiming.SLIDE_EXTRA * m.pxPerStage) * (1f - step) + ToastTiming.EXIT_SLIDE * m.pxPerStage * leaving()
                    if (!reduced) {
                        transformOrigin = RightCenter
                        scaleX = Motion.lerp(ToastTiming.FROM_SCALE, 1f, step)
                        scaleY = scaleX
                    }
                }
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .drawBehind {
                val age = ageOf()
                if (age < 0f) return@drawBehind
                val op = opacity(age)
                val glow = op * (1f - Motion.progress(age, ToastTiming.GLOW_AT, (hold - ToastTiming.GLOW_AT).toInt().coerceAtLeast(1), Motion.Ease))
                val inset = ToastTiming.SHADOW_INSET * m.pxPerStage
                drawHalo(shadowHalo, inset, inset + ToastTiming.SHADOW_DROP * m.pxPerStage, size.width - 2f * inset, size.height - 2f * inset, op, fillInside = true)
                // The glow: one-pixel rings that fade with the eased falloff, so every step differs by less than one 8-bit level: smooth, no blur, no offscreen layer
                // The halo also spreads while the toast is shown: from [GLOW_REACH_FROM] of its reach at the start to all of it at the end
                val spread = ToastTiming.GLOW_REACH_FROM + (1f - ToastTiming.GLOW_REACH_FROM) * Motion.progress(age, 0, hold.toInt().coerceAtLeast(1), EaseOut)
                val reachPx = ToastTiming.GLOW_REACH * m.pxPerStage * spread
                val glowSteps = (reachPx / GLOW_RING).toInt().coerceAtLeast(1)
                val cardRadius = CARD_RADIUS * m.pxPerU
                for (i in 1..glowSteps) {
                    val a = (ToastTiming.GLOW_PEAK * ToastTiming.haloFalloff(i / glowSteps.toFloat()) * glow).coerceIn(0f, 1f)
                    if (a < .004f) continue
                    val o = i * GLOW_RING - GLOW_RING / 2f
                    drawRoundRect(look.fill.copy(alpha = a), Offset(-o, -o), Size(size.width + 2f * o, size.height + 2f * o), CornerRadius(cardRadius + o), style = Stroke(GLOW_RING * 1.1f))
                }
                drawRoundRect(look.fill.copy(alpha = .35f * glow), Offset(-m.pxPerStage, -m.pxPerStage), Size(size.width + 2f * m.pxPerStage, size.height + 2f * m.pxPerStage), CornerRadius(CARD_RADIUS * m.pxPerU + m.pxPerStage), style = Stroke(m.pxPerStage))
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
                            val pad = SWEEP_PAD * m.pxPerU
                            val x = size.width * Motion.lerp(-.4f, 1.4f, sweep)
                            translate(x - pad, 0f) { drawRect(sweepBrush, size = Size(SWEEP_BAND * m.pxPerStage + 2f * pad, size.height), alpha = strength) }
                        }
                    }
                    // The timer: one thin gold line along the bottom edge (no track, no glow) that draws in from the left and then drains toward it
                    val line = ToastTiming.timerFraction(ageOf(), hold)
                    if (line > 0f) {
                        val lineHeight = TIMER_HEIGHT * m.pxPerStage
                        drawRect(lineBrush, Offset(0f, size.height - lineHeight), Size(size.width * line, lineHeight))
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
                    BasicText(kindWord, style = eyebrowStyle, modifier = Modifier.rising(slot, clock, m, reduced, ToastTiming.ROW_AT))
                    BasicText(
                        toast.text, style = textStyle,
                        modifier = Modifier.width(with(density) { textWidthPx.toDp() }).rising(slot, clock, m, reduced, ToastTiming.ROW_AT + Motion.STAGGER_ROW),
                        overflow = TextOverflow.Ellipsis, maxLines = TOAST_MAX_LINES,
                    )
                }
                if (action != null) {
                    Row(
                        Modifier
                            .rising(slot, clock, m, reduced, ToastTiming.ROW_AT + 2 * Motion.STAGGER_ROW, pressed = true)
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

/**
 * A soft halo around a rounded rectangle, drawn as four edge strips (a linear gradient each) and four corner quadrants (a radial gradient each) whose
 * opacity follows [ToastTiming.haloFalloff] in [STOPS] stops: smooth at any opacity, no blur, no offscreen layer. The brushes are built once per toast;
 * the strength of a frame is the `alpha` of the draw call, so nothing is allocated while it animates.
 */
private class Halo(val color: Color, val peak: Float, val reach: Float, val radius: Float) {
    // Edge strips: position 0 is the far side
    private fun edge(farFirst: Boolean, vertical: Boolean): Brush {
        val stops = Array(STOPS + 1) { i ->
            val p = i / STOPS.toFloat()
            p to color.copy(alpha = peak * ToastTiming.haloFalloff(if (farFirst) 1f - p else p))
        }
        return if (vertical) Brush.verticalGradient(*stops) else Brush.horizontalGradient(*stops)
    }

    val top = edge(true, true)
    val bottom = edge(false, true)
    val left = edge(true, false)
    val right = edge(false, false)

    private fun corner(cx: Float, cy: Float): Brush {
        val outer = radius + reach
        val inner = radius / outer
        val stops = Array(STOPS + 2) { i ->
            if (i == 0) 0f to color.copy(alpha = peak)
            else {
                val t = (i - 1) / STOPS.toFloat()
                (inner + (1f - inner) * t) to color.copy(alpha = peak * ToastTiming.haloFalloff(t))
            }
        }
        return Brush.radialGradient(*stops, center = Offset(cx, cy), radius = outer)
    }

    private val span = radius + reach
    val topLeft = corner(span, span)
    val topRight = corner(0f, span)
    val bottomLeft = corner(span, 0f)
    val bottomRight = corner(0f, 0f)
}

private fun DrawScope.drawHalo(h: Halo, left: Float, top: Float, width: Float, height: Float, alpha: Float, fillInside: Boolean = false) {
    if (alpha <= 0f) return
    // The shadow rectangle reaches below the card: without a fill there would be a gap (a dark hairline) between the card and the halo
    if (fillInside) drawRoundRect(h.color.copy(alpha = h.peak * alpha), Offset(left, top), Size(width, height), CornerRadius(h.radius))
    val d = h.reach
    val r = h.radius
    val span = Size(r + d, r + d)
    drawRect(h.top, Offset(left + r, top - d), Size(width - 2f * r, d), alpha)
    drawRect(h.bottom, Offset(left + r, top + height), Size(width - 2f * r, d), alpha)
    drawRect(h.left, Offset(left - d, top + r), Size(d, height - 2f * r), alpha)
    drawRect(h.right, Offset(left + width, top + r), Size(d, height - 2f * r), alpha)
    drawRect(h.topLeft, Offset(left - d, top - d), span, alpha)
    drawRect(h.topRight, Offset(left + width - r, top - d), span, alpha)
    drawRect(h.bottomLeft, Offset(left - d, top + height - r), span, alpha)
    drawRect(h.bottomRight, Offset(left + width - r, top + height - r), span, alpha)
}

private const val GLOW_RING = 2f // px per glow ring: 2 px steps change the opacity by far less than one 8-bit level each, and halve the draw calls
private const val STOPS = 14
private const val TIMER_HEIGHT = 4f
private const val SWEEP_STOPS = 10
private const val SWEEP_BAND = 190f
private const val SWEEP_PAD = 56f
private const val SWEEP_PEAK = .30f
private val RightCenter = TransformOrigin(1f, .5f)
