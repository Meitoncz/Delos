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
        "ui_dark" -> if (label == "Dark") "Dark surfaces with light text." else "Light surfaces with dark text."
        "ui_clock" -> when (label) {
            "System" -> "Follows Android's 12/24-hour setting."
            "12-hour" -> "12-hour clock with AM or PM."
            else -> "24-hour clock."
        }
        "ui_home_layout" -> if (label == "Grid") "Several rows of covers; scrolls vertically."
            else "One row of covers; running and recent games come first."
        "ui_reduced_motion" -> if (label == "Reduced") "Less interface motion. Game FPS is unchanged."
            else "Full transitions and focus animations."
        "ui_palette" -> palettes[label].orEmpty()
        "ui_background" -> backgrounds[label].orEmpty()
        "ui_stats_position" -> "Full statistics panel in the ${label.lowercase()} corner."
        "ui_default_pc" -> if (label == "None") "No preferred PC; Skip the start screen is unavailable."
            else "Used by Skip the start screen. Must be paired and reachable."
        else -> ""
    }

    private val palettes = mapOf(
        "Delos" to "Warm orange, cyan and violet over a blue base.",
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
        "Tide" to "A broad tide of color across the lower screen.",
        "Silk" to "Slow flowing folds of color, like fabric.",
        "Contour" to "Smooth moving contour lines in the selected colors.",
        "Nebula" to "Cloud-like fields of color, like space.",
        "Plasma" to "Flowing waves of color, like plasma.",
        "Lava" to "Slowly moving, merging shapes of color.",
        "Caustics" to "Shifting light patterns, like reflections under water.",
        "Bokeh" to "Soft out-of-focus circles of light.",
        "Orbit" to "Moving colored forms in an orbital pattern.",
        "Still" to "A static background without animation.",
    )
}
