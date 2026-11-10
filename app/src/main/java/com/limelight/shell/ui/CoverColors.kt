package com.limelight.shell.ui

import androidx.compose.runtime.mutableStateMapOf
import com.limelight.shell.HeaderTint

/**
 * The main colors of each game's cover, worked out once per decoded image and kept for the life of the process. The dashboard
 * hands in every cover it shows as soon as it is decoded, so whatever reads a game's colors later (the animated background,
 * the info pills, the context menu's header) finds them at once. Reads are snapshot state: a composable that reads a game
 * whose colors are still being worked out recomposes when they arrive.
 */
internal class CoverColorCache {
    private val colors = mutableStateMapOf<String, GameColors?>()

    /** The image (its identity) each game's colors were last asked for. */
    private val sources = HashMap<String, Int>()

    /** Whether [key]'s colors have been worked out (they may be null: a grey cover). */
    fun known(key: String): Boolean = key in colors

    /** The colors of [key]'s cover; null while unknown or for a grey cover. */
    fun get(key: String): GameColors? = colors[key]

    /**
     * Whether the caller is to work out the colors of [key]'s image [source]: true once per image, and again when the game
     * gets another one (new artwork). The caller then owes a [store].
     */
    fun claim(key: String, source: Int): Boolean = synchronized(sources) { sources.put(key, source) != source }

    /**
     * Keeps [result] as [key]'s colors, unless the game got another image since [source] was claimed (its colors are on the
     * way and must not be replaced by the older image's). Until then the previous image's colors stay.
     */
    fun store(key: String, source: Int, result: GameColors?) {
        if (synchronized(sources) { sources[key] != source }) return
        colors[key] = result
    }
}

/**
 * The color of a game's context menu header tile: the theme's accent when the interface does not take the games' colors
 * ([matching] off) or the cover is grey; the placeholder art's [placeholderHue] while the game has no cover; the cover's
 * main color once it is [known]; [HeaderTint.Pending] in the short time between a cover's decoding and its colors.
 */
internal fun gameHeaderTint(matching: Boolean, hasCover: Boolean, known: Boolean, cover: GameColors?, placeholderHue: Float): HeaderTint = when {
    !matching -> HeaderTint.Accent
    !hasCover -> HeaderTint.Game(hsl(placeholderHue, 70f, 52f))
    !known -> HeaderTint.Pending
    cover == null -> HeaderTint.Accent
    else -> HeaderTint.Game(hsl(cover.hues.first(), cover.saturations.first() * 80f, 52f))
}
