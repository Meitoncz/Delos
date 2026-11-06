package com.limelight.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.em
import com.limelight.shell.StreamMenuLayout as L
import com.limelight.shell.StreamMenuMotion as M
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.EaseOut
import com.limelight.shell.ui.EaseSpring
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.ChoiceMotion
import com.limelight.shell.ui.Motion
import com.limelight.shell.ui.MotionSwitch
import com.limelight.shell.ui.animateKnobScale
import com.limelight.shell.ui.chevronBump
import com.limelight.shell.ui.rememberChoiceMotion
import com.limelight.shell.ui.staggerEntrance
import com.limelight.shell.ui.valueSlide
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.rememberMarginBringIntoView
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.fadeEdges
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.FocusedListNavigation
import com.limelight.shell.ui.RowGlideHost
import com.limelight.shell.ui.focusedListBoundary
import com.limelight.shell.ui.focusedListRow
import com.limelight.shell.ui.MarqueeText
import com.limelight.shell.ui.focusTrap
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val CardTint = Color(34, 44, 84)
private val CardBacking = Color(0xFF070A18)

internal const val PopoverBackFocusKey = "popoverBack"
private val LocalPopoverNavigation = staticCompositionLocalOf<FocusedListNavigation<String>?> { null }

/** The popover's original inset ring/shadow, with reusable native paths and paint for moving plate frames. */
private class PopoverRowFocusDecoration(k: Float) {
    private val corner = L.ROW_RADIUS * k
    private val stroke = L.ROW_RING * k
    private val strokeStyle = Stroke(stroke)
    private val blur = 36f * k
    private val spread = 6f * k
    private val margin = blur * 2f + 8f
    private val inner = android.graphics.Path()
    private val frame = android.graphics.Path().apply { fillType = android.graphics.Path.FillType.EVEN_ODD }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
    }
    private var width = Float.NaN
    private var height = Float.NaN
    private var shadowColor = 0
    private var shadowReady = false

    fun draw(scope: DrawScope, accent: Color, alpha: Float) = with(scope) {
        drawRoundRect(
            accent.copy(alpha = alpha), topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(corner - stroke / 2f), style = strokeStyle,
        )
        if (width != size.width || height != size.height) {
            width = size.width
            height = size.height
            val radius = corner - stroke
            inner.rewind()
            inner.addRoundRect(stroke, stroke, width - stroke, height - stroke, radius, radius, android.graphics.Path.Direction.CW)
            frame.rewind()
            frame.fillType = android.graphics.Path.FillType.EVEN_ODD // rewind() may reset it: with winding the whole frame (hole included) paints black
            frame.addRect(stroke - margin, stroke - margin, width - stroke + margin, height - stroke + margin, android.graphics.Path.Direction.CW)
            frame.addRoundRect(
                stroke - spread, stroke - spread, width - stroke + spread, height - stroke + spread,
                radius + spread, radius + spread, android.graphics.Path.Direction.CW,
            )
        }
        val color = accent.copy(alpha = .22f * alpha).toArgb()
        if (!shadowReady || shadowColor != color) {
            shadowColor = color
            shadowReady = true
            paint.setShadowLayer(shadowRadius(blur), 0f, 0f, color)
        }
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val save = native.save()
            native.clipPath(inner)
            native.drawPath(frame, paint)
            native.restoreToCount(save)
        }
    }
}

/**
 * The geometry of the popover, stage pixels: the card's left edge [x], its [w]idth, the caret's center from the left
 * edge [cx] and the card's visible height [h] (the caret excluded). Each follows its target with a slightly
 * under-damped spring (stiffness 190, damping ratio .8: the prototype's `stepGeo`), so moving to another tile's page
 * slides and resizes the card instead of replacing it. A snap puts it there at once (the popover opening from the dock).
 * It starts at the [initial] placement, so the first layout of a popover already has its width.
 */
internal class PopoverGeometry(initial: PopoverPlacement) {
    val x = Animatable(initial.left)
    val w = Animatable(initial.width)
    val cx = Animatable(initial.caretX)
    val h = Animatable(0f)
    private val follow = spring<Float>(dampingRatio = Motion.GEO_DAMPING, stiffness = Motion.GEO_STIFFNESS, visibilityThreshold = .15f)

    suspend fun place(placement: PopoverPlacement, snap: Boolean) {
        if (snap) {
            x.snapTo(placement.left)
            w.snapTo(placement.width)
            cx.snapTo(placement.caretX)
        } else coroutineScope {
            launch { x.animateTo(placement.left, follow) }
            launch { w.animateTo(placement.width, follow) }
            launch { cx.animateTo(placement.caretX, follow) }
        }
    }

    suspend fun fit(height: Float, snap: Boolean) {
        if (snap) h.snapTo(height) else h.animateTo(height, follow)
    }
}

/**
 * The outline of the card and its caret as ONE path (the prototype's `cardPath`): a rounded rectangle [w] x [h] with a
 * rounded caret on its bottom edge, centered at [cx], [StreamMenuLayout.CARET_WIDTH] wide at its base and
 * [StreamMenuLayout.CARET_HEIGHT] tall, so the glass is continuous. All values are pixels; [k] is pixels per stage pixel.
 */
internal fun caretCardPath(w: Float, h: Float, cx: Float, k: Float): Path {
    val r = minOf(L.POPOVER_RADIUS * k, h / 2f - 1f).coerceAtLeast(0f)
    val a = L.CARET_WIDTH * k / 2f
    val b = L.CARET_WIDTH * k / 6f
    val d = L.CARET_HEIGHT * k
    return Path().apply {
        moveTo(r, 0f)
        lineTo(w - r, 0f)
        arcTo(Rect(w - 2 * r, 0f, w, 2 * r), -90f, 90f, false)
        lineTo(w, h - r)
        arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
        lineTo(cx + a, h)
        lineTo(cx + b, h + d * .78f)
        quadraticTo(cx, h + d * 1.15f, cx - b, h + d * .78f)
        lineTo(cx - a, h)
        lineTo(r, h)
        arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
        lineTo(0f, r)
        arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
        close()
    }
}

/**
 * The popover's chrome (`#pop`): a frosted card with its caret, [geo] wide and high, bottom-anchored (the caller puts
 * its box [StreamMenuLayout.POPOVER_BOTTOM] above the stage's bottom edge). It grows from [scale] around the caret's
 * tip ([StreamMenuLayout.POPOVER_HIDDEN_SCALE] to 1) while [alpha] fades the glass and its content in. The card's size
 * is its content's natural size: [onNaturalHeight] reports it (stage pixels, the caret excluded) so the caller can
 * move [geo] to it. While [active] the focus cannot leave it; a tap inside is not "outside". [content] is the page.
 */
@Composable
internal fun StreamMenuPopover(
    geo: PopoverGeometry,
    scale: () -> Float,
    alpha: () -> Float,
    active: Boolean,
    navigation: FocusedListNavigation<String>? = null,
    onNaturalHeight: (Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val k = stageScale()
    val sheen = LocalDelosColors.current.accent2
    val maxHeight = ((L.POPOVER_MAX_HEIGHT + L.CARET_HEIGHT) * k).roundToInt()
    Box(
        modifier
            .offset { IntOffset((geo.x.value * k).roundToInt(), 0) }
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin((geo.cx.value * k / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f), ((size.height - L.CARET_HEIGHT * k / 2f) / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f))
            }
            .drawWithContent {
                val a = alpha()
                val body = (size.height - L.CARET_HEIGHT * k).coerceAtLeast(0f)
                // The shadow is a sibling of the card with its rounded rectangle (without the caret): 0 40px 90px -20px
                drawBoxShadow(Rect(0f, 0f, size.width, body), L.POPOVER_RADIUS * k, 40f * k, 90f * k, -20f * k, Color.Black.copy(alpha = .62f * a))
                clipPath(caretCardPath(size.width, maxOf(body, 120f * k), geo.cx.value * k, k)) {
                    drawRect(CardBacking.copy(alpha = L.CARD_BACKING_ALPHA * a))
                    drawRect(CardTint.copy(alpha = .50f * a))
                    drawRect(Brush.verticalGradient(0f to sheen.copy(alpha = .20f * a), .52f to Color.Transparent, 1f to Color.Transparent))
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.White.copy(alpha = .20f * a), startY = size.height - 150f * k, endY = size.height))
                    this@drawWithContent.drawContent()
                }
            }
            .layout { measurable, _ ->
                val w = (geo.w.value * k).roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints(minWidth = w, maxWidth = w, minHeight = 0, maxHeight = maxHeight))
                layout(w, ((geo.h.value + L.CARET_HEIGHT) * k).roundToInt().coerceAtLeast(0)) { placeable.place(0, 0) }
            }
            .focusTrap(active)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (navigation != null) Modifier.focusedListBoundary(navigation) else Modifier)
                .graphicsLayer {
                    this.alpha = alpha()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .onSizeChanged { onNaturalHeight(it.height / k - L.CARET_HEIGHT) }
                .padding(
                    start = L.POPOVER_PADDING_X.px, end = L.POPOVER_PADDING_X.px, top = L.POPOVER_PADDING_Y.px,
                    bottom = (L.POPOVER_PADDING_BOTTOM + L.CARET_HEIGHT).px,
                ),
            content = content,
        )
    }
}

/**
 * One page of the popover: the header (the back circle and the [title]), the page's rows in a scroller that fades at
 * its edges when it scrolls, and under them the [help] of the focused row in a box of [helpLines] lines. [leaving] is
 * the moment another page of the same tile replaces this one: the rows rise out and the title hides; [titleIn] makes
 * the title of the replacing page fade in.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ColumnScope.PopoverPage(
    title: String,
    help: String,
    onBack: () -> Unit,
    leaving: Boolean,
    titleIn: Boolean,
    navigation: FocusedListNavigation<String>? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDelosColors.current
    val rm = LocalReducedMotion.current
    val stage = LocalStage.current
    val k = stageScale()
    val rowDecoration = remember(k) { PopoverRowFocusDecoration(k) }
    val ts = stage.textScale
    val scroll = rememberScrollState()
    val bringIntoView = rememberMarginBringIntoView(scroll, before = 48f * k, after = 48f * k, stagePx = k)
    val leave by animateFloatAsState(if (leaving) 1f else 0f, tween(M.MORPH_ROWS_OUT, easing = Motion.EaseIn), label = "rowsLeave")

    Row(
        Modifier.fillMaxWidth().height(L.POPOVER_HEAD_HEIGHT.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(L.POPOVER_HEAD_GAP.px),
    ) {
        BackButton(
            onBack,
            if (navigation != null) Modifier.focusedListRow(navigation, PopoverBackFocusKey) else Modifier,
        )
        val titleShow = remember { Animatable(if (titleIn) 0f else 1f) }
        LaunchedEffect(Unit) { if (titleIn) titleShow.animateTo(1f, tween(M.MORPH_TITLE, easing = EaseOut)) }
        MarqueeText(
            title,
            style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = L.POPOVER_TITLE_SIZE.fsp, letterSpacing = (-.005f).em),
            modifier = Modifier.graphicsLayer {
                alpha = if (leaving) 0f else titleShow.value
                translationY = if (rm) 0f else 8f * (1f - titleShow.value) * k
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        )
    }
    Spacer(Modifier.height(L.POPOVER_GAP_AFTER_HEAD.px))
    // A list at its top or bottom has nothing beyond that edge: no fade there, so the first or last row is never cut
    val topFade by animateFloatAsState(if (scroll.value > 0) 1f else 0f, tween(Motion.FADE, easing = Motion.Ease), label = "fadeTop")
    val bottomFade by animateFloatAsState(if (scroll.value < scroll.maxValue) 1f else 0f, tween(Motion.FADE, easing = Motion.Ease), label = "fadeBottom")
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides bringIntoView,
        LocalPopoverNavigation provides navigation,
    ) {
        Box(
            Modifier
                .weight(1f, fill = false)
                .graphicsLayer {
                    alpha = 1f - leave
                    translationY = if (rm) 0f else -10f * leave * k
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        ) {
            RowGlideHost(
                ring = null,
                corner = L.ROW_RADIUS,
                focusScale = 1f,
                fill = colors.accent.copy(alpha = .17f),
                glow = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (scroll.maxValue > 0) Modifier.fadeEdges(stage, top = 26f * topFade, bottom = 64f * bottomFade) else Modifier)
                    .verticalScroll(scroll),
                decoration = { alpha -> rowDecoration.draw(this, colors.accent, alpha) },
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(L.ROW_GAP.px),
                    content = content,
                )
            }
        }
    }
    Spacer(Modifier.height(L.POPOVER_HELP_GAP.px))
    PageHelp(help, Modifier.fillMaxWidth().height(popoverHelpHeight(ts).px))
}

/**
 * The help of the focused row (`.phelp`): it rises 8 px out of nothing when it changes (300 ms); the first one of a page
 * waits 260 ms and takes 400, so it arrives after the rows.
 */
@Composable
private fun PageHelp(text: String, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val rm = LocalReducedMotion.current
    val k = stageScale()
    val show = remember { Animatable(0f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(text) {
        show.snapTo(0f)
        if (first[0]) {
            first[0] = false
            delay(M.HELP_FIRST_DELAY.toLong())
            show.animateTo(1f, tween(M.HELP_FIRST_DURATION, easing = EaseOut))
        } else show.animateTo(1f, tween(M.HELP_DURATION, easing = EaseOut))
    }
    Box(
        modifier.graphicsLayer {
            alpha = show.value
            translationY = if (rm) 0f else 8f * (1f - show.value) * k
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    ) {
        BasicText(
            text,
            style = TextStyle(
                color = colors.text2, fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = L.HELP_SIZE.tpx,
                lineHeight = L.HELP_LINE_HEIGHT.em, lineHeightStyle = CssLineHeight, lineBreak = LineBreak.Paragraph,
            ),
        )
    }
}

/** The back circle of a popover (`.back`): 64 px, a pale fill, the chevron in the secondary accent; its focus is the gold ring. */
@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDelosColors.current
    val k = stageScale()
    var focused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(Color.White.copy(alpha = if (focused) .20f else .10f), tween(Motion.FADE, easing = Motion.Ease), label = "backFill")
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(Motion.FADE, easing = Motion.Ease), label = "backRing")
    Box(
        modifier
            .size(L.POPOVER_HEAD_HEIGHT.px)
            .focusEffects(onBack, corner = 999f, focusScale = 1.06f, ring = null, glow = null, pressScale = 1f, clickCue = Cue.BACK, onFocusChange = { focused = it })
            .drawBehind {
                drawCircle(fill)
                if (ring > 0f) {
                    val stroke = L.ROW_RING * k
                    drawCircle(colors.accent.copy(alpha = ring), radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(Symbols.CHEVRON_LEFT, 36.fsp, colors.accent2)
    }
}

/**
 * A row of a popover page (`.row`): 72 px, the icon in a 56 px column, the label, and the value on the right. The page's
 * shared focus plate carries its pale gold fill, 3 px gold inset ring and soft inner shadow; the icon turns gold and
 * filled. Left and right adjust choices and sliders (a held key accelerates a slider); A or a tap activates. [help]
 * selects the help text; [confirmation] replaces the chevron of an action with a check and a word.
 */
@Composable
internal fun MenuRow(
    icon: String,
    label: String,
    value: RowValue,
    modifier: Modifier = Modifier,
    confirmation: String? = null,
    enabled: Boolean = true,
    help: String = label,
) {
    val colors = LocalDelosColors.current
    val feedback = LocalFeedback.current
    val navigation = LocalPopoverNavigation.current
    val ts = LocalStage.current.textScale
    val show = LocalStreamHelp.current
    var focused by remember { mutableStateOf(false) }
    val choice = rememberChoiceMotion()
    val adjust: ((Int) -> Unit)? = when (value) {
        is RowValue.Choice -> { dir ->
            choice.step(dir)
            value.onStep(dir)
        }
        // Steps left or right ([dir] may be several while a key is held), landing on the slider's grid
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
        adjust(direction * if (value is RowValue.Slider) com.limelight.shell.ui.sliderAcceleration(value, repeatCount) else 1)
    }
    val iconColor by animateColorAsState(if (focused) colors.accent2 else colors.accent2.copy(alpha = .62f), tween(Motion.FADE, easing = Motion.Ease), label = "rowIcon")
    Row(
        modifier
            .then(if (navigation != null) Modifier.focusedListRow(navigation, label) else Modifier)
            .fillMaxWidth()
            .staggerEntrance()
            .onPreviewKeyEvent { event ->
                val dir = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> 0
                }
                if (dir == 0 || adjust == null || !enabled) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    feedback.play(Cue.MOVE)
                    adjust(dir * if (value is RowValue.Slider) com.limelight.shell.ui.sliderAcceleration(value, event.nativeKeyEvent.repeatCount) else 1)
                }
                true
            }
            .focusEffects(
                onClick = { if (enabled) activate() else feedback.play(Cue.ERROR) },
                corner = L.ROW_RADIUS, focusScale = 1f, ring = null, glow = null, pressScale = 1f,
                glide = true,
                clickCue = if (enabled && value !is RowValue.Info) (if (value is RowValue.Action) Cue.OPEN else Cue.SELECT) else null,
                adjustable = adjust != null && enabled,
                controllerAdjust = controllerAdjust,
                onFocusChange = {
                    focused = it
                    if (it) show(help)
                },
            )
            .defaultMinSize(minHeight = L.ROW_HEIGHT.px)
            .padding(start = L.ROW_PADDING_START.px, end = L.ROW_PADDING_END.px)
            .graphicsLayer {
                alpha = if (enabled) 1f else .45f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(L.ROW_ICON_COLUMN.px), contentAlignment = Alignment.Center) {
            Symbol(icon, L.ROW_ICON.fsp, iconColor, filled = focused, fadeMillis = Motion.FADE)
        }
        Spacer(Modifier.width(L.ROW_COLUMN_GAP.px))
        MarqueeText(
            label, active = focused,
            style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = L.ROW_SIZE.tpx, lineHeight = 1.2f.em, lineHeightStyle = CssLineHeight),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(L.ROW_COLUMN_GAP.px))
        RowValueView(value, focused, confirmation, choice, ts)
    }
}

/** The value side of a row. [choice] is what the last step of a choice does: the chevron nudge and the slide of the new value. */
@Composable
private fun RowValueView(value: RowValue, focused: Boolean, confirmation: String?, choice: ChoiceMotion, ts: Float) {
    val colors = LocalDelosColors.current
    val text = TextStyle(color = colors.text2, fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = L.ROW_SIZE.tpx, fontFeatureSettings = "tnum")
    val chevronColor by animateColorAsState(if (focused) colors.accent else colors.text3.copy(alpha = .5f), tween(Motion.FADE, easing = Motion.Ease), label = "chevron")
    when {
        confirmation != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.px)) {
            Symbol(Symbols.CHECK, 28.fsp, colors.accent)
            BasicText(confirmation, style = text.copy(color = colors.accent, fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false)
        }
        value is RowValue.Toggle -> MotionSwitch(value.on, L.SWITCH_WIDTH, L.SWITCH_HEIGHT, Color.White.copy(alpha = .18f))
        value is RowValue.Choice -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.px)) {
            Symbol(Symbols.CHEVRON_LEFT, 28.fsp, chevronColor, Modifier.chevronBump(choice, -1))
            // A new value slides in from the side the key came from
            Box(
                Modifier
                    .width((L.CHOICE_VALUE_MIN_WIDTH * ts).px)
                    .valueSlide(choice),
                contentAlignment = Alignment.Center,
            ) {
                MarqueeText(value.label, style = text, active = focused, modifier = Modifier.widthIn(max = 520.px))
            }
            Symbol(Symbols.CHEVRON_RIGHT, 28.fsp, chevronColor, Modifier.chevronBump(choice, 1))
        }
        value is RowValue.Slider -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.px)) {
            val number = MenuSlider(value, focused)
            val label = if (number % value.divisor == 0) (number / value.divisor).toString() else String.format(java.util.Locale.US, "%.1f", number.toDouble() / value.divisor)
            BasicText(
                "$label${value.unit}",
                style = text.copy(color = colors.text, textAlign = TextAlign.End),
                maxLines = 1,
                modifier = Modifier.width((L.NUMBER_MIN_WIDTH * ts).px),
            )
        }
        value is RowValue.Action -> Symbol(Symbols.CHEVRON_RIGHT, 28.fsp, chevronColor)
        value is RowValue.Text -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.px)) {
            MarqueeText(value.text.ifEmpty { "Not set" }, style = text, active = focused, modifier = Modifier.widthIn(max = 420.px))
            Symbol(Symbols.CHEVRON_RIGHT, 28.fsp, chevronColor)
        }
        value is RowValue.Info -> BasicText(value.text, style = text)
    }
}

/**
 * The slider of a row (`.sl`): a 240 x 12 track with the accent filling it up to the 32 px knob; the focused row's
 * knob grows to 1.18 inside a soft halo. A touch or a drag sets the value on the slider's grid. Returns the value
 * being shown (the one under the finger while dragging).
 */
@Composable
private fun MenuSlider(value: RowValue.Slider, focused: Boolean): Int {
    val colors = LocalDelosColors.current
    val rm = LocalReducedMotion.current
    val k = stageScale()
    val onChange by rememberUpdatedState(value.onChange)
    var dragging by remember { mutableStateOf<Int?>(null) }
    val number = dragging ?: value.value
    val fraction by animateFloatAsState(
        ((number - value.min) / (value.max - value.min).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f),
        tween(if (dragging == null && !rm) Motion.SLIDER_FILL else 0, easing = EaseOut), label = "sliderFraction",
    )
    val knobScale by animateKnobScale(focused)
    Box(
        Modifier
            .size(L.SLIDER_WIDTH.px, (L.KNOB + 12f).px)
            .pointerInput(value.min, value.max, value.step) {
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
            }
            .drawBehind {
                val trackHeight = L.SLIDER_HEIGHT * k
                val top = (size.height - trackHeight) / 2f
                drawRoundRect(Color.White.copy(alpha = .18f), Offset(0f, top), Size(size.width, trackHeight), CornerRadius(trackHeight / 2f))
                if (fraction > 0f) drawRoundRect(
                    Brush.horizontalGradient(listOf(colors.accent, colors.accent2), startX = 0f, endX = size.width * fraction),
                    Offset(0f, top), Size(size.width * fraction, trackHeight), CornerRadius(trackHeight / 2f),
                )
                val radius = L.KNOB * k / 2f * knobScale
                val center = Offset(size.width * fraction, size.height / 2f)
                if (focused) drawCircle(colors.accent3.copy(alpha = .40f), radius = radius + 7f * k * knobScale, center = center)
                drawBoxShadow(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius), radius, 4f * k, 12f * k, 0f, Color.Black.copy(alpha = .45f))
                drawCircle(colors.text, radius = radius, center = center)
            },
    )
    return number
}

/** Wrapping text choices keep spatial 2D navigation and a static white base under one gliding capsule focus plate. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PopoverPills(labels: List<String>, first: FocusRequester, command: Boolean = false, onSelect: (Int) -> Unit) {
    val colors = LocalDelosColors.current
    val help = LocalStreamHelp.current
    val k = stageScale()
    val stroke = L.ROW_RING * k
    val strokeStyle = remember(stroke) { Stroke(stroke) }
    RowGlideHost(
        ring = null,
        corner = 999f,
        focusScale = 1f,
        fill = colors.accent.copy(alpha = .17f),
        glow = null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.px, vertical = 4.px),
        decoration = { alpha ->
            drawRoundRect(
                colors.accent.copy(alpha = alpha), topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(size.height / 2f - stroke / 2f), style = strokeStyle,
            )
        },
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.px),
            verticalArrangement = Arrangement.spacedBy(14.px),
        ) {
            labels.forEachIndexed { i, label ->
                BasicText(
                    label,
                    style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = L.ROW_SIZE.tpx),
                    maxLines = 1,
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .staggerEntrance()
                        .focusEffects(
                            { onSelect(i) }, corner = 999f, focusScale = 1f, ring = null, glow = null,
                            pressScale = 1f, glide = true,
                            onFocusChange = {
                                if (it) help((if (command) "Command: " else "Keys: ") + label)
                            },
                        )
                        .drawBehind {
                            drawRoundRect(Color.White.copy(alpha = .10f), cornerRadius = CornerRadius(size.height / 2f))
                        }
                        .padding(horizontal = 26.px, vertical = 14.px),
                )
            }
        }
    }
}
