package com.limelight.shell.ui

import com.limelight.shell.data.GameGenre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GameTagsTest {
    private fun g(id: Int) = GameGenre(id, "genre $id")

    private val shooter = 5
    private val platform = 8
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
        // LEGO Batman and Silent Hill: Townfall: IGDB lists Puzzle and Adventure
        assertEquals(g(adventure), mainGenre(listOf(g(puzzle), g(adventure))))
        // Cairn
        assertEquals(g(adventure), mainGenre(listOf(g(simulator), g(sport), g(adventure), g(indie))))
        assertEquals(g(platform), mainGenre(listOf(g(platform), g(adventure))))
        // Overcooked
        assertEquals(g(simulator), mainGenre(listOf(g(simulator), g(strategy), g(indie))))
        assertEquals(g(sport), mainGenre(listOf(g(simulator), g(sport))))
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
    fun actionIsShownOnlyWhenItIsTheOnlyUsableTheme() {
        // Life is Strange: Double Exposure lists Action alone
        assertEquals(g(1), mainTheme(listOf(g(1))))
        assertEquals(g(1), mainTheme(listOf(g(42), g(1))))
        assertEquals(g(32), mainTheme(listOf(g(1), g(42), g(32))))
        assertEquals(g(27), mainTheme(listOf(g(1), g(27))))
    }

    @Test
    fun eroticIsNeverShown() {
        assertNull(mainTheme(listOf(g(42))))
        assertNull(mainTheme(listOf(g(42), g(42))))
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
        val themeIds = listOf(1, 17, 18, 19, 20, 21, 22, 23, 27, 28, 31, 32, 33, 34, 35, 38, 39, 40, 41, 43, 44)
        val icons = themeIds.map { themeIcon(it) }
        icons.forEach { assertNotEquals(Symbols.GENRE_UNKNOWN, it) }
        assertEquals(themeIds.size, icons.toSet().size)
    }

    @Test
    fun unknownThemesFallBackToTheNeutralIcon() {
        assertEquals(Symbols.GENRE_UNKNOWN, themeIcon(0))
        assertEquals(Symbols.GENRE_UNKNOWN, themeIcon(999))
        assertEquals(Symbols.GENRE_UNKNOWN, themeIcon(-1))
        // Erotic is never shown, so it has no icon of its own
        assertEquals(Symbols.GENRE_UNKNOWN, themeIcon(42))
    }

    @Test
    fun everyThemeIconDiffersFromEveryGenreIcon() {
        val genreIcons = listOf(2, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 24, 25, 26, 30, 31, 32, 33, 34, 35, 36).map { genreIcon(it) }.toSet()
        listOf(1, 17, 18, 19, 20, 21, 22, 23, 27, 28, 31, 32, 33, 34, 35, 38, 39, 40, 41, 43, 44).forEach {
            assertFalse(themeIcon(it) in genreIcons)
        }
    }

    private fun ids(entries: List<GameGenre>) = entries.map { it.id }

    private val cronosTags = listOf("Survival Horror", "Horror", "Sci-fi", "Third-Person Shooter", "Combat")

    @Test
    fun rankedGenresListEveryGenreFromTheMostToTheLeastSpecific() {
        // Forza: the ladder puts Sport before Simulator and Arcade last
        assertEquals(listOf(racing, sport, simulator, arcade), ids(rankedGenres(listOf(g(racing), g(simulator), g(sport), g(arcade)))))
        assertEquals(listOf(adventure, 998, 999), ids(rankedGenres(listOf(g(998), g(adventure), g(999)))))
        assertEquals(listOf(adventure), ids(rankedGenres(listOf(g(indie), g(adventure)))))
        assertEquals(listOf(indie), ids(rankedGenres(listOf(g(indie), g(indie)))))
        assertEquals(listOf(adventure), ids(rankedGenres(listOf(g(adventure), g(adventure)))))
        assertEquals(emptyList<GameGenre>(), rankedGenres(emptyList()))
    }

    @Test
    fun rankedThemesSkipEroticAndKeepActionForLast() {
        assertEquals(listOf(19, 21), ids(rankedThemes(listOf(g(1), g(21), g(42), g(19)))))
        assertEquals(listOf(1), ids(rankedThemes(listOf(g(42), g(1)))))
        assertEquals(emptyList<GameGenre>(), rankedThemes(listOf(g(42))))
        assertEquals(listOf(32, 998), ids(rankedThemes(listOf(g(998), g(32)))))
    }

    @Test
    fun steamTagsDecideTheGenresAndIgdbFillsTheSecondSlot() {
        // Cronos: Steam knows one genre (Shooter); IGDB (Shooter, Puzzle, Adventure) adds its next best, Adventure
        assertEquals(listOf(shooter, adventure), ids(topGenres(listOf(g(shooter), g(puzzle), g(adventure)), cronosTags)))
        // Steam names are IGDB's, the filler is IGDB's own entry
        assertEquals(listOf(GameGenre(5, "Shooter"), g(adventure)), topGenres(listOf(g(shooter), g(adventure)), cronosTags))
        // Two Steam genres need no filler
        assertEquals(listOf(simulator, arcade), ids(topGenres(listOf(g(strategy), g(racing)), listOf("Cooking", "Indie", "Arcade"))))
        // The filler is never a genre Steam already gave, and never Indie
        assertEquals(listOf(shooter, puzzle), ids(topGenres(listOf(g(shooter), g(puzzle)), cronosTags)))
        assertEquals(listOf(shooter), ids(topGenres(listOf(g(indie)), cronosTags)))
        // Steam gives no genre (only features): IGDB alone decides
        assertEquals(listOf(adventure, puzzle), ids(topGenres(listOf(g(puzzle), g(adventure)), listOf("Singleplayer", "Co-op"))))
    }

    @Test
    fun withoutSteamTagsTheIgdbLadderGivesTheFirstTwo() {
        // LEGO Batman (Puzzle, Adventure) -> Adventure, Puzzle
        assertEquals(listOf(adventure, puzzle), ids(topGenres(listOf(g(puzzle), g(adventure)), emptyList())))
        // Forza (Racing, Simulator, Sport, Arcade) -> Racing, Sport
        assertEquals(listOf(racing, sport), ids(topGenres(listOf(g(racing), g(simulator), g(sport), g(arcade)), emptyList())))
        assertEquals(listOf(racing), ids(topGenres(listOf(g(racing)), emptyList())))
        assertEquals(listOf(adventure, 998), ids(topGenres(listOf(g(indie), g(998), g(adventure)), emptyList())))
        assertEquals(listOf(indie), ids(topGenres(listOf(g(indie)), emptyList())))
        assertEquals(listOf(racing), ids(topGenres(listOf(g(racing), g(simulator)), emptyList(), limit = 1)))
    }

    @Test
    fun noGenresAndNoSteamTagsShowNothing() {
        assertEquals(emptyList<GameGenre>(), topGenres(emptyList(), emptyList()))
        assertEquals(emptyList<GameGenre>(), topThemes(emptyList(), emptyList()))
        assertEquals(emptyList<GameGenre>(), topThemes(listOf(g(42)), listOf("Singleplayer")))
    }

    @Test
    fun themesFollowTheSameRulesAndActionNeverFills() {
        assertEquals(listOf(19, 18), ids(topThemes(listOf(g(1), g(27)), cronosTags)))
        assertEquals(listOf(19, 21), ids(topThemes(listOf(g(1), g(21), g(42), g(19)), emptyList())))
        // One Steam theme and IGDB's next best: Horror from Steam, Survival from IGDB, never Action or the same theme again
        assertEquals(listOf(19, 21), ids(topThemes(listOf(g(19), g(21), g(1)), listOf("Horror"))))
        assertEquals(listOf(19), ids(topThemes(listOf(g(1)), listOf("Horror"))))
        // Steam's Action is a fallback only: IGDB's real themes win, and Action stands when it is all there is
        assertEquals(listOf(17), ids(topThemes(listOf(g(17)), listOf("Action"))))
        assertEquals(listOf(1), ids(topThemes(emptyList(), listOf("Action"))))
        assertEquals(listOf(1), ids(topThemes(listOf(g(1)), listOf("Superhero"))))
        assertEquals(listOf(1), ids(topThemes(listOf(g(1)), emptyList())))
    }

    @Test
    fun theLimitBoundsTheResultAndNothingIsEverShownTwice() {
        val many = listOf("Shooter", "Platformer", "Puzzle", "Racing")
        assertEquals(listOf(shooter, platform), ids(topGenres(emptyList(), many)))
        assertEquals(listOf(shooter, platform, puzzle), ids(topGenres(emptyList(), many, limit = 3)))
        assertEquals(listOf(shooter), ids(topGenres(listOf(g(shooter)), listOf("Shooter", "FPS"), limit = 1)))
        val result = topGenres(listOf(g(shooter), g(shooter), g(adventure)), listOf("Shooter"))
        assertEquals(result.map { it.id }.distinct(), ids(result))
        assertThrows(IllegalArgumentException::class.java) { topGenres(emptyList(), emptyList(), limit = 0) }
    }
}
