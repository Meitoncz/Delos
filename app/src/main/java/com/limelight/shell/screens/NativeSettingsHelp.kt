package com.limelight.shell.screens

import android.content.res.Resources
import com.limelight.R
import com.limelight.display.SecondDisplayOverride
import com.limelight.shell.ui.RowValue

/** Choice-specific help for native shell rows; the row remains the source of its current value. */
internal object NativeSettingsHelp {
    /** The row's panel text: the selected choice with its [choiceHelp] (a [choice] text, may be blank), then the row's own [description]. */
    fun details(description: String, value: RowValue, choiceHelp: String): String {
        val selected = value as? RowValue.Choice ?: return description
        // A choice without its own help (the palettes: the row description already says what they are) must not leave an empty line behind
        val head = if (choiceHelp.isBlank()) selected.label else "${selected.label}\n$choiceHelp"
        return listOf(head, description).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /**
     * What the choice [id] of the native row [key] does. The ids are the enum names of the choice (`ClockFormat`, `HomeLayout`,
     * `BackgroundKind`, `StatsPosition`) or, for the two-way rows, `dark` / `light`, `reduced` / `full` and `none` / `pc`
     * (a default device or no default device). Blank for a choice that needs none (the palettes).
     */
    fun choice(res: Resources, key: String, id: String): String {
        val text = when (key) {
            "ui_dark" -> if (id == "dark") R.string.settings_native_help_theme_dark else R.string.settings_native_help_theme_light
            "ui_clock" -> when (id) {
                "SYSTEM" -> R.string.settings_native_help_clock_system
                "H12" -> R.string.settings_native_help_clock_12
                else -> R.string.settings_native_help_clock_24
            }
            "ui_home_layout" -> if (id == "GRID") R.string.settings_native_help_layout_grid else R.string.settings_native_help_layout_row
            "ui_reduced_motion" -> if (id == "reduced") R.string.settings_native_help_motion_reduced else R.string.settings_native_help_motion_full
            "ui_background" -> backgrounds[id]
            "ui_stats_position" -> statsPositions[id]
            SecondDisplayOverride.KEY -> secondDisplays[id]
            "ui_default_pc" -> if (id == "none") R.string.settings_native_help_default_pc_none else R.string.settings_native_help_default_pc_set
            else -> null
        }
        return text?.let(res::getString).orEmpty()
    }

    private val backgrounds = mapOf(
        "AURORA" to R.string.settings_native_help_background_aurora,
        "TIDE" to R.string.settings_native_help_background_tide,
        "SILK" to R.string.settings_native_help_background_silk,
        "CONTOUR" to R.string.settings_native_help_background_contour,
        "NEBULA" to R.string.settings_native_help_background_nebula,
        "PLASMA" to R.string.settings_native_help_background_plasma,
        "LAVA" to R.string.settings_native_help_background_lava,
        "CAUSTICS" to R.string.settings_native_help_background_caustics,
        "BOKEH" to R.string.settings_native_help_background_bokeh,
        "ORBIT" to R.string.settings_native_help_background_orbit,
        "STILL" to R.string.settings_native_help_background_still,
    )

    private val statsPositions = mapOf(
        "TOP_LEFT" to R.string.settings_native_help_stats_top_left,
        "TOP_RIGHT" to R.string.settings_native_help_stats_top_right,
        "BOTTOM_LEFT" to R.string.settings_native_help_stats_bottom_left,
        "BOTTOM_RIGHT" to R.string.settings_native_help_stats_bottom_right,
    )

    private val secondDisplays = mapOf(
        "AUTOMATIC" to R.string.settings_native_help_second_display_automatic,
        "EXTERNAL" to R.string.settings_native_help_second_display_external,
        "BUILT_IN" to R.string.settings_native_help_second_display_built_in,
    )
}
