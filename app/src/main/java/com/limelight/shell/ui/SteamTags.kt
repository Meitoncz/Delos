package com.limelight.shell.ui

import com.limelight.shell.data.GameGenre
import java.util.Locale

/*
 * Steam user tags -> IGDB genre / theme entries, so the card pills (and their icons) keep one vocabulary.
 *
 * Steam tags mix genres ("Platformer"), settings ("Sci-fi"), features ("Co-op") and moods ("Cozy"). Each table
 * below maps tag names to the id of ONE IGDB entry; a tag that appears in no table is ignored. Genre ids and
 * theme ids overlap numerically (genre 33 is Arcade, theme 33 is Sandbox), which is why the tables are separate;
 * a tag that is both a genre and a setting (4X, Space Sim) is listed in both. "Strong" entries are what the tag
 * really says; "weak" entries are only a fallback (see [steamGenres], [steamThemes]).
 *
 * The tags are matched case-insensitively after trimming ([steamTagKey]). To extend: add the Steam tag name to
 * the group of the IGDB entry it should show as, in the order of the groups below. Duplicate names across a
 * table are rejected when the table is built.
 */

/** Only the most voted tags describe a game; the long tail is noise (and Steam lists up to 20). */
internal const val STEAM_TAGS_CONSIDERED = 15

private val WHITESPACE = Regex("\\s+")

/** Case-insensitive key of a Steam tag name: trimmed, typographic apostrophes straightened, spaces collapsed. */
internal fun steamTagKey(tag: String): String =
    tag.trim().replace('\u2018', '\'').replace('\u2019', '\'').replace(WHITESPACE, " ").lowercase(Locale.ROOT)

private fun tagTable(vararg groups: Pair<Int, List<String>>): Map<String, Int> {
    val table = HashMap<String, Int>()
    for ((id, names) in groups) {
        for (name in names) {
            val previous = table.put(steamTagKey(name), id)
            check(previous == null) { "Steam tag \"$name\" is mapped twice" }
        }
    }
    return table
}

/**
 * IGDB's genre names by id (the names IGDB returns, so a Steam-derived entry reads like an IGDB-derived one). This is data, the
 * English name carried in [GameGenre.name]; what the user reads comes from `strings_tags.xml` through [genreName].
 */
internal val IGDB_GENRE_NAMES: Map<Int, String> = mapOf(
    2 to "Point-and-click", // l10n-ignore
    4 to "Fighting", // l10n-ignore
    5 to "Shooter", // l10n-ignore
    7 to "Music", // l10n-ignore
    8 to "Platform", // l10n-ignore
    9 to "Puzzle", // l10n-ignore
    10 to "Racing", // l10n-ignore
    11 to "Real Time Strategy (RTS)", // l10n-ignore
    12 to "Role-playing (RPG)", // l10n-ignore
    13 to "Simulator", // l10n-ignore
    14 to "Sport", // l10n-ignore
    15 to "Strategy", // l10n-ignore
    16 to "Turn-based strategy (TBS)", // l10n-ignore
    24 to "Tactical", // l10n-ignore
    25 to "Hack and slash/Beat 'em up", // l10n-ignore
    26 to "Quiz/Trivia", // l10n-ignore
    30 to "Pinball", // l10n-ignore
    31 to "Adventure", // l10n-ignore
    32 to "Indie", // l10n-ignore
    33 to "Arcade", // l10n-ignore
    34 to "Visual Novel", // l10n-ignore
    35 to "Card & Board Game", // l10n-ignore
    36 to "MOBA", // l10n-ignore
)

/** IGDB's theme names by id; data like [IGDB_GENRE_NAMES], shown through [themeName]. */
internal val IGDB_THEME_NAMES: Map<Int, String> = mapOf(
    1 to "Action", // l10n-ignore
    17 to "Fantasy", // l10n-ignore
    18 to "Science fiction", // l10n-ignore
    19 to "Horror", // l10n-ignore
    20 to "Thriller", // l10n-ignore
    21 to "Survival", // l10n-ignore
    22 to "Historical", // l10n-ignore
    23 to "Stealth", // l10n-ignore
    27 to "Comedy", // l10n-ignore
    28 to "Business", // l10n-ignore
    31 to "Drama", // l10n-ignore
    32 to "Non-fiction", // l10n-ignore
    33 to "Sandbox", // l10n-ignore
    34 to "Educational", // l10n-ignore
    35 to "Kids", // l10n-ignore
    38 to "Open world", // l10n-ignore
    39 to "Warfare", // l10n-ignore
    40 to "Party", // l10n-ignore
    41 to "4X (explore, expand, exploit, and exterminate)", // l10n-ignore
    42 to "Erotic", // l10n-ignore
    43 to "Mystery", // l10n-ignore
    44 to "Romance", // l10n-ignore
)

/* The Steam tag names below are DATA: the English names Steam's store returns, matched case-insensitively. They are never shown and never translated. */

/** Tags that say what the game plays like. */
internal val STEAM_GENRE_TAGS = tagTable(
    5 to listOf(
        "Shooter", "First-Person Shooter", "Third-Person Shooter", "FPS", "Bullet Hell", "Shoot 'Em Up", "Shmup", // l10n-ignore
        "Looter Shooter", "Hero Shooter", "Arena Shooter", "Tactical Shooter", "Twin Stick Shooter", // l10n-ignore
        "Top-Down Shooter", "Rail Shooter", "On-Rails Shooter", "Extraction Shooter", "Boomer Shooter", "Battle Royale", // l10n-ignore
    ),
    4 to listOf("Fighting", "2D Fighter", "3D Fighter", "Martial Arts"), // l10n-ignore
    25 to listOf("Hack and Slash", "Beat 'em up", "Brawler", "Character Action Game", "Character Action", "Souls-like", "Soulslike", "Spectacle fighter"), // l10n-ignore
    12 to listOf(
        "RPG", "Action RPG", "ARPG", "JRPG", "CRPG", "Party-Based RPG", "MMORPG", "Dungeon Crawler", // l10n-ignore
    ),
    10 to listOf("Racing", "Driving", "Combat Racing", "Motorsports", "Kart Racing"), // l10n-ignore
    8 to listOf("Platformer", "2D Platformer", "3D Platformer", "Precision Platformer", "Metroidvania"), // l10n-ignore
    9 to listOf("Puzzle", "Puzzle Platformer", "Hidden Object", "Match 3", "Logic", "Tile-Matching", "Escape Room", "Jigsaw"), // l10n-ignore
    31 to listOf("Adventure", "Action-Adventure", "Walking Simulator", "Interactive Fiction", "Text-Based", "Choose Your Own Adventure"), // l10n-ignore
    2 to listOf("Point & Click"), // l10n-ignore
    15 to listOf("Strategy", "Grand Strategy", "4X", "City Builder", "Tower Defense", "Auto Battler", "Wargame"), // l10n-ignore
    11 to listOf("RTS", "Real Time Strategy"), // l10n-ignore
    16 to listOf("Turn-Based Strategy", "Strategy RPG"), // l10n-ignore
    24 to listOf("Tactical", "Tactical RPG", "Turn-Based Tactics", "Real Time Tactics"), // l10n-ignore
    35 to listOf("Card Battler", "Deckbuilding", "Card Game", "Roguelike Deckbuilder", "Board Game", "Tabletop", "Trading Card Game"), // l10n-ignore
    36 to listOf("MOBA"),
    13 to listOf(
        "Simulation", "Life Sim", "Farming Sim", "Management", "Resource Management", "Time Management", "Colony Sim", "Cooking", // l10n-ignore
        "Flight", "Space Sim", "Automobile Sim", "Medical Sim", "Trains", "Hunting", "Sailing", "God Game", "Hobby Sim", "Job Simulator", // l10n-ignore
    ),
    14 to listOf(
        "Sports", "Football", "Football (Soccer)", "Football (American)", "Soccer", "Basketball", "Baseball", "Golf", "Mini Golf", // l10n-ignore
        "Tennis", "Hockey", "Skateboarding", "Wrestling", "Boxing", "Cycling", "Bowling", "Fishing", "Pool", "Skating", "Skiing", // l10n-ignore
    ),
    7 to listOf("Rhythm", "Music", "Dance"), // l10n-ignore
    34 to listOf("Visual Novel"), // l10n-ignore
    26 to listOf("Trivia", "Word Game"), // l10n-ignore
    33 to listOf("Arcade"), // l10n-ignore
    30 to listOf("Pinball"), // l10n-ignore
)

/** Genre-ish tags that are only a fallback: they say "a game with X in it", not what it plays like. */
internal val STEAM_WEAK_GENRE_TAGS = tagTable(
    12 to listOf("Roguelike", "Roguelite", "Action Roguelike", "Roguevania", "Rogue-like", "Rogue-lite"), // l10n-ignore
)

/** Tags that say what the game is about or where it takes place. */
internal val STEAM_THEME_TAGS = tagTable(
    19 to listOf("Horror", "Survival Horror", "Psychological Horror", "Cosmic Horror", "Lovecraftian", "Zombies"), // l10n-ignore
    18 to listOf("Sci-fi", "Science Fiction", "Cyberpunk", "Space", "Aliens", "Futuristic", "Dystopian", "Mechs", "Robots", "Space Sim"), // l10n-ignore
    17 to listOf("Fantasy", "Dark Fantasy", "Medieval", "Magic", "Dragons", "Mythology"), // l10n-ignore
    38 to listOf("Open World"), // l10n-ignore
    21 to listOf("Survival", "Open World Survival Craft", "Post-apocalyptic"), // l10n-ignore
    23 to listOf("Stealth"), // l10n-ignore
    39 to listOf("War", "WWII", "World War I", "World War II", "Military", "Cold War", "Wargame"), // l10n-ignore
    33 to listOf("Sandbox"), // l10n-ignore
    43 to listOf("Mystery", "Detective", "Noir", "Crime", "Investigation"), // l10n-ignore
    20 to listOf("Thriller", "Psychological", "Conspiracy", "Espionage"), // l10n-ignore
    27 to listOf("Comedy", "Funny", "Parody", "Dark Humor", "Satire", "Black Comedy", "Memes"), // l10n-ignore
    40 to listOf("Party", "Party Game"), // l10n-ignore
    41 to listOf("4X"),
    22 to listOf("Historical", "Ancient", "Alternate History", "Vikings"), // l10n-ignore
    31 to listOf("Drama"), // l10n-ignore
    44 to listOf("Romance", "Dating Sim", "Otome"), // l10n-ignore
    28 to listOf("Business", "Economy", "Trading"), // l10n-ignore
    34 to listOf("Education", "Educational"), // l10n-ignore
    35 to listOf("Kids", "Family Friendly"), // l10n-ignore
    32 to listOf("Documentary"), // l10n-ignore
)

/** Theme tags that are only a fallback: IGDB's "Action" is a genre in disguise (see [mainTheme]). */
internal val STEAM_WEAK_THEME_TAGS = tagTable(
    1 to listOf("Action", "Superhero"), // l10n-ignore
)

/**
 * Tags that are deliberately NOT shown: features, modes, mood, art style, perspective, business model and
 * meta tags say nothing about what the game plays like or is about. Everything unlisted is ignored as well; this
 * list documents the decisions and a test keeps it disjoint from the tables, so none is mapped by accident.
 */
internal val STEAM_IGNORED_TAGS: Set<String> = listOf(
    "Singleplayer", "Multiplayer", "Co-op", "Online Co-Op", "Local Co-Op", "Co-op Campaign", "Local Multiplayer", // l10n-ignore
    "4 Player Local", "PvP", "PvE", "MMO", "Massively Multiplayer", "Indie", "Casual", "Atmospheric", "Difficult", // l10n-ignore
    "Relaxing", "Cozy", "Wholesome", "Story Rich", "Narrative", "Great Soundtrack", "Early Access", "Controller", // l10n-ignore
    "Full controller support", "Third Person", "First-Person", "Female Protagonist", "Anime", "Cute", "Colorful", // l10n-ignore
    "Realistic", "Stylized", "2D", "3D", "Pixel Graphics", "Moddable", "Replay Value", "Choices Matter", // l10n-ignore
    "Free to Play", "VR", "Exploration", "Combat", "Crafting", "Building", "Base Building", "Procedural Generation", // l10n-ignore
    "Comic Book", "Classic", "Retro", "Short", "Violent", "Gore", "Nudity", "Sexual Content", "Mature", // l10n-ignore
    "Multiple Endings", "Fast-Paced", "Turn-Based", "Turn-Based Combat", // l10n-ignore
).map(::steamTagKey).toSet()

/**
 * The IGDB entries (in vote order, no duplicates by id) that [tags] (Steam's user tags, most voted first) map to in
 * [strong] and, when fewer than [weakBelow] strong entries exist, in [weak]. Only the first [STEAM_TAGS_CONSIDERED]
 * tags count.
 */
private fun select(
    tags: List<String>,
    strong: Map<String, Int>,
    weak: Map<String, Int>,
    names: Map<Int, String>,
    weakBelow: Int,
): List<GameGenre> {
    val considered = tags.take(STEAM_TAGS_CONSIDERED).map(::steamTagKey)
    val ids = LinkedHashSet<Int>()
    considered.mapNotNullTo(ids) { strong[it] }
    if (ids.size < weakBelow) considered.mapNotNullTo(ids) { weak[it] }
    return ids.map { GameGenre(it, names.getValue(it)) }
}

/**
 * The genres Steam's user tags give a game, in vote order. Weak mappings (Roguelike -> RPG) only join when
 * fewer than two strong genres exist. Empty when no tag maps to a genre.
 */
fun steamGenres(tags: List<String>): List<GameGenre> = select(tags, STEAM_GENRE_TAGS, STEAM_WEAK_GENRE_TAGS, IGDB_GENRE_NAMES, weakBelow = 2)

/**
 * The themes Steam's user tags give a game, in vote order. The weak theme "Action" only counts when no other theme
 * exists, the same rule as for IGDB's themes. Empty when no tag maps to a theme.
 */
fun steamThemes(tags: List<String>): List<GameGenre> = select(tags, STEAM_THEME_TAGS, STEAM_WEAK_THEME_TAGS, IGDB_THEME_NAMES, weakBelow = 1)
