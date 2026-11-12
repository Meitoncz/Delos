package com.limelight.shell.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.settings.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The eight palettes: Delos dark is the old Delos but for the pink third color, the table drives every palette, contrast holds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class PaletteColorsTest {
    private class Row(val palette: Palette, val base: Float, val sat: Float, val main: Float, val second: Float, val third: Float, val soft: Boolean)

    private val table = listOf(
        Row(Palette.DELOS, 222f, 38f, 40f, 190f, 335f, false),
        Row(Palette.EMBER, 195f, 28f, 12f, 40f, 330f, false),
        Row(Palette.ROSE, 285f, 30f, 345f, 15f, 205f, false),
        Row(Palette.ORCHID, 258f, 36f, 305f, 270f, 48f, false),
        Row(Palette.DUSK, 232f, 12f, 268f, 205f, 330f, true),
        Row(Palette.IRIS, 25f, 22f, 232f, 190f, 40f, false),
        Row(Palette.GRAPHITE, 220f, 7f, 205f, 175f, 45f, true),
        Row(Palette.MINT, 190f, 22f, 150f, 190f, 330f, true),
    )

    private fun colors(p: Palette, dark: Boolean) = delosColors(Appearance(dark = dark, palette = p), null)

    @Test fun tableIsTheEnumInOrder() {
        assertEquals(table.map { it.palette }, Palette.entries)
        table.forEach {
            assertEquals(it.base, it.palette.base, 0f); assertEquals(it.sat, it.palette.baseSat, 0f)
            assertEquals(it.main, it.palette.accent, 0f); assertEquals(it.second, it.palette.accent2, 0f)
            assertEquals(it.third, it.palette.accent3, 0f); assertEquals(it.soft, it.palette.soft)
        }
    }

    @Test fun delosDarkIsTheOldDelosWithAPinkThirdColor() {
        val c = colors(Palette.DELOS, dark = true)
        // The formulas before the palette set changed (base 222, saturation 38, hues 40 / 190)
        assertEquals(hsl(222f, 38f, 7f), c.bg)
        assertEquals(hsl(222f, 34f, 11f), c.bg2)
        assertEquals(hsl(222f, 28f, 14f, .72f), c.surface)
        assertEquals(hsl(222f, 26f, 19f, .84f), c.surface2)
        assertEquals(hsl(222f, 24f, 23f, .94f), c.surface3)
        assertEquals(hsl(222f, 35f, 70f, .14f), c.line)
        assertEquals(hsl(222f, 30f, 96f), c.text)
        assertEquals(hsl(222f, 16f, 76f), c.text2)
        assertEquals(hsl(222f, 12f, 58f), c.text3)
        assertEquals(hsl(40f, 95f, 62f), c.accent)
        assertEquals(hsl(190f, 85f, 62f), c.accent2)
        assertEquals(hsl(40f, 55f, 12f), c.onAccent)
        assertEquals(hsl(40f, 95f, 58f, .45f), c.glow)
        assertEquals(hsl(222f, 50f, 3f, .62f), c.scrim)
        assertEquals(hsl(335f, 85f, 68f), c.accent3)
    }

    @Test fun everyPaletteIsProducedFromTheTable() {
        table.forEach { r ->
            val d = colors(r.palette, dark = true)
            val s = r.sat
            assertEquals(hsl(r.base, s, 7f), d.bg)
            assertEquals(hsl(r.base, s - 4f, 11f), d.bg2)
            assertEquals(hsl(r.base, maxOf(2f, s - 10f), 14f, .72f), d.surface)
            assertEquals(hsl(r.base, maxOf(2f, s - 12f), 19f, .84f), d.surface2)
            assertEquals(hsl(r.base, maxOf(2f, s - 14f), 23f, .94f), d.surface3)
            assertEquals(hsl(r.base, minOf(30f, s), 96f), d.text)
            assertEquals(hsl(r.base, minOf(16f, s), 76f), d.text2)
            assertEquals(hsl(r.base, minOf(12f, s), 58f), d.text3)
            if (r.soft) {
                assertEquals(hsl(r.main, 70f, 70f), d.accent)
                assertEquals(hsl(r.second, 62f, 68f), d.accent2)
                assertEquals(hsl(r.third, 60f, 72f), d.accent3)
                assertEquals(hsl(r.main, 70f, 58f, .45f), d.glow)
            } else {
                assertEquals(hsl(r.main, 95f, 62f), d.accent)
                assertEquals(hsl(r.second, 85f, 62f), d.accent2)
                assertEquals(hsl(r.third, 85f, 68f), d.accent3)
                assertEquals(hsl(r.main, 95f, 58f, .45f), d.glow)
            }
            val l = colors(r.palette, dark = false)
            assertEquals(hsl(r.base, s + 2f, 95f), l.bg)
            assertEquals(hsl(r.base, s - 4f, 90f), l.bg2)
            if (r.soft) {
                assertEquals(hsl(r.main - 6f, 65f, 46f), l.accent)
                assertEquals(hsl(r.second, 55f, 40f), l.accent2)
                assertEquals(hsl(r.third, 50f, 44f), l.accent3)
            } else {
                assertEquals(hsl(r.main - 6f, 92f, 52f), l.accent)
                assertEquals(hsl(r.second, 80f, 40f), l.accent2)
                assertEquals(hsl(r.third, 75f, 44f), l.accent3)
            }
        }
    }

    @Test fun aRemovedStoredNameFallsBackToDelos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        for ((stored, expected) in listOf("LAGOON" to Palette.DELOS, "MINT" to Palette.MINT)) {
            prefs.edit().clear().putString("ui_palette", stored).commit()
            assertEquals(stored, expected, AppearanceStore(context) { prefs }.appearance.value.palette)
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance(); val lb = b.luminance()
        return (maxOf(la, lb) + .05f) / (minOf(la, lb) + .05f)
    }

    @Test fun textReadsOnBackgroundAndSurfacesInBothThemes() {
        for (p in Palette.entries) for (dark in listOf(true, false)) {
            val c = colors(p, dark)
            val bg = c.bg
            val surfaces = listOf(c.surface.compositeOver(bg), c.surface2.compositeOver(bg), c.surface3.compositeOver(bg))
            for ((name, text) in listOf("text" to c.text, "text2" to c.text2)) {
                assertTrue("$p dark=$dark $name on bg", contrast(text.compositeOver(bg), bg) >= 4.5f)
                surfaces.forEachIndexed { i, s ->
                    assertTrue("$p dark=$dark $name on surface${i + 1}", contrast(text.compositeOver(s), s) >= 4.5f)
                }
            }
        }
    }
}
