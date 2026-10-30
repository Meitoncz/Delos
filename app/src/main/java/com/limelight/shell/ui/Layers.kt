package com.limelight.shell.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.limelight.shell.ContextMenu
import com.limelight.shell.Toast
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlinx.coroutines.delay

/**
 * The animated background as a blur source, for glass that stays on (the focused settings row, the top
 * bar); overlays blur the whole screen through their own state instead.
 */
val LocalBackdropHaze = androidx.compose.runtime.staticCompositionLocalOf<HazeState?> { null }

/** Blur of the content behind an overlay, with a tint; the tint alone below Android 12. */
@Composable
fun Modifier.backdropBlur(haze: HazeState, radius: Float, tint: Color): Modifier {
    val r = radius.px
    return hazeBlur(
        HazeInput.Sources(haze),
        style = HazeBlurStyle {
            blurRadius(r)
            noiseFactor(0f)
            colorEffects(listOf(HazeColorEffect.tint(tint)))
            fallbackColorEffect(HazeColorEffect.tint(tint.copy(alpha = (tint.alpha + .35f).coerceAtMost(.92f))))
        },
    )
}

/**
 * Frosted glass over the animated background with [tint], like the top bar and the focused settings row;
 * just [tint] inside overlays, which turn the background source off ([LocalBackdropHaze] is null there).
 */
@Composable
fun Modifier.glass(tint: Color): Modifier =
    LocalBackdropHaze.current?.let { backdropBlur(it, 8f, tint) } ?: background(tint)

/** Dims and slightly blurs the screen under a menu (`#scrim`); a tap outside closes the menu. */
@Composable
fun Scrim(visible: Boolean, haze: HazeState, onDismiss: () -> Unit) {
    val colors = LocalDelosColors.current
    AnimatedVisibility(visible, enter = fadeIn(tween(350)), exit = fadeOut(tween(350))) {
        Box(
            Modifier
                .fillMaxSize()
                .backdropBlur(haze, 6f, colors.scrim)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
    }
}

/**
 * Keeps the focus inside an open layer: D-pad moves cannot leave it while [active]. Once the layer
 * closes (still animating out) the trap is released, so the focus can return to the screen.
 */
@Composable
private fun Modifier.focusTrap(active: Boolean): Modifier {
    val current by rememberUpdatedState(active)
    return focusProperties { onExit = { if (current) cancelFocusChange() } }.focusGroup()
}

/**
 * The context menu (right menu button / Start), placed at the focused element like in the prototype:
 * slightly inside its top-left corner, kept on screen. With [below] it opens centered under the
 * element instead (a menu that belongs to a button, like the start screen's profile).
 */
@Composable
fun ContextMenuLayer(menu: ContextMenu?, anchor: Rect?, below: Boolean, haze: HazeState, onDismiss: () -> Unit) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val density = LocalDensity.current.density
    // Keep the last menu while animating out
    var shown by remember { mutableStateOf(menu) }
    var shownAnchor by remember { mutableStateOf(anchor) }
    var shownBelow by remember { mutableStateOf(below) }
    if (menu != null) {
        shown = menu
        shownAnchor = anchor
        shownBelow = below
    }
    val k = stage.dpPerPx * density
    val a = shownAnchor
    val centered = shownBelow && a != null
    var x = 760f
    var y = 300f
    if (a != null) {
        if (centered) {
            x = a.center.x / k
            y = a.bottom / k + 18f
        } else {
            x = a.left / k + minOf(60f, a.width / k / 2f)
            y = a.top / k + minOf(80f, a.height / k / 2f)
        }
    }
    if (!centered) x = x.coerceIn(40f, 1920f - 420f)
    y = y.coerceIn(if (centered) 40f else 110f, (1080f - 120f - 72f * (shown?.items?.size ?: 0)).coerceAtLeast(110f))
    // The animated layer covers the whole screen and grows from the menu's corner (or its top center):
    // a layer only as big as the menu would cut off the part of the menu that is moved down into place
    val origin = TransformOrigin(x / 1920f, y / 1080f)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            menu != null,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(300)) + scaleIn(tween(450, easing = EaseOut), initialScale = .9f, transformOrigin = origin),
            exit = fadeOut(tween(300)) + scaleOut(tween(450, easing = EaseOut), targetScale = .9f, transformOrigin = origin),
        ) {
            val m = shown ?: return@AnimatedVisibility
            val first = remember { FocusRequester() }
            InitialFocus(first, m)
            Column(
                Modifier
                    .then(
                        if (!centered) Modifier.wrapContentSize(Alignment.TopStart).offset(x.px, y.px)
                        // Centered on x once its width is known, kept 40 stage pixels from the screen edges
                        else Modifier.layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                val left = (x * k - placeable.width / 2f).coerceIn(40f * k, constraints.maxWidth - placeable.width - 40f * k)
                                placeable.place(left.toInt(), (y * k).toInt())
                            }
                        }
                    )
                    .focusTrap(menu != null)
                    // As wide as the longest item (the items fill that width), at least 380
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 380.px)
                    .clip(RoundedCornerShape(30.px))
                    .backdropBlur(haze, 24f, colors.surface3)
                    .border(1.px, colors.line, RoundedCornerShape(30.px))
                    .padding(12.px),
            ) {
                BasicText(
                    m.title.uppercase(),
                    style = TextStyle(color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 18.tpx, letterSpacing = .12.em),
                    modifier = Modifier.padding(start = 24.px, end = 24.px, top = 14.px, bottom = 10.px),
                )
                m.items.forEachIndexed { i, item ->
                    MenuItemView(item.icon, item.label, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                        onDismiss()
                        item.action()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItemView(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalDelosColors.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .focusEffects(onClick = onClick, corner = 20f, focusScale = 1f, ring = null, glow = null, onFocusChange = { focused = it })
            .fillMaxWidth()
            .height(72.px)
            .clip(RoundedCornerShape(20.px))
            .background(if (focused) colors.accent else Color.Transparent)
            .padding(horizontal = 24.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.px),
    ) {
        Symbol(icon, 30.tpx, if (focused) colors.onAccent else colors.text3, filled = focused)
        BasicText(label, style = TextStyle(color = if (focused) colors.onAccent else colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 24.tpx))
    }
}

/**
 * Width of the side menu in stage pixels (the prototype's 620 plus room for the longest quick-setting row, Background
 * with Caustics, at the largest interface scale: the row's label and value need about 402 stage pixels at 130 % and
 * the panel leaves 680 - 248 = 432).
 */
private const val SIDE_MENU_WIDTH = 680f

/**
 * The side menu (left menu button), sliding in from the left: profile header and quick settings.
 * [header] and [content] are supplied by the shell; the first focusable of [content] should use
 * [firstFocus].
 */
@Composable
fun SideMenuLayer(
    visible: Boolean,
    haze: HazeState,
    header: @Composable () -> Unit,
    content: @Composable ColumnScope.(firstFocus: FocusRequester) -> Unit,
) {
    val colors = LocalDelosColors.current
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(300)) + slideInHorizontally(tween(450, easing = EaseOut)) { -it / 10 },
        exit = fadeOut(tween(300)) + slideOutHorizontally(tween(450, easing = EaseOut)) { -it / 10 },
    ) {
        val first = remember { FocusRequester() }
        InitialFocus(first)
        Column(
            Modifier
                .fillMaxHeight()
                .width(SIDE_MENU_WIDTH.px)
                .focusTrap(visible)
                .backdropBlur(haze, 28f, colors.surface3)
                .padding(horizontal = 40.px, vertical = 48.px),
            verticalArrangement = Arrangement.spacedBy(10.px),
        ) {
            header()
            content(first)
        }
        // border-right: 1px line
        Box(Modifier.fillMaxHeight().width(SIDE_MENU_WIDTH.px), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.fillMaxHeight().width(1.px).background(colors.line))
        }
    }
}

/**
 * The scrolling rows of the side menu. The content padding leaves room for the focus scale and ring of the
 * first and last row (the scroll area clips to its bounds), the edges fade out like the other lists and the
 * focused row is scrolled clear of the fades.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColumnScope.SideMenuScroll(content: @Composable ColumnScope.() -> Unit) {
    val stage = LocalStage.current
    val k = stage.dpPerPx * LocalDensity.current.density
    val bringIntoView = remember(k) { MarginBringIntoView(before = 40f * k, after = 56f * k) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
        Column(
            Modifier
                .weight(1f)
                .fadeEdges(stage, top = 16f, bottom = 40f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.px, vertical = 18.px),
            verticalArrangement = Arrangement.spacedBy(10.px),
            content = content,
        )
    }
}

/** Profile header of the side menu (`.side-head`). */
@Composable
fun SideMenuHeader(avatar: @Composable BoxScope.() -> Unit, name: String, status: String) {
    val colors = LocalDelosColors.current
    Column {
        Row(
            Modifier.padding(start = 12.px, end = 12.px, bottom = 24.px),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.px),
        ) {
            Box(Modifier.size(72.px).clip(CircleShape), contentAlignment = Alignment.Center, content = avatar)
            Column {
                BasicText(name, style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 28.tpx))
                BasicText(status, style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 20.tpx))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.px).background(colors.line))
        Box(Modifier.height(14.px))
    }
}

/** A toast at the bottom center (`#toast`), hidden after 2.6 s. */
@Composable
fun ToastHost(toast: Toast?, onHidden: (Int) -> Unit) {
    val colors = LocalDelosColors.current
    var shown by remember { mutableStateOf(toast) }
    if (toast != null) shown = toast
    LaunchedEffect(toast?.id) {
        val id = toast?.id ?: return@LaunchedEffect
        delay(2600)
        onHidden(id)
    }
    Box(Modifier.fillMaxSize().padding(bottom = 120.px), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            toast != null,
            enter = fadeIn(tween(300)) + slideInVertically(spring(dampingRatio = .6f, stiffness = 400f)) { it / 3 },
            exit = fadeOut(tween(300)) + slideOutVertically(tween(450, easing = EaseOut)) { it / 3 },
        ) {
            val t = shown ?: return@AnimatedVisibility
            Row(
                Modifier.clip(CircleShape).background(colors.text).padding(horizontal = 30.px, vertical = 16.px),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.px),
            ) {
                if (t.icon != null) Symbol(t.icon, 30.tpx, colors.bg, filled = true)
                BasicText(t.text, style = TextStyle(color = colors.bg, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx))
            }
        }
    }
}

/** Soft gradient used behind the side menu avatar placeholder. */
fun avatarBrush(hue: Float): Brush = Brush.linearGradient(listOf(hsl(hue, 80f, 60f), hsl(hue + 40f, 70f, 40f)))
