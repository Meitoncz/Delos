package com.limelight.shell.screens

import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.limelight.BuildConfig
import com.limelight.shell.DialogField
import com.limelight.shell.DialogSpec
import com.limelight.shell.Layer
import com.limelight.shell.LocalFocusTracker
import com.limelight.shell.LocalLibrary
import com.limelight.shell.LocalShell
import com.limelight.shell.data.Library
import com.limelight.shell.data.LocalArtwork
import com.limelight.shell.data.downloadAll
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.limelight.shell.Screen
import com.limelight.shell.StatsPosition
import com.limelight.shell.data.Pc
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.SettingImpact
import com.limelight.shell.settings.SettingSpec
import com.limelight.shell.settings.SettingsTab
import com.limelight.shell.settings.SettingsHelp
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
    val detail: String = description,
    val searchTerms: String = "",
    val impact: SettingImpact? = null,
)

/** The choice names a setting can be found by, besides its own texts. */
internal fun settingSearchTerms(spec: SettingSpec): String =
    spec.choices.joinToString(" ") { "${it.label} ${SettingsHelp.choice(spec.key, it.value)}" }

/** The settings whose texts contain every word of [query], in any order and case. */
internal fun searchSettings(items: List<SettingsItem>, query: String): List<SettingsItem> {
    val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
    return items.filter { item ->
        val text = "${item.label} ${item.description} ${item.detail} ${item.searchTerms} ${item.section} ${item.tab.label}".lowercase(Locale.ROOT)
        words.all { text.contains(it) }
    }
}

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
    fun providerWebsite(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { shell.showToast("No browser is available", Symbols.ERROR) }
    }
    val systemHue = remember { systemAccentHue(context) }
    val list = mutableListOf<SettingsItem>()
    fun row(key: String, icon: String, title: String, value: RowValue, description: String, enabled: Boolean = true,
            tab: SettingsTab = SettingsTab.APPEARANCE, section: String = "Interface") {
        val help = SettingsHelp.description(key, description)
        list += SettingsItem(key, tab, section, icon, title, help, value, enabled,
            NativeSettingsHelp.details(key, help, value))
    }
    row("ui_dark", Symbols.DARK_MODE, "Theme", RowValue.Choice(if (a.dark) "Dark" else "Light", { update { it.copy(dark = !it.dark) } }), "Dark or light interface.")
    row("ui_material_you", Symbols.SYSTEM_COLORS, "Material You colors", RowValue.Toggle(a.materialYou) { on -> update { it.copy(materialYou = on) } },
        if (systemHue != null) "Uses only your wallpaper colors. Turn off to choose a palette."
        else "No wallpaper palette on this device, so Lagoon's colors are used. Turn off to choose a palette.")
    row("ui_palette", Symbols.PALETTE, "Color palette", RowValue.Choice(a.palette.label, { dir -> update { it.copy(palette = it.palette.step(dir)) } }, hsl(a.palette.accent, 90f, 60f)),
        if (a.materialYou) "Needs Material You colors off." else "Colors of the interface and animated backgrounds.", !a.materialYou)
    row("ui_match_game_colors", Symbols.MATCH_COLORS, "Match the game's colors", RowValue.Toggle(a.matchGameColors) { on -> update { it.copy(matchGameColors = on) } },
        if (a.materialYou) "Needs Material You colors off." else "The animated background takes the main color of the selected game's background image.", !a.materialYou)
    row("ui_clock", Symbols.SCHEDULE, "Clock", RowValue.Choice(a.clock.label, { dir -> update { it.copy(clock = it.clock.step(dir)) } }),
        "Time format in the status bar.")
    row("ui_background", Symbols.ANIMATION, "Animated background", RowValue.Choice(a.background.label, { dir -> update { it.copy(background = it.background.step(dir)) } }), "Animated shader background (Android 13 or later), or a still one.")
    row("ui_home_layout", Symbols.VIEW_CAROUSEL, "Home layout", RowValue.Choice(a.homeLayout.label, { dir -> update { it.copy(homeLayout = it.homeLayout.step(dir)) } }), "Library as a scrolling grid or a single row.")
    row("ui_hero_background", Symbols.IMAGE, "Game background", RowValue.Toggle(a.heroBackground) { on -> update { it.copy(heroBackground = on) } }, "Shows the selected game's background on the home screen.")
    row("ui_hero_blur", Symbols.BLUR_ON, "Game background blur", RowValue.Slider(a.heroBlur, 0, 40, 5, " px") { v -> update { it.copy(heroBlur = v) } }, "Blurs the game background behind the library. Needs Game background.", a.heroBackground)
    row("ui_hero_opacity", Symbols.OPACITY, "Game background opacity", RowValue.Slider(a.heroOpacity, 0, 100, 5, " %") { v -> update { it.copy(heroOpacity = v) } }, "How strongly the game artwork shows through the animated background. Needs Game background.", a.heroBackground)
    row("ui_scale", Symbols.SCALE, "Interface scale", RowValue.Slider(a.uiScale, 100, 130, 5, " %") { v -> update { it.copy(uiScale = v) } }, "Size of text, status bar and button hints.")
    row("ui_reduced_motion", Symbols.MOTION_PHOTOS_ON, "Animations", RowValue.Choice(if (a.reducedMotion) "Reduced" else "Full", { update { it.copy(reducedMotion = !it.reducedMotion) } }), "Interface movement only.")
    row("ui_haptics", Symbols.VIBRATION, "Interface haptics", RowValue.Toggle(a.haptics) { on -> update { it.copy(haptics = on) } }, "Vibration when moving and selecting.", tab = SettingsTab.CONTROLS)
    row("ui_sounds", Symbols.MUSIC_NOTE, "Interface sounds", RowValue.Toggle(a.sounds) { on -> update { it.copy(sounds = on) } }, "Soft clicks when moving through menus.", tab = SettingsTab.AUDIO)
    val menuButtons = StreamMenuSettings.buttons(settings)
    row("ui_stream_menu_buttons", Symbols.SPORTS_ESPORTS, "Stream menu buttons", RowValue.Text(StreamMenuSettings.label(menuButtons)) {
        shell.buttonCapture = { buttons -> StreamMenuSettings.setButtons(settings, buttons) }
    }, "Buttons pressed together during a stream to open or close the stream menu. Select to record others, e.g. a rear button.",
        tab = SettingsTab.CONTROLS, section = "Stream menu")
    row("ui_stream_menu_swipe", Symbols.SWIPE_UP, "Swipe up to open the menu", RowValue.Toggle(StreamMenuSettings.swipe(settings)) { on ->
        settings.edit().putBoolean(StreamMenuSettings.KEY_SWIPE, on).apply()
    }, "A swipe up from the bottom edge also opens the stream menu. Touches starting there do not reach the game.",
        tab = SettingsTab.CONTROLS, section = "Stream menu")
    val statsPosition = StatsPosition.read(settings)
    row("ui_stats_position", Symbols.STATS_POSITION, "Statistics position", RowValue.Choice(statsPosition.label, { dir ->
        settings.edit().putString(StatsPosition.KEY, statsPosition.step(dir).name).apply()
    }), "Corner of the full statistics panel while streaming. Compact mode is always top center.", tab = SettingsTab.DISPLAY, section = "Statistics")
    val choices = listOf<Pc?>(null) + pcs.filter { it.paired }.sortedBy { it.name.lowercase() }
    val index = choices.indexOfFirst { it?.uuid == startup.defaultPc }.coerceAtLeast(0)
    row("ui_default_pc", Symbols.STAR, "Default device", RowValue.Choice(choices[index]?.name ?: "None", { dir ->
        val next = choices[Math.floorMod(index + dir, choices.size)]
        updateStartup { it.copy(defaultPc = next?.uuid, skipStart = next != null && it.skipStart) }
    }), "Preferred PC, marked on the start screen.", choices.size > 1, SettingsTab.DEVICES, "Startup")
    row("ui_skip_start", Symbols.FAST_FORWARD, "Skip the start screen", RowValue.Toggle(startup.skipStart) { on -> updateStartup { it.copy(skipStart = on) } },
        "Connects straight to the default device at startup.", startup.defaultPc != null, SettingsTab.DEVICES, "Startup")
    row("devices_manage", Symbols.DEVICES, "Manage devices", RowValue.Action { shell.navigate(Screen.Devices) }, "Connect, rename, wake, unpair or remove a PC.", tab = SettingsTab.DEVICES, section = "Paired PCs")
    row("devices_pair", Symbols.ADD_LINK, "Pair a new PC", RowValue.Action { shell.navigate(Screen.PairPc(Screen.Settings)) }, "Finds a PC running Vibepollo, Apollo or Sunshine.", tab = SettingsTab.DEVICES, section = "Paired PCs")
    val artwork = LocalArtwork.current
    val tracker = LocalFocusTracker.current
    val library = LocalLibrary.current as? Library.Ready
    val scope = rememberCoroutineScope()
    row("covers_sgdb_key", Symbols.KEY, "SteamGridDB API key", RowValue.Text(if (artwork.enabled) "Added" else "Not set") {
        shell.openLayer(Layer.Dialog(DialogSpec(
            "SteamGridDB API key",
            "Free on steamgriddb.com: sign in, then Preferences → API. Leave it empty to stop using SteamGridDB.",
            listOf(DialogField(Symbols.KEY, artwork.apiKey, "API key", password = true, maxLength = 64)), "Save",
        ) { values ->
            artwork.apiKey = values.first()
            shell.showToast(if (artwork.enabled) "API key saved" else "SteamGridDB is off", Symbols.CHECK_CIRCLE)
            true
        }), tracker)
    }, "Your own key lets Delos download covers, wide covers, backgrounds and logos from SteamGridDB. Kept on this device for every profile.",
        tab = SettingsTab.COVERS, section = "SteamGridDB")
    row("covers_sgdb_auto", Symbols.AUTORENEW, "Download automatically", RowValue.Toggle(artwork.automatic) { on -> artwork.automatic = on },
        "Fetches SteamGridDB's best-rated cover, wide cover, background and logo for new games when the library loads.",
        artwork.enabled, SettingsTab.COVERS, "SteamGridDB")
    fun downloadAll(refresh: Boolean) {
        val ready = library ?: return shell.showToast("Connect to a PC first", Symbols.ERROR)
        scope.launch {
            shell.showToast("Downloading the artwork of ${ready.pc.name}'s games…", Symbols.DOWNLOAD)
            var failed = false
            val found = artwork.downloadAll(ready.pc, ready.apps, refresh) {
                failed = true
                shell.showToast(it, Symbols.ERROR)
            }
            if (!failed) shell.showToast(if (found == 0 && !refresh) "All games have their artwork already" else "Artwork ready for $found games", Symbols.CHECK_CIRCLE)
        }
    }
    row("covers_sgdb_missing", Symbols.DOWNLOAD, "Download missing artwork", RowValue.Action { downloadAll(refresh = false) },
        "Fetches artwork for the games of ${library?.pc?.name ?: "the current PC"} that have none.", artwork.enabled, SettingsTab.COVERS, "SteamGridDB")
    row("covers_sgdb_refresh", Symbols.REFRESH, "Download all artwork again", RowValue.Action { downloadAll(refresh = true) },
        "Looks up every game again and takes the best-rated images; ones you picked stay.", artwork.enabled, SettingsTab.COVERS, "SteamGridDB")
    row("covers_sgdb_choose", Symbols.PHOTO_LIBRARY, "Choose artwork for one game", RowValue.Info("Game options"),
        "Home screen: right menu button on a game → Artwork. Pick any cover, wide cover, background or logo.",
        tab = SettingsTab.COVERS, section = "SteamGridDB")
    row("about_version", Symbols.DEPLOYED_CODE, "Version", RowValue.Info(BuildConfig.VERSION_NAME), "Delos for Android.", tab = SettingsTab.ABOUT, section = "Delos")
    row("about_based", Symbols.ACCOUNT_TREE, "Based on", RowValue.Info("Artemis · Moonlight"), "Open source game streaming, licensed under the GPLv3.", tab = SettingsTab.ABOUT, section = "Delos")
    row("about_welcome", Symbols.REPLAY, "Replay the welcome", RowValue.Action { shell.navigate(Screen.Boot(back = Screen.Settings)) }, "Opens the welcome screen again. Profiles and paired PCs are kept.", tab = SettingsTab.ABOUT, section = "Delos")
    row("about_igdb", Symbols.SPORTS_ESPORTS, "Game metadata · IGDB",
        RowValue.Action { providerWebsite("https://www.igdb.com/") },
        "Game information and genres provided by IGDB.", tab = SettingsTab.ABOUT, section = "Game data")
    row("about_pcgw", Symbols.HDR_ON, "Technical data · PCGamingWiki",
        RowValue.Action { providerWebsite("https://www.pcgamingwiki.com/wiki/PCGamingWiki:Copyrights") },
        "Native HDR evidence from PCGamingWiki (CC BY-NC-SA 3.0); the derived status is Delos's interpretation.", tab = SettingsTab.ABOUT, section = "Game data")
    row("about_steam", Symbols.GENRE_UNKNOWN, "Genre and theme tags · Steam store",
        RowValue.Action { providerWebsite("https://store.steampowered.com/") },
        "Card genres and themes come from Steam's public user tags where a game has a Steam page, read from the store page through the Delos metadata service. Unofficial and may change; IGDB is used otherwise.", tab = SettingsTab.ABOUT, section = "Game data")
    row("about_hltb", Symbols.SPORTS_ESPORTS, "Completion times · HowLongToBeat",
        RowValue.Action { providerWebsite("https://howlongtobeat.com/") },
        "HowLongToBeat's headline time. Unofficial client adapted from howlongtobeatpy (MIT, © 2018 Michele).", tab = SettingsTab.ABOUT, section = "Game data")
    return list
}
