package com.limelight.shell

import androidx.compose.ui.unit.IntRect

/** One screen shape the debug build can simulate: the simulated panel's pixels, the density Android would report and optional cutout insets in pixels. */
internal data class SimShape(
    /** The short name of the contact-sheet lists (`tools/layout/shapes.txt`) and of the shape in logs. */
    val id: String,
    /** What the debug menu shows. */
    val title: String,
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Int,
    val cutout: IntRect = IntRect.Zero,
    /** True for the shapes of the layout contact sheets: exactly these are listed in `tools/layout/shapes.txt` (ShapeCatalogTest keeps the two in step). */
    val sheet: Boolean = false,
) {
    /** The menu line: the name, the pixels and the density. */
    val menuLabel: String get() = "$title · ${widthPx}x$heightPx @$dpi"

    /** The line of `tools/layout/shapes.txt`, without the comment: `<id> <W>x<H>@<dpi> [<L>,<T>,<R>,<B>]`. */
    fun sheetLine(): String = buildString {
        append("$id $widthPx" + "x$heightPx@$dpi")
        if (cutout != IntRect.Zero) append(" ${cutout.left},${cutout.top},${cutout.right},${cutout.bottom}")
    }
}

/**
 * The one list of screen shapes (debug builds only): the shape simulator ([ShapeSimulator]), its debug menu (Settings → About → Debug info,
 * [ShapeSimulatorMenu]) and, through `tools/layout/shapes.txt`, the layout contact sheets and the crash check. The file is a mirror of the
 * [sheet] shapes that scripts can read without Gradle; `ShapeCatalogTest` fails when the two differ. dpi is the reference's reported 369 where the
 * shape belongs to no device, otherwise the ppi of the device (docs/HANDHELD_DEVICES.md) rounded to 10.
 */
internal object ShapeCatalog {
    private fun sheet(id: String, title: String, w: Int, h: Int, dpi: Int, cutout: IntRect = IntRect.Zero) = SimShape(id, title, w, h, dpi, cutout, sheet = true)
    private fun device(id: String, title: String, w: Int, h: Int, dpi: Int) = SimShape(id, title, w, h, dpi)

    val all: List<SimShape> = listOf(
        // The contact-sheet shapes, in the order of tools/layout/shapes.txt
        sheet("odin", "AYN Odin 2 (reference)", 1920, 1080, 369),
        sheet("thor-top", "AYN Thor, top", 1920, 1080, 370),
        sheet("thor-bottom", "AYN Thor, bottom", 1240, 1080, 420),
        sheet("dds-top", "RG DDS, top", 1200, 720, 300),
        sheet("dds-bottom", "RG DDS, bottom", 960, 720, 300),
        sheet("retroid-mini", "Retroid Pocket Mini", 1280, 960, 430),
        sheet("micro", "AYANEO Pocket Micro", 960, 640, 330),
        sheet("16x10", "16:10 handheld", 1920, 1200, 369),
        sheet("3x2", "3:2 panel", 1620, 1080, 369),
        sheet("4x3", "4:3 panel", 1440, 1080, 369),
        sheet("1x1", "Square (RG Cube)", 720, 720, 255),
        sheet("20x9", "20:9 phone in a grip", 2400, 1080, 369),
        sheet("21x9", "21:9 phone in a grip", 2520, 1080, 369),
        sheet("20x9-cutout", "20:9 phone, left cutout", 2400, 1080, 369, IntRect(96, 0, 0, 0)),
        sheet("split-half", "Odin half (split-screen)", 960, 1080, 369),
        // More devices of docs/HANDHELD_DEVICES.md (not in the sheets: they would only repeat a shape above)
        device("odin2-mini", "AYN Odin 2 Mini", 1920, 1080, 440),
        device("odin2-portal-7in", "7 in 1080p (Portal, EVO, G Cloud)", 1920, 1080, 310),
        device("rp4-pro", "Retroid Pocket 4 Pro", 1334, 750, 330),
        device("rp5", "Retroid Pocket 5 / 6 / Flip 2", 1920, 1080, 400),
        device("retroid-mini-v2", "Retroid Mini V2 / Pocket DMG", 1240, 1080, 420),
        device("pocket-s-1440", "AYANEO Pocket S, 1440p", 2560, 1440, 490),
        device("pocket-ds-bottom", "AYANEO Pocket DS, bottom", 1024, 768, 260),
        device("pocket-ace", "AYANEO Pocket ACE", 1620, 1080, 430),
        device("rg476h", "Anbernic RG 476H", 1280, 960, 340),
        device("razer-edge", "Razer Edge", 2400, 1080, 390),
        device("xperia-1", "Xperia 1 VII in a grip", 2340, 1080, 400),
        device("galaxy-s25-ultra", "Galaxy S25 Ultra in a grip", 3120, 1440, 500),
    )

    /** A dual-screen device: the main panel (the one with more pixels, as `Settings > Display > Main screen` "Automatic" picks) and its companion. */
    data class DualPreset(val title: String, val main: SimShape, val companion: SimShape)

    private fun byId(id: String) = all.first { it.id == id }

    /** Dual-screen devices of docs/HANDHELD_DEVICES.md, "Dual-screen devices". */
    val duals: List<DualPreset> = listOf(
        DualPreset("AYN Thor", byId("thor-top"), byId("thor-bottom")),
        DualPreset("Anbernic RG DDS", byId("dds-top"), byId("dds-bottom")),
        DualPreset("AYANEO Pocket DS", byId("odin2-portal-7in"), byId("pocket-ds-bottom")),
        DualPreset("Retroid 5 / 6 + add-on", byId("rp5"), byId("rp5")),
        DualPreset("Retroid Mini + add-on", byId("rp5"), byId("retroid-mini")),
        DualPreset("Retroid 4 Pro + add-on", byId("rp5"), byId("rp4-pro")),
    )

    /** The shapes of `tools/layout/shapes.txt`. */
    val sheets: List<SimShape> = all.filter { it.sheet }

    private val LINE = Regex("""(\S+)\s+(\d{2,5})x(\d{2,5})@(\d{2,4})(?:\s+(\d+),(\d+),(\d+),(\d+))?""")

    /** Reads a line of `tools/layout/shapes.txt` (comment already removed) as a shape; null for a blank or malformed line. */
    fun parseSheetLine(line: String): SimShape? {
        val m = LINE.matchEntire(line.trim()) ?: return null
        val (id, w, h, dpi) = m.destructured
        val cut = m.groupValues.drop(5)
        val cutout = if (cut.all { it.isNotEmpty() }) IntRect(cut[0].toInt(), cut[1].toInt(), cut[2].toInt(), cut[3].toInt()) else IntRect.Zero
        return SimShape(id, id, w.toInt(), h.toInt(), dpi.toInt(), cutout, sheet = true)
    }
}
