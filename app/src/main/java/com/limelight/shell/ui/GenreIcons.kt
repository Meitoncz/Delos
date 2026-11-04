package com.limelight.shell.ui

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
