package com.limelight.shell.ui

import com.limelight.shell.data.GameGenre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamTagsTest {
    private val shooter = GameGenre(5, "Shooter")
    private val platform = GameGenre(8, "Platform")
    private val racing = GameGenre(10, "Racing")
    private val rpg = GameGenre(12, "Role-playing (RPG)")
    private val simulator = GameGenre(13, "Simulator")
    private val adventure = GameGenre(31, "Adventure")
    private val arcade = GameGenre(33, "Arcade")

    private val action = GameGenre(1, "Action")
    private val scienceFiction = GameGenre(18, "Science fiction")
    private val horror = GameGenre(19, "Horror")
    private val comedy = GameGenre(27, "Comedy")
    private val kids = GameGenre(35, "Kids")
    private val openWorld = GameGenre(38, "Open world")

    // Live Steam user tags (most voted first), as checked on the store pages.
    private val cronos = listOf("Survival Horror", "Horror", "Sci-fi", "Third-Person Shooter", "Combat", "Atmospheric", "Singleplayer")
    private val legoBatman = listOf(
        "Co-op", "Open World", "Superhero", "Multiplayer", "Comic Book", "Action", "Local Co-Op", "Action-Adventure", "Funny",
        "Third Person", "3D Platformer", "Adventure",
    )
    private val overcooked = listOf(
        "Local Co-Op", "Multiplayer", "Cooking", "Indie", "Arcade", "Co-op", "Local Multiplayer", "4 Player Local", "Casual",
        "Funny", "Family Friendly",
    )
    private val returnal = listOf(
        "Bullet Hell", "Action", "Roguelike", "Singleplayer", "Female Protagonist", "Third-Person Shooter", "Co-op",
        "Action Roguelike", "Space",
    )
    private val americanTruck = listOf("Automobile Sim", "Driving", "Open World", "Simulation", "Transportation")
    private val forza = listOf("Racing", "Open World", "Driving", "Multiplayer", "Automobile Sim", "Realistic", "Simulation")

    @Test fun cronosIsAShooterWithHorrorAndScienceFiction() {
        assertEquals(listOf(shooter), steamGenres(cronos))
        assertEquals(listOf(horror, scienceFiction), steamThemes(cronos))
    }

    @Test fun legoBatmanKeepsTheVoteOrderAndTurnsFunnyIntoComedy() {
        // Action-Adventure comes before 3D Platformer; the Action theme is only a fallback and the genre "Action" does not exist
        assertEquals(listOf(adventure, platform), steamGenres(legoBatman))
        assertEquals(listOf(openWorld, comedy), steamThemes(legoBatman))
    }

    @Test fun overcookedIsASimulatorAndArcadeWithComedyAndKids() {
        assertEquals(listOf(simulator, arcade), steamGenres(overcooked))
        assertEquals(listOf(comedy, kids), steamThemes(overcooked))
    }

    @Test fun returnalIsAShooterAndOnlyThenARoguelikeRpg() {
        assertEquals(listOf(shooter, rpg), steamGenres(returnal))
        assertEquals(listOf(scienceFiction), steamThemes(returnal))
    }

    @Test fun vehicleGamesFollowTheVotesAcrossRacingAndSimulator() {
        assertEquals(listOf(simulator, racing), steamGenres(americanTruck))
        assertEquals(listOf(openWorld), steamThemes(americanTruck))
        assertEquals(listOf(racing, simulator), steamGenres(forza))
        assertEquals(listOf(openWorld), steamThemes(forza))
    }

    @Test fun weakGenresOnlyJoinWhenFewerThanTwoStrongOnesExist() {
        assertEquals(listOf(rpg), steamGenres(listOf("Roguelike", "Singleplayer")))
        assertEquals(listOf(GameGenre(9, "Puzzle"), rpg), steamGenres(listOf("Roguelike", "Puzzle")))
        assertEquals(listOf(GameGenre(9, "Puzzle"), platform), steamGenres(listOf("Roguelike", "Puzzle", "Platformer")))
        assertEquals(listOf(rpg), steamGenres(listOf("Roguelite", "Action Roguelike")))
    }

    @Test fun theActionThemeIsAFallbackOnly() {
        assertEquals(listOf(action), steamThemes(listOf("Action")))
        assertEquals(listOf(action), steamThemes(listOf("Superhero", "Singleplayer")))
        assertEquals(listOf(horror), steamThemes(listOf("Action", "Horror")))
    }

    @Test fun matchingIsCaseInsensitiveAndIgnoresSpacingAndTypographicApostrophes() {
        for (tag in listOf("shooter", "SHOOTER", "  Shooter  ", "Shoot 'Em Up", "SHOOT 'EM UP", "Shoot \u2019Em Up", "shoot  'em  up")) {
            assertEquals(tag, listOf(shooter), steamGenres(listOf(tag)))
        }
    }

    @Test fun unknownAndIgnoredTagsMapToNothing() {
        assertTrue(steamGenres(emptyList()).isEmpty())
        assertTrue(steamThemes(emptyList()).isEmpty())
        assertTrue(steamGenres(listOf("Made Up Tag", "", "   ")).isEmpty())
        for (tag in STEAM_IGNORED_TAGS) {
            assertTrue(tag, steamGenres(listOf(tag)).isEmpty())
            assertTrue(tag, steamThemes(listOf(tag)).isEmpty())
        }
        for (tag in listOf("Singleplayer", "Co-op", "Indie", "Atmospheric", "Third Person", "Female Protagonist", "Story Rich", "Cozy")) {
            assertTrue(tag, steamKeyIsIgnored(tag))
        }
    }

    private fun steamKeyIsIgnored(tag: String) = steamTagKey(tag) in STEAM_IGNORED_TAGS

    @Test fun duplicatesCollapseToTheirFirstVote() {
        assertEquals(listOf(shooter), steamGenres(listOf("Shooter", "First-Person Shooter", "FPS", "Bullet Hell")))
        assertEquals(listOf(horror), steamThemes(listOf("Horror", "Survival Horror", "Psychological Horror")))
    }

    @Test fun aTagThatIsBothAGenreAndAThemeCountsInBoth() {
        assertEquals(listOf(GameGenre(15, "Strategy")), steamGenres(listOf("4X")))
        assertEquals(listOf(GameGenre(41, "4X (explore, expand, exploit, and exterminate)")), steamThemes(listOf("4X")))
    }

    @Test fun onlyTheFirstFifteenTagsCount() {
        val noise = List(15) { "Noise $it" }
        assertTrue(steamGenres(noise + "Racing").isEmpty())
        assertTrue(steamThemes(noise + "Horror").isEmpty())
        assertEquals(listOf(racing), steamGenres(noise.take(14) + "Racing" + "Shooter"))
    }

    @Test fun everyMappedEntryHasAnIgdbNameAndItsOwnIcon() {
        for ((tag, id) in STEAM_GENRE_TAGS + STEAM_WEAK_GENRE_TAGS) {
            assertTrue("genre $tag", id in IGDB_GENRE_NAMES)
            assertNotEquals("genre $tag", Symbols.GENRE_UNKNOWN, genreIcon(id))
            // Indie is a business model: never the target of a tag
            assertNotEquals(tag, 32, id)
        }
        for ((tag, id) in STEAM_THEME_TAGS + STEAM_WEAK_THEME_TAGS) {
            assertTrue("theme $tag", id in IGDB_THEME_NAMES)
            assertNotEquals("theme $tag", Symbols.GENRE_UNKNOWN, themeIcon(id))
            // Erotic has no icon and is never shown
            assertNotEquals(tag, 42, id)
        }
    }

    @Test fun namesAreIgdbsOwn() {
        assertEquals("Role-playing (RPG)", IGDB_GENRE_NAMES.getValue(12))
        assertEquals("Real Time Strategy (RTS)", IGDB_GENRE_NAMES.getValue(11))
        assertEquals("Turn-based strategy (TBS)", IGDB_GENRE_NAMES.getValue(16))
        assertEquals("Science fiction", IGDB_THEME_NAMES.getValue(18))
        assertEquals("Open world", IGDB_THEME_NAMES.getValue(38))
    }

    @Test fun everyIgdbGenreAndThemeTheCardCanShowHasAName() {
        // The ids genreIcon/themeIcon know are exactly the ones with a name here (Erotic: a name, never an icon)
        for (id in listOf(2, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 24, 25, 26, 30, 31, 32, 33, 34, 35, 36)) assertTrue(id in IGDB_GENRE_NAMES)
        assertEquals(23, IGDB_GENRE_NAMES.size)
        for (id in listOf(1, 17, 18, 19, 20, 21, 22, 23, 27, 28, 31, 32, 33, 34, 35, 38, 39, 40, 41, 43, 44)) assertTrue(id in IGDB_THEME_NAMES)
        assertEquals(22, IGDB_THEME_NAMES.size)
    }
}
