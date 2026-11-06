package com.limelight.shell.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.limelight.shell.data.GameGenre
import com.limelight.shell.data.GameMetadata
import com.limelight.shell.data.HdrFilter
import com.limelight.shell.data.LengthFilter
import com.limelight.shell.data.Library
import com.limelight.shell.data.LocalGameMetadata
import com.limelight.shell.data.MetadataFilters
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.EaseOut
import com.limelight.shell.ui.dialogEnter
import com.limelight.shell.ui.dialogExit
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.FocusRing
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.rememberMarginBringIntoView
import com.limelight.shell.ui.PillButton
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.genreIcon
import com.limelight.shell.ui.themeIcon
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.backdropBlur
import com.limelight.shell.ui.fadeEdges
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.glass
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import dev.chrisbanes.haze.HazeState

private fun countGames(games: List<GameMetadata?>, entries: (GameMetadata) -> List<GameGenre>): Map<Int, Int> {
    val counts = HashMap<Int, Int>()
    for (game in games) {
        if (game == null) continue
        for (id in entries(game).map { it.id }.toSet()) counts[id] = (counts[id] ?: 0) + 1
    }
    return counts
}

/** How many games carry each genre (by genre id); a game lists a genre at most once. */
internal fun genreCounts(games: List<GameMetadata?>): Map<Int, Int> =
    countGames(games) { it.candidate?.genres.orEmpty() }

/** How many games carry each theme (by theme id); a game lists a theme at most once. */
internal fun themeCounts(games: List<GameMetadata?>): Map<Int, Int> =
    countGames(games) { it.candidate?.themes.orEmpty() }

/** The live result line of the filter modal: "27 of 55 games", or "All 55 games" without a filter. */
internal fun filterResultLine(shown: Int, total: Int, active: Boolean): String {
    val games = if (total == 1) "game" else "games"
    return if (active) "$shown of $total $games" else "All $total $games"
}

/** The home hint bar's label for Y: "Filter", with the number of narrowing criteria while any is set. */
internal fun filterHintLabel(filters: MetadataFilters): String =
    if (filters.active) "Filter • ${filters.activeCount} active" else "Filter"

internal fun hdrFilterLabel(filter: HdrFilter): String = when (filter) {
    HdrFilter.ANY -> "All"
    HdrFilter.HDR -> "Native HDR"
    HdrFilter.SDR -> "SDR"
    HdrFilter.UNKNOWN -> UNVERIFIED_HDR_LABEL
}

internal fun lengthFilterLabel(filter: LengthFilter): String = when (filter) {
    LengthFilter.ANY -> "Any"
    LengthFilter.UNDER_10 -> "<10 h"
    LengthFilter.FROM_10_TO_30 -> "10–30 h"
    LengthFilter.FROM_30_TO_60 -> "30–60 h"
    LengthFilter.OVER_60 -> "60+ h"
    LengthFilter.UNKNOWN -> "Unknown"
}

/** The icons of the home badges (sparkle, sun and question mark), so a filter and its badge look alike. */
private fun hdrFilterIcon(filter: HdrFilter): String? = when (filter) {
    HdrFilter.ANY -> null
    HdrFilter.HDR -> Symbols.ANIMATION
    HdrFilter.SDR -> Symbols.HDR_ON
    HdrFilter.UNKNOWN -> Symbols.HELP
}

/**
 * The library filter modal (Y on the home screen): a centered glass panel with genre, theme, HDR support
 * and duration chips. Choices apply at once; the focus stays inside while it is open and starts on the selected
 * genre. [ready] is the library to count in (null while it is not available, which also hides the panel).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterModalLayer(visible: Boolean, ready: Library.Ready?, haze: HazeState, reducedMotion: Boolean, onClose: () -> Unit) {
    // Keep the last library while animating out
    var shown by remember { mutableStateOf(ready) }
    if (ready != null) shown = ready
    val open by rememberUpdatedState(visible)
    val enter: EnterTransition = dialogEnter(reducedMotion)
    val exit: ExitTransition = dialogExit(reducedMotion)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible, enter = enter, exit = exit) {
            val library = shown ?: return@AnimatedVisibility
            FilterPanel(library, open, haze, onClose)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun FilterPanel(ready: Library.Ready, open: Boolean, haze: HazeState, onClose: () -> Unit) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val metadata = LocalGameMetadata.current
    val k = stage.dpPerPx * LocalDensity.current.density
    val shape = RoundedCornerShape(34.px)
    val chipScroll = rememberScrollState()
    val bringIntoView = rememberMarginBringIntoView(chipScroll, before = 150f * k, after = 56f * k, stagePx = k)
    val filters = metadata.filters
    val apps = ready.apps

    // Reading the entries here makes the counts follow the metadata as it arrives
    val games = apps.map { metadata.entry(ready.pc, it).data }
    val genres = remember(games) { metadata.genres(ready.pc, apps) }
    val themes = remember(games) { metadata.themes(ready.pc, apps) }
    val counts = remember(games) { genreCounts(games) }
    val themeCount = remember(games) { themeCounts(games) }
    val matching = games.indices.count { filters.matches(apps[it], games[it]) }

    // One requester per genre chip: the first focus goes to the selected one, a reset back to "All"
    val genreRequesters = remember { HashMap<Int, FocusRequester>() }
    fun genreRequester(id: Int?) = genreRequesters.getOrPut(id ?: 0) { FocusRequester() }
    val startGenre = filters.genreId?.takeIf { id -> genres.any { it.id == id } }
    // The focus goes in every time the modal opens, also when it is reopened while it still animates out:
    // the panel then stays composed, so a one-time request would leave the focus on the dashboard behind it.
    if (open) InitialFocus(genreRequester(startGenre))

    // Reset disables itself once nothing is filtered; if it had the focus, the focus goes back up to "All"
    var panelFocused by remember { mutableStateOf(false) }
    var wasActive by remember { mutableStateOf(filters.active) }
    LaunchedEffect(filters.active) {
        if (wasActive && !filters.active) {
            withFrameNanos { }
            if (!panelFocused) runCatching { genreRequester(null).requestFocus() }
        }
        wasActive = filters.active
    }

    Column(
        Modifier
            .width(1240.px)
            .heightIn(max = 820.px)
            // box-shadow: 0 40px 100px -30px rgba(0, 0, 0, .7), like the dialogs
            .dropShadow(shape) {
                radius = 100f * k
                spread = -30f * k
                offset = Offset(0f, 40f * k)
                color = Color.Black
                alpha = .7f
            }
            .clip(shape)
            .backdropBlur(haze, 24f, colors.surface3)
            .border(1.px, colors.line, shape)
            .padding(horizontal = 40.px, vertical = 44.px)
            .onFocusChanged { panelFocused = it.hasFocus }
            .focusProperties { onExit = { if (open) cancelFocusChange() } }
            .focusGroup(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.px, end = 12.px, bottom = 26.px), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Filter library", style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 40.tpx))
            Spacer(Modifier.weight(1f))
            val result = filterResultLine(matching, apps.size, filters.active)
            BasicText(
                result,
                Modifier
                    .clip(CircleShape)
                    .background(if (filters.active) colors.accent.copy(alpha = .16f) else colors.surface)
                    .border(1.px, if (filters.active) colors.accent.copy(alpha = .45f) else colors.line, CircleShape)
                    .padding(horizontal = 26.px, vertical = 10.px),
                style = TextStyle(color = if (filters.active) colors.accent3 else colors.text2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 24.tpx),
            )
        }
        // The chips scroll when the genres need more room than the panel has; the focused one stays in view
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .fadeEdges(stage, top = 20f, bottom = 40f)
                .verticalScroll(chipScroll)
                .padding(start = 12.px, end = 12.px, top = 24.px, bottom = 48.px),
            verticalArrangement = Arrangement.spacedBy(34.px),
        ) {
            FilterSection(Symbols.SPORTS_ESPORTS, "Genre") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.px), verticalArrangement = Arrangement.spacedBy(14.px)) {
                    FilterChip(
                        "All", filters.genreId == null, { metadata.updateFilters(metadata.filters.copy(genreId = null)) },
                        Modifier.focusRequester(genreRequester(null)), count = apps.size,
                    )
                    genres.forEach { genre ->
                        key(genre.id) {
                            FilterChip(
                                genre.name, filters.genreId == genre.id, { metadata.updateFilters(metadata.filters.copy(genreId = genre.id)) },
                                Modifier.focusRequester(genreRequester(genre.id)), icon = genreIcon(genre.id), count = counts[genre.id] ?: 0,
                            )
                        }
                    }
                }
            }
            FilterDivider()
            // Without any theme in the library there is nothing to choose (a theme that was chosen stays visible)
            if (themes.isNotEmpty() || filters.themeId != null) {
                FilterSection(Symbols.THEME_DRAMA, "Theme") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.px), verticalArrangement = Arrangement.spacedBy(14.px)) {
                        FilterChip(
                            "All", filters.themeId == null, { metadata.updateFilters(metadata.filters.copy(themeId = null)) },
                            count = apps.size,
                        )
                        themes.forEach { theme ->
                            key(theme.id) {
                                FilterChip(
                                    theme.name, filters.themeId == theme.id, { metadata.updateFilters(metadata.filters.copy(themeId = theme.id)) },
                                    icon = themeIcon(theme.id), count = themeCount[theme.id] ?: 0,
                                )
                            }
                        }
                    }
                }
                FilterDivider()
            }
            FilterSection(Symbols.HDR_ON, "HDR support") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.px), verticalArrangement = Arrangement.spacedBy(14.px)) {
                    HdrFilter.entries.forEach { option ->
                        FilterChip(
                            hdrFilterLabel(option), filters.hdr == option, { metadata.updateFilters(metadata.filters.copy(hdr = option)) },
                            icon = hdrFilterIcon(option),
                        )
                    }
                }
            }
            FilterDivider()
            FilterSection(Symbols.HISTORY, "Duration (HowLongToBeat)") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.px), verticalArrangement = Arrangement.spacedBy(14.px)) {
                    LengthFilter.entries.forEach { option ->
                        FilterChip(
                            lengthFilterLabel(option), filters.length == option, { metadata.updateFilters(metadata.filters.copy(length = option)) },
                        )
                    }
                }
            }
        }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 30.px, start = 12.px, end = 12.px),
            horizontalArrangement = Arrangement.spacedBy(24.px, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val active = filters.active
            PillButton(
                "Reset", Symbols.REFRESH, { if (metadata.filters.active) metadata.updateFilters(MetadataFilters()) },
                Modifier.alpha(if (active) 1f else .4f).then(if (active) Modifier else Modifier.focusProperties { canFocus = false }),
            )
            PillButton("Done", Symbols.CHECK, onClose, primary = true, clickCue = Cue.BACK)
        }
    }
}

@Composable
private fun FilterSection(icon: String, title: String, content: @Composable () -> Unit) {
    val colors = LocalDelosColors.current
    Column(verticalArrangement = Arrangement.spacedBy(22.px)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.px)) {
            Box(Modifier.size(60.px).clip(CircleShape).background(colors.accent.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                Symbol(icon, 32.tpx, colors.accent2, filled = true)
            }
            BasicText(title, style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 29.tpx))
        }
        content()
    }
}

/** The thin line between two filter sections. */
@Composable
private fun FilterDivider() {
    Box(Modifier.fillMaxWidth().height(1.px).background(LocalDelosColors.current.line))
}

/** A selectable chip: filled with the accent while chosen, glass otherwise; the focus ring marks the cursor. */
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: String? = null, count: Int? = null) {
    val colors = LocalDelosColors.current
    var focused by remember { mutableStateOf(false) }
    val content = if (selected) colors.onAccent else colors.text
    Row(
        modifier
            .focusEffects(
                onClick = onClick, corner = 999f, focusScale = 1.05f, ring = FocusRing(3f, 3f), glow = null,
                onFocusChange = { focused = it },
            )
            .clip(CircleShape)
            .then(if (selected) Modifier.background(colors.accent) else Modifier.glass(colors.surface).border(1.px, colors.line, CircleShape))
            .heightIn(min = 60.px)
            .padding(horizontal = 30.px, vertical = 12.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.px),
    ) {
        if (icon != null) Symbol(icon, 28.tpx, content, filled = selected || focused)
        BasicText(label, style = TextStyle(color = content, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 24.tpx))
        if (count != null) BasicText("$count", style = TextStyle(color = content.copy(alpha = .6f), fontFamily = Figtree, fontSize = 22.tpx))
    }
}
