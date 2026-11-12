package com.limelight.shell.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.px
import kotlin.math.ceil
import kotlin.math.floor

/*
 * Long lists on the companion (the special keys, the PC's commands, the More page of the stream menu): they never scroll. The entries sit in a grid of
 * buttons at least 64 px high that takes as many columns and rows as the room allows; when they do not all fit, the grid shows one page of them and a
 * row of Previous / Next buttons with "2 / 3" between them. The arithmetic is pure ([GridPaging], tested in CompanionPagingTest).
 */

/** The page of a grid: how many [rows] one page has, its [perPage] entries, the number of [pages], and whether the pager row takes the last row of the room. */
internal data class GridPlan(val rows: Int, val perPage: Int, val pages: Int, val pager: Boolean)

internal object GridPaging {
    /**
     * The grid of [count] entries in a room of [height] with rows of [rowHeight] and [gap] between them, [columns] to a row. All entries when they fit;
     * else the pager takes one row and the rest is the page (at least one row, so a page never has less than [columns] entries).
     */
    fun plan(height: Float, rowHeight: Float, gap: Float, columns: Int, count: Int): GridPlan {
        val cols = columns.coerceAtLeast(1)
        fun rowsIn(room: Float) = floor((room + gap) / (rowHeight + gap)).toInt().coerceAtLeast(1)
        val needed = ceil(count / cols.toFloat()).toInt().coerceAtLeast(1)
        if (rowsIn(height) >= needed) return GridPlan(needed, count.coerceAtLeast(1), 1, false)
        val rows = rowsIn(height - rowHeight - gap)
        val perPage = rows * cols
        return GridPlan(rows, perPage, ceil(count / perPage.toFloat()).toInt().coerceAtLeast(1), true)
    }

    /**
     * How many columns (at most [max]) a grid in [width] can have so that the widest of [labelWidths] still fits one line of a button: the buttons add
     * [chrome] (padding and icon) to their label and [gap] separates them. One column when even that does not.
     */
    fun columns(labelWidths: List<Float>, width: Float, gap: Float, chrome: Float, max: Int): Int {
        val widest = (labelWidths.maxOrNull() ?: 0f) + chrome
        for (columns in max downTo 2) if ((width - gap * (columns - 1)) / columns >= widest) return columns
        return 1
    }
}

/** One entry of a [PagedGrid]: a touch button that runs [onClick] (the stream menu's own handler); [selected] gives it the accent outline. */
internal class GridItem(val label: String, val icon: String? = null, val selected: Boolean = false, val onClick: () -> Unit)

/**
 * The entries as a grid that fills the room it gets ([FitScope.fills] gives it the height the other blocks leave): [maxColumns] at most, fewer when the
 * longest label does not fit, one page when everything fits and Previous / Next with the page count otherwise.
 */
@Composable
internal fun PagedGrid(items: List<GridItem>, modifier: Modifier = Modifier, maxColumns: Int = 3) {
    val fit = rememberFit()
    val colors = LocalDelosColors.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = GRID_GAP.px
        val rowHeight = MIN_TARGET.px
        val width = fit.panel.width - 2 * CompanionFit.MARGIN - 2 * CompanionFit.cardPadding(fit.panel)
        val labelWidths = items.map { fit.metrics.width(it.label) + if (it.icon != null) ICON_FIT else 0f }
        val columns = GridPaging.columns(labelWidths, width, GRID_GAP, BUTTON_CHROME, maxColumns)
        val plan = GridPaging.plan(maxHeight.value, rowHeight.value, gap.value, columns, items.size)
        var page by remember(items.size, columns, plan.perPage) { mutableIntStateOf(0) }
        val current = page.coerceIn(0, plan.pages - 1)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
            items.drop(current * plan.perPage).take(plan.perPage).chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(rowHeight), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach {
                        CompanionButton(it.label, it.onClick, Modifier.weight(1f).fillMaxHeight(), icon = it.icon, selected = it.selected, textSize = MIN_TEXT)
                    }
                    repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                }
            }
            if (plan.pager) {
                Box(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().height(rowHeight), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                    CompanionButton(stringResource(R.string.companion_pager_previous), { page = current - 1 }, Modifier.weight(1f).fillMaxHeight(), icon = Symbols.CHEVRON_LEFT, enabled = current > 0, textSize = MIN_TEXT)
                    BodyText("${current + 1} / ${plan.pages}", color = colors.text2, maxLines = 1) // l10n-ignore
                    CompanionButton(stringResource(R.string.companion_pager_next), { page = current + 1 }, Modifier.weight(1f).fillMaxHeight(), icon = Symbols.CHEVRON_RIGHT, enabled = current < plan.pages - 1, textSize = MIN_TEXT)
                }
            }
        }
    }
}

/** The gap between the buttons of a grid, in stage px. */
private const val GRID_GAP = 8f

/** What a button adds to its label's width, in fit px: its padding at both sides (a little over) and the gap to its icon. */
private const val BUTTON_CHROME = 48f

/** The icon of a button and its gap, in fit px. */
private const val ICON_FIT = 48f
