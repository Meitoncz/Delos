package com.limelight.shell.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BackgroundKind(val label: String) {
    AURORA("Aurora"), TIDE("Tide"), SILK("Silk"), CONTOUR("Contour"),
    // Shaders, Android 13 and later (Aurora below)
    NEBULA("Nebula"), PLASMA("Plasma"), LAVA("Lava"), CAUSTICS("Caustics"), BOKEH("Bokeh"), ORBIT("Orbit"),
    STILL("Still"),
}

enum class HomeLayout(val label: String) { GRID("Grid"), ROW("Row") }

/**
 * Color palettes used while Material You is off (with it on, the system's wallpaper colors are used):
 * the background's hue and the three colors, main ([accent]: buttons, focus, selection), secondary
 * ([accent2]: rings, pulses, gradients) and accent ([accent3]: small highlights and a third light in the
 * animated backgrounds). Delos is the prototype's own palette.
 */
enum class Palette(val label: String, val base: Float, val accent: Float, val accent2: Float, val accent3: Float) {
    DELOS("Delos", 222f, 40f, 192f, 300f),
    LAGOON("Lagoon", 190f, 176f, 228f, 18f),
    DUSK("Dusk", 262f, 268f, 320f, 200f),
    EMBER("Ember", 14f, 14f, 40f, 330f),
    MOSS("Moss", 110f, 96f, 150f, 45f),
    IRIS("Iris", 232f, 226f, 280f, 180f),
    ROSE("Rose", 330f, 340f, 20f, 270f),
    SUNSET("Sunset", 285f, 24f, 330f, 48f),
    MINT("Mint", 165f, 158f, 205f, 330f),
    GOLD("Gold", 35f, 45f, 195f, 345f),
}

/** How the status bar shows the time. */
enum class ClockFormat(val label: String) { SYSTEM("System"), H12("12-hour"), H24("24-hour") }

/** Settings → Appearance of the new UI (docs/UI_PLAN.md, step 6.2). Defaults follow the prototype. */
data class Appearance(
    val dark: Boolean,
    /** The system's wallpaper colors; off, [palette] applies. */
    val materialYou: Boolean = false,
    val palette: Palette = Palette.DELOS,
    val clock: ClockFormat = ClockFormat.SYSTEM,
    /** The animated background takes on the main color of the focused game's background image (palettes only). */
    val matchGameColors: Boolean = true,
    val background: BackgroundKind = BackgroundKind.AURORA,
    val homeLayout: HomeLayout = HomeLayout.GRID,
    val heroBackground: Boolean = true,
    /** Game background blur in stage pixels (0–40). */
    val heroBlur: Int = 15,
    /** Game background opacity in percent. */
    val heroOpacity: Int = 70,
    /** Interface scale in percent (100–130). */
    val uiScale: Int = 115,
    val reducedMotion: Boolean = false,
    val sounds: Boolean = true,
    val haptics: Boolean = true,
)

/**
 * Stores [Appearance] under `ui_*` keys in the current profile's [settings] (ProfileStore.settings), so
 * every profile has its own and the classic UI and the stream are not affected.
 */
class AppearanceStore(context: Context, private val settings: () -> SharedPreferences) {
    private val prefs: SharedPreferences get() = settings()
    private val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val state = MutableStateFlow(read())

    val appearance: StateFlow<Appearance> = state.asStateFlow()

    /** Reads the settings again (after switching profiles). */
    fun reload() {
        state.value = read()
    }

    fun update(transform: (Appearance) -> Appearance) {
        val next = transform(state.value)
        if (next != state.value) {
            write(next)
            state.value = next
        }
    }

    private fun read(): Appearance {
        val defaults = Appearance(dark = systemDark)
        return Appearance(
            dark = prefs.getBoolean(KEY_DARK, defaults.dark),
            materialYou = prefs.getBoolean(KEY_MATERIAL_YOU, defaults.materialYou),
            palette = enumOr(prefs.getString(KEY_PALETTE, null), defaults.palette),
            clock = enumOr(prefs.getString(KEY_CLOCK, null), defaults.clock),
            matchGameColors = prefs.getBoolean(KEY_MATCH_GAME, defaults.matchGameColors),
            background = enumOr(prefs.getString(KEY_BACKGROUND, null), defaults.background),
            homeLayout = enumOr(prefs.getString(KEY_HOME_LAYOUT, null), defaults.homeLayout),
            heroBackground = prefs.getBoolean(KEY_HERO, defaults.heroBackground),
            heroBlur = prefs.getInt(KEY_HERO_BLUR, defaults.heroBlur).coerceIn(0, 40),
            heroOpacity = prefs.getInt(KEY_HERO_OPACITY, defaults.heroOpacity).coerceIn(0, 100),
            uiScale = prefs.getInt(KEY_UI_SCALE, defaults.uiScale).coerceIn(100, 130),
            reducedMotion = prefs.getBoolean(KEY_REDUCED_MOTION, defaults.reducedMotion),
            sounds = prefs.getBoolean(KEY_SOUNDS, defaults.sounds),
            haptics = prefs.getBoolean(KEY_HAPTICS, defaults.haptics),
        )
    }

    private fun write(a: Appearance) {
        prefs.edit()
            .putBoolean(KEY_DARK, a.dark)
            .putBoolean(KEY_MATERIAL_YOU, a.materialYou)
            .putString(KEY_PALETTE, a.palette.name)
            .putString(KEY_CLOCK, a.clock.name)
            .putBoolean(KEY_MATCH_GAME, a.matchGameColors)
            .putString(KEY_BACKGROUND, a.background.name)
            .putString(KEY_HOME_LAYOUT, a.homeLayout.name)
            .putBoolean(KEY_HERO, a.heroBackground)
            .putInt(KEY_HERO_BLUR, a.heroBlur)
            .putInt(KEY_HERO_OPACITY, a.heroOpacity)
            .putInt(KEY_UI_SCALE, a.uiScale)
            .putBoolean(KEY_REDUCED_MOTION, a.reducedMotion)
            .putBoolean(KEY_SOUNDS, a.sounds)
            .putBoolean(KEY_HAPTICS, a.haptics)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_DARK = "ui_dark"
        const val KEY_MATERIAL_YOU = "ui_material_you"
        const val KEY_PALETTE = "ui_palette"
        const val KEY_CLOCK = "ui_clock"
        const val KEY_MATCH_GAME = "ui_match_game_colors"
        const val KEY_BACKGROUND = "ui_background"
        const val KEY_HOME_LAYOUT = "ui_home_layout"
        const val KEY_HERO = "ui_hero_background"
        const val KEY_HERO_BLUR = "ui_hero_blur"
        const val KEY_HERO_OPACITY = "ui_hero_opacity"
        const val KEY_UI_SCALE = "ui_scale"
        const val KEY_REDUCED_MOTION = "ui_reduced_motion"
        const val KEY_SOUNDS = "ui_sounds"
        const val KEY_HAPTICS = "ui_haptics"
    }
}
