package com.limelight.shell.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

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

/**
 * Dims and slightly blurs the screen under a menu (`#scrim`); a tap outside closes the menu. The [light] scrim of the
 * context menu only dims (26 %, no blur): the tiles beside its card stay readable. It fades by drawing its color with the
 * animated alpha, not as a full-screen alpha layer of its own (that layer cost 16 MB of offscreen texture while the menu opened and closed).
 */
@Composable
fun Scrim(visible: Boolean, light: Boolean, haze: HazeState, onDismiss: () -> Unit) {
    val colors = LocalDelosColors.current
    // The scrim that fades out is the one that faded in
    var shownLight by remember { mutableStateOf(light) }
    if (visible) shownLight = light
    AnimatedVisibility(
        visible,
        enter = if (shownLight) EnterTransition.None else fadeIn(tween(Motion.SCRIM, easing = Motion.Ease)),
        exit = if (shownLight) ExitTransition.None else fadeOut(tween(Motion.EXIT, easing = Motion.Ease)),
    ) {
        // The animation in the content keeps it composed until it has faded out
        val fade = transition.animateFloat(
            transitionSpec = { tween(if (targetState == EnterExitState.Visible) Motion.SCRIM else Motion.EXIT, easing = Motion.Ease) },
            label = "scrimFade",
        ) { if (it == EnterExitState.Visible) 1f else 0f }
        Box(
            Modifier
                .fillMaxSize()
                .then(if (shownLight) Modifier.drawBehind { drawRect(LightScrim, alpha = fade.value) } else Modifier.backdropBlur(haze, 6f, colors.scrim))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
    }
}

private val LightScrim = Color(4, 8, 16).copy(alpha = .26f)

/**
 * Keeps the focus inside an open layer: D-pad moves cannot leave it while [active]. Once the layer
 * closes (still animating out) the trap is released, so the focus can return to the screen.
 */
@Composable
internal fun Modifier.focusTrap(active: Boolean): Modifier {
    val current by rememberUpdatedState(active)
    return focusProperties { onExit = { if (current) cancelFocusChange() } }.focusGroup()
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
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible,
        enter = sideEnter(reduced),
        exit = sideExit(reduced),
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
            EntranceScope {
                header()
                content(first)
            }
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
fun <K : Any> ColumnScope.SideMenuScroll(navigation: FocusedListNavigation<K>, content: @Composable ColumnScope.() -> Unit) {
    val stage = LocalStage.current
    val k = stage.dpPerPx * LocalDensity.current.density
    val sideScroll = rememberScrollState()
    val bringIntoView = rememberMarginBringIntoView(sideScroll, before = 40f * k, after = 56f * k, stagePx = k)
    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
        SettingRowList(Modifier.weight(1f).fadeEdges(stage, top = 16f, bottom = 40f)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .focusedListBoundary(navigation)
                    .verticalScroll(sideScroll)
                    .padding(horizontal = 14.px, vertical = 18.px),
                verticalArrangement = Arrangement.spacedBy(10.px),
                content = content,
            )
        }
    }
}

/** Profile header of the side menu (`.side-head`). */
@Composable
fun SideMenuHeader(avatar: @Composable BoxScope.() -> Unit, name: String, status: String) {
    val colors = LocalDelosColors.current
    Column(Modifier.staggerEntrance()) {
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

/** Soft gradient used behind the side menu avatar placeholder. */
fun avatarBrush(hue: Float): Brush = Brush.linearGradient(listOf(hsl(hue, 80f, 60f), hsl(hue + 40f, 70f, 40f)))
