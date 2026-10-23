package com.limelight.shell.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import com.limelight.shell.ui.MarginBringIntoView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.limelight.shell.settings.Appearance
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.fadeEdges
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.SettingRow
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.hsl
import com.limelight.shell.ui.px
import com.limelight.shell.ui.systemAccentHue
import com.limelight.shell.ui.tpx

/** Cycles an enum by [dir] steps. */
inline fun <reified E : Enum<E>> E.step(dir: Int): E {
    val all = enumValues<E>()
    return all[((ordinal + dir) % all.size + all.size) % all.size]
}

/**
 * Settings preview of step 6.3: the Appearance category only. Tabs, the description panel, search and
 * all stream settings come in step 6.7.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(appearance: Appearance, update: ((Appearance) -> Appearance) -> Unit) {
    val colors = LocalDelosColors.current
    val uiScale = appearance.uiScale / 100f
    val first = remember { FocusRequester() }
    InitialFocus(first)
    Column(Modifier.fillMaxSize().padding(start = 150.px, top = 124.px)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.px)) {
            BasicText("Settings", style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 46.tpx))
            BasicText(
                "Appearance · the other categories come in step 6.7",
                style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 22.tpx),
                modifier = Modifier.padding(bottom = 8.px),
            )
        }
        // The list ends above the hint bar (which grows with the interface scale) and fades out at its
        // edges; the focused row is always scrolled clear of the fades
        val stage = LocalStage.current
        val density = LocalDensity.current.density
        val k = stage.dpPerPx * density
        val bringIntoView = remember(k) { MarginBringIntoView(before = 24f * k, after = 110f * k) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
            Column(
                Modifier
                    .padding(top = 46.px, bottom = (96f * uiScale).px)
                    .width(1100.px)
                    .weight(1f)
                    .fadeEdges(stage, top = 14f, bottom = 90f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.px, end = 18.px, top = 14.px, bottom = 90.px),
                verticalArrangement = Arrangement.spacedBy(10.px),
            ) {
                AppearanceRows(appearance, update, Modifier.focusRequester(first))
            }
        }
    }
}

/** The Appearance settings (also used by the side menu in a shorter form). */
@Composable
fun AppearanceRows(appearance: Appearance, update: ((Appearance) -> Appearance) -> Unit, firstModifier: Modifier = Modifier) {
    val context = LocalContext.current
    val systemHue = remember { systemAccentHue(context) }
    SettingRow(
        Symbols.DARK_MODE, "Theme",
        RowValue.Choice(if (appearance.dark) "Dark" else "Light", { update { it.copy(dark = !it.dark) } }),
        modifier = firstModifier,
    )
    SettingRow(
        Symbols.WALLPAPER, "Material You colors",
        RowValue.Toggle(appearance.materialYou) { on -> update { it.copy(materialYou = on) } },
        description = "Takes the color palette from the system wallpaper",
    )
    SettingRow(
        Symbols.COLORIZE, "Wallpaper colors",
        RowValue.Choice(
            appearance.seed.label, { dir -> update { it.copy(seed = it.seed.step(dir)) } },
            swatch = hsl(appearance.seed.hue ?: systemHue ?: 176f, 70f, 55f),
        ),
        description = if (systemHue != null) "System = the colors of your wallpaper" else "This device has no wallpaper palette; System uses Lagoon",
        enabled = appearance.materialYou,
    )
    SettingRow(
        Symbols.ANIMATION, "Animated background",
        RowValue.Choice(appearance.background.label, { dir -> update { it.copy(background = it.background.step(dir)) } }),
    )
    SettingRow(
        Symbols.VIEW_CAROUSEL, "Home layout",
        RowValue.Choice(appearance.homeLayout.label, { dir -> update { it.copy(homeLayout = it.homeLayout.step(dir)) } }),
        description = "Grid scrolls down, Row is one horizontally scrolling row",
    )
    SettingRow(
        Symbols.IMAGE, "Game background",
        RowValue.Toggle(appearance.heroBackground) { on -> update { it.copy(heroBackground = on) } },
        description = "Shows the background of the selected game on the home screen",
    )
    SettingRow(
        Symbols.BLUR_ON, "Game background blur",
        RowValue.Slider(appearance.heroBlur, 0, 40, 2, " px") { v -> update { it.copy(heroBlur = v) } },
        enabled = appearance.heroBackground,
    )
    SettingRow(
        Symbols.OPACITY, "Game background opacity",
        RowValue.Slider(appearance.heroOpacity, 0, 100, 5, " %") { v -> update { it.copy(heroOpacity = v) } },
        enabled = appearance.heroBackground,
    )
    SettingRow(
        Symbols.FORMAT_SIZE, "Interface scale",
        RowValue.Slider(appearance.uiScale, 100, 130, 5, " %") { v -> update { it.copy(uiScale = v) } },
        description = "Text size, status bar and button hints",
    )
    SettingRow(
        Symbols.MOTION_PHOTOS_ON, "Animations",
        RowValue.Choice(if (appearance.reducedMotion) "Reduced" else "Full", { update { it.copy(reducedMotion = !it.reducedMotion) } }),
    )
    SettingRow(
        Symbols.MUSIC_NOTE, "Interface sounds",
        RowValue.Toggle(appearance.sounds) { on -> update { it.copy(sounds = on) } },
    )
    SettingRow(
        Symbols.VIBRATION, "Haptics",
        RowValue.Toggle(appearance.haptics) { on -> update { it.copy(haptics = on) } },
        description = "Vibration when moving and selecting",
    )
}
