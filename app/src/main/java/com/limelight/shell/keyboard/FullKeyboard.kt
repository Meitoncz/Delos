package com.limelight.shell.keyboard

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.limelight.shell.MenuDirection
import com.limelight.shell.ui.EaseOut
import com.limelight.shell.ui.EaseSpring
import com.limelight.shell.ui.FocusRing
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.GlideRingHost
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.Motion
import com.limelight.shell.ui.StageMetrics
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.animateFocusScale
import com.limelight.shell.ui.glideTarget
import com.limelight.shell.ui.px
import com.limelight.shell.ui.stageUnit
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The Full keyboard (docs/GUI.md, "Full keyboard"): the approved "Flat console" prototype. A solid bar docked to the
 * bottom edge with six rows of flat keys, a hint strip under them and, for the controller, one gold ring that glides
 * between the keys. Everything moves with the shared motion language (Motion.kt, MotionKit.kt) like the stream menu.
 */

/** The ring: a 4 px gold line with a 3 px gap in the background color, around a key of corner 11. */
private val KeyRing = FocusRing(width = 4f, gap = 3f)
private const val KEY_CORNER = 11f

/** The popup stands this far above the key and is 1.1 keys wide and 1.15 keys tall at least. */
private const val POPUP_LIFT = 10f
private const val POPUP_MIN_WIDTH = 1.1f
private const val POPUP_HEIGHT = 1.15f

/** Opacity of the popup's transition (`--d-fade` is too slow for a popup that lives 75 ms past the release). */
private const val POPUP_FADE_MS = 75

/**
 * The keyboard. [state] says what it shows; it is laid out in the width it is given and as tall as it needs, with room
 * above for the popup of the top row. It lays out on the 1080-high stage like the rest of the new UI (the keys of a 21:9 or
 * 4:3 screen are what [keyboardMetrics] makes of its width). While [FullKeyboardState.present] is false it takes no room.
 */
@Composable
fun FullKeyboard(state: FullKeyboardState) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val dpPerPx = min(maxWidth.value / 1920f, maxHeight.value / 1080f)
        val screenHeight = with(LocalDensity.current) { maxHeight.toPx() }
        CompositionLocalProvider(LocalStage provides StageMetrics(dpPerPx)) {
            KeyboardLayer(state, keyboardMetrics(maxWidth.value / dpPerPx, state.prefs, dpPerPx), screenHeight)
        }
    }
}

@Composable
private fun KeyboardLayer(state: FullKeyboardState, metrics: KeyboardMetrics, screenHeight: Float) {
    val colors = LocalDelosColors.current
    val palette = remember(colors) { keyboardPalette(colors) }
    val k = stageUnit()
    val open = state.open
    // The bar's glass fades in with the card's fade and out with every exit; the rows rise one after the other
    val barFade = remember { Animatable(0f) }
    val rows = remember { List(KeyboardMetrics.ROWS) { Animatable(0f) } }
    LaunchedEffect(open) {
        coroutineScope {
            launch { barFade.animateTo(if (open) 1f else 0f, tween(if (open) Motion.CARD_FADE_IN else Motion.EXIT, easing = Motion.Ease)) }
            rows.forEachIndexed { i, row ->
                launch {
                    if (open) {
                        delay(Motion.staggerDelay(i, Motion.ROW_DELAY, Motion.STAGGER_ROW).toLong())
                        row.animateTo(1f, tween(Motion.ROW_IN, easing = EaseOut))
                    } else {
                        row.animateTo(0f, tween(Motion.EXIT, easing = Motion.In))
                    }
                }
            }
        }
    }
    // The ring appears when the keys are in, then follows them
    LaunchedEffect(open) {
        if (open) {
            delay(Motion.RING.toLong())
            state.settled()
        }
    }
    // The popup is placed in the keyboard's own coordinates: the layer reports where it is
    val root = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { root[0] = it }
            // Not present: measured like any layout, but no room, nothing drawn and nothing to touch
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                if (state.present) layout(placeable.width, placeable.height) { placeable.place(0, 0) } else layout(0, 0) {}
            },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val alpha = barFade.value
                    if (alpha > 0f && size.height > 0f) drawRect(shade(size.height, metrics.popupRoom * k, screenHeight), alpha = alpha)
                },
        )
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(metrics.popupRoom.px))
            KeyboardBar(state, metrics, palette, barFade, rows)
        }
        KeyPopupLayer(state, metrics, palette, root)
    }
}

/** The prototype's `#shade`: black fading in from half the screen's height to .38 at the bottom edge, only where there is something to darken. */
private fun shade(height: Float, barTop: Float, screenHeight: Float): Brush {
    val top = screenHeight - height
    val half = screenHeight / 2f
    fun alphaAt(y: Float) = SHADE_MAX * ((y + top - half) / half).coerceIn(0f, 1f)
    fun color(alpha: Float) = Color(4, 6, 14).copy(alpha = alpha)
    val stops = buildList {
        add(0f to color(0f))
        add((barTop / height) to color(alphaAt(barTop)))
        if (half - top > barTop && half - top < height) add(((half - top) / height) to color(0f))
        add(1f to color(SHADE_MAX))
    }
    return Brush.verticalGradient(colorStops = stops.toTypedArray(), startY = 0f, endY = height)
}

private const val SHADE_MAX = .38f

@Composable
private fun KeyboardBar(
    state: FullKeyboardState,
    metrics: KeyboardMetrics,
    palette: KeyboardPalette,
    barFade: Animatable<Float, *>,
    rows: List<Animatable<Float, *>>,
) {
    val k = stageUnit()
    val alignment = when (state.prefs.align) {
        KeyboardAlign.LEFT -> Alignment.TopStart
        KeyboardAlign.CENTER -> Alignment.TopCenter
        KeyboardAlign.RIGHT -> Alignment.TopEnd
    }
    val opacity = state.prefs.opacityPercent / 100f
    Box(Modifier.fillMaxWidth()) {
        // The group the opacity setting dims: the bar, its shadow, the keys and the ring. Touches on it never reach the stream
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = opacity }
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } },
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = barFade.value }
                    .dropShadow(RectangleShape) {
                        radius = 60f * k
                        spread = -8f * k
                        offset = Offset(0f, -24f * k)
                        color = Color.Black
                        alpha = .5f
                    }
                    .background(Brush.verticalGradient(listOf(palette.barTop, palette.barBottom))),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = KeyboardMetrics.SIDE.px, end = KeyboardMetrics.SIDE.px, top = KeyboardMetrics.TOP_PAD.px, bottom = KeyboardMetrics.BOTTOM_PAD.px),
            ) {
                KeyRows(state, metrics, palette, rows, Modifier.width(metrics.bodyWidth.px).align(alignment))
            }
        }
        // The hints are not dimmed with the keys: they say what the buttons do
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = KeyboardMetrics.SIDE.px, end = KeyboardMetrics.SIDE.px, bottom = HINT_BOTTOM.px),
        ) {
            HintStrip(state, palette, metrics.bodyWidth, Modifier.width(metrics.bodyWidth.px).align(alignment))
        }
    }
}

private const val HINT_BOTTOM = 14f
private const val HINT_HEIGHT = 48f

@Composable
private fun KeyRows(state: FullKeyboardState, metrics: KeyboardMetrics, palette: KeyboardPalette, rows: List<Animatable<Float, *>>, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val k = stageUnit()
    // The ring squeezes with the key under it
    val focus = state.focus
    val focusedPressed = state.focusVisible && focus != null && KeyGrid.standard.key(focus).code in state.pressed
    val ringScale = animateFocusScale(focused = false, pressed = focusedPressed, focusScale = 1f, label = "keyRingScale")
    // At the edge of the keyboard the ring bumps against it and returns (the chevron nudge of the choices)
    val nudge = remember { Animatable(0f) }
    LaunchedEffect(state.nudgeCount) {
        if (state.nudgeCount == 0 || reduced) return@LaunchedEffect
        nudge.snapTo(0f)
        nudge.animateTo(1f, tween((Motion.CHOICE_BUMP * .4f).toInt(), easing = EaseSpring))
        nudge.animateTo(0f, tween((Motion.CHOICE_BUMP * .6f).toInt(), easing = EaseSpring))
    }
    GlideRingHost(
        ring = KeyRing,
        corner = KEY_CORNER,
        focusScale = 1f,
        modifier = modifier,
        inFront = true,
        scale = { ringScale.value },
        offset = {
            val d = when (state.nudgeDirection) {
                MenuDirection.LEFT -> Offset(-1f, 0f)
                MenuDirection.RIGHT -> Offset(1f, 0f)
                MenuDirection.UP -> Offset(0f, -1f)
                MenuDirection.DOWN -> Offset(0f, 1f)
            }
            d * (Motion.CHEVRON_BUMP * k * nudge.value)
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KeyboardMetrics.GAP.px)) {
            KeyGrid.standard.rows.forEachIndexed { r, row -> KeyRow(r, row, state, metrics, palette, rows[r]) }
        }
    }
}

@Composable
private fun KeyRow(row: Int, keys: List<KeyDef>, state: FullKeyboardState, metrics: KeyboardMetrics, palette: KeyboardPalette, progress: Animatable<Float, *>) {
    val reduced = LocalReducedMotion.current
    val rise = Motion.RISE * stageUnit()
    Row(
        Modifier
            .fillMaxWidth()
            .height(metrics.keyHeight.px)
            .graphicsLayer {
                val p = progress.value
                alpha = p
                translationY = Motion.rise(rise, p, reduced)
                // Each key fades by itself: an offscreen layer per row is not needed
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        horizontalArrangement = Arrangement.spacedBy(KeyboardMetrics.GAP.px),
    ) {
        keys.forEachIndexed { col, key ->
            Box(
                Modifier
                    .weight(key.weight)
                    .fillMaxHeight()
                    .padding(start = if (key.marginStart) KeyboardMetrics.SEP.px else 0.dp, end = if (key.marginEnd) KeyboardMetrics.SEP.px else 0.dp),
            ) {
                KeyCap(key, KeyPos(row, col), state, metrics, palette)
            }
        }
    }
}

// ---- A key ----

@Composable
private fun KeyCap(key: KeyDef, pos: KeyPos, state: FullKeyboardState, metrics: KeyboardMetrics, palette: KeyboardPalette) {
    // Each key only recomposes when its own state changes, not on every move of the focus
    val focused by remember(pos) { derivedStateOf { state.focusVisible && state.focus == pos } }
    val latched by remember(key.code) { derivedStateOf { key.code in state.latched } }
    val pressed by remember(key.code) { derivedStateOf { key.code in state.pressed } }
    // What the key shows follows Shift and Caps Lock; only the keys whose label changes recompose
    val label by remember(key) { derivedStateOf { keyLabel(key, state.shifted, state.capsOn) } }
    val lit = latched && !focused
    // The look, by priority: pressed (cyan), sticky (gold), focused (white, inverted), the Hide key's tint, quiet and plain keys
    val fillTarget = when {
        pressed -> palette.pressed
        lit -> palette.latched
        focused -> palette.focus
        key.kind == KeyKind.HIDE -> palette.hide
        key.quiet -> palette.quiet
        else -> palette.key
    }
    val contentTarget = when {
        pressed -> palette.onPressed
        lit -> palette.onLatched
        focused -> palette.onFocus
        else -> palette.text
    }
    val fill by animateColorAsState(fillTarget, tween(Motion.FADE, easing = Motion.Ease), label = "keyFill")
    val content by animateColorAsState(contentTarget, tween(Motion.FADE, easing = Motion.Ease), label = "keyContent")
    val scale = animateFocusScale(focused = false, pressed = pressed, focusScale = 1f, label = "keyScale")
    val shape = RoundedCornerShape(KEY_CORNER.px)
    val fontSize = metrics.fontSize
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { state.keyCoordinates[key.code] = it }
            // The gliding ring measures this box, before the press squeezes it
            .glideTarget(focused)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(shape)
            .drawBehind { drawRect(fill) }
            .keyTouch(state, key.code),
        contentAlignment = Alignment.Center,
    ) {
        when (key.glyph) {
            KeyGlyph.TEXT -> FadingLegend(label.primary, if (key.small) fontSize * .8f else fontSize, content)
            KeyGlyph.STACKED -> StackedLegend(label, fontSize, content, palette, focused, pressed)
            KeyGlyph.ARROW -> ArrowGlyph(arrowOf(key.code)!!, fontSize * 1.1f, content)
            KeyGlyph.SPACE -> Box(
                Modifier
                    .fillMaxWidth(.38f)
                    .sizeIn(maxWidth = 120f.px)
                    .height(3f.px)
                    .clip(RoundedCornerShape(2f.px))
                    .background(content.copy(alpha = content.alpha * .55f)),
            )
            KeyGlyph.HIDE -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((fontSize * .1f).px)) {
                Symbol(Symbols.CLOSE, (fontSize * 1.05f).tpx, if (pressed) content else palette.hideIcon, filled = focused, fadeMillis = Motion.FADE)
                BasicText("HIDE", style = legendStyle(fontSize * .52f, content, FontWeight.Bold, .1.em))
            }
        }
        // A latched modifier carries a bar under its legend: the state never relies on color alone
        if (latched) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8f.px)
                    .fillMaxWidth(.3f)
                    .height(4f.px)
                    .clip(RoundedCornerShape(2f.px))
                    .background(if (focused) palette.latchedBarFocused else palette.onLatched.copy(alpha = .85f)),
            )
        }
    }
}

/** Touch, as the keys always took it: a key reads its own finger (so several keys can be held), down presses, up or cancel releases. */
private fun Modifier.keyTouch(state: FullKeyboardState, code: Int): Modifier = pointerInput(code) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        if (!state.touchDown(code)) return@awaitEachGesture
        try {
            // The finger holds the key until it lifts, wherever it has wandered to (the touch listener of the old keys did so too)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
            }
        } finally {
            state.touchUp(code)
        }
    }
}

@Composable
private fun StackedLegend(label: KeyLabel, fontSize: Float, content: Color, palette: KeyboardPalette, focused: Boolean, pressed: Boolean) {
    val hintTarget = when {
        pressed -> palette.onPressed
        focused -> palette.focusTop
        else -> palette.text2
    }
    val hint by animateColorAsState(hintTarget, tween(Motion.FADE, easing = Motion.Ease), label = "stackHint")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((fontSize * .08f).px)) {
        // The other character small above, the one the key types now below: Shift swaps them with a crossfade, so nothing moves
        FadingLegend(label.secondary.orEmpty(), fontSize * .7f, hint)
        FadingLegend(label.primary, fontSize, content)
    }
}

/** A legend that crossfades when its text changes (Shift, Caps Lock): both texts are centered in the same box, so the key does not jump. */
@Composable
private fun FadingLegend(text: String, size: Float, color: Color) {
    Crossfade(text, Modifier.fillMaxWidth(), animationSpec = tween(Motion.FADE, easing = Motion.Ease), label = "legend") { shown ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Legend(shown, size, color) }
    }
}

@Composable
private fun Legend(text: String, size: Float, color: Color) {
    BasicText(text, style = legendStyle(size, color, FontWeight.SemiBold, .01.em), maxLines = 1, softWrap = false)
}

private val LegendLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

@Composable
private fun legendStyle(size: Float, color: Color, weight: FontWeight, spacing: TextUnit) = TextStyle(
    color = color,
    fontSize = size.tpx,
    fontFamily = Figtree,
    fontWeight = weight,
    letterSpacing = spacing,
    lineHeight = size.tpx,
    lineHeightStyle = LegendLineHeight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** The arrows are strokes (the icon font has no up and down arrows): 24-unit paths drawn with a 2.4 unit round line. */
private val ArrowPaths = mapOf(
    Arrow.UP to "M12 19V5M5.5 11.5 12 5l6.5 6.5",
    Arrow.DOWN to "M12 5v14M5.5 12.5 12 19l6.5-6.5",
    Arrow.LEFT to "M19 12H5M11.5 5.5 5 12l6.5 6.5",
    Arrow.RIGHT to "M5 12h14M12.5 5.5 19 12l-6.5 6.5",
)

@Composable
private fun ArrowGlyph(arrow: Arrow, size: Float, color: Color) {
    val path = remember(arrow) { PathParser().parsePathString(ArrowPaths.getValue(arrow)).toPath() }
    Canvas(Modifier.size(size.px)) {
        val unit = this.size.minDimension / 24f
        withTransform({ scale(unit, unit, Offset.Zero) }) {
            drawPath(path, color, style = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

// ---- Popup ----

/** The bubble above the pressed key: where the finger is, so it is not hidden under it. Not dimmed with the keys. */
@Composable
private fun KeyPopupLayer(state: FullKeyboardState, metrics: KeyboardMetrics, palette: KeyboardPalette, root: Array<LayoutCoordinates?>) {
    val popup = state.popup ?: return
    val layer = root[0]
    val key = state.keyCoordinates[popup.code]
    if (layer == null || key == null || !layer.isAttached || !key.isAttached) return
    val bounds = layer.localBoundingBoxOf(key, clipBounds = false)
    val reduced = LocalReducedMotion.current
    val k = stageUnit()
    val on = state.popupOn
    val fade by animateFloatAsState(if (on) 1f else 0f, tween(POPUP_FADE_MS, easing = Motion.Ease), label = "popupFade")
    val settle by animateFloatAsState(if (on) 1f else 0f, if (reduced) snap() else tween(Motion.PRESS, easing = EaseSpring), label = "popupSettle")
    if (fade <= 0f && !on) return
    Box(
        Modifier
            .offset { IntOffset(bounds.center.x.roundToInt(), (bounds.top - POPUP_LIFT * k).roundToInt()) }
            .graphicsLayer {
                alpha = fade
                // From 88 % of its height below its place and 0.9 big to its place at full size, centered over the key
                translationX = -size.width / 2f
                translationY = -size.height * (.88f + .12f * settle)
                val s = .9f + .1f * settle
                scaleX = s
                scaleY = s
            }
            .dropShadow(RoundedCornerShape(14f.px)) {
                radius = 34f * k
                spread = -8f * k
                offset = Offset(0f, 16f * k)
                color = Color.Black
                alpha = .7f
            }
            .sizeIn(minWidth = (metrics.keyHeight * POPUP_MIN_WIDTH).px, minHeight = (metrics.keyHeight * POPUP_HEIGHT).px)
            .clip(RoundedCornerShape(14f.px))
            .background(palette.popup)
            .padding(horizontal = 24f.px),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(popup.text, style = legendStyle(40f, palette.onPopup, FontWeight.Bold, TextUnit.Unspecified), maxLines = 1, softWrap = false)
    }
}

// ---- Hints ----

/** `A Press`, `B Close`, `L3 Shift`, `X Delete`, `Y Space` and the strip's title: only while a controller drives the keyboard. */
@Composable
private fun HintStrip(state: FullKeyboardState, palette: KeyboardPalette, bodyWidth: Float, modifier: Modifier) {
    val on = state.open && state.input == InputMode.CONTROLLER
    // They come in after the keys (the row delay), go out with everything else
    val visible by animateFloatAsState(
        if (on) 1f else 0f,
        if (on) tween(Motion.FADE, delayMillis = Motion.ROW_DELAY, easing = Motion.Ease) else tween(Motion.EXIT, easing = Motion.Ease),
        label = "hints",
    )
    Row(
        modifier
            .height(HINT_HEIGHT.px)
            .graphicsLayer { alpha = visible },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Hint("A", "Press", palette)
        Spacer(Modifier.width(30f.px))
        Hint("B", "Close", palette)
        Spacer(Modifier.width(30f.px))
        Hint("L3", "Shift", palette)
        Spacer(Modifier.width(30f.px))
        Hint("X", "Delete", palette)
        Spacer(Modifier.width(30f.px))
        Hint("Y", "Space", palette)
        Spacer(Modifier.weight(1f))
        // The five hints take about 720 px: on a narrower keyboard the title goes, so the strip stays one line
        if (bodyWidth >= TITLE_MIN_WIDTH) {
            BasicText("ON-SCREEN KEYBOARD", style = legendStyle(17f, palette.text3, FontWeight.SemiBold, .14.em), maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun Hint(glyph: String, label: String, palette: KeyboardPalette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12f.px)) {
        Box(Modifier.size(GLYPH_SIZE.px).clip(CircleShape).background(palette.text), contentAlignment = Alignment.Center) {
            // The stick click is a two-letter glyph: smaller, so it stays inside the circle
            BasicText(glyph, style = legendStyle(GLYPH_SIZE * (if (glyph.length > 1) .42f else .55f), palette.onText, FontWeight.ExtraBold, TextUnit.Unspecified))
        }
        BasicText(label, style = legendStyle(22f, palette.text2, FontWeight.SemiBold, TextUnit.Unspecified), maxLines = 1, softWrap = false)
    }
}

private const val GLYPH_SIZE = 36f
private const val TITLE_MIN_WIDTH = 1100f
