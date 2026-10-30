package com.limelight.shell.screens

import com.limelight.shell.ui.RowValue

/** Choice-specific help for native shell rows; the row remains the source of its current value. */
internal object NativeSettingsHelp {
    fun details(key: String, description: String, value: RowValue): String {
        val selected = value as? RowValue.Choice ?: return description
        val help = choice(key, selected.label)
        return listOf("${selected.label}\n$help", description).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    fun choice(key: String, label: String): String = when (key) {
        "ui_dark" -> if (label == "Dark") "Dark surfaces with light text. The streamed picture is unchanged."
            else "Light surfaces with dark text. The streamed picture is unchanged."
        "ui_clock" -> when (label) {
            "System" -> "Follows Android's 12-hour or 24-hour format."
            "12-hour" -> "12-hour clock with AM or PM in the status bar."
            else -> "24-hour clock in the status bar."
        }
        "ui_home_layout" -> if (label == "Grid") "Several rows of covers; scrolls vertically."
            else "One horizontal row of covers that wraps at the ends; the running and recent games come first."
        "ui_reduced_motion" -> if (label == "Reduced") "Less interface motion. Game FPS and frame pacing are unchanged."
            else "Full transitions and focus animations. Game FPS and frame pacing are unchanged."
        "ui_palette" -> palettes[label]?.let { "$it The swatches below show main, secondary and accent colors." }.orEmpty()
        "ui_background" -> backgrounds[label]?.let { "$it Shader backgrounds need Android 13 or later; older devices use fallbacks." }.orEmpty()
        "ui_stats_position" -> "Full statistics panel in the ${label.lowercase()} corner while streaming."
        "ui_default_pc" -> if (label == "None") "No preferred PC; Skip the start screen is unavailable."
            else "$label is marked on the start screen and used by Skip the start screen. It must be paired and reachable."
        else -> ""
    }

    private val palettes = mapOf(
        "Delos" to "The original Delos palette: warm orange, cyan and violet over a blue base.",
        "Lagoon" to "Turquoise, blue and warm orange over a cool base.",
        "Dusk" to "Violet, pink and cyan over a purple base.",
        "Ember" to "Orange, gold and pink over a warm base.",
        "Moss" to "Green, turquoise and gold over a green base.",
        "Iris" to "Blue, violet and orange over a blue base.",
        "Rose" to "Pink, orange and turquoise over a rose base.",
        "Sunset" to "Orange, pink and gold over a purple base.",
        "Mint" to "Mint, blue and pink over a cool green base.",
        "Gold" to "Gold, cyan and pink over a warm base.",
    )

    private val backgrounds = mapOf(
        "Aurora" to "Soft moving bands of color, like an aurora.",
        "Tide" to "A broad moving tide of color across the lower part of the screen.",
        "Silk" to "Slow flowing folds of color with a fabric-like appearance.",
        "Contour" to "Smooth moving contour lines in the selected colors.",
        "Nebula" to "Cloud-like fields of color with a space-inspired appearance.",
        "Plasma" to "Flowing waves of color with a plasma-like appearance.",
        "Lava" to "Slowly moving, merging shapes of color.",
        "Caustics" to "Shifting light patterns inspired by reflections under water.",
        "Bokeh" to "Soft out-of-focus circles of light.",
        "Orbit" to "Moving colored forms following an orbital pattern.",
        "Still" to "A static background without background animation.",
    )
}
