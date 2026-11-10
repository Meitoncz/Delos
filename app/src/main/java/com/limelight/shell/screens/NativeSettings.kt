package com.limelight.shell.screens

import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import android.content.res.Resources
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.limelight.BuildConfig
import com.limelight.R
import com.limelight.diag.DiagLog
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
import com.limelight.shell.settings.Palette
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
import com.limelight.shell.ui.textResources

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
internal fun settingSearchTerms(res: Resources, spec: SettingSpec): String =
    (spec.choices.map { "${it.label} ${SettingsHelp.choice(res, spec.key, it.value)}" } + listOfNotNull(RENAMED_SEARCH_TERMS[spec.key]))
        .joinToString(" ")

/** The earlier names of renamed rows, so searching for them still finds the row. */
private val RENAMED_SEARCH_TERMS = mapOf(
    "checkbox_gamepad_motion_sensors" to "allow use of gamepad motion sensors motion sensor",
    "checkbox_gamepad_motion_fallback" to "emulate gamepad motion sensor support motion sensors",
    "checkbox_force_device_motion" to "always use device's motion sensors motion sensors",
    "seekbar_gyro_smoothing" to "gyroscope stabilisation stabilization motion sensors",
)

/** The settings whose texts contain every word of [query], in any order and case. */
internal fun searchSettings(res: Resources, items: List<SettingsItem>, query: String): List<SettingsItem> {
    val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
    return items.filter { item ->
        val text = "${item.label} ${item.description} ${item.detail} ${item.searchTerms} ${item.section} ${item.tab.label(res)}".lowercase(Locale.ROOT)
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
    val res = textResources()
    val shell = LocalShell.current
    fun providerWebsite(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { shell.showToast(res.getString(R.string.settings_native_no_browser_toast), Symbols.ERROR) }
    }
    val systemHue = remember { systemAccentHue(context) }
    val list = mutableListOf<SettingsItem>()
    val interfaceSection = res.getString(R.string.settings_native_section_interface)
    val streamMenuSection = res.getString(R.string.settings_native_section_stream_menu)
    val startupSection = res.getString(R.string.settings_native_section_startup)
    val pairedSection = res.getString(R.string.settings_native_section_paired_pcs)
    val sgdbSection = res.getString(R.string.settings_native_section_steamgriddb)
    val delosSection = res.getString(R.string.settings_native_section_delos)
    val gameDataSection = res.getString(R.string.settings_native_section_game_data)
    /** [choiceHelp] explains the selected choice of a choice row (see [NativeSettingsHelp.choice]). */
    fun row(key: String, icon: String, title: String, value: RowValue, description: String, enabled: Boolean = true,
            tab: SettingsTab = SettingsTab.APPEARANCE, section: String = interfaceSection, choiceHelp: String = "") {
        val help = SettingsHelp.description(res, key, description)
        list += SettingsItem(key, tab, section, icon, title, help, value, enabled,
            NativeSettingsHelp.details(help, value, choiceHelp))
    }
    row("ui_dark", Symbols.DARK_MODE, res.getString(R.string.settings_native_theme_title),
        RowValue.Choice(res.getString(if (a.dark) R.string.settings_native_theme_dark else R.string.settings_native_theme_light), { update { it.copy(dark = !it.dark) } }),
        res.getString(R.string.settings_native_theme_description), choiceHelp = NativeSettingsHelp.choice(res, "ui_dark", if (a.dark) "dark" else "light"))
    row("ui_material_you", Symbols.SYSTEM_COLORS, res.getString(R.string.settings_native_material_you_title), RowValue.Toggle(a.materialYou) { on -> update { it.copy(materialYou = on) } },
        if (systemHue != null) res.getString(R.string.settings_native_material_you_description)
        else res.getString(R.string.settings_native_material_you_fallback_description, Palette.LAGOON.label(res)))
    row("ui_palette", Symbols.PALETTE, res.getString(R.string.settings_native_palette_title), RowValue.Choice(a.palette.label(res), { dir -> update { it.copy(palette = it.palette.step(dir)) } }, hsl(a.palette.accent, 90f, 60f)),
        if (a.materialYou) res.getString(R.string.settings_native_needs_material_you_off) else res.getString(R.string.settings_native_palette_description), !a.materialYou)
    row("ui_match_game_colors", Symbols.MATCH_COLORS, res.getString(R.string.settings_native_match_colors_title), RowValue.Toggle(a.matchGameColors) { on -> update { it.copy(matchGameColors = on) } },
        if (a.materialYou) res.getString(R.string.settings_native_needs_material_you_off) else res.getString(R.string.settings_native_match_colors_description), !a.materialYou)
    row("ui_clock", Symbols.SCHEDULE, res.getString(R.string.settings_native_clock_title), RowValue.Choice(a.clock.label(res), { dir -> update { it.copy(clock = it.clock.step(dir)) } }),
        res.getString(R.string.settings_native_clock_description), choiceHelp = NativeSettingsHelp.choice(res, "ui_clock", a.clock.name))
    row("ui_background", Symbols.ANIMATION, res.getString(R.string.settings_native_background_title), RowValue.Choice(a.background.label(res), { dir -> update { it.copy(background = it.background.step(dir)) } }),
        res.getString(R.string.settings_native_background_description), choiceHelp = NativeSettingsHelp.choice(res, "ui_background", a.background.name))
    row("ui_home_layout", Symbols.VIEW_CAROUSEL, res.getString(R.string.settings_native_home_layout_title), RowValue.Choice(a.homeLayout.label(res), { dir -> update { it.copy(homeLayout = it.homeLayout.step(dir)) } }),
        res.getString(R.string.settings_native_home_layout_description), choiceHelp = NativeSettingsHelp.choice(res, "ui_home_layout", a.homeLayout.name))
    row("ui_hero_background", Symbols.WALLPAPER, res.getString(R.string.settings_native_hero_background_title), RowValue.Toggle(a.heroBackground) { on -> update { it.copy(heroBackground = on) } },
        res.getString(R.string.settings_native_hero_background_description))
    row("ui_hero_blur", Symbols.BLUR_ON, res.getString(R.string.settings_native_hero_blur_title), RowValue.Slider(a.heroBlur, 0, 40, 5, " px") { v -> update { it.copy(heroBlur = v) } },
        res.getString(R.string.settings_native_hero_blur_description), a.heroBackground)
    row("ui_hero_opacity", Symbols.OPACITY, res.getString(R.string.settings_native_hero_opacity_title), RowValue.Slider(a.heroOpacity, 0, 100, 5, " %") { v -> update { it.copy(heroOpacity = v) } },
        res.getString(R.string.settings_native_hero_opacity_description), a.heroBackground)
    row("ui_scale", Symbols.FORMAT_SIZE, res.getString(R.string.settings_native_scale_title), RowValue.Slider(a.uiScale, 100, 130, 5, " %") { v -> update { it.copy(uiScale = v) } },
        res.getString(R.string.settings_native_scale_description))
    row("ui_reduced_motion", Symbols.REDUCED_MOTION, res.getString(R.string.settings_native_animations_title),
        RowValue.Choice(res.getString(if (a.reducedMotion) R.string.settings_native_animations_reduced else R.string.settings_native_animations_full), { update { it.copy(reducedMotion = !it.reducedMotion) } }),
        res.getString(R.string.settings_native_animations_description), choiceHelp = NativeSettingsHelp.choice(res, "ui_reduced_motion", if (a.reducedMotion) "reduced" else "full"))
    row("ui_haptics", Symbols.HAPTICS, res.getString(R.string.settings_native_haptics_title), RowValue.Toggle(a.haptics) { on -> update { it.copy(haptics = on) } },
        res.getString(R.string.settings_native_haptics_description), tab = SettingsTab.CONTROLS)
    row("ui_sounds", Symbols.MUSIC_NOTE, res.getString(R.string.settings_native_sounds_title), RowValue.Toggle(a.sounds) { on -> update { it.copy(sounds = on) } },
        res.getString(R.string.settings_native_sounds_description), tab = SettingsTab.AUDIO)
    val menuButtons = StreamMenuSettings.buttons(settings)
    row("ui_stream_menu_buttons", Symbols.STREAM_MENU_BUTTONS, res.getString(R.string.settings_native_menu_buttons_title), RowValue.Text(StreamMenuSettings.label(menuButtons)) {
        shell.buttonCapture = { buttons -> StreamMenuSettings.setButtons(settings, buttons) }
    }, res.getString(R.string.settings_native_menu_buttons_description),
        tab = SettingsTab.CONTROLS, section = streamMenuSection)
    row("ui_stream_menu_swipe", Symbols.SWIPE_UP, res.getString(R.string.settings_native_menu_swipe_title), RowValue.Toggle(StreamMenuSettings.swipe(settings)) { on ->
        settings.edit().putBoolean(StreamMenuSettings.KEY_SWIPE, on).apply()
    }, res.getString(R.string.settings_native_menu_swipe_description),
        tab = SettingsTab.CONTROLS, section = streamMenuSection)
    val statsPosition = StatsPosition.read(settings)
    row("ui_stats_position", Symbols.STATS_POSITION, res.getString(R.string.settings_native_stats_position_title),
        RowValue.Choice(res.getString(statsPosition.labelRes), { dir ->
            settings.edit().putString(StatsPosition.KEY, statsPosition.step(dir).name).apply()
        }), res.getString(R.string.settings_native_stats_position_description), tab = SettingsTab.DISPLAY,
        section = res.getString(R.string.settings_native_section_statistics), choiceHelp = NativeSettingsHelp.choice(res, "ui_stats_position", statsPosition.name))
    val choices = listOf<Pc?>(null) + pcs.filter { it.paired }.sortedBy { it.name.lowercase() }
    val index = choices.indexOfFirst { it?.uuid == startup.defaultPc }.coerceAtLeast(0)
    row("ui_default_pc", Symbols.STAR, res.getString(R.string.settings_native_default_pc_title), RowValue.Choice(choices[index]?.name ?: res.getString(R.string.settings_native_default_pc_none), { dir ->
        val next = choices[Math.floorMod(index + dir, choices.size)]
        updateStartup { it.copy(defaultPc = next?.uuid, skipStart = next != null && it.skipStart) }
    }), res.getString(R.string.settings_native_default_pc_description), choices.size > 1, SettingsTab.DEVICES, startupSection,
        NativeSettingsHelp.choice(res, "ui_default_pc", if (choices[index] == null) "none" else "pc"))
    row("ui_skip_start", Symbols.FAST_FORWARD, res.getString(R.string.settings_native_skip_start_title), RowValue.Toggle(startup.skipStart) { on -> updateStartup { it.copy(skipStart = on) } },
        res.getString(R.string.settings_native_skip_start_description), startup.defaultPc != null, SettingsTab.DEVICES, startupSection)
    row("devices_manage", Symbols.DEVICES, res.getString(R.string.settings_native_manage_devices_title), RowValue.Action { shell.navigate(Screen.Devices) },
        res.getString(R.string.settings_native_manage_devices_description), tab = SettingsTab.DEVICES, section = pairedSection)
    row("devices_pair", Symbols.ADD_LINK, res.getString(R.string.settings_native_pair_title), RowValue.Action { shell.navigate(Screen.PairPc(Screen.Settings)) },
        res.getString(R.string.settings_native_pair_description), tab = SettingsTab.DEVICES, section = pairedSection)
    val artwork = LocalArtwork.current
    val tracker = LocalFocusTracker.current
    val library = LocalLibrary.current as? Library.Ready
    val scope = rememberCoroutineScope()
    row("covers_sgdb_key", Symbols.KEY, res.getString(R.string.settings_native_sgdb_key_title),
        RowValue.Text(res.getString(if (artwork.enabled) R.string.settings_native_sgdb_key_added else R.string.settings_native_sgdb_key_not_set)) {
        shell.openLayer(Layer.Dialog(DialogSpec(
            res.getString(R.string.settings_native_sgdb_key_title),
            res.getString(R.string.settings_native_sgdb_key_note),
            listOf(DialogField(Symbols.KEY, artwork.apiKey, res.getString(R.string.settings_native_sgdb_key_placeholder), password = true, maxLength = 64)),
            res.getString(R.string.settings_native_sgdb_key_save),
        ) { values ->
            artwork.apiKey = values.first()
            shell.showToast(res.getString(if (artwork.enabled) R.string.settings_native_sgdb_key_saved_toast else R.string.settings_native_sgdb_off_toast), Symbols.CHECK_CIRCLE)
            true
        }), tracker)
    }, res.getString(R.string.settings_native_sgdb_key_description),
        tab = SettingsTab.COVERS, section = sgdbSection)
    row("covers_sgdb_auto", Symbols.CLOUD_DOWNLOAD, res.getString(R.string.settings_native_sgdb_auto_title), RowValue.Toggle(artwork.automatic) { on -> artwork.automatic = on },
        res.getString(R.string.settings_native_sgdb_auto_description),
        artwork.enabled, SettingsTab.COVERS, sgdbSection)
    fun downloadAll(refresh: Boolean) {
        val ready = library ?: return shell.showToast(res.getString(R.string.settings_native_connect_first_toast), Symbols.ERROR)
        scope.launch {
            shell.showToast(res.getString(R.string.settings_native_downloading_toast, ready.pc.name), Symbols.DOWNLOAD)
            var failed = false
            val found = artwork.downloadAll(ready.pc, ready.apps, refresh) {
                failed = true
                shell.showToast(it.text(res), Symbols.ERROR)
            }
            if (!failed) shell.showToast(
                if (found == 0 && !refresh) res.getString(R.string.settings_native_artwork_complete_toast) else res.getQuantityString(R.plurals.settings_native_artwork_ready_toast, found, found),
                Symbols.CHECK_CIRCLE,
            )
        }
    }
    row("covers_sgdb_missing", Symbols.DOWNLOAD, res.getString(R.string.settings_native_missing_artwork_title), RowValue.Action { downloadAll(refresh = false) },
        library?.pc?.name?.let { res.getString(R.string.settings_native_missing_artwork_description, it) } ?: res.getString(R.string.settings_native_missing_artwork_description_current),
        artwork.enabled, SettingsTab.COVERS, sgdbSection)
    row("covers_sgdb_refresh", Symbols.REFRESH, res.getString(R.string.settings_native_refresh_artwork_title), RowValue.Action { downloadAll(refresh = true) },
        res.getString(R.string.settings_native_refresh_artwork_description), artwork.enabled, SettingsTab.COVERS, sgdbSection)
    row("covers_sgdb_choose", Symbols.IMAGE_CHOOSE, res.getString(R.string.settings_native_choose_artwork_title), RowValue.Info(res.getString(R.string.settings_native_choose_artwork_value)),
        res.getString(R.string.settings_native_choose_artwork_description),
        tab = SettingsTab.COVERS, section = sgdbSection)
    row("about_version", Symbols.VERSION, res.getString(R.string.settings_native_version_title), RowValue.Info(BuildConfig.VERSION_NAME), res.getString(R.string.settings_native_version_description), tab = SettingsTab.ABOUT, section = delosSection)
    row("about_based", Symbols.ACCOUNT_TREE, res.getString(R.string.settings_native_based_title), RowValue.Info("Artemis · Moonlight"), // l10n-ignore: product names
        res.getString(R.string.settings_native_based_description), tab = SettingsTab.ABOUT, section = delosSection)
    row("about_welcome", Symbols.REPLAY, res.getString(R.string.settings_native_welcome_title), RowValue.Action { shell.navigate(Screen.Boot(back = Screen.Settings)) },
        res.getString(R.string.settings_native_welcome_description), tab = SettingsTab.ABOUT, section = delosSection)
    var diagnosticLogs by remember { mutableStateOf(DiagLog.enabled) }
    row("about_diag_logs", Symbols.DIAGNOSTIC_LOGS, res.getString(R.string.settings_native_diag_logs_title), RowValue.Toggle(diagnosticLogs) { on ->
        diagnosticLogs = on
        DiagLog.setEnabled(context, on)
    }, res.getString(R.string.settings_native_diag_logs_description),
        tab = SettingsTab.ABOUT, section = res.getString(R.string.settings_native_section_diagnostics))
    row("about_igdb", Symbols.PROVIDER_IGDB, res.getString(R.string.settings_native_igdb_title),
        RowValue.Action { providerWebsite("https://www.igdb.com/") },
        res.getString(R.string.settings_native_igdb_description), tab = SettingsTab.ABOUT, section = gameDataSection)
    row("about_pcgw", Symbols.PROVIDER_PCGW, res.getString(R.string.settings_native_pcgw_title),
        RowValue.Action { providerWebsite("https://www.pcgamingwiki.com/wiki/PCGamingWiki:Copyrights") },
        res.getString(R.string.settings_native_pcgw_description), tab = SettingsTab.ABOUT, section = gameDataSection)
    row("about_steam", Symbols.STEAM, res.getString(R.string.settings_native_steam_title),
        RowValue.Action { providerWebsite("https://store.steampowered.com/") },
        res.getString(R.string.settings_native_steam_description), tab = SettingsTab.ABOUT, section = gameDataSection)
    row("about_hltb", Symbols.COMPLETION_TIME, res.getString(R.string.settings_native_hltb_title),
        RowValue.Action { providerWebsite("https://howlongtobeat.com/") },
        res.getString(R.string.settings_native_hltb_description), tab = SettingsTab.ABOUT, section = gameDataSection)
    return list
}
