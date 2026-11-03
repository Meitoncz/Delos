package com.limelight.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GenreIconsTest {
    private val knownIds = listOf(2, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 24, 25, 26, 30, 31, 32, 33, 34, 35, 36)

    @Test
    fun everyKnownGenreHasItsOwnIcon() {
        val icons = knownIds.map { genreIcon(it) }
        icons.forEach { assertNotEquals(Symbols.GENRE_UNKNOWN, it) }
        assertEquals(knownIds.size, icons.toSet().size)
    }

    @Test
    fun unknownGenresFallBackToTheNeutralIcon() {
        assertEquals(Symbols.GENRE_UNKNOWN, genreIcon(0))
        assertEquals(Symbols.GENRE_UNKNOWN, genreIcon(999))
        assertEquals(Symbols.GENRE_UNKNOWN, genreIcon(-1))
    }
}
