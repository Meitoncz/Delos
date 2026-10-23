package com.limelight.shell.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BackgroundKind(val label: String) { AURORA("Aurora"), TIDE("Tide"), SILK("Silk"), CONTOUR("Contour"), STILL("Still") }

enum class HomeLayout(val label: String) { GRID("Grid"), ROW("Row") }

/** Accent seeds for Material You; [hue] null = the system (wallpaper) palette. */
enum class Seed(val label: String, val hue: Float?) {
    SYSTEM("System", null), LAGOON("Lagoon", 176f), DUSK("Dusk", 268f), EMBER("Ember", 14f), MOSS("Moss", 96f), IRIS("Iris", 226f)
}

/** Settings → Appearance of the new UI (docs/UI_PLAN.md, step 6.2). Defaults follow the prototype. */
data class Appearance(
    val dark: Boolean,
    val materialYou: Boolean = false,
    val seed: Seed = Seed.SYSTEM,
    val background: BackgroundKind = BackgroundKind.AURORA,
    val homeLayout: HomeLayout = HomeLayout.GRID,
    val heroBackground: Boolean = true,
    /** Game background blur in stage pixels (0–40). */
    val heroBlur: Int = 16,
    /** Game background opacity in percent. */
    val heroOpacity: Int = 70,
    /** Interface scale in percent (100–130). */
    val uiScale: Int = 115,
    val reducedMotion: Boolean = false,
    val sounds: Boolean = true,
    val haptics: Boolean = true,
)

/**
 * Stores [Appearance] in the default SharedPreferences (next to the stream settings) under `ui_*` keys,
 * so the classic UI and the stream are not affected.
 */
class AppearanceStore(context: Context) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val state = MutableStateFlow(read())

    val appearance: StateFlow<Appearance> = state.asStateFlow()

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
            seed = enumOr(prefs.getString(KEY_SEED, null), defaults.seed),
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
            .putString(KEY_SEED, a.seed.name)
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
        const val KEY_SEED = "ui_seed"
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
