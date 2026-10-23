package com.limelight.shell.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
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
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.px)) {
            Symbol(wifiIcon(wifi), 30.tpx, colors.text2)
            if (battery != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.px)) {
                    Symbol(batteryIcon(battery), 30.tpx, colors.text2)
                    BasicText("${(battery.level * 100).roundToInt()}%", style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx))
                }
            }
            BasicText(clock, style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 24.tpx))
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

private fun batteryIcon(b: BatteryStatus) = when {
    b.charging -> Symbols.BATTERY_CHARGING_FULL
    b.level >= .995f -> Symbols.BATTERY_FULL
    else -> when ((b.level * 6).roundToInt().coerceIn(0, 6)) {
        0 -> Symbols.BATTERY_0_BAR
        1 -> Symbols.BATTERY_1_BAR
        2 -> Symbols.BATTERY_2_BAR
        3 -> Symbols.BATTERY_3_BAR
        4 -> Symbols.BATTERY_4_BAR
        5 -> Symbols.BATTERY_5_BAR
        else -> Symbols.BATTERY_6_BAR
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
        Modifier.clip(CircleShape).background(colors.surface).border(1.px, colors.line, CircleShape).padding(horizontal = 18.px, vertical = 8.px),
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
    Row(
        // Not clipped: the focused button's glow and tooltip reach outside the pill
        modifier.background(colors.surface, CircleShape).border(1.px, colors.line, CircleShape).padding(8.px),
        horizontalArrangement = Arrangement.spacedBy(14.px),
    ) {
        buttons.forEach { NavButtonView(it) }
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
                modifier = Modifier.clip(CircleShape).background(colors.surface3).padding(horizontal = 14.px, vertical = 6.px),
            )
        }
    }
}

/** What a hint's button looks like: a letter (A, B, L1…) or an icon (menu buttons, adjust). */
@Immutable
sealed interface Glyph {
    data class Letter(val text: String) : Glyph
    /** [scale] evens out icons that look too big or too small next to the others. */
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

        /** The left top menu button (View / Select). */
        val MENU_LEFT = if (ayn) Icon(Symbols.SQUARE, scale = .72f) else Icon(Symbols.FILTER_NONE)

        /** The right top menu button (Start). */
        val MENU_RIGHT = if (ayn) Icon(Symbols.PLAY_ARROW, scale = 1.3f) else Icon(Symbols.MENU)
        val ADJUST = Icon(Symbols.SWAP_HORIZ)
    }
}

@Immutable
data class Hint(val glyph: Glyph, val label: String)

/** The bottom bar: an optional note on the left, the button hints on the right. */
@Composable
fun HintBar(hints: List<Hint>, note: String?, modifier: Modifier = Modifier) {
    val colors = LocalDelosColors.current
    Row(
        modifier.fillMaxWidth().height(92.px).padding(horizontal = 72.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(34.px),
    ) {
        if (note != null) BasicText(note, style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 21.tpx))
        Spacer(Modifier.weight(1f))
        hints.forEach { hint ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
                GlyphButton(hint.glyph)
                BasicText(hint.label, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 21.tpx))
            }
        }
    }
}

@Composable
fun GlyphButton(glyph: Glyph) {
    val colors = LocalDelosColors.current
    Box(
        // Icons always sit in a circle; letters (L1…) may widen it into a pill
        (if (glyph is Glyph.Icon) Modifier.size(38.px) else Modifier.defaultMinSize(minWidth = 38.px).height(38.px))
            .clip(CircleShape)
            .background(colors.text)
            .then(if (glyph is Glyph.Icon) Modifier else Modifier.padding(horizontal = 8.px)),
        contentAlignment = Alignment.Center,
    ) {
        when (glyph) {
            is Glyph.Letter -> BasicText(glyph.text, style = TextStyle(color = colors.bg, fontFamily = Figtree, fontWeight = FontWeight.ExtraBold, fontSize = 21.tpx))
            is Glyph.Icon -> Symbol(glyph.symbol, (24f * glyph.scale).tpx, colors.bg, filled = true, filledWeight = 600)
        }
    }
}
