package com.limelight.shell.ui

import com.limelight.shell.data.GameGenre

/** IGDB's genre "Indie": a business model, not a genre, so it only counts when nothing else is listed. */
private const val INDIE = 32

/**
 * Genres from the most to the least specific for what the game plays like: a game listed as Shooter and
 * Adventure is a Shooter, one listed as Puzzle and Adventure is an Adventure (its puzzles are part of the
 * adventure), one listed as Simulator and Sport is a Sport. Genres IGDB adds later come after these, in
 * IGDB's order.
 */
private val GENRE_LADDER = intArrayOf(
    5, // Shooter
    4, // Fighting
    10, // Racing
    12, // Role-playing (RPG)
    11, // Real Time Strategy (RTS)
    16, // Turn-based strategy (TBS)
    24, // Tactical
    25, // Hack and slash/Beat 'em up
    8, // Platform
    31, // Adventure
    9, // Puzzle
    36, // MOBA
    7, // Music
    34, // Visual Novel
    35, // Card & Board Game
    26, // Quiz/Trivia
    30, // Pinball
    2, // Point-and-click
    14, // Sport
    13, // Simulator
    15, // Strategy
    33, // Arcade
)

/** IGDB's theme "Erotic": no label for a shelf, never shown. */
private const val EROTIC = 42

/** IGDB's theme "Action": a genre in disguise, so it is only shown when it is the one usable theme. */
private const val ACTION = 1

/** Themes from the most to the least telling; themes IGDB adds later come after these, in IGDB's order. */
private val THEME_LADDER = intArrayOf(
    19, // Horror
    21, // Survival
    38, // Open world
    18, // Science fiction
    17, // Fantasy
    23, // Stealth
    39, // Warfare
    33, // Sandbox
    43, // Mystery
    20, // Thriller
    40, // Party
    41, // 4X (explore, expand, exploit, and exterminate)
    22, // Historical
    27, // Comedy
    31, // Drama
    44, // Romance
    28, // Business
    34, // Educational
    35, // Kids
    32, // Non-fiction
)

/** [entries] one per id, those the [ladder] knows by their position in it, then the others in their own order. */
private fun ranked(entries: List<GameGenre>, ladder: IntArray): List<GameGenre> {
    val distinct = entries.distinctBy { it.id }
    return distinct.filter { it.id in ladder }.sortedBy { ladder.indexOf(it.id) } + distinct.filter { it.id !in ladder }
}

/** Every genre of [genres] from the one that describes the game best to the least; Indie only when nothing else is listed. */
fun rankedGenres(genres: List<GameGenre>): List<GameGenre> =
    ranked(genres.filter { it.id != INDIE }, GENRE_LADDER).ifEmpty { genres.filter { it.id == INDIE }.take(1) }

/**
 * Every usable theme of [themes] from the most to the least telling. Action only counts when no other usable
 * theme is listed; Erotic never does.
 */
fun rankedThemes(themes: List<GameGenre>): List<GameGenre> {
    val usable = themes.filter { it.id != EROTIC }
    return ranked(usable.filter { it.id != ACTION }, THEME_LADDER).ifEmpty { usable.filter { it.id == ACTION }.take(1) }
}

/** The one genre that describes a game best (IGDB data only); the whole list stays in the metadata. */
fun mainGenre(genres: List<GameGenre>): GameGenre? = rankedGenres(genres).firstOrNull()

/** The one theme that describes a game best (IGDB data only), or null when IGDB lists none worth showing. */
fun mainTheme(themes: List<GameGenre>): GameGenre? = rankedThemes(themes).firstOrNull()

/**
 * Up to [limit] entries: [steam] in its order; when that has fewer, the first IGDB [ranked] entries that are
 * [eligible] as a filler and not present yet. Without any Steam entry the IGDB ranking decides alone.
 */
private fun top(steam: List<GameGenre>, ranked: List<GameGenre>, limit: Int, eligible: (GameGenre) -> Boolean): List<GameGenre> {
    require(limit > 0) { "limit must be positive" }
    if (steam.isEmpty()) return ranked.take(limit)
    val result = steam.take(limit).toMutableList()
    for (entry in ranked) {
        if (result.size >= limit) break
        if (eligible(entry) && result.none { it.id == entry.id }) result += entry
    }
    return result
}

/**
 * The genres shown on the card, at most [limit]: those Steam's user tags give ([steamGenres], vote order), the
 * next ones from IGDB's ranking when Steam gives fewer, or IGDB's ranking alone when Steam gives none (or the
 * game has no Steam tags). Never a duplicate; Indie only when nothing else exists.
 */
fun topGenres(igdbGenres: List<GameGenre>, steamTags: List<String>, limit: Int = 2): List<GameGenre> =
    top(steamGenres(steamTags), rankedGenres(igdbGenres), limit) { it.id != INDIE }

/**
 * The themes shown on the card, at most [limit], chosen like [topGenres]. Action (IGDB's genre in disguise) only
 * counts when it is the one usable theme, whichever provider lists it.
 */
fun topThemes(igdbThemes: List<GameGenre>, steamTags: List<String>, limit: Int = 2): List<GameGenre> {
    val steam = steamThemes(steamTags)
    val chosen = top(steam.filter { it.id != ACTION }, rankedThemes(igdbThemes), limit) { it.id != ACTION }
    return chosen.ifEmpty { steam.take(limit) }
}
