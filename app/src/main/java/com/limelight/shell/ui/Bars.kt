package com.limelight.shell.ui

import android.os.Build
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import com.limelight.shell.system.BatteryStatus
import com.limelight.shell.system.WifiStatus
import kotlin.math.roundToInt

/**
 * The status and hint bars are scaled as a whole by the interface scale (the prototype's CSS zoom);
 * inside them text keeps its base size relative to the bar.
 */
@Composable
fun ZoomedBar(uiScale: Float, content: @Composable () -> Unit) {
    val stage = LocalStage.current
    CompositionLocalProvider(
        // The bar's stage is as many stage pixels across as the zoomed bar really has room for
        LocalStage provides stage.copy(
            dpPerPx = stage.dpPerPx * uiScale, textScale = 1f, safe = stage.safe.dividedBy(uiScale),
            stageWidth = stage.stageWidth / uiScale, stageHeight = stage.stageHeight / uiScale,
        ),
        content = content,
    )
}

/** A top navigation button of the status bar. */
@Immutable
data class NavButton(val icon: String, val label: String, val current: Boolean, val onClick: () -> Unit)

/**
 * Delos draws its own status bar (the system bars are hidden): brand on the left, the main navigation
 * in the middle and Wi-Fi, battery and clock on the right.
 */
@Composable
fun StatusBar(
    nav: List<NavButton>?,
    pcName: String?,
    pcOnline: Boolean,
    wifi: WifiStatus,
    battery: BatteryStatus?,
    clock: String,
    modifier: Modifier = Modifier,
    /** False where the screen shows something else in the corner (the start screen's profile). */
    brand: Boolean = true,
) {
    val colors = LocalDelosColors.current
    // The display cutout pads the bar (zero on a screen without one, so nothing moves there)
    val stage = LocalStage.current
    val safe = stage.safe
    // A compact stage drops the wordmark and holds the PC pill to what is left of its side of the centered navigation
    val compact = stage.compact
    val wordmark = !compact || stage.stageWidth >= Compact.NARROW_STAGE
    val sideRoom = (stage.stageWidth - 2f * 72f - safe.left - safe.right - (if (nav != null) NAV_WIDTH else 0f)) / 2f
    val pillMax = (sideRoom - 40f - 18f - (if (wordmark) WORDMARK_WIDTH + 18f else 0f) - 12f).coerceAtLeast(120f)
    Box(modifier.fillMaxWidth().padding(top = safe.top.px).height(104.px).padding(start = 72.px + safe.left.px, end = 72.px + safe.right.px)) {
        if (brand) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.px)) {
                LogoMark()
                if (wordmark) BasicText("DELOS", style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 26.tpx, letterSpacing = .14.em))
                if (pcName != null) PcPill(pcName, pcOnline, if (compact && nav != null) pillMax else null)
            }
        }
        if (nav != null) TopNav(nav, Modifier.align(Alignment.Center))
        // Wi-Fi, the battery with its percentage and the clock, the same gap between them
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.px)) {
            // Wi-Fi, battery and clock in the same color
            Symbol(wifiIcon(wifi), 30.tpx, colors.text)
            if (battery != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.px)) {
                    BatteryIndicator(battery)
                    // Equal-width digits: from 10 to 99 % the bar does not shift when the level changes
                    BasicText(
                        "${(battery.level * 100).roundToInt()}%",
                        style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx, fontFeatureSettings = "tnum"),
                    )
                }
            }
            BasicText(clock, style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 24.tpx, fontFeatureSettings = "tnum"))
        }
    }
}

private fun wifiIcon(wifi: WifiStatus) = when (wifi.level) {
    null -> Symbols.WIFI_OFF
    0, 1 -> Symbols.NETWORK_WIFI_1_BAR
    2 -> Symbols.NETWORK_WIFI_2_BAR
    3 -> Symbols.NETWORK_WIFI_3_BAR
    else -> Symbols.WIFI
}

/** Battery at or below this level shows red while not charging (like iOS). */
private const val LOW_BATTERY = .2f

internal val LowRed = androidx.compose.ui.graphics.Color(0xFFFF453A)

/**
 * The battery in the style of iOS: a light outline with a small cap, filled green to the charge level
 * (red when low). While charging, a bolt sits inside and the green pulses.
 */
@Composable
private fun BatteryIndicator(battery: BatteryStatus) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    // While charging, one cycle of 1.8 s: the green fades up and down, and the moment it is at full strength
    // a ring pulses out from the outline, like the one around the focused cover
    val phase = if (battery.charging) {
        val p by rememberInfiniteTransition(label = "charging").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "chargingPhase",
        )
        p
    } else 0f
    val pulse = if (battery.charging) .55f + .45f * EaseOut.transform(if (phase < .5f) phase * 2f else (1f - phase) * 2f) else 1f
    val wave = EaseOut.transform((phase + .5f) % 1f)
    val fill = if (!battery.charging && battery.level <= LOW_BATTERY) LowRed else OnlineGreen
    Box(Modifier.size(48.px, 24.px), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val k = stage.dpPerPx * density
            val stroke = 2f * k
            val cap = 3f * k
            val body = Size(size.width - cap - 1.5f * k, size.height)
            if (battery.charging) {
                val reach = (2f + 10f * wave) * k
                drawRoundRect(
                    OnlineGreen.copy(alpha = .7f * (1f - wave)), Offset(stroke / 2f - reach, stroke / 2f - reach),
                    Size(body.width - stroke + reach * 2f, body.height - stroke + reach * 2f), CornerRadius(7f * k + reach), style = Stroke(1.5f * k),
                )
            }
            // The outline and the cap on the right
            drawRoundRect(colors.text.copy(alpha = .9f), Offset(stroke / 2f, stroke / 2f), Size(body.width - stroke, body.height - stroke), CornerRadius(7f * k), style = Stroke(stroke))
            drawRoundRect(colors.text.copy(alpha = .6f), Offset(body.width + 1.5f * k, size.height * .32f), Size(cap, size.height * .36f), CornerRadius(1.5f * k))
            // The charge, inset from the outline
            val inset = stroke + 2f * k
            val inner = Size(body.width - inset * 2f, body.height - inset * 2f)
            drawRoundRect(
                fill.copy(alpha = pulse), Offset(inset, inset), Size(inner.width * battery.level.coerceIn(.04f, 1f), inner.height), CornerRadius(4f * k),
            )
        }
        if (battery.charging) Symbol(Symbols.BOLT, 20.tpx, colors.text, Modifier.offset(x = (-2).px), filled = true)
    }
}

/** The Delos ring with its small moon (`.logo-mark`): drawn for 40 stage px; at another [size] the stroke and the moon scale with it, so the mark keeps the look of the launcher icon. */
@Composable
fun LogoMark(size: Float = 40f) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    Box(Modifier.size(size.px).drawBehind {
        val k = stage.dpPerPx * density * (size / 40f)
        val stroke = 3f * k
        drawCircle(colors.accent, radius = this.size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
        val moon = Offset(this.size.width - 1.5f * k, 7.5f * k)
        drawCircle(colors.accent2.copy(alpha = .35f), radius = 9f * k, center = moon)
        drawCircle(colors.accent2, radius = 5.5f * k, center = moon)
    })
}

/** The connected PC next to the brand; the dot is green while the PC is online. [maxWidth] (stage pixels) cuts a long name with an ellipsis; null: as wide as the name. */
@Composable
private fun PcPill(name: String, online: Boolean, maxWidth: Float? = null) {
    val colors = LocalDelosColors.current
    Row(
        Modifier.then(if (maxWidth != null) Modifier.widthIn(max = maxWidth.px) else Modifier)
            .clip(CircleShape).glass(colors.surface).border(1.px, colors.line, CircleShape).padding(horizontal = 18.px, vertical = 8.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.px),
    ) {
        Box(Modifier.size(10.px).clip(CircleShape).background(if (online) ONLINE else colors.text3))
        BasicText(
            name, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 20.tpx),
            maxLines = if (maxWidth != null) 1 else Int.MAX_VALUE, overflow = if (maxWidth != null) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
    }
}

private val ONLINE = androidx.compose.ui.graphics.Color(0xFF3DDC84)

/** The top navigation's width (three 64 px buttons, their gaps and the pill's padding) and the wordmark's, for the compact status bar's room. */
private const val NAV_WIDTH = 3 * 64f + 2 * 14f + 16f
private const val WORDMARK_WIDTH = 175f

@Composable
internal fun TopNav(buttons: List<NavButton>, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val glass = LocalBackdropHaze.current
    val requesters = remember(buttons.size) { List(buttons.size) { FocusRequester() } }
    TransientPillHost(
        modifier,
        background = {
            // Frosted glass in its own layer: the pill itself is not clipped, the focused button's glow and
            // tooltip reach outside it
            Box(
                Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .then(if (glass != null) Modifier.backdropBlur(glass, 8f, colors.surface) else Modifier.background(colors.surface))
                    .border(1.px, colors.line, CircleShape),
            )
        },
    ) {
        Row(Modifier.padding(8.px), horizontalArrangement = Arrangement.spacedBy(14.px)) {
            buttons.forEachIndexed { i, button ->
                NavButtonView(
                    button, requesters[i],
                    wrapLeft = if (i == 0 && requesters.size > 1) requesters.last() else null,
                    wrapRight = if (i == requesters.lastIndex && requesters.size > 1) requesters.first() else null,
                )
            }
        }
    }
}

/** What a focused top menu button is scaled to; the transient pill takes its focused bounds. */
private const val NAV_FOCUS_SCALE = 1.12f

@Composable
private fun NavButtonView(button: NavButton, requester: FocusRequester, wrapLeft: FocusRequester?, wrapRight: FocusRequester?) {
    val colors = LocalDelosColors.current
    val pill = LocalTransientPill.current
    val reduced = LocalReducedMotion.current
    var focused by remember { mutableStateOf(false) }
    val position = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // The icon's colour follows the pill under it (dark while the accent pill is there) instead of switching at once
    val iconColor by animateColorAsState(
        when {
            focused -> colors.onAccent
            button.current -> colors.accent
            else -> colors.text2
        },
        if (reduced) snap() else tween(Motion.FADE, easing = Motion.Ease), label = "navIcon",
    )
    Box(contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                // Left of the first button is the last one, right of the last one the first (cyclic, like the stream menu's tile bar)
                .focusProperties {
                    wrapLeft?.let { left = it }
                    wrapRight?.let { right = it }
                }
                .focusRequester(requester)
                // The layout bounds the pill glides between (as a focused button looks: scaled), before the focus scale
                .onGloballyPositioned { position[0] = it }
                .focusEffects(
                    onClick = button.onClick, corner = 999f, focusScale = NAV_FOCUS_SCALE, ring = null,
                    glow = FocusGlow(offsetY = 10f, blur = 30f, spread = -8f),
                    onFocusChange = {
                        focused = it
                        pill?.focusChanged(it, position[0], NAV_FOCUS_SCALE)
                    },
                )
                .size(64.px)
                .clip(CircleShape)
                // The focused button's accent fill; while the transient pill is the focus, the pill is what shows, the fill fades in over it at the hand-over
                .drawBehind { if (focused) drawRect(colors.accent, alpha = pill?.handover() ?: 1f) },
            contentAlignment = Alignment.Center,
        ) {
            Symbol(button.icon, 34.tpx, iconColor, filled = focused)
            // The page you are on: a small dot in the third color under its icon (the focused button is filled instead)
            if (button.current && !focused) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.px).size(6.px).clip(CircleShape).background(colors.accent3))
        }
        // Tooltip under the focused button (`.nav-btn .tip`); it takes the button's size, so a long label
        // cannot widen the bar, and overflows it centered
        AnimatedVisibility(
            focused,
            modifier = Modifier.matchParentSize().wrapContentSize(Alignment.TopCenter, unbounded = true).offset(y = 84.px),
            enter = fadeIn(tween(Motion.FADE, easing = Motion.Ease)) + slideInVertically(tween(Motion.POP_IN, easing = EaseOut)) { -it / 4 },
            exit = fadeOut(tween(Motion.EXIT, easing = Motion.Ease)) + slideOutVertically(tween(Motion.EXIT, easing = Motion.In)) { -it / 4 },
        ) {
            BasicText(
                button.label,
                style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 18.tpx),
                modifier = Modifier.clip(CircleShape).glass(colors.surface3).padding(horizontal = 14.px, vertical = 6.px),
            )
        }
    }
}

/** What a hint's button looks like: a letter (A, B, L1…) or an icon (menu buttons, adjust). */
@Immutable
sealed interface Glyph {
    data class Letter(val text: String) : Glyph
    /**
     * [scale] multiplies the 24 px base font size to even out icons that look too big or too small next to
     * the others. The scales are measured against the Material Symbols Rounded glyphs (FILL 1, wght 600,
     * opsz 24, 960 units/em) the sizes were tuned on by eye (b83165f2) and recalibrated for the Phosphor Fill
     * glyphs (1024 units/em), which have another ink size in the em box:
     * `new = old * (oldInk / 960) / (newInk / 1024)`, ink being the glyph's bounding box.
     */
    data class Icon(val symbol: String, val scale: Float = 1f) : Glyph

    companion object {
        val A = Letter("A")
        val B = Letter("B")
        val X = Letter("X")
        val Y = Letter("Y")
        val L1 = Letter("L1")
        val R1 = Letter("R1")

        /** AYN handhelds (Odin, Thor) have a square and a play icon on their two menu buttons. */
        private val ayn = Build.MANUFACTURER.equals("AYN", ignoreCase = true)

        /**
         * The left top menu button (View / Select). AYN square: ink 0.790 em (Material, scale .72 = 13.65 px) vs
         * 0.750 em (Phosphor) -> .72 * 0.790 / 0.750 = .758, the same 13.65 px. Other devices, copy: 0.9005 em vs
         * 0.750 em -> 1.2, 21.6 px.
         */
        val MENU_LEFT = if (ayn) Icon(Symbols.SQUARE, scale = .758f) else Icon(Symbols.FILTER_NONE, scale = 1.2f)

        /**
         * The right top menu button (Start). AYN play: ink 0.4672 x 0.5640 em (Material, scale 1.3 = 14.6 x 17.6 px)
         * vs 0.6875 x 0.8125 em (Phosphor) -> 1.3 * 0.5640 / 0.8125 = .9025, 14.9 x 17.6 px (the same height). Other
         * devices, list: Material bars 0.790 x 0.558 em vs the Phosphor list-fill block 0.750 x 0.750 em; the shapes
         * differ, so the area-equivalent factor sqrt(0.790/0.750 * 0.558/0.750) = .885 is used (15.9 px block).
         */
        val MENU_RIGHT = if (ayn) Icon(Symbols.PLAY_ARROW, scale = .9025f) else Icon(Symbols.MENU, scale = .885f)

        /** Material swap_horiz 0.827 x 0.661 em vs Phosphor arrows-left-right 0.6875 x 0.6875 em -> area-equivalent 1.075 (17.7 px). */
        val ADJUST = Icon(Symbols.SWAP_HORIZ, scale = 1.075f)
    }
}

@Immutable
data class Hint(val glyph: Glyph, val label: String)

/** The bottom bar: an optional note on the left, the button hints on the right. */
@Composable
fun HintBar(hints: List<Hint>, note: String?, modifier: Modifier = Modifier, noteLabel: String? = null) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    if (stage.compact) {
        CompactHintBar(hints, note, noteLabel, modifier)
        return
    }
    val safe = stage.safe
    Row(
        modifier.fillMaxWidth().padding(bottom = safe.bottom.px).height(92.px).padding(start = 72.px + safe.left.px, end = 72.px + safe.right.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(34.px),
    ) {
        if (note != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.px)) {
            if (noteLabel != null) BasicText(noteLabel, style = TextStyle(color = colors.accent3,
                fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx, letterSpacing = .14.em),
                modifier = Modifier.alignByBaseline())
            BasicText(note, style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 21.tpx),
                modifier = Modifier.alignByBaseline())
        }
        Spacer(Modifier.weight(1f))
        hints.forEach { hint ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
                GlyphButton(hint.glyph)
                BasicText(hint.label, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 21.tpx))
            }
        }
    }
}

/**
 * The hint bar of a compact stage: the same note and hints, but only as much of them as the width holds. It tries the note with its
 * "Library" label, the note alone, no note, and finally the hints as glyphs with the label of the first one only; the first variant
 * that fits is drawn (the hints are the bar, so they are never cut and never overlap the note).
 */
@Composable
private fun CompactHintBar(hints: List<Hint>, note: String?, noteLabel: String?, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val safe = LocalStage.current.safe
    val variants = remember(note, noteLabel) {
        buildList {
            if (note != null && noteLabel != null) add(HintVariant(true, true, true))
            if (note != null) add(HintVariant(true, false, true))
            add(HintVariant(false, false, true))
            add(HintVariant(false, false, false))
        }
    }
    val noteGap = 34.px
    val hintGap = 28.px
    SubcomposeLayout(
        modifier.fillMaxWidth().padding(bottom = safe.bottom.px).height(92.px).padding(start = 72.px + safe.left.px, end = 72.px + safe.right.px),
    ) { constraints ->
        val open = Constraints()
        val gap = noteGap.roundToPx()
        var left: List<Placeable> = emptyList()
        var right: List<Placeable> = emptyList()
        for ((i, variant) in variants.withIndex()) {
            left = if (variant.note && note != null) subcompose("note$i") {
                HintNote(note, if (variant.noteLabel) noteLabel else null)
            }.map { it.measure(open) } else emptyList()
            right = subcompose("hints$i") { HintGlyphs(hints, variant.labels, hintGap) }.map { it.measure(open) }
            val used = left.sumOf { it.width } + right.sumOf { it.width } + if (left.isEmpty()) 0 else gap
            if (used <= constraints.maxWidth) break
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            left.forEach { it.placeRelative(0, (constraints.maxHeight - it.height) / 2) }
            right.forEach { it.placeRelative(constraints.maxWidth - it.width, (constraints.maxHeight - it.height) / 2) }
        }
    }
}

private class HintVariant(val note: Boolean, val noteLabel: Boolean, val labels: Boolean)

/** The note of a hint bar: its small label, then the sentence, on one baseline. */
@Composable
private fun HintNote(note: String, noteLabel: String?) {
    val colors = LocalDelosColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.px)) {
        if (noteLabel != null) BasicText(noteLabel, style = TextStyle(color = colors.accent3,
            fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx, letterSpacing = .14.em),
            modifier = Modifier.alignByBaseline())
        BasicText(note, style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 21.tpx),
            modifier = Modifier.alignByBaseline(), maxLines = 1)
    }
}

/** The hints of a compact bar: glyph and label each, or with [labels] false only the first one keeps its label. */
@Composable
private fun HintGlyphs(hints: List<Hint>, labels: Boolean, gap: androidx.compose.ui.unit.Dp) {
    val colors = LocalDelosColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
        hints.forEachIndexed { index, hint ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
                GlyphButton(hint.glyph)
                if (labels || index == 0) BasicText(hint.label, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 21.tpx), maxLines = 1)
            }
        }
    }
}

/** A button hint's glyph, [size] stage pixels (the stream menu's bar uses a bigger one than the hint bars). */
@Composable
fun GlyphButton(glyph: Glyph, size: Int = 38) {
    val colors = LocalDelosColors.current
    val k = size / 38f
    Box(
        // Icons always sit in a circle; letters (L1…) may widen it into a pill
        (if (glyph is Glyph.Icon) Modifier.size(size.px) else Modifier.defaultMinSize(minWidth = size.px).height(size.px))
            .clip(CircleShape)
            .background(colors.text)
            .then(if (glyph is Glyph.Icon) Modifier else Modifier.padding(horizontal = 8.px)),
        contentAlignment = Alignment.Center,
    ) {
        when (glyph) {
            is Glyph.Letter -> BasicText(glyph.text, style = TextStyle(color = colors.bg, fontFamily = Figtree, fontWeight = FontWeight.ExtraBold, fontSize = (21f * k).tpx))
            is Glyph.Icon -> Symbol(glyph.symbol, (24f * glyph.scale * k).tpx, colors.bg, filled = true)
        }
    }
}
