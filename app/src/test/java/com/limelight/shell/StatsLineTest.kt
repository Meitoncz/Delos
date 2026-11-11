package com.limelight.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/** The compact statistics line on a narrow stage (`keepFigures`): it may wrap only after a separator, never inside a figure. */
class StatsLineTest {
    private val nb = '\u00A0'

    @Test
    fun onlyTheSpacesAfterASeparatorCanBreak() {
        assertEquals("60${nb}FPS", keepFigures("60 FPS"))
        assertEquals("$nb$nb·  1%${nb}low${nb}57", keepFigures("  ·  1% low 57"))
        assertEquals(
            "Network${nb}11${nb}ms$nb· Decode${nb}4.2${nb}ms$nb· Lost${nb}0.1$nb%",
            keepFigures("Network 11 ms · Decode 4.2 ms · Lost 0.1 %"),
        )
        assertEquals("", keepFigures(""))
    }

    @Test
    fun theTextKeepsItsLengthAndItsCharacters() {
        val line = "60 FPS  ·  1% low 57  ·  9.6 MB/s · 80.3 Mb/s  ·  Network 11 ms · Decode 4.2 ms · Total 22.0 ms · Lost 0.1 %"
        val kept = keepFigures(line)
        assertEquals(line.length, kept.length)
        assertEquals(line, kept.replace(nb, ' '))
        // Seven separators: seven places to break
        assertEquals(7, kept.split(' ').filter { it.isNotEmpty() }.size - 1)
    }
}
