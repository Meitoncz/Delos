package com.limelight.shell

import android.content.Context
import androidx.compose.ui.text.input.KeyboardType
import com.limelight.shell.screens.DebugToolRow
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.Symbols

/**
 * The "Screen shape simulator" rows of Settings → About → Debug info (debug builds only; the release build's [ShapeSimulatorMenu] has none).
 * Every change is applied live by [ShapeSimulator] and remembered by [ShapeSimulation]. Debug builds only, so the texts are English like the
 * crash trigger row's.
 */
internal object ShapeSimulatorMenu {
    const val SECTION_TITLE = "Screen shape simulator"

    fun rows(shell: ShellState, tracker: FocusTracker, context: Context): List<DebugToolRow> {
        val main = ShapeSimulation.main
        val cutout = ShapeSimulation.cutout
        val companion = ShapeSimulation.companion
        return listOf(
            DebugToolRow(
                "sim_screen", Symbols.MONITOR, "Simulated screen", RowValue.Text(main?.shortSize ?: "Real screen") {
                    openMenu(shell, tracker, "Simulated screen", screenItems(context, main?.label, companion = false))
                },
                "Draws the whole app as if the screen were another panel: the real screen, or one of the handhelds, phones and aspect ratios of " +
                    "the layout sheets. Applies at once, stays after a restart, and a pink chip under the screen says it is on.",
                main?.let { "Simulating ${it.label}, ${it.sizeLabel} (pixels, dpi)." } ?: "The real screen of this device.",
            ),
            DebugToolRow(
                "sim_custom", Symbols.ASPECT_RATIO, "Custom size", RowValue.Text(ShapeSimulation.custom.shortSize) { customDialog(shell, tracker, context) },
                "Any panel: width and height in pixels and the density in dpi (for example 1240, 1080 and 420).",
            ),
            DebugToolRow(
                "sim_cutout", Symbols.FULLSCREEN, "Cutout", RowValue.Choice(cutout.label, { dir ->
                    val presets = SimCutout.PRESETS
                    val at = presets.indexOf(cutout).takeIf { it >= 0 } ?: 0
                    ShapeSimulation.setCutout(context, presets[(at + dir).mod(presets.size)])
                }),
                "A camera cutout on the left, top or right edge, or a small notch at the top: drawn in the panel and given to the app as the safe area.",
            ),
            DebugToolRow(
                "sim_dual", Symbols.DISPLAY_TOP, "Dual-screen simulation", RowValue.Text(
                    if (companion == null) "Off" else ShapeCatalog.duals.firstOrNull { it.main.title == main?.label && it.companion.title == companion.label }?.title ?: "Custom pair",
                ) {
                    openMenu(shell, tracker, "Dual-screen simulation", dualItems(context, main?.label, companion?.label))
                },
                "Shows both screens of a dual-screen handheld on this one screen: the app in the main panel's shape and the companion screen " +
                    "in the second panel's, side by side or stacked, scaled together. The companion half is the same screen a real second display shows " +
                    "(touch only, the controller drives the main half).",
                companion?.let { "Main: ${main?.label ?: "real screen"}\nCompanion: ${it.label}, ${it.sizeLabel} (pixels, dpi)." },
            ),
            DebugToolRow(
                "sim_companion", Symbols.DISPLAY_TOP, "Companion display", RowValue.Text(companion?.shortSize ?: "Off") {
                    openMenu(shell, tracker, "Companion display", screenItems(context, companion?.label, companion = true))
                },
                "Only the second panel: any shape from the list as the companion next to the main panel chosen above.",
            ),
            DebugToolRow(
                "sim_companion_custom", Symbols.ASPECT_RATIO, "Companion custom size",
                RowValue.Text((companion ?: ShapeSimulation.customCompanion).shortSize) { customDialog(shell, tracker, context, companion = true) },
                "Any second panel: width and height in pixels and the density in dpi.",
            ),
            DebugToolRow(
                "sim_swap", Symbols.SCREEN_ROTATION, "Swap panels", RowValue.Action {
                    if (!ShapeSimulation.swap(context)) shell.showToast("Simulate a dual-screen device first", Symbols.INFO)
                },
                "Makes the companion panel the main one and the main panel the companion (Settings > Display > Main screen \"Swapped\").",
            ),
        )
    }

    private fun openMenu(shell: ShellState, tracker: FocusTracker, title: String, items: List<MenuItem>) =
        shell.openLayer(Layer.Context(ContextMenu(title, items), tracker.bounds()), tracker)

    /** "Off" (the companion goes, the main panel stays) and every dual-screen device of [ShapeCatalog.duals], the current one ticked. */
    private fun dualItems(context: Context, main: String?, companion: String?): List<MenuItem> =
        listOf(MenuItem(if (companion == null) Symbols.CHECK_CIRCLE else Symbols.MONITOR, "Off") { ShapeSimulation.setCompanion(context, null) }) +
            ShapeCatalog.duals.map { d ->
                val current = main == d.main.title && companion == d.companion.title
                MenuItem(if (current) Symbols.CHECK_CIRCLE else Symbols.DISPLAY_TOP, "${d.title} · ${d.main.widthPx}x${d.main.heightPx}+${d.companion.widthPx}x${d.companion.heightPx}") {
                    ShapeSimulation.setPair(context, d)
                }
            }

    /** "Real screen" (or "Off") and then every shape of [ShapeCatalog], the current one ticked. */
    private fun screenItems(context: Context, current: String?, companion: Boolean): List<MenuItem> {
        fun item(label: String, selected: Boolean, apply: () -> Unit) =
            MenuItem(if (selected) Symbols.CHECK_CIRCLE else Symbols.MONITOR, label) { apply() }
        val none = item(if (companion) "Off" else "Real screen", current == null) {
            if (companion) ShapeSimulation.setCompanion(context, null) else ShapeSimulation.setMain(context, null, SimCutout.NONE)
        }
        return listOf(none) + ShapeCatalog.all.map { shape ->
            item(shape.menuLabel, current == shape.title) {
                if (companion) ShapeSimulation.setCompanion(context, SimPanel.of(shape))
                else ShapeSimulation.setMain(context, SimPanel.of(shape), ShapeSimulation.cutoutOf(shape))
            }
        }
    }

    private fun customDialog(shell: ShellState, tracker: FocusTracker, context: Context, companion: Boolean = false) {
        val start = if (companion) ShapeSimulation.companion ?: ShapeSimulation.customCompanion else ShapeSimulation.main ?: ShapeSimulation.custom
        shell.openLayer(
            Layer.Dialog(
                DialogSpec(
                    if (companion) "Companion custom size" else "Custom size", "Width and height in pixels, then the density in dpi.",
                    listOf(
                        DialogField(Symbols.WIDTH, start.widthPx.toString(), "Width in pixels", KeyboardType.Number, maxLength = 5),
                        DialogField(Symbols.HEIGHT, start.heightPx.toString(), "Height in pixels", KeyboardType.Number, maxLength = 5),
                        DialogField(Symbols.TUNE, start.dpi.toString(), "Density in dpi", KeyboardType.Number, maxLength = 4),
                    ),
                    "Simulate",
                ) { values ->
                    val w = values[0].trim().toIntOrNull()
                    val h = values[1].trim().toIntOrNull()
                    val dpi = values[2].trim().toIntOrNull()
                    if (w == null || h == null || dpi == null || w !in 200..8000 || h !in 200..8000 || dpi !in 60..800) {
                        shell.showToast("Width and height 200 to 8000 pixels, density 60 to 800 dpi", Symbols.ERROR)
                        false
                    } else {
                        if (companion) ShapeSimulation.setCompanion(context, SimPanel("Custom", w, h, dpi))
                        else ShapeSimulation.setMain(context, SimPanel("Custom", w, h, dpi), ShapeSimulation.cutout)
                        true
                    }
                },
            ),
            tracker,
        )
    }
}
