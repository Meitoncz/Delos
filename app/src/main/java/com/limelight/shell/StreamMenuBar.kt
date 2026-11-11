package com.limelight.shell

import androidx.compose.ui.res.stringResource
import com.limelight.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.limelight.shell.ui.fadeMask
import com.limelight.shell.ui.rememberMarginBringIntoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.em
import com.limelight.shell.StreamMenuLayout as L
import com.limelight.shell.StreamMenuMotion as M
import com.limelight.shell.ui.EaseOut
import com.limelight.shell.ui.EaseSpring
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.Glyph
import com.limelight.shell.ui.GlyphButton
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.Motion
import com.limelight.shell.ui.animateFocusScale
import com.limelight.shell.ui.rememberPressPulse
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import kotlin.math.roundToInt

/** Called with the help key of the popover row that gets the focus; the popover's help text shows its text. */
internal val LocalStreamHelp = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * One tile of the dock. [label] is also its help key. [on] is a toggle's state (the accent glass and an "On" / "Off"
 * pill), null for actions; [state] replaces "On" / "Off" for toggles with more steps; [showState] false keeps the accent
 * but draws no pill. [opensPage] is a tile whose
 * page opens in the popover: those stay usable while a popover is open.
 */
@Immutable
internal class BarCircleSpec(
    val item: BarItem,
    val icon: String,
    val label: String,
    val on: Boolean? = null,
    val state: String? = null,
    val showState: Boolean = true,
    val opensPage: Boolean = false,
    val onClick: () -> Unit,
)

/** A dimmed tile that opens no page does nothing while a popover is open (and cannot take the focus out of it). */
private fun tileFocusable(spec: BarCircleSpec, popOpen: Boolean): Boolean = !(popOpen && !spec.opensPage)

/** The room a node keeps around its box so a shadow that reaches outside it is not clipped by an alpha layer, stage pixels. */
private const val SHADOW_ROOM = 130f

private val Backing = Color(0xFF05070F)

/**
 * The soft gradient under the dock (`#grad`) and the extra scrim while a popover is open (`#scrim`): the only dimming
 * of the stream, there is no card behind the tiles. Both fill the whole area; their alphas are read while drawing.
 */
@Composable
internal fun StreamMenuBackdrop(gradientAlpha: () -> Float, scrimAlpha: () -> Float, modifier: Modifier = Modifier) {
    val gradient = remember {
        Brush.verticalGradient(
            0f to Color(4, 6, 14, 0),
            .34f to Color(4, 6, 14, 0),
            .48f to Color(4, 6, 14).copy(alpha = .22f),
            .66f to Color(4, 6, 14).copy(alpha = .55f),
            .84f to Color(4, 6, 14).copy(alpha = .80f),
            1f to Color(4, 6, 14).copy(alpha = .90f),
        )
    }
    Box(
        modifier.fillMaxSize().drawBehind {
            val g = gradientAlpha()
            if (g > 0f) drawRect(gradient, alpha = g)
            val s = scrimAlpha()
            if (s > 0f) drawRect(Color(4, 6, 14).copy(alpha = L.SCRIM_POPOVER_ALPHA), alpha = s)
        }
    )
}

/**
 * The dock (docs/GUI.md, "Stream menu"): the game's name and the button hints above the row of nine glass tiles, one
 * gold ring that glides between them, and under them the caption with the complete help of the focused tile. All of it
 * is placed on the 1920-pixel-wide dock box (anchored to the stage's bottom edge) with the tiles' top at [StreamMenuLayout.DOCK_TOP]; the popover sinks it by
 * [dockShift] stage pixels. [enter] and [exit] are the clocks (milliseconds) of the entrance and the exit, read while
 * drawing; [firstItem] is the tile the focus starts on (the ring arrives after it).
 *
 * On a compact stage the nine tiles do not fit: they keep their size and the row scrolls sideways ([scroll], [DockScroller]), the ring,
 * the pills and the labels with it. [headHidden] fades the name and the hints out (an open card covers them there); [onHeadWidth]
 * reports how wide they are, stage pixels. [onOutsideTap] is a tap on the row beside the tiles.
 */
@Composable
internal fun StreamMenuDock(
    title: String,
    circles: List<BarCircleSpec>,
    requesters: Map<BarItem, FocusRequester>,
    focusItem: BarItem?,
    firstItem: BarItem,
    popOpen: Boolean,
    caption: String,
    enter: () -> Float,
    exit: () -> Float,
    dockShift: () -> Float,
    onCircleFocused: (BarItem, Boolean) -> Unit,
    scroll: ScrollState,
    headHidden: Boolean,
    onHeadWidth: (Float) -> Unit,
    onOutsideTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ts = LocalStage.current.textScale
    val k = stageScale()
    val g = LocalDockGeometry.current
    Box(
        modifier
            .size(g.width.px, g.dockHeight.px)
            .graphicsLayer { translationY = dockShift() * k },
    ) {
        DockHead(
            title, enter, exit, Modifier.offset(g.tilesLeft.px, 0.px), maxTitleWidth = minOf(L.TITLE_MAX_WIDTH, g.width - 2f * g.tilesLeft),
            hidden = headHidden, onWidth = onHeadWidth,
        )
        val tiles: @Composable () -> Unit = {
            TileRing(focusItem ?: firstItem, firstItem, enter, exit)
            // The bar wraps: Right on the last tile that can take the focus goes to the first one and Left on the first goes
            // to the last. Both ends come from the list (a hidden tile or the dimmed ones of an open popover are skipped).
            val reachable = circles.filter { tileFocusable(it, popOpen) }
            val firstReachable = reachable.firstOrNull()
            val lastReachable = reachable.lastOrNull()
            circles.forEach { spec ->
                val wrapLeft = if (spec === firstReachable && lastReachable !== spec) requesters.getValue(lastReachable!!.item) else null
                val wrapRight = if (spec === lastReachable && firstReachable !== spec) requesters.getValue(firstReachable!!.item) else null
                TileCell(
                    spec, spec.item == focusItem, popOpen, requesters.getValue(spec.item), wrapLeft, wrapRight, enter, exit, onCircleFocused,
                    Modifier.offset(tileLeft(spec.item, g).px, 0.px),
                )
            }
        }
        if (g.scrolls) DockScroller(scroll, onOutsideTap, tiles) else tiles()
        DockCaption(caption, popOpen, enter, exit, Modifier.offset(((g.width - g.captionWidth) / 2f).px, captionTop(ts, g).px))
    }
}

/**
 * A compact stage's row of tiles: as wide as the stage, scrolling sideways under the D-pad focus (the focused tile keeps
 * [DockGeometry.FOCUS_MARGIN] from the edges, so it is whole and the next tile peeks in) and under a finger. The scroll area starts
 * [DockGeometry.ROOM_TOP] above the tiles and ends at the dock's bottom, so its layer cuts nothing of the ring, its glow, the pills, the
 * labels or the tiles' entrance; an edge fades only while the row goes on beyond it, so the first and the last tile are never faded.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockScroller(scroll: ScrollState, onOutsideTap: () -> Unit, content: @Composable () -> Unit) {
    val stage = LocalStage.current
    val g = LocalDockGeometry.current
    val k = stageScale()
    val margin = DockGeometry.FOCUS_MARGIN * k
    val bringIntoView = rememberMarginBringIntoView(scroll, before = margin, after = margin, stagePx = k)
    val more by remember(scroll) { derivedStateOf { (scroll.value > 0) to (scroll.value < scroll.maxValue) } }
    val startFade by animateFloatAsState(if (more.first) 1f else 0f, tween(Motion.FADE, easing = Motion.Ease), label = "dockStartFade")
    val endFade by animateFloatAsState(if (more.second) 1f else 0f, tween(Motion.FADE, easing = Motion.Ease), label = "dockEndFade")
    val top = DockGeometry.ROOM_TOP
    Box(
        Modifier
            .offset(0.px, (-top).px)
            .size(g.width.px, (top + g.dockHeight).px)
            .fadeMask(stage, vertical = false, startClear = 0f, startSolid = DockGeometry.FADE * startFade, endSolid = DockGeometry.FADE * endFade, endClear = 0f)
            .pointerInput(Unit) { detectTapGestures { onOutsideTap() } }
            .horizontalScroll(scroll),
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
            Box(Modifier.padding(top = top.px).size(g.rowWidth.px, g.dockHeight.px)) { content() }
        }
    }
}

/**
 * The game's name (Unbounded 36) and the stacked A / B hints, their bottom edge [StreamMenuLayout.HEAD_BOTTOM] above the tiles.
 * [hidden] fades them out; [onWidth] reports their width in stage pixels.
 */
@Composable
private fun DockHead(title: String, enter: () -> Float, exit: () -> Float, modifier: Modifier, maxTitleWidth: Float, hidden: Boolean, onWidth: (Float) -> Unit) {
    val colors = LocalDelosColors.current
    val rm = LocalReducedMotion.current
    val k = stageScale()
    val shadow = menuTextShadow(k, 2f, 16f, .55f)
    val shown by animateFloatAsState(if (hidden) 0f else 1f, tween(Motion.FADE, easing = Motion.Ease), label = "dockHead")
    Column(
        modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Int.MAX_VALUE))
                layout(placeable.width, 0) { placeable.place(0, -(placeable.height + (L.HEAD_BOTTOM * k).roundToInt())) }
            }
            .graphicsLayer {
                val p = Motion.progress(enter(), M.HEAD_DELAY, Motion.ENTER, EaseOut)
                alpha = p.coerceIn(0f, 1f) * (1f - Motion.linear(exit(), 0, Motion.EXIT)) * shown
                translationY = if (rm) 0f else 24f * (1f - p) * k
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .onSizeChanged { onWidth(it.width / k) },
        verticalArrangement = Arrangement.spacedBy(L.HEAD_GAP.px),
    ) {
        BasicText(
            title,
            style = TextStyle(
                color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = L.TITLE_SIZE.fsp,
                lineHeight = L.TITLE_LINE_HEIGHT.em, lineHeightStyle = CssLineHeight, letterSpacing = (-.005f).em, shadow = shadow,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = maxTitleWidth.px),
        )
        // A low compact stage has no room for the two hints one above the other: side by side there
        if (LocalDockGeometry.current.hintsInRow) Row(horizontalArrangement = Arrangement.spacedBy(L.HINTS_ROW_GAP.px)) {
            DockHint(Glyph.A, stringResource(R.string.stream_menu_hint_select), shadow)
            DockHint(Glyph.B, stringResource(R.string.stream_menu_hint_back), shadow)
        } else Column(verticalArrangement = Arrangement.spacedBy(L.HINTS_GAP.px)) {
            DockHint(Glyph.A, stringResource(R.string.stream_menu_hint_select), shadow)
            DockHint(Glyph.B, stringResource(R.string.stream_menu_hint_back), shadow)
        }
    }
}

@Composable
private fun DockHint(glyph: Glyph, label: String, shadow: Shadow) {
    val colors = LocalDelosColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.px)) {
        GlyphButton(glyph, size = L.HINT_GLYPH_SIZE)
        BasicText(
            label,
            style = TextStyle(color = colors.text2, fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = L.HINT_SIZE.tpx, shadow = shadow),
            maxLines = 1,
        )
    }
}

/**
 * The caption under the tiles: the complete help of the focused tile, centered, in a box as tall as three lines. A new
 * text slides up 18 px out of a 6 px blur; while a popover is open it is gone.
 */
@Composable
private fun DockCaption(text: String, popOpen: Boolean, enter: () -> Float, exit: () -> Float, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val rm = LocalReducedMotion.current
    val ts = LocalStage.current.textScale
    val k = stageScale()
    val change = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(text) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        change.snapTo(0f)
        change.animateTo(1f, tween(Motion.CAPTION, easing = EaseOut))
    }
    val popAlpha by animateFloatAsState(if (popOpen) 0f else 1f, tween(Motion.FADE, easing = Motion.Ease), label = "captionPop")
    Box(
        modifier
            .size(LocalDockGeometry.current.captionWidth.px, captionHeight(ts).px)
            .graphicsLayer {
                val p = Motion.progress(enter(), M.CAPTION_DELAY, Motion.ENTER, EaseOut)
                val c = change.value
                alpha = p.coerceIn(0f, 1f) * (1f - Motion.linear(exit(), 0, Motion.EXIT)) * popAlpha * c.coerceIn(0f, 1f)
                translationY = if (rm) 0f else (20f * (1f - p) + 18f * (1f - c)) * k
                renderEffect = if (rm || c >= 1f) null else BlurEffect(6f * (1f - c) * k, 6f * (1f - c) * k)
            },
    ) {
        BasicText(
            text,
            style = TextStyle(
                color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = L.CAPTION_SIZE.tpx,
                lineHeight = L.CAPTION_LINE_HEIGHT.em, lineHeightStyle = CssLineHeight, textAlign = TextAlign.Center, lineBreak = LineBreak.Heading,
                shadow = menuTextShadow(k, 2f, 14f, .65f),
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The dock's one focus ring: a gold 186 box (radius 56, 4 px) with an outer glow and a soft glow inside that breathes
 * (2.6 s) and glides to whichever tile is [target] with the prototype's `--ease-glide` (560 ms, overshoots). While the
 * focus is in a popover it rests on the tile that opened it. It arrives after the first tile
 * ([firstItem]): from a 0.7 scale, 260 ms after that tile. Behind the tiles, so only what reaches outside them shows.
 */
@Composable
private fun TileRing(target: BarItem, firstItem: BarItem, enter: () -> Float, exit: () -> Float) {
    val colors = LocalDelosColors.current
    val rm = LocalReducedMotion.current
    val k = stageScale()
    val g = LocalDockGeometry.current
    val x = remember { Animatable(ringLeft(target, g)) }
    LaunchedEffect(target) {
        val goal = ringLeft(target, g)
        if (rm) x.snapTo(goal) else x.animateTo(goal, tween(Motion.RING, easing = Motion.Glide))
    }
    val breathe = if (rm) null else rememberInfiniteTransition(label = "breathe").animateFloat(
        0f, 1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = Motion.BREATHE
                0f at 0 using Motion.EaseInOut
                1f at Motion.BREATHE / 2 using Motion.EaseInOut
                0f at Motion.BREATHE
            },
            RepeatMode.Restart,
        ),
        label = "breatheValue",
    )
    val room = SHADOW_ROOM
    Box(
        Modifier
            .offset { IntOffset((x.value * k).roundToInt(), (g.ringTop * k).roundToInt()) }
            .size(g.ring.px),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .requiredSize((g.ring + 2 * room).px)
                .graphicsLayer {
                    val p = Motion.progress(enter(), M.ringDelay(firstItem.ordinal), Motion.ENTER, EaseSpring)
                    alpha = p.coerceIn(0f, 1f) * (1f - Motion.linear(exit(), 0, Motion.EXIT))
                    val s = if (rm) 1f else Motion.lerp(.7f, 1f, p)
                    scaleX = s
                    scaleY = s
                }
                .drawBehind {
                    val b = breathe?.value ?: 0f
                    val m = room * k
                    val box = Rect(m, m, m + g.ring * k, m + g.ring * k)
                    val corner = g.ringRadius * k
                    // 0 0 44px 4px glow, breathing to 0 0 62px 8px (the spread is folded into the strength: a positive one would show the shape itself)
                    drawBoxShadow(box, corner, 0f, Motion.lerp(44f, 62f, b) * k, 0f, colors.glow.copy(alpha = colors.glow.alpha * Motion.lerp(1.15f, 1.3f, b)))
                    val stroke = L.RING_WIDTH * k
                    drawRoundRect(
                        colors.accent,
                        topLeft = Offset(box.left + stroke / 2f, box.top + stroke / 2f),
                        size = Size(box.width - stroke, box.height - stroke),
                        cornerRadius = CornerRadius(corner - stroke / 2f),
                        style = Stroke(stroke),
                    )
                    // inset 0 0 30px -4px glow-soft, breathing to 36px -2px
                    val inner = Rect(box.left + stroke, box.top + stroke, box.right - stroke, box.bottom - stroke)
                    drawInsetShadow(inner, corner - stroke, Motion.lerp(30f, 36f, b) * k, Motion.lerp(-4f, -2f, b) * k, colors.accent.copy(alpha = .22f))
                },
        )
    }
}

/**
 * A tile of the dock with its label: 150 px of frosted glass (a 28 % dark undercoat stands in for the blur the
 * stream's window cannot give), the icon, and for toggles the On / Off pill. Focus lifts it to 1.12 with the
 * prototype's spring, lights its glass, fills its icon and turns the label gold and 8 px lower; a toggle that is on has
 * accent glass and an accent icon. While a popover is open the tiles but the focused one dim. The entrance (each tile
 * 42 ms after the one before, rising 90 px from a 0.8 scale with the spring) and the exit (the last tile first) run on
 * the [enter] and [exit] clocks.
 */
@Composable
private fun TileCell(
    spec: BarCircleSpec,
    focused: Boolean,
    popOpen: Boolean,
    requester: FocusRequester,
    wrapLeft: FocusRequester?,
    wrapRight: FocusRequester?,
    enter: () -> Float,
    exit: () -> Float,
    onFocused: (BarItem, Boolean) -> Unit,
    modifier: Modifier,
) {
    val colors = LocalDelosColors.current
    val rm = LocalReducedMotion.current
    val k = stageScale()
    val g = LocalDockGeometry.current
    val index = spec.item.ordinal
    val on = spec.on == true
    val dim = popOpen && !focused
    val press = rememberPressPulse()

    val scale by animateFocusScale(focused, press.pressed, L.FOCUS_SCALE, L.PRESS_SCALE, "tileScale")
    val iconScale by animateFloatAsState(if (focused) L.ICON_FOCUS_SCALE else 1f, if (rm) snap() else tween(Motion.FOCUS, easing = EaseSpring), label = "iconScale")
    val labelShift by animateFloatAsState(if (focused) L.LABEL_FOCUS_SHIFT else 0f, if (rm) snap() else tween(Motion.FOCUS, easing = EaseSpring), label = "labelShift")
    val focusFill by animateFloatAsState(if (focused) 1f else 0f, tween(Motion.FADE, easing = Motion.Ease), label = "focusFill")
    val onFill by animateFloatAsState(if (on) 1f else 0f, tween(Motion.FADE, easing = Motion.Ease), label = "onFill")
    val lift by animateFloatAsState(if (focused) 1f else 0f, tween(Motion.FOCUS, easing = EaseOut), label = "tileLift")
    val tileAlpha by animateFloatAsState(if (dim) .46f else 1f, tween(Motion.FADE, easing = Motion.Ease), label = "tileAlpha")
    // A low compact stage's sunk dock has no room for the labels: they fade out while a popover is open
    val labelAlpha by animateFloatAsState(if (popOpen && !g.sunkLabels) 0f else if (dim) .4f else 1f, tween(Motion.FADE, easing = Motion.Ease), label = "labelAlpha")
    val iconColor by animateColorAsState(if (on) colors.accent else colors.text, tween(Motion.FADE, easing = Motion.Ease), label = "iconColor")
    val labelColor by animateColorAsState(if (focused) colors.accent else colors.text2, tween(Motion.FADE, easing = Motion.Ease), label = "labelColor")

    Box(
        modifier
            .size(g.tile.px)
            // A dimmed tile that opens no page does nothing while a popover is open (and cannot take the focus out of it)
            .focusProperties {
                canFocus = tileFocusable(spec, popOpen)
                wrapLeft?.let { left = it }
                wrapRight?.let { right = it }
            }
            .focusRequester(requester)
            .focusEffects(
                {
                    press.fire()
                    if (!popOpen || spec.opensPage) spec.onClick()
                },
                corner = g.tileRadius, focusScale = 1f, ring = null, glow = null, pressScale = 1f,
                onFocusChange = { onFocused(spec.item, it) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .requiredSize((g.tile + 2 * SHADOW_ROOM).px)
                .graphicsLayer {
                    val p = Motion.progress(enter(), M.tileDelay(index), Motion.ENTER, EaseSpring)
                    val q = Motion.progress(exit(), M.exitDelay(index), Motion.EXIT, Motion.In)
                    var s = scale
                    var dy = 0f
                    if (!rm) {
                        s = Motion.lerp(.8f, scale, p)
                        dy = 90f * (1f - p)
                        s = Motion.lerp(s, .92f, q)
                        dy += 46f * q
                    }
                    scaleX = s
                    scaleY = s
                    translationY = dy * k
                    alpha = p.coerceIn(0f, 1f) * (1f - q) * tileAlpha
                }
                .drawBehind {
                    val m = SHADOW_ROOM * k
                    val box = Rect(m, m, m + g.tile * k, m + g.tile * k)
                    val corner = g.tileRadius * k
                    // 0 18px 40px -14px .55 at rest; focused: 0 26px 56px -14px .6 and 0 0 60px -8px glow-soft
                    drawBoxShadow(box, corner, 18f * k, 40f * k, -14f * k, Color.Black.copy(alpha = .55f * (1f - lift)))
                    drawBoxShadow(box, corner, 26f * k, 56f * k, -14f * k, Color.Black.copy(alpha = .60f * lift))
                    drawBoxShadow(box, corner, 0f, 60f * k, -8f * k, colors.accent.copy(alpha = .22f * lift))
                    inset(m) {
                        drawRoundRect(Backing.copy(alpha = L.TILE_BACKING_ALPHA), cornerRadius = CornerRadius(corner))
                        drawTileFill(corner)
                        drawTileFocusFill(corner, focusFill)
                        drawTileOnFill(corner, colors.accent, onFill)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(g.tile.px), contentAlignment = Alignment.Center) {
                Symbol(
                    spec.icon, g.icon.fsp, iconColor,
                    Modifier.graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                    filled = focused, fadeMillis = Motion.FADE,
                )
                if (spec.on != null && spec.showState) {
                    TilePill(spec.state ?: stringResource(if (on) R.string.common_on else R.string.common_off), on, Modifier.align(Alignment.TopEnd).offset((-L.PILL_RIGHT).px, L.PILL_TOP.px))
                }
            }
        }
        BasicText(
            spec.label,
            style = TextStyle(
                color = labelColor, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = L.LABEL_SIZE.tpx,
                lineHeight = L.LABEL_LINE_HEIGHT.em, lineHeightStyle = CssLineHeight, textAlign = TextAlign.Center, shadow = menuTextShadow(k, 2f, 12f, .6f),
            ),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .wrapContentWidth(unbounded = true)
                .graphicsLayer {
                    val e = Motion.progress(enter(), M.labelDelay(index), M.LABEL_DURATION, Motion.EaseOutCss)
                    alpha = e * (1f - Motion.linear(exit(), 0, Motion.EXIT)) * labelAlpha
                    translationY = (g.tile + L.LABEL_GAP + labelShift) * k
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        )
    }
}

/** The On / Off pill on a toggle tile's top right corner (it grows with the tile). A new text pops in from a 0.6 scale. */
@Composable
private fun TilePill(text: String, on: Boolean, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val rm = LocalReducedMotion.current
    val ts = LocalStage.current.textScale
    val k = stageScale()
    val pop = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(text) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (rm) return@LaunchedEffect
        pop.snapTo(.6f)
        pop.animateTo(1f, tween(Motion.PILL_POP, easing = EaseSpring))
    }
    // The pill is as wide as its text (it may be wider than the tile), its right edge 12 px outside the tile's
    Box(modifier.wrapContentWidth(Alignment.End, unbounded = true)) {
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                }
                .drawBehind {
                    val box = Rect(Offset.Zero, size)
                    val corner = size.height / 2f
                    if (on) {
                        drawBoxShadow(box, corner, 8f * k, 22f * k, -6f * k, colors.glow)
                        drawRoundRect(colors.accent, cornerRadius = CornerRadius(corner))
                    } else {
                        drawBoxShadow(box, corner, 8f * k, 18f * k, -6f * k, Color.Black.copy(alpha = .55f))
                        drawRoundRect(Color(22, 28, 48).copy(alpha = .78f), cornerRadius = CornerRadius(corner))
                    }
                }
                .requiredHeight((L.PILL_HEIGHT * ts).px)
                .padding(horizontal = (L.PILL_PADDING_X * ts).px),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text,
                style = TextStyle(
                    color = if (on) colors.onAccent else colors.text2, fontFamily = Figtree, fontWeight = FontWeight.Bold,
                    fontSize = L.PILL_SIZE.tpx, letterSpacing = .02f.em, lineHeight = 1f.em, lineHeightStyle = CssLineHeight,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
