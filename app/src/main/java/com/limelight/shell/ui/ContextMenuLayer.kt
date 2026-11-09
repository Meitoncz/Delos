package com.limelight.shell.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import com.limelight.shell.ContextMenu
import com.limelight.shell.MenuHeader
import com.limelight.shell.MenuItem
import com.limelight.shell.drawBoxShadow
import com.limelight.shell.shadowRadius
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlin.math.min
import kotlin.math.roundToInt
import com.limelight.shell.ui.ContextMenuLayout as L

/** The glass of the stream menu's popover and of the prototype's card: this tint (50 %) over the blurred screen. */
private val CardTint = Color(34, 44, 84)

/**
 * The prototype's glass: blur 46 under a navy tint. The prototype sat over a dark backdrop where tint .50 and saturate 1.6 gave a calm navy;
 * over the real, brighter and more colourful dashboard that came out pink-violet (measured: card header (105, 65, 126) against the
 * prototype's (61, 61, 89)), so the tint is denser (.80) and the backdrop is hardly saturated (1.15): the same navy on any background.
 */
@Composable
private fun Modifier.menuGlass(haze: HazeState): Modifier {
    val radius = 46f.px
    val saturate = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.15f) })
    val tint = CardTint.copy(alpha = .80f)
    return hazeBlur(
        HazeInput.Sources(haze),
        style = HazeBlurStyle {
            blurRadius(radius)
            noiseFactor(0f)
            colorEffects(listOf(HazeColorEffect.colorFilter(saturate), HazeColorEffect.tint(tint)))
            fallbackColorEffect(HazeColorEffect.tint(tint.copy(alpha = .92f)))
        },
    )
}

/** The glyph color on a [fill]: near black on a light one, white on a dark one. */
internal fun onColor(fill: Color): Color = if (fill.luminance() > .42f) Color(0xFF14110A) else Color.White

/** Red of a destructive row and of its plate (the prototype's `--danger`). */
internal val MenuDanger = Color(0xFFFF8F86)

private val OnlineDot = Color(0xFF3DDC84)

/**
 * What the layer knows about where its card is: the placement the layout phase resolved (the caret and the point it opens
 * from follow it) and the card's size. The draw phase reads [version], so a new placement redraws the glass without
 * recomposing; [resolved] flips once so the composition can swap the estimated opening point for the real one.
 */
@Stable
private class MenuPlaceState(initial: MenuPlacement) {
    var placement: MenuPlacement = initial
        private set
    var cardWidth = 0f
        private set
    var cardHeight = 0f
        private set
    var version by mutableIntStateOf(0)
        private set
    var resolved by mutableStateOf(false)
        private set
    private var counter = 0
    private var placedOnce = false
    private var shape: PathShape? = null

    fun place(next: MenuPlacement, w: Float, h: Float) {
        if (next != placement || w != cardWidth || h != cardHeight) {
            placement = next
            cardWidth = w
            cardHeight = h
            version = ++counter
        }
        if (!placedOnce) {
            placedOnce = true
            resolved = true
        }
    }

    /** The point the card grows from and shrinks to: the caret's tip. */
    fun origin(): TransformOrigin = TransformOrigin(
        (placement.tipX / StageWidth).coerceIn(0f, 1f), (placement.tipY / StageHeight).coerceIn(0f, 1f),
    )

    /** The outline of the card with its caret inside the layer's [size] (card plus the room around it for the caret), [k] pixels per stage pixel. */
    fun outline(k: Float, size: Size): Shape {
        version // the draw phase redraws with a new placement
        val s = shape
        if (s != null && s.k == k && s.size == size && s.placement == placement) return s
        val outline = menuCardPath(placement.side, placement.caret, size.width / k - 2 * L.CARET_DEPTH, size.height / k - 2 * L.CARET_DEPTH, k)
        return PathShape(outline, k, size, placement).also { shape = it }
    }
}

private const val StageWidth = 1920f
private const val StageHeight = 1080f

private class PathShape(outline: MenuOutline, val k: Float, val size: Size, val placement: MenuPlacement) : Shape {
    val path = outline.card

    /** The caret's curve, lit like the rim of the stream popover's card. */
    val caretEdge = outline.caretEdge

    /** The card moved down 1.5 px: what the card has and this has not is the lit crescent along its top edge. */
    val lower = Path().apply { addPath(outline.card, Offset(0f, 1.5f * k)) }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Generic(path)
}

/** The card with its caret as one filled path, and the caret's curve alone (null without a caret). */
internal class MenuOutline(val card: Path, val caretEdge: Path?)

/**
 * The card's outline as ONE path: a rounded rectangle [w] x [h] (stage pixels) with the caret on [side] centered at [caret]
 * along that edge. The path lies in a box [ContextMenuLayout.CARET_DEPTH] larger than the card on every side (the room of
 * the caret); [k] is pixels per stage pixel. The caret is the prototype's (`a.css`, `.cm-caret.left`: 60 wide, 24 out of the
 * card, a rounded tip), turned to its [side].
 */
internal fun menuCardPath(side: CaretSide, caret: Float, w: Float, h: Float, k: Float): MenuOutline {
    val d = L.CARET_DEPTH * k
    val card = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(d, d, d + w * k, d + h * k, CornerRadius(min(L.CORNER * k, min(w, h) * k / 2f))))
    }
    if (side == CaretSide.None) return MenuOutline(card, null)
    val c = caret * k
    // (u, v) in stage pixels: u along the edge from the caret's center, v out of the card
    fun x(u: Float, v: Float) = k * when (side) {
        CaretSide.Left -> -v
        CaretSide.Right -> v
        else -> u
    } + when (side) {
        CaretSide.Left -> d
        CaretSide.Right -> d + w * k
        else -> d + c
    }
    fun y(u: Float, v: Float) = k * when (side) {
        CaretSide.Top -> -v
        CaretSide.Bottom -> v
        else -> u
    } + when (side) {
        CaretSide.Top -> d
        CaretSide.Bottom -> d + h * k
        else -> d + c
    }
    fun Path.curve() {
        moveTo(x(-30f, 0f), y(-30f, 0f))
        cubicTo(x(-18f, 0f), y(-18f, 0f), x(-11f, 4f), y(-11f, 4f), x(-6f, 13f), y(-6f, 13f))
        quadraticTo(x(0f, 35f), y(0f, 35f), x(6f, 13f), y(6f, 13f))
        cubicTo(x(11f, 4f), y(11f, 4f), x(18f, 0f), y(18f, 0f), x(30f, 0f), y(30f, 0f))
    }
    val edge = Path().apply { curve() }
    val tip = Path().apply {
        curve()
        // Back into the card under its edge, so the union has no seam
        lineTo(x(30f, -2f), y(30f, -2f))
        lineTo(x(-30f, -2f), y(-30f, -2f))
        close()
    }
    return MenuOutline(Path.combine(PathOperation.Union, card, tip), edge)
}

/**
 * The context menu (right menu button / Start): an anchored glass card with a caret pointing at the focused element, a
 * header (an icon in the game's color, status, title), rows in the shared gliding plate, and submenus that swap inside the same card ([path];
 * B goes back through [onPath]). It grows from the caret's tip ([popEnter] / [popExit]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContextMenuLayer(
    menu: ContextMenu?,
    anchor: Rect?,
    path: List<Int>,
    onPath: (List<Int>) -> Unit,
    haze: HazeState,
    onDismiss: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val k = stageUnit()
    // Keep the last menu while animating out
    var shown by remember { mutableStateOf(menu) }
    var shownAnchor by remember { mutableStateOf(anchor) }
    var shownPath by remember { mutableStateOf(path) }
    if (menu != null) {
        shown = menu
        shownAnchor = anchor
        shownPath = path
    }
    val m = shown
    val stageAnchor = shownAnchor?.let { Rect(it.left / k, it.top / k, it.right / k, it.bottom / k) }
    // The card is placed once its size is known; until then the estimated one gives the opening point
    val state = remember(m, stageAnchor) {
        MenuPlaceState(placeContextMenu(stageAnchor, L.MIN_WIDTH, estimatedHeight(m), m?.below == true))
    }
    val resolved = state.resolved
    val origin = remember(state, resolved) { state.origin() }
    val cameFrom = remember { arrayOfNulls<Pair<ContextMenu, List<Int>>>(1) }
    if (menu == null) cameFrom[0] = null
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            menu != null,
            modifier = Modifier.fillMaxSize(),
            enter = popEnter(origin, reduced),
            exit = popExit(origin, reduced),
        ) {
            if (m == null) return@AnimatedVisibility
            CardPlacer(state, stageAnchor, m.below, k) {
                // Switching level (or menu) is a new card content: its rows stagger in again and the plate starts on its first row
                key(m, shownPath) { MenuCard(m, shownPath, cameFrom, onPath, state, haze, k, active = menu != null, onDismiss) }
            }
        }
    }
}

/** A guess of the card's height before it is measured (header and rows), stage pixels. */
private fun estimatedHeight(menu: ContextMenu?): Float {
    val rows = menu?.items?.size ?: 0
    return (110f + rows * (L.ROW_HEIGHT + L.ROW_GAP) + 2 * L.PADDING + 8f).coerceAtMost(L.MAX_HEIGHT)
}

/** Places its child (the card with the room around it for the caret) by [placeContextMenu]. */
@Composable
private fun CardPlacer(state: MenuPlaceState, anchor: Rect?, below: Boolean, k: Float, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val room = L.CARET_DEPTH * k
        val child = measurables.first().measure(
            Constraints(maxWidth = ((L.MAX_WIDTH + 2 * L.CARET_DEPTH) * k).roundToInt(), maxHeight = ((L.MAX_HEIGHT + 2 * L.CARET_DEPTH) * k).roundToInt()),
        )
        val w = child.width / k - 2 * L.CARET_DEPTH
        val h = child.height / k - 2 * L.CARET_DEPTH
        val placement = placeContextMenu(anchor, w, h, below)
        state.place(placement, w, h)
        layout(constraints.maxWidth, constraints.maxHeight) {
            child.place((placement.x * k - room).roundToInt(), (placement.y * k - room).roundToInt())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MenuCard(
    menu: ContextMenu,
    path: List<Int>,
    cameFrom: Array<Pair<ContextMenu, List<Int>>?>,
    onPath: (List<Int>) -> Unit,
    state: MenuPlaceState,
    haze: HazeState,
    k: Float,
    active: Boolean,
    onDismiss: () -> Unit,
) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val reduced = LocalReducedMotion.current
    val items = MenuPath.items(menu.items, path)
    val keys = remember(items.size) { items.indices.toList() }
    val navigation = rememberFocusedListNavigation(keys, allowUpExit = false)
    // Opening puts the focus on the first row; coming back from a submenu on the row that opened it
    val target = remember {
        val before = cameFrom[0]?.takeIf { it.first == menu }?.second
        if (before != null && before.size > path.size) before[path.size].coerceIn(0, (items.size - 1).coerceAtLeast(0)) else 0
    }
    SideEffect { cameFrom[0] = menu to path }
    val first = keys.getOrNull(target)?.let(navigation::requester) ?: remember { FocusRequester() }
    InitialFocus(first, menu to path)
    val scroll = remember { androidx.compose.foundation.ScrollState(0) }
    val bringIntoView = rememberMarginBringIntoView(scroll, before = 40f * k, after = 40f * k, stagePx = k)
    val plateDanger = remember { mutableStateOf(false) }
    val plate = animateColorAsState(if (plateDanger.value) MenuDanger else colors.accent, tween(Motion.ROW_FOCUS, easing = Motion.Ease), label = "menuPlate")
    val decoration = remember(k) { MenuPlate(k) }
    val parent = MenuPath.parent(menu.items, path)
    val topFade = if (scroll.value > 0) 1f else 0f
    val bottomFade = if (scroll.value < scroll.maxValue) 1f else 0f

    Box {
        // The shadow in a layer of its own, so the plate's frames do not redraw it
        Box(Modifier.matchParentSize().graphicsLayer { compositingStrategy = CompositingStrategy.ModulateAlpha }.drawBehind { drawCardShadow(k) })
        // The glass (the prototype's `.cm-glass`): the screen under it blurred 46 and saturated 1.6, a tint (34, 44, 84 at 50 %),
        // a white sheen from the top (13 % fading out at 52 %), a 1 px rim (11 %) all around the card and its caret (the caret is just part of the glass, like the stream popover's) and a 1.5 px lit top edge (26 %)
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    shape = state.outline(k, size)
                    clip = true
                }
                .menuGlass(haze)
                .drawBehind {
                    drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = .13f), .52f to Color.Transparent, 1f to Color.Transparent))
                    val lit = state.outline(k, size)
                    if (lit is PathShape) {
                        // The rim is the inside half of a double-width stroke: the outline clips it
                        clipPath(lit.path) {
                            drawPath(lit.path, Color.White.copy(alpha = .11f), style = Stroke(2f * k))
                            clipPath(lit.lower, androidx.compose.ui.graphics.ClipOp.Difference) { drawRect(Color.White.copy(alpha = .26f)) }
                        }
                    }
                },
        )
        Column(
            Modifier
                .padding(L.CARET_DEPTH.px)
                .animateContentSize(if (reduced) snap() else tween(Motion.ROW_FOCUS, easing = Motion.Glide))
                .focusTrap(active)
                // As wide as the longest row, between the card's least and greatest width
                .width(IntrinsicSize.Max)
                .widthIn(min = L.MIN_WIDTH.px, max = L.MAX_WIDTH.px)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(L.PADDING.px),
        ) {
            Header(menu, parent, path.isNotEmpty(), onBack = { onPath(MenuPath.back(path)) })
            Box(Modifier.weight(1f, fill = false)) {
                RowGlideHost(
                    ring = null,
                    corner = L.ROW_RADIUS,
                    focusScale = 1f,
                    fill = null,
                    glow = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (scroll.maxValue > 0) Modifier.fadeEdges(stage, top = 34f * topFade, bottom = 34f * bottomFade) else Modifier)
                        .focusedListBoundary(navigation)
                        .verticalScroll(scroll),
                    decoration = { alpha -> decoration.draw(this, plate.value, alpha) },
                ) {
                    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
                        Column(
                            Modifier.fillMaxWidth().padding(start = 4.px, end = 4.px, top = 6.px, bottom = 2.px),
                            verticalArrangement = Arrangement.spacedBy(L.ROW_GAP.px),
                        ) {
                            items.forEachIndexed { i, item ->
                                MenuRow(item, i + 1, Modifier.focusedListRow(navigation, i), onFocused = { plateDanger.value = item.danger }) {
                                    val deeper = MenuPath.open(menu.items, path, i)
                                    if (deeper != null) onPath(deeper)
                                    else {
                                        onDismiss()
                                        item.action()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The card's soft shadow (`0 40px 90px -20px`), a layer of its own so the plate's frames do not redraw it. */
private fun DrawScope.drawCardShadow(k: Float) {
    val room = L.CARET_DEPTH * k
    drawBoxShadow(Rect(room, room, size.width - room, size.height - room), L.CORNER * k, 40f * k, 90f * k, -20f * k, Color.Black.copy(alpha = .66f))
}

/** Cover or back chip, status line, title and meta line of the card. */
@Composable
private fun Header(menu: ContextMenu, parent: MenuItem?, submenu: Boolean, onBack: () -> Unit) {
    val colors = LocalDelosColors.current
    val header: MenuHeader? = menu.header
    val eyebrow = if (submenu) parent?.label else header?.status
    Column(Modifier.staggerEntrance(0, Motion.MENU_DELAY)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.px, end = 16.px, top = 8.px, bottom = 14.px),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.px),
        ) {
            if (submenu) {
                // Touch only (B goes back on a controller): never focusable
                Box(
                    Modifier.size(52.px).clip(CircleShape).background(Color.White.copy(alpha = .10f))
                        .pointerInput(Unit) { detectTapGestures { onBack() } },
                    contentAlignment = Alignment.Center,
                ) { Symbol(Symbols.CHEVRON_LEFT, 28.tpx, colors.text) }
            } else if (header != null) {
                // The game's main color with a controller on it (the dark or the white glyph, whichever reads better)
                val fill = header.tint ?: colors.accent
                val shape = RoundedCornerShape(14.px)
                Box(
                    Modifier.size(width = 52.px, height = 78.px).clip(shape)
                        .background(fill.copy(alpha = .85f))
                        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = .22f), .6f to Color.Transparent))
                        .border(1.px, Color.White.copy(alpha = .16f), shape),
                    contentAlignment = Alignment.Center,
                ) { Symbol(Symbols.SPORTS_ESPORTS, 34.tpx, onColor(fill), filled = true) }
            }
            Column(Modifier.weight(1f)) {
                if (eyebrow != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.px)) {
                        if (!submenu && header?.running == true) Box(Modifier.size(10.px).clip(CircleShape).background(OnlineDot))
                        BasicText(
                            eyebrow.uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 16.tpx, letterSpacing = .13.em),
                        )
                    }
                }
                BasicText(
                    menu.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 25.tpx),
                    modifier = Modifier.padding(top = if (eyebrow != null) 3.px else 0.px, bottom = 2.px),
                )
                header?.meta?.let {
                    BasicText(
                        it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = colors.text2, fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 19.tpx),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(bottom = 4.px).height(1.px).background(Color.White.copy(alpha = .10f)))
    }
}

/** A row: icon, label and, for a submenu, a chevron. Hints stay in the hint strip. */
@Composable
private fun MenuRow(item: MenuItem, index: Int, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    val colors = LocalDelosColors.current
    var focused by remember { mutableStateOf(false) }
    val rest = if (item.danger) MenuDanger else colors.text2
    val iconTint by animateColorAsState(if (item.danger) MenuDanger else if (focused) colors.accent else rest, tween(Motion.ROW_FOCUS, easing = Motion.Ease), label = "menuItemIcon")
    Row(
        modifier
            .staggerEntrance(minOf(index, Motion.STAGGER_CAP), Motion.MENU_DELAY)
            .focusEffects(
                onClick = onClick, corner = L.ROW_RADIUS, focusScale = 1f, ring = null, glow = null, glide = true,
                onFocusChange = {
                    focused = it
                    if (it) onFocused()
                },
            )
            .fillMaxWidth()
            .height(L.ROW_HEIGHT.px)
            .clip(RoundedCornerShape(L.ROW_RADIUS.px))
            .padding(start = 24.px, end = 22.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.px),
    ) {
        Symbol(item.icon, 30.tpx, iconTint, filled = focused)
        BasicText(
            item.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (item.danger) MenuDanger else colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 23.tpx),
            modifier = Modifier.weight(1f),
        )
        if (item.children != null) Symbol(Symbols.CHEVRON_RIGHT, 26.tpx, colors.text3)
    }
}

/**
 * The plate of the menu's rows (drawn by [RowGlideHost]): a [color] fill (17 %), a 3 px ring and a soft glow around the
 * focused row. [color] is the accent, or red while a destructive row has the focus (it cross-fades as the plate glides).
 * The glow's paint and clip are reused between frames.
 */
private class MenuPlate(private val k: Float) {
    private val stroke = Stroke(L.ROW_RING * k)
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.BLACK }
    private val clip = Path()
    private var shadowColor = 0

    fun draw(scope: DrawScope, color: Color, alpha: Float) = with(scope) {
        val radius = min(L.ROW_RADIUS * k, size.minDimension / 2f)
        val ring = L.ROW_RING * k
        // The glow (0 0 20px -6px): outside the plate only
        val argb = color.copy(alpha = .45f * alpha).toArgb()
        if (argb != shadowColor) {
            shadowColor = argb
            paint.setShadowLayer(shadowRadius(20f * k), 0f, 0f, argb)
        }
        val spread = 6f * k
        clip.asAndroidPath().apply {
            rewind()
            addRoundRect(0f, 0f, size.width, size.height, radius, radius, android.graphics.Path.Direction.CW)
        }
        clipPath(clip, androidx.compose.ui.graphics.ClipOp.Difference) {
            drawIntoCanvas {
                it.nativeCanvas.drawRoundRect(spread, spread, size.width - spread, size.height - spread, (radius - spread).coerceAtLeast(0f), (radius - spread).coerceAtLeast(0f), paint)
            }
        }
        drawRoundRect(color.copy(alpha = .17f * alpha), cornerRadius = CornerRadius(radius))
        drawRoundRect(
            color.copy(alpha = alpha), topLeft = Offset(ring / 2f, ring / 2f), size = Size(size.width - ring, size.height - ring),
            cornerRadius = CornerRadius((radius - ring / 2f).coerceAtLeast(0f)), style = stroke,
        )
    }
}
