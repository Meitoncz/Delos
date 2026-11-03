package com.limelight.shell.ui

import com.limelight.shell.data.GameGenre

/** IGDB's genre "Indie": a business model, not a genre, so it only counts when nothing else is listed. */
private const val INDIE = 32

/**
 * Genres from the most to the least specific for what the game plays like: a game listed as Shooter and
 * Adventure is a Shooter, one listed as Simulator and Sport is a Sport. Genres IGDB adds later come after
 * these, in IGDB's order.
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
    31, // Adventure
)

/** IGDB themes that say nothing about the game: Action is a genre in disguise, Erotic is no label for a shelf. */
private val IGNORED_THEMES = setOf(1, 42)

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

/** The entry of [entries] that comes first in [ladder]; else the first one the ladder does not know. */
private fun firstRanked(entries: List<GameGenre>, ladder: IntArray): GameGenre? =
    entries.filter { it.id in ladder }.minByOrNull { ladder.indexOf(it.id) } ?: entries.firstOrNull { it.id !in ladder }

/** The one genre that describes a game best (shown on the home screen); the whole list stays in the metadata. */
fun mainGenre(genres: List<GameGenre>): GameGenre? =
    firstRanked(genres.filter { it.id != INDIE }, GENRE_LADDER) ?: genres.firstOrNull { it.id == INDIE }

/** The one theme that describes a game best, or null when IGDB lists none worth showing. */
fun mainTheme(themes: List<GameGenre>): GameGenre? =
    firstRanked(themes.filter { it.id !in IGNORED_THEMES }, THEME_LADDER)
