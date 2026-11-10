package com.limelight.shell.data

import com.limelight.nvstream.http.NvApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataFiltersTest {
    private val game = App(1, "Game", false, false, NvApp())

    private fun metadata(hdr: NativeHdr = NativeHdr.UNKNOWN, seconds: Long? = null, genre: Int = 1, themes: IntArray = intArrayOf()) = GameMetadata(
        candidate = MetadataCandidate(
            10, "Game", 2020, listOf(20L), listOf(GameGenre(genre, "Genre")),
            themes = themes.map { GameGenre(it, "Theme $it") },
        ),
        hdr = HdrEvidence(hdr),
        completion = seconds?.let { CompletionTime(30, "Game", it, "https://howlongtobeat.com/game/30") },
    )

    @Test fun confirmedSdrDoesNotIncludeUnmatchedOrUnknownGames() {
        val filter = MetadataFilters(hdr = HdrFilter.SDR)
        assertTrue(filter.matches(game, metadata(NativeHdr.SDR)))
        assertFalse(filter.matches(game, metadata(NativeHdr.HDR)))
        assertFalse(filter.matches(game, metadata(NativeHdr.UNKNOWN)))
        assertFalse(filter.matches(game, null))
        assertTrue(MetadataFilters(hdr = HdrFilter.UNKNOWN).matches(game, null))
    }

    @Test fun unverifiedHdrMatchesUnknownRecordsAndMissingMetadataButNeverConfirmedSupport() {
        val filter = MetadataFilters(hdr = HdrFilter.UNKNOWN)
        assertTrue(filter.matches(game, metadata(NativeHdr.UNKNOWN)))
        assertTrue(filter.matches(game, null))
        assertFalse(filter.matches(game, metadata(NativeHdr.HDR)))
        assertFalse(filter.matches(game, metadata(NativeHdr.SDR)))
    }

    @Test fun activeCountCountsEachNarrowingCriterionOnce() {
        assertEquals(0, MetadataFilters().activeCount)
        assertFalse(MetadataFilters().active)
        assertEquals(1, MetadataFilters(length = LengthFilter.UNKNOWN).activeCount)
        assertEquals(4, MetadataFilters(genreId = 5, themeId = 19, hdr = HdrFilter.SDR, length = LengthFilter.OVER_60).activeCount)
        assertEquals(1, MetadataFilters(themeId = 19).activeCount)
        assertTrue(MetadataFilters(genreId = 5).active)
        assertTrue(MetadataFilters(themeId = 19).active)
    }

    @Test fun aThemeFilterMatchesAnyOfTheGamesThemesAndNeverGamesWithoutThemes() {
        val filter = MetadataFilters(themeId = 19)
        assertTrue(filter.matches(game, metadata(themes = intArrayOf(1, 19))))
        assertTrue(filter.matches(game, metadata(themes = intArrayOf(19))))
        assertFalse(filter.matches(game, metadata(themes = intArrayOf(1, 21))))
        assertFalse(filter.matches(game, metadata()))
        assertFalse(filter.matches(game, null))
    }

    @Test fun aGenreFilterMatchesAnyOfTheGamesGenresNotOnlyTheMainOne() {
        val twoGenres = GameMetadata(candidate = MetadataCandidate(10, "Game", 2020, genres = listOf(GameGenre(9, "Puzzle"), GameGenre(31, "Adventure"))))
        assertTrue(MetadataFilters(genreId = 9).matches(game, twoGenres))
        assertTrue(MetadataFilters(genreId = 31).matches(game, twoGenres))
        assertFalse(MetadataFilters(genreId = 5).matches(game, twoGenres))
    }

    @Test fun genreAndThemeAreIndependentRequirements() {
        val filter = MetadataFilters(genreId = 12, themeId = 19)
        assertTrue(filter.matches(game, metadata(genre = 12, themes = intArrayOf(19))))
        assertFalse(filter.matches(game, metadata(genre = 12, themes = intArrayOf(21))))
        assertFalse(filter.matches(game, metadata(genre = 5, themes = intArrayOf(19))))
    }

    @Test fun durationRangesHaveUnambiguousBoundariesAndKeepMissingValuesSeparate() {
        val times = listOf(35_999L, 36_000L, 107_999L, 108_000L, 215_999L, 216_000L)
        val expected = mapOf(
            LengthFilter.UNDER_10 to listOf(35_999L),
            LengthFilter.FROM_10_TO_30 to listOf(36_000L, 107_999L),
            LengthFilter.FROM_30_TO_60 to listOf(108_000L, 215_999L),
            LengthFilter.OVER_60 to listOf(216_000L),
        )
        for ((range, values) in expected) {
            val filter = MetadataFilters(length = range)
            assertEquals(values, times.filter { filter.matches(game, metadata(seconds = it)) })
            assertFalse(filter.matches(game, metadata(seconds = null)))
            assertFalse(filter.matches(game, metadata(seconds = 0)))
        }
        assertTrue(MetadataFilters(length = LengthFilter.UNKNOWN).matches(game, metadata(seconds = null)))
        assertTrue(MetadataFilters(length = LengthFilter.UNKNOWN).matches(game, metadata(seconds = 0)))
        assertFalse(MetadataFilters(length = LengthFilter.UNKNOWN).matches(game, metadata(seconds = 36_000)))
    }

    @Test fun genreHdrAndHeadlineLengthAreCombinedAsIndependentRequirements() {
        val filter = MetadataFilters(genreId = 12, hdr = HdrFilter.HDR, length = LengthFilter.UNDER_10)
        assertTrue(filter.matches(game, metadata(NativeHdr.HDR, 20_000, 12)))
        assertFalse(filter.matches(game, metadata(NativeHdr.HDR, 20_000, 1)))
        assertFalse(filter.matches(game, metadata(NativeHdr.SDR, 20_000, 12)))
        assertFalse(filter.matches(game, metadata(NativeHdr.HDR, 36_000, 12)))
        assertFalse(filter.matches(game, null))
    }

    @Test fun builtInsMatchOnlyInactiveFiltersEvenWithStaleGameMetadata() {
        val builtIns = listOf(
            " desktop " to null,
            "REMOTE INPUT" to null,
            " remote monitor " to null,
            "Steam Big Picture" to null,
            " steam bigpicture " to null,
            "Renamed Input" to "9A1C5A25-58FE-40E0-B9AA-7D3F00000006",
            "Renamed Monitor" to "9a1c5a25-58fe-40e0-b9aa-7d3f00000005",
        ).mapIndexed { index, (name, uuid) ->
            App(index + 10, name, false, false, NvApp().apply { setAppUUID(uuid) })
        }
        val activeFilters = listOf(MetadataFilters(genreId = 1), MetadataFilters(themeId = 1)) +
            HdrFilter.values().filter { it != HdrFilter.ANY }.map { MetadataFilters(hdr = it) } +
            LengthFilter.values().filter { it != LengthFilter.ANY }.map { MetadataFilters(length = it) }
        for (builtIn in builtIns) {
            for (data in listOf(null, metadata(), metadata(NativeHdr.HDR, 20_000))) {
                assertTrue(builtIn.name, MetadataFilters().matches(builtIn, data))
                for (filter in activeFilters) {
                    assertFalse("${builtIn.name}: $filter", filter.matches(builtIn, data))
                }
            }
        }
    }

    @Test fun ordinaryGamesWithUnknownUuidsRetainMissingMetadataMatching() {
        val ordinary = App(2, "Ordinary Game", false, false, NvApp().apply {
            setAppUUID("9a1c5a25-58fe-40e0-b9aa-7d3f00000007")
        })
        assertTrue(MetadataFilters().matches(ordinary, null))
        assertTrue(MetadataFilters(hdr = HdrFilter.UNKNOWN).matches(ordinary, null))
        assertTrue(MetadataFilters(length = LengthFilter.UNKNOWN).matches(ordinary, null))
        assertTrue(MetadataFilters(hdr = HdrFilter.UNKNOWN, length = LengthFilter.UNKNOWN).matches(ordinary, null))
        assertFalse(MetadataFilters(genreId = 1).matches(ordinary, null))
    }
}
