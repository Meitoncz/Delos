package com.limelight.shell.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
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
    CompositionLocalProvider(LocalStage provides stage.copy(dpPerPx = stage.dpPerPx * uiScale, textScale = 1f), content = content)
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
    Box(modifier.fillMaxWidth().height(104.px).padding(horizontal = 72.px)) {
        if (brand) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.px)) {
                LogoMark()
                BasicText("DELOS", style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 26.tpx, letterSpacing = .14.em))
                if (pcName != null) PcPill(pcName, pcOnline)
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

private val LowRed = androidx.compose.ui.graphics.Color(0xFFFF453A)

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

/** The Delos ring with its small moon (`.logo-mark`). */
@Composable
fun LogoMark(size: Float = 40f) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    Box(Modifier.size(size.px).drawBehind {
        val k = stage.dpPerPx * density
        val stroke = 3f * k
        drawCircle(colors.accent, radius = this.size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
        val moon = Offset(this.size.width - 1.5f * k, 7.5f * k)
        drawCircle(colors.accent2.copy(alpha = .35f), radius = 9f * k, center = moon)
        drawCircle(colors.accent2, radius = 5.5f * k, center = moon)
    })
}

/** The connected PC next to the brand; the dot is green while the PC is online. */
@Composable
private fun PcPill(name: String, online: Boolean) {
    val colors = LocalDelosColors.current
    Row(
        Modifier.clip(CircleShape).glass(colors.surface).border(1.px, colors.line, CircleShape).padding(horizontal = 18.px, vertical = 8.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.px),
    ) {
        Box(Modifier.size(10.px).clip(CircleShape).background(if (online) ONLINE else colors.text3))
        BasicText(name, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 20.tpx))
    }
}

private val ONLINE = androidx.compose.ui.graphics.Color(0xFF3DDC84)

@Composable
private fun TopNav(buttons: List<NavButton>, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val glass = LocalBackdropHaze.current
    Box(modifier) {
        // Frosted glass in its own layer: the pill itself is not clipped, the focused button's glow and
        // tooltip reach outside it
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .then(if (glass != null) Modifier.backdropBlur(glass, 8f, colors.surface) else Modifier.background(colors.surface))
                .border(1.px, colors.line, CircleShape),
        )
        Row(Modifier.padding(8.px), horizontalArrangement = Arrangement.spacedBy(14.px)) {
            buttons.forEach { NavButtonView(it) }
        }
    }
}

@Composable
private fun NavButtonView(button: NavButton) {
    val colors = LocalDelosColors.current
    var focused by remember { mutableStateOf(false) }
    Box(contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .focusEffects(
                    onClick = button.onClick, corner = 999f, focusScale = 1.12f, ring = null,
                    glow = FocusGlow(offsetY = 10f, blur = 30f, spread = -8f), onFocusChange = { focused = it },
                )
                .size(64.px)
                .clip(CircleShape)
                .background(if (focused) colors.accent else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(
                button.icon, 34.tpx,
                when {
                    focused -> colors.onAccent
                    button.current -> colors.accent
                    else -> colors.text2
                },
                filled = focused,
            )
        }
        // Tooltip under the focused button (`.nav-btn .tip`); it takes the button's size, so a long label
        // cannot widen the bar, and overflows it centered
        AnimatedVisibility(
            focused,
            modifier = Modifier.matchParentSize().wrapContentSize(Alignment.TopCenter, unbounded = true).offset(y = 84.px),
            enter = fadeIn(tween(250)) + slideInVertically(tween(350, easing = EaseOut)) { -it / 4 },
            exit = fadeOut(tween(250)) + slideOutVertically(tween(350, easing = EaseOut)) { -it / 4 },
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
    Row(
        modifier.fillMaxWidth().height(92.px).padding(horizontal = 72.px),
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
