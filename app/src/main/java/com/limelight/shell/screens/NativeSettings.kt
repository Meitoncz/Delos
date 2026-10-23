package com.limelight.shell.screens

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.limelight.BuildConfig
import com.limelight.shell.LocalShell
import com.limelight.shell.Screen
import com.limelight.shell.StatsPosition
import com.limelight.shell.data.Pc
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.SettingsTab
import com.limelight.shell.settings.Startup
import com.limelight.shell.settings.StreamMenuSettings
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.hsl
import com.limelight.shell.ui.systemAccentHue

internal data class SettingsItem(
    val key: String,
    val tab: SettingsTab,
    val section: String,
    val icon: String,
    val label: String,
    val description: String,
    val value: RowValue,
    val enabled: Boolean = true,
)

/** Shell preferences and device actions participate in the same search as stream settings. */
@Composable
internal fun nativeSettings(
    a: Appearance, update: ((Appearance) -> Appearance) -> Unit,
    startup: Startup, updateStartup: ((Startup) -> Startup) -> Unit, pcs: List<Pc>,
    /** The current profile's settings (the stream menu shortcut is kept there). */
    settings: SharedPreferences,
): List<SettingsItem> {
    val context = LocalContext.current
    val shell = LocalShell.current
    val systemHue = remember { systemAccentHue(context) }
    val list = mutableListOf<SettingsItem>()
    fun row(key: String, icon: String, title: String, value: RowValue, description: String = "", enabled: Boolean = true,
            tab: SettingsTab = SettingsTab.APPEARANCE, section: String = "Interface") {
        list += SettingsItem(key, tab, section, icon, title, description, value, enabled)
    }
    row("ui_dark", Symbols.DARK_MODE, "Theme", RowValue.Choice(if (a.dark) "Dark" else "Light", { update { it.copy(dark = !it.dark) } }), "Choose a dark or light interface.")
    row("ui_material_you", Symbols.WALLPAPER, "Material You colors", RowValue.Toggle(a.materialYou) { on -> update { it.copy(materialYou = on) } },
        if (systemHue != null) "Uses only the colors of your system wallpaper. Turn it off to choose a color palette."
        else "This device has no wallpaper palette, so Material You uses Lagoon's colors. Turn it off to choose a color palette.")
    row("ui_palette", Symbols.COLORIZE, "Color palette", RowValue.Choice(a.palette.label, { dir -> update { it.copy(palette = it.palette.step(dir)) } }, hsl(a.palette.accent, 90f, 60f)),
        if (a.materialYou) "Turn off Material You colors to choose a palette." else "The colors of the whole interface and the animated backgrounds.", !a.materialYou)
    row("ui_match_game_colors", Symbols.IMAGE, "Match the game's colors", RowValue.Toggle(a.matchGameColors) { on -> update { it.copy(matchGameColors = on) } },
        if (a.materialYou) "Turn off Material You colors to use it." else "The animated background takes on the main color of the selected game's background image.", !a.materialYou)
    row("ui_clock", Symbols.SCHEDULE, "Clock", RowValue.Choice(a.clock.label, { dir -> update { it.copy(clock = it.clock.step(dir)) } }),
        "12- or 24-hour time in the status bar; System follows the Android setting.")
    row("ui_background", Symbols.ANIMATION, "Animated background", RowValue.Choice(a.background.label, { dir -> update { it.copy(background = it.background.step(dir)) } }), "Aurora, Tide, Silk, Contour or a still background.")
    row("ui_home_layout", Symbols.VIEW_CAROUSEL, "Home layout", RowValue.Choice(a.homeLayout.label, { dir -> update { it.copy(homeLayout = it.homeLayout.step(dir)) } }), "Grid scrolls down. Row is one horizontally scrolling row.")
    row("ui_hero_background", Symbols.IMAGE, "Game background", RowValue.Toggle(a.heroBackground) { on -> update { it.copy(heroBackground = on) } }, "Shows the background of the selected game on the home screen.")
    row("ui_hero_blur", Symbols.BLUR_ON, "Game background blur", RowValue.Slider(a.heroBlur, 0, 40, 2, " px") { v -> update { it.copy(heroBlur = v) } }, "Soften the game background behind the library.", a.heroBackground)
    row("ui_hero_opacity", Symbols.OPACITY, "Game background opacity", RowValue.Slider(a.heroOpacity, 0, 100, 5, " %") { v -> update { it.copy(heroOpacity = v) } }, "How strongly the game artwork shows through the animated background.", a.heroBackground)
    row("ui_scale", Symbols.FORMAT_SIZE, "Interface scale", RowValue.Slider(a.uiScale, 100, 130, 5, " %") { v -> update { it.copy(uiScale = v) } }, "Text size, status bar and button hints.")
    row("ui_reduced_motion", Symbols.MOTION_PHOTOS_ON, "Animations", RowValue.Choice(if (a.reducedMotion) "Reduced" else "Full", { update { it.copy(reducedMotion = !it.reducedMotion) } }), "Reduce movement in the interface.")
    row("ui_haptics", Symbols.VIBRATION, "Interface haptics", RowValue.Toggle(a.haptics) { on -> update { it.copy(haptics = on) } }, "Vibration when moving and selecting.", tab = SettingsTab.CONTROLS)
    row("ui_sounds", Symbols.MUSIC_NOTE, "Interface sounds", RowValue.Toggle(a.sounds) { on -> update { it.copy(sounds = on) } }, "Soft clicks when you move around the menus.", tab = SettingsTab.AUDIO)
    val menuButtons = StreamMenuSettings.buttons(settings)
    row("ui_stream_menu_buttons", Symbols.SPORTS_ESPORTS, "Stream menu buttons", RowValue.Text(StreamMenuSettings.label(menuButtons)) {
        shell.buttonCapture = { buttons -> StreamMenuSettings.setButtons(settings, buttons) }
    }, "Press them together while streaming to open the Delos stream menu. Select to record other buttons, e.g. a rear button.",
        tab = SettingsTab.CONTROLS, section = "Stream menu")
    row("ui_stream_menu_reset", Symbols.RESTART_ALT, "Use L3 + R3 again", RowValue.Action {
        StreamMenuSettings.setButtons(settings, StreamMenuSettings.DEFAULT_BUTTONS)
    }, "Back to pressing both sticks.", menuButtons != StreamMenuSettings.DEFAULT_BUTTONS, SettingsTab.CONTROLS, "Stream menu")
    row("ui_stream_menu_swipe", Symbols.SWIPE_UP, "Swipe up to open the menu", RowValue.Toggle(StreamMenuSettings.swipe(settings)) { on ->
        settings.edit().putBoolean(StreamMenuSettings.KEY_SWIPE, on).apply()
    }, "A swipe up from the bottom edge of the screen opens the stream menu too. Touches that start right at that edge do not reach the game.",
        tab = SettingsTab.CONTROLS, section = "Stream menu")
    val statsPosition = StatsPosition.read(settings)
    row("ui_stats_position", Symbols.MONITORING, "Statistics position", RowValue.Choice(statsPosition.label, { dir ->
        settings.edit().putString(StatsPosition.KEY, statsPosition.step(dir).name).apply()
    }), "The corner of the statistics panel while streaming (Performance in the stream menu).", tab = SettingsTab.DISPLAY, section = "Statistics")
    val choices = listOf<Pc?>(null) + pcs.filter { it.paired }.sortedBy { it.name.lowercase() }
    val index = choices.indexOfFirst { it?.uuid == startup.defaultPc }.coerceAtLeast(0)
    row("ui_default_pc", Symbols.STAR, "Default device", RowValue.Choice(choices[index]?.name ?: "None", { dir ->
        val next = choices[Math.floorMod(index + dir, choices.size)]
        updateStartup { it.copy(defaultPc = next?.uuid, skipStart = next != null && it.skipStart) }
    }), "Marked on the start screen. Delos can connect to it right away.", choices.size > 1, SettingsTab.DEVICES, "Startup")
    row("ui_skip_start", Symbols.FAST_FORWARD, "Skip the start screen", RowValue.Toggle(startup.skipStart) { on -> updateStartup { it.copy(skipStart = on) } },
        "Connect straight to the default device when Delos starts.", startup.defaultPc != null, SettingsTab.DEVICES, "Startup")
    row("devices_manage", Symbols.DEVICES, "Manage devices", RowValue.Action { shell.navigate(Screen.Devices) }, "Connect, rename, wake, unpair or remove a PC.", tab = SettingsTab.DEVICES, section = "Paired PCs")
    row("devices_pair", Symbols.ADD_LINK, "Pair a new PC", RowValue.Action { shell.navigate(Screen.PairPc(Screen.Settings)) }, "Find a PC running Vibepollo, Apollo or Sunshine.", tab = SettingsTab.DEVICES, section = "Paired PCs")
    row("covers_source", Symbols.IMAGE, "Game artwork", RowValue.Info("From your PC"), "Delos currently uses covers provided by your streaming PC.", tab = SettingsTab.COVERS, section = "Artwork")
    row("covers_steamgriddb", Symbols.PHOTO_LIBRARY, "SteamGridDB", RowValue.Info("Coming soon"), "Portrait covers, wide covers, backgrounds and logos with your own API key.", tab = SettingsTab.COVERS, section = "Artwork")
    row("about_version", Symbols.DEPLOYED_CODE, "Version", RowValue.Info(BuildConfig.VERSION_NAME), "Delos for Android.", tab = SettingsTab.ABOUT, section = "Delos")
    row("about_based", Symbols.ACCOUNT_TREE, "Based on", RowValue.Info("Artemis · Moonlight"), "Open source game streaming, licensed under the GPLv3.", tab = SettingsTab.ABOUT, section = "Delos")
    row("about_welcome", Symbols.REPLAY, "Replay the welcome", RowValue.Action { shell.navigate(Screen.Boot(back = Screen.Settings)) }, "Open the welcome screen again. Existing profiles and paired PCs are kept.", tab = SettingsTab.ABOUT, section = "Delos")
    return list
}
