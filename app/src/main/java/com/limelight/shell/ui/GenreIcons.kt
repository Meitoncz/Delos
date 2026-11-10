package com.limelight.shell.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import com.limelight.R
import com.limelight.shell.data.GameGenre

/**
 * Name resource of an IGDB genre (`strings_tags.xml`), or null for one IGDB added later. The id is the stable tag id: ranking,
 * filters and the Steam mapping use ids and the English names as data; only drawing looks the translated name up with
 * [genreName].
 */
@StringRes
internal fun genreNameRes(id: Int): Int? = when (id) {
    2 -> R.string.tags_genre_point_and_click
    4 -> R.string.tags_genre_fighting
    5 -> R.string.tags_genre_shooter
    7 -> R.string.tags_genre_music
    8 -> R.string.tags_genre_platform
    9 -> R.string.tags_genre_puzzle
    10 -> R.string.tags_genre_racing
    11 -> R.string.tags_genre_real_time_strategy
    12 -> R.string.tags_genre_role_playing
    13 -> R.string.tags_genre_simulator
    14 -> R.string.tags_genre_sport
    15 -> R.string.tags_genre_strategy
    16 -> R.string.tags_genre_turn_based_strategy
    24 -> R.string.tags_genre_tactical
    25 -> R.string.tags_genre_hack_and_slash
    26 -> R.string.tags_genre_quiz
    30 -> R.string.tags_genre_pinball
    31 -> R.string.tags_genre_adventure
    32 -> R.string.tags_genre_indie
    33 -> R.string.tags_genre_arcade
    34 -> R.string.tags_genre_visual_novel
    35 -> R.string.tags_genre_card_and_board
    36 -> R.string.tags_genre_moba
    else -> null
}

/** Name resource of an IGDB theme, like [genreNameRes] (theme ids are a separate id space from genre ids). */
@StringRes
internal fun themeNameRes(id: Int): Int? = when (id) {
    1 -> R.string.tags_theme_action
    17 -> R.string.tags_theme_fantasy
    18 -> R.string.tags_theme_science_fiction
    19 -> R.string.tags_theme_horror
    20 -> R.string.tags_theme_thriller
    21 -> R.string.tags_theme_survival
    22 -> R.string.tags_theme_historical
    23 -> R.string.tags_theme_stealth
    27 -> R.string.tags_theme_comedy
    28 -> R.string.tags_theme_business
    31 -> R.string.tags_theme_drama
    32 -> R.string.tags_theme_non_fiction
    33 -> R.string.tags_theme_sandbox
    34 -> R.string.tags_theme_educational
    35 -> R.string.tags_theme_kids
    38 -> R.string.tags_theme_open_world
    39 -> R.string.tags_theme_warfare
    40 -> R.string.tags_theme_party
    41 -> R.string.tags_theme_four_x
    42 -> R.string.tags_theme_erotic
    43 -> R.string.tags_theme_mystery
    44 -> R.string.tags_theme_romance
    else -> null
}

/** What the user reads for [genre]: the translated name of its id, or IGDB's own (English) name for a genre we have no string for. */
fun genreName(res: Resources, genre: GameGenre): String = genreNameRes(genre.id)?.let(res::getString) ?: genre.name

/** What the user reads for [theme]; see [genreName]. */
fun themeName(res: Resources, theme: GameGenre): String = themeNameRes(theme.id)?.let(res::getString) ?: theme.name

/**
 * Icon of an IGDB genre chip. The ids are IGDB's fixed genre list; one genre gets one icon, so no two
 * genres share a glyph. IGDB may add genres later: those get the neutral tag until they are mapped here.
 */
fun genreIcon(id: Int): String = when (id) {
    2 -> Symbols.GENRE_POINT_AND_CLICK
    4 -> Symbols.GENRE_FIGHTING
    5 -> Symbols.GENRE_SHOOTER
    7 -> Symbols.GENRE_MUSIC
    8 -> Symbols.GENRE_PLATFORM
    9 -> Symbols.GENRE_PUZZLE
    10 -> Symbols.GENRE_RACING
    11 -> Symbols.GENRE_REAL_TIME_STRATEGY
    12 -> Symbols.GENRE_ROLE_PLAYING
    13 -> Symbols.GENRE_SIMULATOR
    14 -> Symbols.GENRE_SPORT
    15 -> Symbols.GENRE_STRATEGY
    16 -> Symbols.GENRE_TURN_BASED_STRATEGY
    24 -> Symbols.GENRE_TACTICAL
    25 -> Symbols.GENRE_HACK_AND_SLASH
    26 -> Symbols.GENRE_QUIZ
    30 -> Symbols.GENRE_PINBALL
    31 -> Symbols.GENRE_ADVENTURE
    32 -> Symbols.GENRE_INDIE
    33 -> Symbols.GENRE_ARCADE
    34 -> Symbols.GENRE_VISUAL_NOVEL
    35 -> Symbols.GENRE_CARD_AND_BOARD
    36 -> Symbols.GENRE_MOBA
    else -> Symbols.GENRE_UNKNOWN
}

/**
 * Icon of an IGDB theme chip, one per theme like [genreIcon]. Erotic is never shown (see [mainTheme]), so
 * it has none; unmapped themes get the same neutral tag as unmapped genres.
 */
fun themeIcon(id: Int): String = when (id) {
    1 -> Symbols.THEME_ACTION
    17 -> Symbols.THEME_FANTASY
    18 -> Symbols.THEME_SCIENCE_FICTION
    19 -> Symbols.THEME_HORROR
    20 -> Symbols.THEME_THRILLER
    21 -> Symbols.THEME_SURVIVAL
    22 -> Symbols.THEME_HISTORICAL
    23 -> Symbols.THEME_STEALTH
    27 -> Symbols.THEME_COMEDY
    28 -> Symbols.THEME_BUSINESS
    31 -> Symbols.THEME_DRAMA
    32 -> Symbols.THEME_NON_FICTION
    33 -> Symbols.THEME_SANDBOX
    34 -> Symbols.THEME_EDUCATIONAL
    35 -> Symbols.THEME_KIDS
    38 -> Symbols.THEME_OPEN_WORLD
    39 -> Symbols.THEME_WARFARE
    40 -> Symbols.THEME_PARTY
    41 -> Symbols.THEME_4X
    43 -> Symbols.THEME_MYSTERY
    44 -> Symbols.THEME_ROMANCE
    else -> Symbols.GENRE_UNKNOWN
}
