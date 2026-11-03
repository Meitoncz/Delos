package com.limelight.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import com.limelight.shell.data.GameGenre
import com.limelight.shell.ui.GameColors
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.glass
import com.limelight.shell.ui.genreIcon
import com.limelight.shell.ui.hsl
import com.limelight.shell.ui.px
import com.limelight.shell.ui.themeIcon

/** What [InfoRow] places apart from the facts. */
private enum class InfoPart { DIVIDER, TAGS }

private class InfoSlot(val placeable: Placeable, val x: Int, val line: Int)

/**
 * The information row under a game's title: the [facts], then a thin divider and the [tags] (the game's
 * main genre and theme). It wraps like a FlowRow, but the tags are one group: it moves to the next line as a
 * whole, and its divider stays behind, so a line never starts with a divider and the pills never split up.
 * Without [hasTags] there is neither a divider nor a group.
 */
@Composable
internal fun InfoRow(modifier: Modifier = Modifier, hasTags: Boolean, tags: @Composable () -> Unit, facts: @Composable () -> Unit) {
    val colors = LocalDelosColors.current
    val gap = 12.px
    val lineGap = 6.px
    val dividerMargin = 14.px
    Layout(
        content = {
            facts()
            if (hasTags) {
                Box(Modifier.layoutId(InfoPart.DIVIDER).width(1.px).height(26.px).background(colors.line))
                Row(Modifier.layoutId(InfoPart.TAGS), horizontalArrangement = Arrangement.spacedBy(gap)) { tags() }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val maxWidth = constraints.maxWidth
        val loose = Constraints(maxWidth = maxWidth)
        val gapPx = gap.roundToPx()
        val dividerMarginPx = dividerMargin.roundToPx()
        val slots = ArrayList<InfoSlot>()
        val heights = arrayListOf(0)
        var x = 0
        for (fact in measurables.filter { it.layoutId == null }) {
            val placeable = fact.measure(loose)
            if (x > 0 && x + placeable.width > maxWidth) {
                heights.add(0)
                x = 0
            }
            slots += InfoSlot(placeable, x, heights.lastIndex)
            heights[heights.lastIndex] = maxOf(heights.last(), placeable.height)
            x += placeable.width + gapPx
        }
        if (x > 0) x -= gapPx
        val divider = measurables.firstOrNull { it.layoutId == InfoPart.DIVIDER }?.measure(Constraints())
        val group = measurables.firstOrNull { it.layoutId == InfoPart.TAGS }?.measure(loose)
        if (divider != null && group != null) {
            val beside = dividerMarginPx * 2 + divider.width
            val groupX = if (x > 0 && x + beside + group.width <= maxWidth) {
                slots += InfoSlot(divider, x + dividerMarginPx, heights.lastIndex)
                x + beside
            } else {
                if (x > 0) heights.add(0)
                0
            }
            slots += InfoSlot(group, groupX, heights.lastIndex)
            heights[heights.lastIndex] = maxOf(heights.last(), group.height)
        }
        val lineGapPx = lineGap.roundToPx()
        val tops = IntArray(heights.size)
        for (line in 1 until heights.size) tops[line] = tops[line - 1] + heights[line - 1] + lineGapPx
        val width = (slots.maxOfOrNull { it.x + it.placeable.width } ?: 0).coerceIn(constraints.minWidth, maxWidth)
        val height = (tops.last() + heights.last()).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            for (slot in slots) slot.placeable.placeRelative(slot.x, tops[slot.line] + (heights[slot.line] - slot.placeable.height) / 2)
        }
    }
}

/** The colors of the main genre's pill: a soft tint of one hue, its text and icon a light shade of it. */
@Immutable
private class GenreTint(val fill: Color, val border: Color, val content: Color)

private fun genreTint(hue: Float, saturation: Float, dark: Boolean): GenreTint {
    val s = saturation.coerceIn(20f, 95f)
    return if (dark) GenreTint(hsl(hue, s, 34f, .35f), hsl(hue, s, 55f, .55f), hsl(hue, s, 85f))
    else GenreTint(hsl(hue, s, 50f, .2f), hsl(hue, s, 40f, .5f), hsl(hue, s, 24f))
}

/**
 * The game's main genre: the size of every info pill, filled with a tint of the game's main color (the
 * accent's while the game's colors are off or unknown), so it is the row's accent.
 */
@Composable
internal fun GenrePill(genre: GameGenre, gameColors: GameColors?) {
    val colors = LocalDelosColors.current
    val hue = gameColors?.hues?.firstOrNull() ?: colors.accentHue
    // How vivid the game's color is (1 = as the palette's) sets the pill's saturation; the accent's is 1
    val vividness = gameColors?.saturations?.firstOrNull() ?: 1f
    val tint = genreTint(hue, vividness * 80f, colors.dark)
    Pill(genreIcon(genre.id), genre.name, tint.content, tint.content, Modifier.glass(colors.surface).background(tint.fill), tint.border)
}

/** The game's main theme: only an outline and the dimmer text color, so it stays behind the genre. */
@Composable
internal fun ThemePill(theme: GameGenre) {
    val colors = LocalDelosColors.current
    Pill(themeIcon(theme.id), theme.name, colors.text3, colors.text3, Modifier, colors.line)
}
