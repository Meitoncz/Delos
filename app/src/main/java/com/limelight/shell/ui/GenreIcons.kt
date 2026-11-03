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
