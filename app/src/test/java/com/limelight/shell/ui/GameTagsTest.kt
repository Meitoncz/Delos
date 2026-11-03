package com.limelight.shell.ui

import com.limelight.shell.data.GameGenre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameTagsTest {
    private fun g(id: Int) = GameGenre(id, "genre $id")

    private val shooter = 5
    private val puzzle = 9
    private val racing = 10
    private val simulator = 13
    private val sport = 14
    private val strategy = 15
    private val arcade = 33
    private val adventure = 31
    private val indie = 32

    @Test
    fun mostSpecificGenreWins() {
        assertEquals(g(shooter), mainGenre(listOf(g(shooter), g(puzzle), g(adventure))))
        assertEquals(g(racing), mainGenre(listOf(g(racing), g(simulator), g(sport), g(arcade))))
        assertEquals(g(puzzle), mainGenre(listOf(g(puzzle), g(adventure))))
        assertEquals(g(sport), mainGenre(listOf(g(simulator), g(sport), g(adventure), g(indie))))
        assertEquals(g(simulator), mainGenre(listOf(g(strategy), g(simulator), g(indie))))
    }

    @Test
    fun theOrderOfTheListDoesNotMatterForKnownGenres() {
        val genres = listOf(g(adventure), g(arcade), g(sport), g(racing))
        assertEquals(mainGenre(genres), mainGenre(genres.reversed()))
    }

    @Test
    fun indieIsNoGenreUnlessItIsAllThereIs() {
        assertEquals(g(indie), mainGenre(listOf(g(indie))))
        assertEquals(g(adventure), mainGenre(listOf(g(indie), g(adventure))))
        // A genre IGDB added later still beats Indie
        assertEquals(g(999), mainGenre(listOf(g(indie), g(999))))
    }

    @Test
    fun newGenresComeAfterTheKnownOnesInIgdbOrder() {
        assertEquals(g(adventure), mainGenre(listOf(g(998), g(adventure), g(999))))
        assertEquals(g(998), mainGenre(listOf(g(998), g(999))))
    }

    @Test
    fun noGenresMeansNoMainGenre() {
        assertNull(mainGenre(emptyList()))
    }

    @Test
    fun mostTellingThemeWins() {
        assertEquals(g(19), mainTheme(listOf(g(17), g(19), g(1))))
        assertEquals(g(38), mainTheme(listOf(g(1), g(38))))
        assertEquals(g(21), mainTheme(listOf(g(31), g(21), g(38))))
    }

    @Test
    fun actionAndEroticAreNeverShown() {
        assertNull(mainTheme(listOf(g(1))))
        assertNull(mainTheme(listOf(g(42), g(1))))
        assertEquals(g(32), mainTheme(listOf(g(1), g(42), g(32))))
    }

    @Test
    fun newThemesComeAfterTheKnownOnesInIgdbOrder() {
        assertEquals(g(32), mainTheme(listOf(g(998), g(32), g(999))))
        assertEquals(g(998), mainTheme(listOf(g(1), g(998), g(999))))
    }

    @Test
    fun noThemesMeansNoMainTheme() {
        assertNull(mainTheme(emptyList()))
    }

    @Test
    fun everyShownThemeHasItsOwnIcon() {
        val themeIds = listOf(17, 18, 19, 20, 21, 22, 23, 27, 28, 31, 32, 33, 34, 35, 38, 39, 40, 41, 43, 44)
        val icons = themeIds.map { themeIcon(it) }
        icons.forEach { assertNotEquals(Symbols.GENRE_UNKNOWN, it) }
        assertEquals(themeIds.size, icons.toSet().size)
    }

    @Test
    fun unknownThemesFallBackToTheNeutralIcon() {
        assertEquals(Symbols.GENRE_UNKNOWN, themeIcon(0))
        assertEquals(Symbols.GENRE_UNKNOWN, themeIcon(999))
        assertEquals(Symbols.GENRE_UNKNOWN, themeIcon(-1))
    }

    @Test
    fun everyThemeIconDiffersFromEveryGenreIcon() {
        val genreIcons = listOf(2, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 24, 25, 26, 30, 31, 32, 33, 34, 35, 36).map { genreIcon(it) }.toSet()
        listOf(17, 18, 19, 20, 21, 22, 23, 27, 28, 31, 32, 33, 34, 35, 38, 39, 40, 41, 43, 44).forEach {
            assertFalse(themeIcon(it) in genreIcons)
        }
    }
}
