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
        "ui_dark" -> if (label == "Dark") "Dark surfaces with light text. Applied immediately to the Delos interface; the streamed picture is unchanged."
            else "Light surfaces with dark text. Applied immediately to the Delos interface; the streamed picture is unchanged."
        "ui_clock" -> when (label) {
            "System" -> "Follow Android's current 12-hour or 24-hour time format."
            "12-hour" -> "Show a 12-hour clock with AM or PM in the status bar."
            else -> "Show a 24-hour clock without AM or PM in the status bar."
        }
        "ui_home_layout" -> if (label == "Grid") "Show several rows of covers and scroll vertically through the library."
            else "Show one horizontal row of covers. Left and right wrap between the ends; the running game and recently played games appear first."
        "ui_reduced_motion" -> if (label == "Reduced") "Reduce interface motion for a calmer experience. This does not change game FPS or the stream's frame pacing."
            else "Use the full interface transitions and focus animations. This does not change game FPS or the stream's frame pacing."
        "ui_palette" -> palettes[label]?.let { "$it The swatches below show the main, secondary and accent colors. Applies immediately when Material You colors are off." }.orEmpty()
        "ui_background" -> backgrounds[label]?.let { "$it Changes immediately. Shader backgrounds need Android 13 or later; supported fallbacks are used on older devices or when a shader cannot run." }.orEmpty()
        "ui_stats_position" -> "Place the full statistics panel in the ${label.lowercase()} corner during streaming. Compact statistics stay at the top center."
        "ui_default_pc" -> if (label == "None") "No preferred PC. Choose a device on the start screen; skipping the start screen is unavailable."
            else "Use $label as the preferred PC. It is marked on the start screen and used by Skip the start screen. Pairing and network availability are still required."
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
