package com.limelight.shell.screens

import android.content.res.Resources
import com.limelight.R

/**
 * The extra words a settings row can be found by besides its own texts: synonyms and the earlier names of renamed rows.
 *
 * Two kinds, both always searched: the **English** words ([ENGLISH]: the earlier English names and the terms gamers search
 * for in any language; data, not interface text) and the **localized** synonyms of the current language
 * (`settings_search_alias_*`, space-separated words that are never shown). A language therefore finds a row by its own words
 * and by the English ones.
 */
internal object SearchAliases {
    private val ENGLISH = mapOf(
        "checkbox_gamepad_motion_sensors" to "allow use of gamepad motion sensors motion sensor",
        "checkbox_gamepad_motion_fallback" to "emulate gamepad motion sensor support motion sensors",
        "checkbox_force_device_motion" to "always use device's motion sensors motion sensors",
        "seekbar_gyro_smoothing" to "gyroscope stabilisation stabilization motion sensors",
        // Rows renamed from "game" to "app": the old word stays searchable.
        "ui_match_game_colors" to "game",
        "ui_hero_background" to "game",
        "ui_hero_blur" to "game",
        "ui_hero_opacity" to "game",
        "covers_sgdb_choose" to "game",
        LINK_TEST_ROW to "bandwidth probe measure speed network wifi",
        LINK_USE_ROW to "recommended bitrate link",
    )

    private val LOCALIZED = mapOf(
        "checkbox_gamepad_motion_sensors" to R.string.settings_search_alias_motion_sensors,
        "checkbox_gamepad_motion_fallback" to R.string.settings_search_alias_motion_sensors,
        "checkbox_force_device_motion" to R.string.settings_search_alias_motion_sensors,
        "seekbar_gyro_smoothing" to R.string.settings_search_alias_gyro_smoothing,
        "ui_match_game_colors" to R.string.settings_search_alias_game,
        "ui_hero_background" to R.string.settings_search_alias_game,
        "ui_hero_blur" to R.string.settings_search_alias_game,
        "ui_hero_opacity" to R.string.settings_search_alias_game,
        "covers_sgdb_choose" to R.string.settings_search_alias_game,
        LINK_TEST_ROW to R.string.settings_search_alias_link_test,
        LINK_USE_ROW to R.string.settings_search_alias_link_use,
    )

    /** The English and the localized alias words of the row [key], empty when it has none. */
    fun terms(res: Resources, key: String): String =
        listOfNotNull(ENGLISH[key], LOCALIZED[key]?.let { res.getString(it) }).joinToString(" ")
}
