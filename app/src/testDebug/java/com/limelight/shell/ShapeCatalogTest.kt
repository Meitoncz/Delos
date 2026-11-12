package com.limelight.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** tools/layout/shapes.txt is a mirror of the contact-sheet shapes of [ShapeCatalog] for scripts: the two must list the same shapes in the same order. */
class ShapeCatalogTest {
    private fun shapesFile(): File = listOf("../tools/layout/shapes.txt", "tools/layout/shapes.txt").map(::File).first { it.isFile }

    @Test
    fun shapesTxtMirrorsTheCatalogSheets() {
        val fromFile = shapesFile().readLines().map { it.substringBefore('#') }.filter { it.isNotBlank() }.map {
            checkNotNull(ShapeCatalog.parseSheetLine(it)) { "Malformed line in shapes.txt: $it" }.sheetLine()
        }
        assertEquals("tools/layout/shapes.txt differs from the sheet shapes of ShapeCatalog", ShapeCatalog.sheets.map { it.sheetLine() }, fromFile)
    }

    @Test
    fun idsAndTitlesAreUnique() {
        assertEquals(ShapeCatalog.all.size, ShapeCatalog.all.map { it.id }.toSet().size)
        assertEquals(ShapeCatalog.all.size, ShapeCatalog.all.map { it.title }.toSet().size)
        assertTrue(ShapeCatalog.all.all { it.widthPx > 0 && it.heightPx > 0 && it.dpi >= 60 })
    }
}
