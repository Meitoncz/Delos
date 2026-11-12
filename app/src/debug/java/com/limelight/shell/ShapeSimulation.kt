package com.limelight.shell

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntRect

/** A panel the simulator draws: pixels and the density Android would report. [label] is what the menu and the chip say. */
internal data class SimPanel(val label: String, val widthPx: Int, val heightPx: Int, val dpi: Int) {
    val sizeLabel: String get() = "$widthPx x $heightPx @ $dpi"

    /** The compact form for a row value. */
    val shortSize: String get() = "${widthPx}x$heightPx @$dpi"

    companion object {
        fun of(shape: SimShape) = SimPanel(shape.title, shape.widthPx, shape.heightPx, shape.dpi)
    }
}

/** A display cutout the simulator draws and feeds to the stage: [inset] in pixels of the simulated panel, a notch [widthFraction] of the edge wide. */
internal data class SimCutout(val label: String, val inset: IntRect, val widthFraction: Float = 0.3f) {
    companion object {
        val NONE = SimCutout("None", IntRect.Zero)
        val LEFT = SimCutout("Left", IntRect(96, 0, 0, 0))
        val TOP = SimCutout("Top", IntRect(0, 96, 0, 0))
        val RIGHT = SimCutout("Right", IntRect(0, 0, 96, 0))
        val NOTCH = SimCutout("Notch", IntRect(0, 56, 0, 0), widthFraction = 0.1f)
        val PRESETS = listOf(NONE, LEFT, TOP, RIGHT, NOTCH)
    }
}

/**
 * What the shape simulator shows right now (debug builds only), as Compose state so that the whole shell is redrawn live. Set from the debug
 * menu ([ShapeSimulatorMenu]; remembered across restarts in a preference file of its own, never in a profile) and, for tools, from the adb extras
 * `preview_shape` and `preview_cutout` ([applyIntent]; not remembered). A null [main] is the real screen (a [cutout] or a [companion] can still be on top of it).
 */
internal object ShapeSimulation {
    private const val FILE = "delos_debug_shape_simulator"

    /** The real preference file: a screen preview hands the menu a sandboxed [PreviewContext] whose preferences are throw-away copies. */
    private fun prefs(context: Context): SharedPreferences {
        var c = context
        while (c is PreviewContext) c = c.baseContext
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** The simulated main panel; null for the real screen. */
    var main by mutableStateOf<SimPanel?>(null)
        private set

    var cutout by mutableStateOf(SimCutout.NONE)
        private set

    /** The simulated second display of a dual-screen device, drawn above the main panel; null for none. */
    var companion by mutableStateOf<SimPanel?>(null)
        private set

    /** The menu changed something this process: the adb extras of a later launch of the same process no longer override it. */
    private var touched = false

    /** The last custom size, for the dialog to start from. */
    var custom by mutableStateOf(SimPanel("Custom", 1280, 720, 320))
        private set

    val active: Boolean get() = main != null || cutout != SimCutout.NONE || companion != null

    fun setMain(context: Context, panel: SimPanel?, cut: SimCutout? = null) {
        main = panel
        if (cut != null) cutout = cut
        if (panel != null && panel.label == "Custom") custom = panel
        save(context)
    }

    fun setCutout(context: Context, next: SimCutout) {
        cutout = next
        save(context)
    }

    /** A dual-screen device: both panels at once. */
    fun setPair(context: Context, preset: ShapeCatalog.DualPreset) {
        main = SimPanel.of(preset.main)
        companion = SimPanel.of(preset.companion)
        cutout = cutoutOf(preset.main)
        save(context)
    }

    /** Swaps the two panels (the main one is the second panel's); false while there is no pair to swap. */
    fun swap(context: Context): Boolean {
        val a = main ?: return false
        val b = companion ?: return false
        main = b
        companion = a
        cutout = SimCutout.NONE
        save(context)
        return true
    }

    /** The last custom companion size, for its dialog to start from. */
    var customCompanion by mutableStateOf(SimPanel("Custom", 1240, 1080, 420))
        private set

    fun setCompanion(context: Context, panel: SimPanel?) {
        companion = panel
        if (panel != null && panel.label == "Custom") customCompanion = panel
        save(context)
    }

    /** The cutout a catalog shape brings: a preset when the insets are one, else its own. */
    fun cutoutOf(shape: SimShape): SimCutout =
        SimCutout.PRESETS.firstOrNull { it.inset == shape.cutout } ?: SimCutout("From ${shape.title}", shape.cutout)

    /** Reads the remembered simulation; called when the activity is created. */
    fun load(context: Context) {
        val p = prefs(context)
        main = p.getString("main", null)?.let(::readPanel)
        companion = p.getString("companion", null)?.let(::readPanel)
        cutout = p.getString("cutout", null)?.let(::readCutout) ?: SimCutout.NONE
        custom = p.getString("custom", null)?.let(::readPanel) ?: custom
        touched = false
    }

    /** `--es preview_shape <W>x<H>@<dpi> [--es preview_cutout L,T,R,B]`: simulates that panel (alone) for this launch. Returns whether it did. */
    fun applyIntent(intent: Intent): Boolean {
        if (touched) return false
        val m = SHAPE.matchEntire(intent.getStringExtra("preview_shape")?.trim() ?: return false) ?: return false
        val (w, h, dpi) = m.destructured
        main = SimPanel("Custom", w.toInt(), h.toInt(), dpi.toInt().coerceAtLeast(60))
        val rect = intent.getStringExtra("preview_cutout")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 4 }?.let { IntRect(it[0], it[1], it[2], it[3]) }
        cutout = if (rect == null) SimCutout.NONE else SimCutout.PRESETS.firstOrNull { it.inset == rect } ?: SimCutout("Cutout", rect)
        companion = null
        return true
    }

    private fun save(context: Context) {
        touched = true
        prefs(context).edit()
            .putString("main", main?.let(::writePanel)).putString("companion", companion?.let(::writePanel))
            .putString("cutout", cutout.takeIf { it != SimCutout.NONE }?.let(::writeCutout)).putString("custom", writePanel(custom)).apply()
    }

    private val SHAPE = Regex("""(\d{2,5})x(\d{2,5})@(\d{2,4})""")

    private fun writePanel(p: SimPanel) = "${p.widthPx}|${p.heightPx}|${p.dpi}|${p.label}"

    private fun readPanel(s: String): SimPanel? {
        val f = s.split('|', limit = 4)
        if (f.size < 4) return null
        return SimPanel(f[3], f[0].toIntOrNull() ?: return null, f[1].toIntOrNull() ?: return null, f[2].toIntOrNull() ?: return null)
    }

    private fun writeCutout(c: SimCutout) = "${c.inset.left}|${c.inset.top}|${c.inset.right}|${c.inset.bottom}|${c.widthFraction}|${c.label}"

    private fun readCutout(s: String): SimCutout? {
        val f = s.split('|', limit = 6)
        if (f.size < 6) return null
        val n = f.take(4).map { it.toIntOrNull() ?: return null }
        return SimCutout(f[5], IntRect(n[0], n[1], n[2], n[3]), f[4].toFloatOrNull() ?: 0.3f)
    }
}
