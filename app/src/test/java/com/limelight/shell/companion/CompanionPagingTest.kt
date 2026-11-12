package com.limelight.shell.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lists and the keyboard of the stream state never scroll: the grid's pages, its columns, the key rows of each width and the bounded echo. */
class CompanionPagingTest {
    // ---- Pages of a grid ----

    @Test fun everythingThatFitsIsOnePageWithoutAPager() {
        // 4 rows of 64 with 8 between need 280; 8 entries in 2 columns are 4 rows
        val plan = GridPaging.plan(height = 280f, rowHeight = 64f, gap = 8f, columns = 2, count = 8)
        assertEquals(GridPlan(rows = 4, perPage = 8, pages = 1, pager = false), plan)
    }

    @Test fun aLongListTakesTheLastRowForThePagerAndSplitsTheRestIntoPages() {
        // 30 keys in 2 columns need 15 rows; 280 holds 4, so 3 rows stay for the entries and the fourth is the pager
        val plan = GridPaging.plan(height = 280f, rowHeight = 64f, gap = 8f, columns = 2, count = 30)
        assertEquals(GridPlan(rows = 3, perPage = 6, pages = 5, pager = true), plan)
        // The pages together cover every entry exactly once
        assertEquals(30, (0 until plan.pages).sumOf { page -> (30 - page * plan.perPage).coerceAtMost(plan.perPage) })
    }

    @Test fun aRoomTooSmallForAnythingStillGivesOneRowAPage() {
        val plan = GridPaging.plan(height = 70f, rowHeight = 64f, gap = 8f, columns = 3, count = 10)
        assertEquals(1, plan.rows)
        assertEquals(3, plan.perPage)
        assertEquals(4, plan.pages)
        assertTrue(plan.pager)
    }

    @Test fun theLastRowIsShortWhenTheEntriesDoNotFillIt() {
        val plan = GridPaging.plan(height = 400f, rowHeight = 64f, gap = 8f, columns = 2, count = 5)
        assertEquals(GridPlan(rows = 3, perPage = 5, pages = 1, pager = false), plan)
        assertEquals(GridPlan(1, 1, 1, false), GridPaging.plan(400f, 64f, 8f, 2, 0))
    }

    @Test fun everyPanelHoldsTwoRowsOfAPageAndThePager() {
        // The sheets give a list's grid at least GRID_MIN; with the pager that is still two rows of entries... never fewer than a row
        for ((name, p) in panels()) {
            val card = CompanionFit.cardHeight(p) * DEFAULT_SCALE - 2 * CompanionFit.cardPadding(p)
            val room = card - 64f - CompanionFit.gap(p)
            val plan = GridPaging.plan(room, 64f, 8f, 2, 30)
            assertTrue("$name: $plan", plan.rows >= 1 && plan.perPage >= 2)
        }
    }

    // ---- Columns ----

    @Test fun theWidestLabelDecidesHowManyColumnsFit() {
        // chrome 48: a label of 200 needs 248 per column
        assertEquals(3, GridPaging.columns(listOf(200f, 120f), width = 800f, gap = 8f, chrome = 48f, max = 3))
        assertEquals(2, GridPaging.columns(listOf(300f), width = 800f, gap = 8f, chrome = 48f, max = 3))
        assertEquals(1, GridPaging.columns(listOf(760f), width = 800f, gap = 8f, chrome = 48f, max = 3))
        assertEquals(2, GridPaging.columns(listOf(100f), width = 800f, gap = 8f, chrome = 48f, max = 2))
        assertEquals(3, GridPaging.columns(emptyList(), width = 800f, gap = 8f, chrome = 48f, max = 3))
    }

    // ---- Keyboard rows ----

    private val stageWidths = listOf(1099f, 1488f, 1191f, 1051f, 1082f, 1116f)

    @Test fun everyPanelOfTheFiveHasTenColumnsAndEveryKeyIsAtLeastATarget() {
        for (width in stageWidths) {
            val inner = width - 2 * CompanionFit.MARGIN
            assertEquals("$width", 10, KeyRows.columnsFor(inner))
            for (symbols in listOf(false, true)) assertKeysAtLeastATarget(inner, KeyRows.rows(symbols, 10), "$width symbols $symbols")
        }
    }

    @Test fun aNarrowPanelGetsRowsOfEightWithKeysStillAtLeastATarget() {
        // From the width eight 64 px keys and their gaps need (568) up to where ten columns take over
        for (inner in listOf(568f, 640f, 700f, 780f)) {
            val columns = KeyRows.columnsFor(inner)
            assertEquals("$inner", 8, columns)
            for (symbols in listOf(false, true)) assertKeysAtLeastATarget(inner, KeyRows.rows(symbols, columns), "$inner symbols $symbols")
        }
    }

    @Test fun theNarrowRowsHaveEveryCharacterAndEveryActionOnce() {
        for (symbols in listOf(false, true)) {
            val rows = KeyRows.rows(symbols, 8)
            val cells = rows.flatten()
            val kinds = cells.filterIsInstance<Cell.Action>().map { it.kind }
            assertEquals(setOf(Kind.SHIFT, Kind.SYMBOLS, Kind.SPACE, Kind.BACKSPACE, Kind.ENTER, Kind.LEFT, Kind.RIGHT), kinds.toSet())
            assertEquals(kinds.size, kinds.toSet().size)
            val chars = cells.filterIsInstance<Cell.Char>().map { it.c }
            assertEquals(chars.size, chars.toSet().size)
            if (!symbols) assertTrue(chars.containsAll("1234567890qwertyuiopasdfghjklzxcvbnm,.".toList()))
            // Every row is at most eight units wide
            rows.forEach { row -> assertTrue("$row", row.sumOf { it.weight.toDouble() } <= 8.0 + 1e-6) }
        }
    }

    @Test fun theLettersPageKeepsTheFiveFamiliarRows() {
        val rows = KeyRows.rows(false, 10)
        assertEquals(5, rows.size)
        assertEquals("1234567890", rows[0].filterIsInstance<Cell.Char>().joinToString("") { it.c.toString() })
        assertEquals("qwertyuiop", rows[1].filterIsInstance<Cell.Char>().joinToString("") { it.c.toString() })
    }

    private fun assertKeysAtLeastATarget(width: Float, rows: List<List<Cell>>, label: String) {
        for (row in rows) {
            val units = row.sumOf { it.weight.toDouble() }.toFloat()
            val unit = (width - (row.size - 1) * KeyRows.GAP) / units
            for (cell in row) if (cell !is Cell.Gap) assertTrue("$label: ${cell.weight} x $unit", cell.weight * unit >= MIN_TARGET)
        }
    }

    // ---- The echo ----

    private val count: (Int) -> String = { "$it characters entered" }

    @Test fun anEmptyEchoShowsTheHintOrItsShortFormOrNothing() {
        val hint = "Tap the keys: they go to the PC."
        val short = "Tap the keys."
        assertEquals(EchoView(hint, true), EchoFit.view("", hint, short, 600f, FakeMetrics, count))
        assertEquals(EchoView(short, true), EchoFit.view("", hint, short, 300f, FakeMetrics, count))
        assertEquals(EchoView("", true), EchoFit.view("", hint, short, 100f, FakeMetrics, count))
    }

    @Test fun whatWasTypedShowsWhenItFitsOneLineElseItsCount() {
        assertEquals(EchoView("hello", false), EchoFit.view("hello", "h", "h", 400f, FakeMetrics, count))
        val long = "x".repeat(40)
        val view = EchoFit.view(long, "h", "h", 400f, FakeMetrics, count)
        assertEquals(EchoView("40 characters entered", false), view)
        assertFalse(view.placeholder)
        // The echo never holds more than the recorder keeps
        assertTrue(TypedEcho().typed('a').value.length <= TypedEcho.MAX)
    }
}
