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

/** IGDB's genre names by id (the names IGDB returns, so a Steam-derived entry reads like an IGDB-derived one). */
internal val IGDB_GENRE_NAMES: Map<Int, String> = mapOf(
    2 to "Point-and-click",
    4 to "Fighting",
    5 to "Shooter",
    7 to "Music",
    8 to "Platform",
    9 to "Puzzle",
    10 to "Racing",
    11 to "Real Time Strategy (RTS)",
    12 to "Role-playing (RPG)",
    13 to "Simulator",
    14 to "Sport",
    15 to "Strategy",
    16 to "Turn-based strategy (TBS)",
    24 to "Tactical",
    25 to "Hack and slash/Beat 'em up",
    26 to "Quiz/Trivia",
    30 to "Pinball",
    31 to "Adventure",
    32 to "Indie",
    33 to "Arcade",
    34 to "Visual Novel",
    35 to "Card & Board Game",
    36 to "MOBA",
)

/** IGDB's theme names by id. */
internal val IGDB_THEME_NAMES: Map<Int, String> = mapOf(
    1 to "Action",
    17 to "Fantasy",
    18 to "Science fiction",
    19 to "Horror",
    20 to "Thriller",
    21 to "Survival",
    22 to "Historical",
    23 to "Stealth",
    27 to "Comedy",
    28 to "Business",
    31 to "Drama",
    32 to "Non-fiction",
    33 to "Sandbox",
    34 to "Educational",
    35 to "Kids",
    38 to "Open world",
    39 to "Warfare",
    40 to "Party",
    41 to "4X (explore, expand, exploit, and exterminate)",
    42 to "Erotic",
    43 to "Mystery",
    44 to "Romance",
)

/** Tags that say what the game plays like. */
internal val STEAM_GENRE_TAGS = tagTable(
    5 to listOf(
        "Shooter", "First-Person Shooter", "Third-Person Shooter", "FPS", "Bullet Hell", "Shoot 'Em Up", "Shmup",
        "Looter Shooter", "Hero Shooter", "Arena Shooter", "Tactical Shooter", "Twin Stick Shooter",
        "Top-Down Shooter", "Rail Shooter", "On-Rails Shooter", "Extraction Shooter", "Boomer Shooter", "Battle Royale",
    ),
    4 to listOf("Fighting", "2D Fighter", "3D Fighter", "Martial Arts"),
    25 to listOf("Hack and Slash", "Beat 'em up", "Brawler", "Character Action Game", "Character Action", "Souls-like", "Soulslike", "Spectacle fighter"),
    12 to listOf(
        "RPG", "Action RPG", "ARPG", "JRPG", "CRPG", "Party-Based RPG", "MMORPG", "Dungeon Crawler",
    ),
    10 to listOf("Racing", "Driving", "Combat Racing", "Motorsports", "Kart Racing"),
    8 to listOf("Platformer", "2D Platformer", "3D Platformer", "Precision Platformer", "Metroidvania"),
    9 to listOf("Puzzle", "Puzzle Platformer", "Hidden Object", "Match 3", "Logic", "Tile-Matching", "Escape Room", "Jigsaw"),
    31 to listOf("Adventure", "Action-Adventure", "Walking Simulator", "Interactive Fiction", "Text-Based", "Choose Your Own Adventure"),
    2 to listOf("Point & Click"),
    15 to listOf("Strategy", "Grand Strategy", "4X", "City Builder", "Tower Defense", "Auto Battler", "Wargame"),
    11 to listOf("RTS", "Real Time Strategy"),
    16 to listOf("Turn-Based Strategy", "Strategy RPG"),
    24 to listOf("Tactical", "Tactical RPG", "Turn-Based Tactics", "Real Time Tactics"),
    35 to listOf("Card Battler", "Deckbuilding", "Card Game", "Roguelike Deckbuilder", "Board Game", "Tabletop", "Trading Card Game"),
    36 to listOf("MOBA"),
    13 to listOf(
        "Simulation", "Life Sim", "Farming Sim", "Management", "Resource Management", "Time Management", "Colony Sim", "Cooking",
        "Flight", "Space Sim", "Automobile Sim", "Medical Sim", "Trains", "Hunting", "Sailing", "God Game", "Hobby Sim", "Job Simulator",
    ),
    14 to listOf(
        "Sports", "Football", "Football (Soccer)", "Football (American)", "Soccer", "Basketball", "Baseball", "Golf", "Mini Golf",
        "Tennis", "Hockey", "Skateboarding", "Wrestling", "Boxing", "Cycling", "Bowling", "Fishing", "Pool", "Skating", "Skiing",
    ),
    7 to listOf("Rhythm", "Music", "Dance"),
    34 to listOf("Visual Novel"),
    26 to listOf("Trivia", "Word Game"),
    33 to listOf("Arcade"),
    30 to listOf("Pinball"),
)

/** Genre-ish tags that are only a fallback: they say "a game with X in it", not what it plays like. */
internal val STEAM_WEAK_GENRE_TAGS = tagTable(
    12 to listOf("Roguelike", "Roguelite", "Action Roguelike", "Roguevania", "Rogue-like", "Rogue-lite"),
)

/** Tags that say what the game is about or where it takes place. */
internal val STEAM_THEME_TAGS = tagTable(
    19 to listOf("Horror", "Survival Horror", "Psychological Horror", "Cosmic Horror", "Lovecraftian", "Zombies"),
    18 to listOf("Sci-fi", "Science Fiction", "Cyberpunk", "Space", "Aliens", "Futuristic", "Dystopian", "Mechs", "Robots", "Space Sim"),
    17 to listOf("Fantasy", "Dark Fantasy", "Medieval", "Magic", "Dragons", "Mythology"),
    38 to listOf("Open World"),
    21 to listOf("Survival", "Open World Survival Craft", "Post-apocalyptic"),
    23 to listOf("Stealth"),
    39 to listOf("War", "WWII", "World War I", "World War II", "Military", "Cold War", "Wargame"),
    33 to listOf("Sandbox"),
    43 to listOf("Mystery", "Detective", "Noir", "Crime", "Investigation"),
    20 to listOf("Thriller", "Psychological", "Conspiracy", "Espionage"),
    27 to listOf("Comedy", "Funny", "Parody", "Dark Humor", "Satire", "Black Comedy", "Memes"),
    40 to listOf("Party", "Party Game"),
    41 to listOf("4X"),
    22 to listOf("Historical", "Ancient", "Alternate History", "Vikings"),
    31 to listOf("Drama"),
    44 to listOf("Romance", "Dating Sim", "Otome"),
    28 to listOf("Business", "Economy", "Trading"),
    34 to listOf("Education", "Educational"),
    35 to listOf("Kids", "Family Friendly"),
    32 to listOf("Documentary"),
)

/** Theme tags that are only a fallback: IGDB's "Action" is a genre in disguise (see [mainTheme]). */
internal val STEAM_WEAK_THEME_TAGS = tagTable(
    1 to listOf("Action", "Superhero"),
)

/**
 * Tags that are deliberately NOT shown: features, modes, mood, art style, perspective, business model and
 * meta tags say nothing about what the game plays like or is about. Everything unlisted is ignored as well; this
 * list documents the decisions and a test keeps it disjoint from the tables, so none is mapped by accident.
 */
internal val STEAM_IGNORED_TAGS: Set<String> = listOf(
    "Singleplayer", "Multiplayer", "Co-op", "Online Co-Op", "Local Co-Op", "Co-op Campaign", "Local Multiplayer",
    "4 Player Local", "PvP", "PvE", "MMO", "Massively Multiplayer", "Indie", "Casual", "Atmospheric", "Difficult",
    "Relaxing", "Cozy", "Wholesome", "Story Rich", "Narrative", "Great Soundtrack", "Early Access", "Controller",
    "Full controller support", "Third Person", "First-Person", "Female Protagonist", "Anime", "Cute", "Colorful",
    "Realistic", "Stylized", "2D", "3D", "Pixel Graphics", "Moddable", "Replay Value", "Choices Matter",
    "Free to Play", "VR", "Exploration", "Combat", "Crafting", "Building", "Base Building", "Procedural Generation",
    "Comic Book", "Classic", "Retro", "Short", "Violent", "Gore", "Nudity", "Sexual Content", "Mature",
    "Multiple Endings", "Fast-Paced", "Turn-Based", "Turn-Based Combat",
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
