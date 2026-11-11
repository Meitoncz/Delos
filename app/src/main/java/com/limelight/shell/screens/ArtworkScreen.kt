package com.limelight.shell.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.limelight.shell.ContextMenu
import com.limelight.shell.DialogField
import com.limelight.shell.DialogSpec
import com.limelight.shell.FocusTracker
import com.limelight.shell.Layer
import com.limelight.shell.LocalFocusTracker
import com.limelight.shell.LocalShell
import com.limelight.shell.MenuItem
import com.limelight.shell.Screen
import com.limelight.shell.ShellState
import com.limelight.shell.data.ActiveFilter
import com.limelight.shell.data.App
import com.limelight.shell.data.ArtFilterRules
import com.limelight.shell.data.ArtFilters
import com.limelight.shell.data.ArtKind
import com.limelight.shell.data.ArtSort
import com.limelight.shell.data.ArtType
import com.limelight.shell.data.ArtworkInfo
import com.limelight.shell.data.ArtworkStore
import com.limelight.shell.data.BundledArtwork
import com.limelight.shell.data.LocalArtwork
import com.limelight.shell.data.LocalBoxArt
import com.limelight.shell.data.NONE
import com.limelight.shell.data.Pc
import com.limelight.shell.data.SgdbGame
import com.limelight.shell.data.SgdbImage
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.shell.data.ArtworkFact
import com.limelight.shell.data.sgdbErrorText
import com.limelight.shell.ui.textResources
import com.limelight.shell.data.SgdbRetry
import com.limelight.shell.data.Thumbnails
import com.limelight.shell.data.rememberArtworkResult
import com.limelight.shell.data.rememberBoxArt
import com.limelight.shell.data.rememberThumbnail
import com.limelight.shell.ui.AnimatedDrawableView
import com.limelight.shell.ui.AnimatedPlayback
import com.limelight.shell.ui.AnimatedSource
import com.limelight.shell.ui.AnimationTicker
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.Feedback
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.FocusGlow
import com.limelight.shell.ui.FocusRing
import com.limelight.shell.ui.FocusTransferTarget
import com.limelight.shell.ui.Glyph
import com.limelight.shell.ui.GlyphButton
import com.limelight.shell.ui.HeroMotion
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.LocalStage
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxHeight
import com.limelight.shell.ui.Motion
import com.limelight.shell.ui.PillButton
import com.limelight.shell.ui.PlaceholderArt
import com.limelight.shell.ui.RowGlideHost
import com.limelight.shell.ui.ScrollRow
import com.limelight.shell.ui.SelectionPillHost
import com.limelight.shell.ui.Spinner
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.fadeEdges
import com.limelight.shell.ui.focusRoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.text.rememberTextMeasurer
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.glass
import com.limelight.shell.ui.popEnter
import com.limelight.shell.ui.popExit
import com.limelight.shell.ui.px
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.limelight.shell.ui.StageShape
import com.limelight.shell.ui.compactScale
import com.limelight.shell.ui.cpx
import com.limelight.shell.ui.targetPx
import com.limelight.shell.ui.rememberAnimatedDrawable
import com.limelight.shell.ui.rememberAnimationTicker
import com.limelight.shell.ui.rememberFocusTransfer
import com.limelight.shell.ui.rememberMarginBringIntoView
import com.limelight.shell.ui.staggerEntrance
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The tab of each kind on the artwork screen, with the columns of its grid (the prototype's: 7 covers, 4 wide covers, 3
 * backgrounds, 5 logos per row) and the shape of its images. Every sentence that names the kind has a resource of its own per
 * tab, so each language inflects it: [count] ("41 covers"), [countAll] ("All 41 covers"), [countOf] ("10 of 41 covers"), [none]
 * (no image matches the filters), [has] and [unknown] (why), [empty] (SteamGridDB has none), [downloading] and [updated].
 */
private enum class ArtTab(
    val kind: ArtKind,
    @StringRes val label: Int,
    @PluralsRes val count: Int, @PluralsRes val countAll: Int, @PluralsRes val countOf: Int,
    @StringRes val none: Int, @PluralsRes val has: Int, @StringRes val unknown: Int, @StringRes val empty: Int,
    @StringRes val downloading: Int, @StringRes val updated: Int,
    val icon: String, val columns: Int, val ratio: Float,
) {
    COVER(
        ArtKind.COVER, R.string.artwork_tab_cover, R.plurals.artwork_count_cover, R.plurals.artwork_count_all_cover, R.plurals.artwork_count_of_cover,
        R.string.artwork_filtered_none_cover, R.plurals.artwork_filtered_has_cover, R.string.artwork_filtered_unknown_cover, R.string.artwork_empty_cover,
        R.string.artwork_downloading_cover, R.string.artwork_updated_cover, Symbols.IMAGE, 7, 600f / 900f,
    ),
    WIDE(
        ArtKind.WIDE, R.string.artwork_tab_wide, R.plurals.artwork_count_wide, R.plurals.artwork_count_all_wide, R.plurals.artwork_count_of_wide,
        R.string.artwork_filtered_none_wide, R.plurals.artwork_filtered_has_wide, R.string.artwork_filtered_unknown_wide, R.string.artwork_empty_wide,
        R.string.artwork_downloading_wide, R.string.artwork_updated_wide, Symbols.ASPECT_RATIO, 4, 920f / 430f,
    ),
    HERO(
        ArtKind.HERO, R.string.artwork_tab_hero, R.plurals.artwork_count_hero, R.plurals.artwork_count_all_hero, R.plurals.artwork_count_of_hero,
        R.string.artwork_filtered_none_hero, R.plurals.artwork_filtered_has_hero, R.string.artwork_filtered_unknown_hero, R.string.artwork_empty_hero,
        R.string.artwork_downloading_hero, R.string.artwork_updated_hero, Symbols.WALLPAPER, 3, 1920f / 620f,
    ),
    LOGO(
        ArtKind.LOGO, R.string.artwork_tab_logo, R.plurals.artwork_count_logo, R.plurals.artwork_count_all_logo, R.plurals.artwork_count_of_logo,
        R.string.artwork_filtered_none_logo, R.plurals.artwork_filtered_has_logo, R.string.artwork_filtered_unknown_logo, R.string.artwork_empty_logo,
        R.string.artwork_downloading_logo, R.string.artwork_updated_logo, Symbols.LOGO, 5, 2f,
    ),
    ;

    fun step(dir: Int) = entries[Math.floorMod(ordinal + dir, entries.size)]

    /** The columns of the grid [gridWidth] stage px wide: [columns] at the 1720 of a 1920 stage, in proportion otherwise. */
    fun columnsFor(gridWidth: Float) = (columns * gridWidth / 1720f).roundToInt().coerceAtLeast(1)

    /** The narrowest tile of the compact grid ([ArtGridLayout.compact]): about 0.7 of the 1920 stage's tile, so a small panel shows more of them. */
    val compactMin: Float
        get() = when (this) {
            COVER -> ArtworkLayout.COVER_MIN
            WIDE -> ArtworkLayout.WIDE_MIN
            HERO -> ArtworkLayout.HERO_MIN
            LOGO -> ArtworkLayout.LOGO_MIN
        }

    /** "10 of 41 covers" ([countOf]) when the unfiltered total differs, else "41 covers"; [all] says "All 41 covers" for an unfiltered list. */
    fun resultLine(res: Resources, shown: Int, total: Int?, all: Boolean): String = when {
        total != null && total != shown -> res.getQuantityString(countOf, total, shown, total)
        all -> res.getQuantityString(countAll, shown, shown)
        else -> res.getQuantityString(count, shown, shown)
    }
}

/** What the grid of a tab shows. [Ready.total] is the unfiltered count when it is known (the unfiltered list was loaded). */
private sealed interface Choices {
    data object Loading : Choices
    data object NoKey : Choices
    data object NoGame : Choices
    data class Failed(val error: Throwable) : Choices
    data class Ready(val tab: ArtTab, val gameId: Int, val images: List<SgdbImage>, val total: Int?) : Choices
}

/** Which image the info strip describes: the first choice or one of SteamGridDB's. */
private sealed interface Described {
    data object FirstChoice : Described
    data class Image(val image: SgdbImage) : Described
}

private val GridItemNone = "none"

/**
 * The artwork of one game (the game's options → Artwork), prototype variant B: a tab per kind (cover, wide cover, background,
 * logo, switched with L1 / R1) over a toolbar (the matched SteamGridDB game, search for another name with X, the filter sheet
 * with Y), the active filters as removable chips, the full-width grid of every SteamGridDB image to choose from (the first
 * choice being the built-in/PC fallback, or the name instead of a logo) and a strip about the focused image. Animated images
 * are marked and play in their tiles while they are on screen. Automatic downloads take the best-rated still image of each kind.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtworkScreen(screen: Screen.Artwork) {
    val shell = LocalShell.current
    val res = textResources()
    val store = LocalArtwork.current
    val feedback = LocalFeedback.current
    val tracker = LocalFocusTracker.current
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val scope = rememberCoroutineScope()
    val pc = screen.pc
    val app = screen.app
    if (BundledArtwork.kind(app) != null) {
        BuiltInArtworkScreen(pc, app)
        return
    }
    var tab by remember { mutableStateOf(ArtTab.COVER) }
    // Per tab, for as long as this screen is open
    val filtersByTab = remember { mutableStateMapOf<ArtTab, ArtFilters>() }
    val filters = ArtFilterRules.normalized(tab.kind, filtersByTab[tab] ?: ArtFilters())
    var sheetOpen by remember { mutableStateOf(false) }
    val loadedLists = remember { HashMap<String, List<SgdbImage>>() }
    val version = store.version.intValue
    val match = remember(version) { store.matched(pc, app) }
    var busy by remember { mutableStateOf(false) }

    // Never looked up yet: find the game first (and download its best-rated images like the automatic run)
    LaunchedEffect(store.enabled) {
        if (store.enabled && store.eligible(app) && !store.searched(pc, app)) {
            busy = true
            runCatching { SgdbRetry.run { store.fetch(pc, app) } }.onFailure { shell.showToast(sgdbErrorText(res, it, R.string.artwork_unreachable), Symbols.ERROR) }
            busy = false
        }
    }

    // The grid keeps its images while a filter loads (the new list replaces them); another tab or game starts over
    var choices by remember { mutableStateOf<Choices>(Choices.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(tab, match?.id, store.enabled, busy, filters) {
        val gameId = match?.id
        when {
            !store.enabled -> choices = Choices.NoKey
            busy -> choices = Choices.Loading
            gameId == null -> choices = Choices.NoGame
            else -> {
                // The sort is applied here to what was loaded, so changing it asks nothing
                val key = "$gameId|${ArtFilterRules.cacheKey(tab.kind, filters)}"
                fun total() = loadedLists["$gameId|${ArtFilterRules.cacheKey(tab.kind, ArtFilters())}"]?.size
                fun ready(list: List<SgdbImage>) = Choices.Ready(tab, gameId, ArtFilterRules.sort(list, filters.sort), total())
                val cached = loadedLists[key]
                if (cached != null) {
                    choices = ready(cached)
                    return@LaunchedEffect
                }
                val previous = choices as? Choices.Ready
                if (previous == null || previous.tab != tab || previous.gameId != gameId) choices = Choices.Loading
                refreshing = true
                try {
                    choices = runCatching { SgdbRetry.run { store.choices(pc, app, tab.kind, filters.copy(sort = ArtSort.SCORE)) } }
                        .map { list -> loadedLists[key] = list; ready(list) }
                        .getOrElse { Choices.Failed(it) }
                } finally {
                    refreshing = false
                }
            }
        }
    }

    val focusTransfer = rememberFocusTransfer()
    val first = remember { FocusRequester() }
    val searchRequester = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    val window = LocalWindowInfo.current
    val currentChoices by rememberUpdatedState(choices)
    val chosen = remember(version, tab) { store.chosen(pc, app, tab.kind) }
    val readyKey = (choices as? Choices.Ready)?.let { "ready:${it.tab}:${it.gameId}" } ?: choices::class.simpleName
    // Where the focus starts: the image in use (the first choice when none is), after a tab, or a new list
    LaunchedEffect(tab, readyKey) {
        snapshotFlow { window.isWindowFocused }.first { it }
        withFrameNanos { }
        when (val c = currentChoices) {
            is Choices.Ready -> {
                val inUse = c.images.indexOfFirst { it.id == chosen }
                grid.scrollToItem(if (inUse >= 0) inUse + 1 else 0)
                withFrameNanos { }
                feedback.silenceNextFocus()
                runCatching { first.requestFocus() }
            }
            is Choices.NoGame, is Choices.Failed -> {
                feedback.silenceNextFocus()
                runCatching { searchRequester.requestFocus() }
            }
            else -> Unit
        }
    }

    var described by remember(tab) { mutableStateOf<Described?>(null) }

    // The focus before the filter sheet opened, to return to it
    var origin by remember { mutableStateOf<FocusRequester?>(null) }
    fun restoreFocus() {
        val target = origin
        scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            feedback.silenceNextFocus()
            val ok = target != null && runCatching { target.requestFocus() }.getOrDefault(false)
            if (!ok) runCatching { first.requestFocus() }
        }
    }
    fun closeSheet() {
        if (!sheetOpen) return
        feedback.play(Cue.BACK)
        sheetOpen = false
        restoreFocus()
    }
    fun openSheet() {
        if (match == null || !store.enabled || sheetOpen) return
        feedback.play(Cue.OPEN)
        origin = tracker.requester
        sheetOpen = true
    }
    fun openSearch() {
        if (!store.enabled) return
        searchDialog(res, scope, shell, tracker, feedback, store, pc, app, match?.name?.ifEmpty { null } ?: app.name)
    }
    fun resetFilters() {
        if (!filters.active) return
        feedback.play(Cue.SELECT)
        filtersByTab[tab] = ArtFilters()
        shell.showToast(res.getString(R.string.filters_art_reset_toast), Symbols.RESET)
    }
    fun selectTab(next: ArtTab) {
        focusTransfer.request()
        if (sheetOpen) sheetOpen = false
        tab = next
    }

    val toggleSheet by rememberUpdatedState({ if (sheetOpen) closeSheet() else openSheet() })
    val xAction by rememberUpdatedState({ if (sheetOpen) resetFilters() else openSearch() })
    DisposableEffect(shell) {
        val shoulder: (Int) -> Unit = { dir ->
            if (!sheetOpen) {
                focusTransfer.request()
                tab = tab.step(dir)
                feedback.play(Cue.MOVE)
            }
        }
        shell.onShoulder = shoulder
        shell.onArtworkFilter = { toggleSheet() }
        shell.onArtworkSearch = { xAction() }
        onDispose {
            if (shell.onShoulder === shoulder) shell.onShoulder = null
            shell.onArtworkFilter = null
            shell.onArtworkSearch = null
            shell.artworkSheetOpen = false
        }
    }
    LaunchedEffect(sheetOpen) { shell.artworkSheetOpen = sheetOpen }
    // B closes the sheet first (registered after the shell's own, so it wins)
    BackHandler(enabled = sheetOpen) { closeSheet() }

    // Animated tiles play while they are on screen and nothing covers or stops them
    val context = LocalContext.current
    val animatorsOff = remember(context) { HeroMotion.animatorsOff(context) }
    val motion = AnimatedPlayback.shouldPlay(
        visible = !sheetOpen && shell.layer == null, foreground = true, streaming = shell.launch != null,
        reducedMotion = LocalReducedMotion.current, animatorsOff = animatorsOff,
    )
    val animatedIds = remember(choices) { (choices as? Choices.Ready)?.images?.filter { it.animated }?.map { it.id }?.toSet().orEmpty() }
    val visibleAnimated by remember(grid, animatedIds) {
        derivedStateOf { grid.layoutInfo.visibleItemsInfo.mapNotNull { info -> (info.key as? Int)?.takeIf { it in animatedIds } } }
    }
    val playing = if (motion) visibleAnimated.toSet() else emptySet()
    val ticker = rememberAnimationTicker(playing.isNotEmpty())

    val compact = stage.shape == StageShape.COMPACT
    val margin = SettingsLayout.margin(stage)
    val contentWidth = stage.stageWidth - 2 * margin
    // Compact: no title. The game chip and the tabs (flush right) share the first row, the filter strip and the two pills the second; the grid
    // and the info strip follow at full width, and a stage too short for a row of covers above the info strip has none
    val bottom = SettingsLayout.bottom(stage.textScale)
    val toolbarY = if (compact) ArtworkLayout.TOOLBAR_Y else 206f
    val gridY = if (compact) ArtworkLayout.GRID_Y else 324f
    val info = !compact || ArtworkLayout.hasInfo(contentWidth, stage.stageHeight, bottom)
    val infoY = if (compact) stage.stageHeight - bottom - ArtworkLayout.INFO_HEIGHT else stage.stageHeight - 194f
    val gridHeight = if (compact) ArtworkLayout.viewport(stage.stageHeight, bottom, info) else (infoY - 10f - gridY).coerceAtLeast(0f)
    // The tile is what the columns leave once the room between tiles covers the focused tile's growth, its ring and 12 px (every shape)
    val art = if (compact) ArtGridLayout.compact(contentWidth, gridHeight, tab.ratio, tab.compactMin)
    else ArtGridLayout.grid(contentWidth, tab.ratio, tab.columnsFor(stage.stageWidth - 200f))
    val ready = choices as? Choices.Ready
    val tabs: @Composable () -> Unit = {
        ShoulderLabel("L1")
        ArtTab.entries.forEach { next ->
            TabPill(next.icon, stringResource(next.label), tab == next, Modifier.padding(horizontal = (if (compact) 3 else 4).px), collapse = compact) {
                if (next != tab) selectTab(next) else runCatching { first.requestFocus() }
            }
        }
        ShoulderLabel("R1")
    }
    val gameChip: @Composable (Modifier) -> Unit = { chipModifier ->
        GameChip(match, busy, app.name, ready?.let { it.total ?: it.images.size.takeIf { _ -> !filters.active } }, tab, chipModifier)
    }
    // Search for another name (X) and the filter sheet (Y)
    val searchPill: @Composable (String) -> Unit = { label ->
        val searchEnabled = store.enabled
        PillButton(
            label, Symbols.SEARCH_NAME, { openSearch() },
            Modifier.focusRequester(searchRequester).alpha(if (searchEnabled) 1f else .4f)
                .then(if (searchEnabled) Modifier else Modifier.focusProperties { canFocus = false }),
            clickCue = Cue.OPEN, compact = true, trailing = { GlyphButton(Glyph.X, 32) },
        )
    }
    val filterPill: @Composable () -> Unit = {
        val filterEnabled = store.enabled && match != null
        PillButton(
            stringResource(R.string.artwork_filter), Symbols.FILTER, { openSheet() },
            Modifier.alpha(if (filterEnabled) 1f else .4f).then(if (filterEnabled) Modifier else Modifier.focusProperties { canFocus = false }),
            clickCue = null, compact = true,
            trailing = {
                if (filters.active) CountBadge(filters.activeCount)
                GlyphButton(Glyph.Y, 32)
            },
        )
    }
    // The result line and the active filters, removable one by one
    val strip: @Composable (Modifier, Boolean) -> Unit = { stripModifier, scrolls ->
        if (ready != null) {
            FilterStrip(
                tab, filters, ready, refreshing,
                onClear = { chip -> filtersByTab[tab] = ArtFilterRules.clear(filters, chip) },
                onReset = ::resetFilters, modifier = stripModifier, scrolls = scrolls,
            )
        }
    }
    Box(Modifier.fillMaxSize()) {
        FocusTransferTarget(focusTransfer)
        if (compact) {
            Row(
                Modifier.offset(margin.px, ArtworkLayout.TABS_Y.px).width(contentWidth.px).height(ArtworkLayout.TABS_HEIGHT.px),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                gameChip(Modifier.weight(1f))
                // 24 px from the chip to the L1 badge, whose own outset is part of it; R1's outset is moved out, so its edge is the content's
                SelectionPillHost(
                    Modifier.padding(start = (24f - 2f * SHOULDER_OUTSET).px).offset(x = SHOULDER_OUTSET.px).fillMaxHeight(),
                    content = tabs,
                )
            }
            // The Search pill takes the longest label the row has room for beside the strip's line; with filters on, the chips scroll and it is the shortest
            val labels = listOf(stringResource(R.string.artwork_search_other), stringResource(R.string.shell_hint_search_name), stringResource(R.string.artwork_search_action))
            val measurer = rememberTextMeasurer()
            val k = stage.dpPerPx * LocalDensity.current.density
            val pillText = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 24.tpx)
            fun width(text: String, style: TextStyle) = measurer.measure(text, style, maxLines = 1).size.width / k
            // A toolbar pill around its label: 28 px padding on each side, the 30 px icon, two 12 px gaps and the 32 px button glyph
            val pillChrome = 2 * 28f + 30f * stage.textScale + 2 * 12f + 32f
            val filterWidth = pillChrome + width(stringResource(R.string.artwork_filter), pillText) + if (filters.active) 32f + 12f else 0f
            val stripWidth = when {
                ready == null -> 0f
                filters.active -> Float.MAX_VALUE / 4f
                else -> FilterStripMetrics.lineWidth(
                    width(tab.resultLine(res, ready.images.size, ready.total, all = true), FilterStripMetrics.countStyle()),
                    width(stringResource(filters.sort.first), FilterStripMetrics.sortStyle()),
                )
            }
            val label = labels[ArtToolbarLayout.label(contentWidth, stripWidth + pillChrome + filterWidth + 2 * ArtworkLayout.PILL_GAP, labels.map { width(it, pillText) })]
            Row(
                Modifier.offset(margin.px, toolbarY.px).width(contentWidth.px).height(ArtworkLayout.TOOLBAR_HEIGHT.px),
                horizontalArrangement = Arrangement.spacedBy(ArtworkLayout.PILL_GAP.px), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).fillMaxHeight()) { strip(Modifier.fillMaxSize(), true) }
                searchPill(label)
                filterPill()
            }
        } else {
            Column(Modifier.offset(margin.px, 104.px).width(560.px)) {
                BasicText(
                    stringResource(R.string.artwork_header),
                    style = TextStyle(color = colors.accent2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx, letterSpacing = .14.em),
                )
                BasicText(
                    "SteamGridDB", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 40.tpx),
                )
            }
            // The tabs end exactly at the content's right edge (the Filter pill's, the info strip's), on every stage width
            TabRow(124f, 66.px, left = margin, right = stage.stageWidth - margin, centred = false, content = tabs)
            // Toolbar: the matched game, search for another name (X), the filter sheet (Y)
            Row(
                Modifier.offset(margin.px, toolbarY.px).width(contentWidth.px).height(64.px),
                horizontalArrangement = Arrangement.spacedBy(16.px), verticalAlignment = Alignment.CenterVertically,
            ) {
                gameChip(Modifier.weight(1f))
                searchPill(stringResource(R.string.artwork_search_other))
                filterPill()
            }
            strip(Modifier.offset(margin.px, 276.px).width(contentWidth.px).height(44.px), false)
        }

        // The grid (or why there is none): from under the filter strip to above the info strip, so a taller stage shows more rows. Its box
        // is the stage's width and the tiles are padded in by the margin: room inside the viewport for the focused tile's ring and glow
        Box(Modifier.offset(y = gridY.px).width(stage.stageWidth.px).height(gridHeight.px)) {
            when (val c = choices) {
                Choices.Loading -> GridMessage(Symbols.LOADING, stringResource(R.string.artwork_loading), null)
                Choices.NoKey -> GridMessage(Symbols.KEY, stringResource(R.string.artwork_no_key_title), stringResource(R.string.artwork_no_key_note))
                Choices.NoGame -> if (store.eligible(app)) GridMessage(Symbols.SEARCH, stringResource(R.string.artwork_no_game_title, app.name), stringResource(R.string.artwork_no_game_note))
                else GridMessage(Symbols.SEARCH, stringResource(R.string.artwork_choose_title, app.name), stringResource(R.string.artwork_choose_note, app.name))
                is Choices.Failed -> GridMessage(Symbols.ERROR, stringResource(R.string.artwork_unreachable), sgdbErrorText(res, c.error, R.string.artwork_unreachable))
                is Choices.Ready -> {
                    // The focused row keeps clear of the faded edges, like the settings rows; on the compact stage by what its ring and glow reach
                    val k = stage.dpPerPx * LocalDensity.current.density
                    val fadeTop = if (compact) ArtworkLayout.FADE_TOP else 30f
                    val fadeBottom = if (compact) ArtworkLayout.FADE_BOTTOM else 70f
                    val before = if (compact) art.padTop else 70f
                    val after = if (compact) (gridHeight - art.padTop - art.tileHeight).coerceIn(art.glowBottom, art.glowBottom + fadeBottom) else 110f
                    val bringIntoView = rememberMarginBringIntoView(grid, before = before * k, after = after * k, stagePx = k)
                    // The edges fade only while rows continue behind them: the ring and glow of the first and of the last row are not dimmed
                    val fadeEnd by animateFloatAsState(if (grid.canScrollForward) 1f else 0f, tween(Motion.FADE, easing = Motion.Ease), label = "gridFade")
                    // Takedown placeholders found while the thumbnails load disappear (the chosen image always stays)
                    val images = c.images.filter { it.id == chosen || !Thumbnails.isRemoved(it.previewUrl) }
                    val inUse = images.indexOfFirst { it.id == chosen }
                    val noneInUse = chosen == NONE || (chosen == null && c.images.isEmpty() && !filters.active)
                    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(art.columns),
                            state = grid,
                            modifier = Modifier.fillMaxSize().fadeEdges(
                                stage, top = fadeTop, bottom = fadeBottom,
                                topPx = { full -> if (grid.firstVisibleItemIndex == 0) grid.firstVisibleItemScrollOffset.toFloat() else full },
                                bottomPx = { full -> full * fadeEnd },
                            ),
                            contentPadding = if (compact) PaddingValues(start = margin.px, end = margin.px, top = art.padTop.px, bottom = art.padBottom.px)
                            else PaddingValues(start = margin.px, end = margin.px, top = 46.px, bottom = 96.px),
                            horizontalArrangement = Arrangement.spacedBy(art.gapX.px),
                            verticalArrangement = Arrangement.spacedBy(art.gapY.px),
                        ) {
                            item(key = GridItemNone) {
                                NoneTile(
                                    pc, app, tab, art.tileWidth, noneInUse, store.applying(pc, app, tab.kind) == NONE,
                                    Modifier.staggerEntrance(0).then(if (inUse < 0) Modifier.focusRequester(first) else Modifier),
                                    onFocused = { described = Described.FirstChoice },
                                ) { apply(res, scope, shell, feedback, store, pc, app, tab, null) }
                            }
                            itemsIndexed(images, key = { _, image -> image.id }) { index, image ->
                                ChoiceTile(
                                    image, tab, art.tileWidth, inUse = image.id == chosen, loading = store.applying(pc, app, tab.kind) == image.id,
                                    playing = image.id in playing, ticker = ticker,
                                    modifier = Modifier.staggerEntrance(if (index + 1 < 8) index + 1 else -1)
                                        .then(if (index == inUse) Modifier.focusRequester(first) else Modifier),
                                    onFocused = { described = Described.Image(image) },
                                ) { apply(res, scope, shell, feedback, store, pc, app, tab, image) }
                            }
                        }
                    }
                    if (c.images.isEmpty()) {
                        NoImages(tab, filters, c, app.name, match, onReset = ::resetFilters, onChange = ::openSheet)
                    }
                }
            }
        }

        if (info) {
            InfoStrip(
                described ?: Described.FirstChoice, tab, chosen,
                Modifier.offset(margin.px, infoY.px).width(contentWidth.px).height(ArtworkLayout.INFO_HEIGHT.px),
            )
        }

        // The filter sheet drops down from the toolbar over a dimmed page; the grid stays visible underneath. Compact: it lies over the
        // toolbar's row (its own count and Done stand in for it), so the options have the room down to the hint bar
        FilterSheetLayer(
            open = sheetOpen, tab = tab, filters = filters, ready = ready, refreshing = refreshing,
            top = if (compact) ArtworkLayout.TOOLBAR_Y else 280f, bottom = bottom,
            onChange = { filtersByTab[tab] = it },
            onReset = ::resetFilters, onClose = ::closeSheet,
        )
    }
}

/** A stale navigation entry may still reach this screen; built-in utility artwork is read-only. */
@Composable
private fun BuiltInArtworkScreen(pc: Pc, app: App) {
    val shell = LocalShell.current
    val colors = LocalDelosColors.current
    val first = remember { FocusRequester() }
    val artwork by rememberArtworkResult(LocalArtwork.current, pc, app, ArtKind.WIDE)
    InitialFocus(first)
    Column(
        Modifier.fillMaxSize().padding(start = 150.px, end = 150.px, top = 92.px, bottom = 110.px),
        verticalArrangement = Arrangement.spacedBy(24.px),
    ) {
        BasicText(app.name, style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 46.tpx))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val image = artwork.image
            if (image != null) Image(image, null, Modifier.width(920.px).aspectRatio(920f / 430f).clip(RoundedCornerShape(26.px)), contentScale = ContentScale.Fit)
            else Spinner(48f)
        }
        BasicText(stringResource(R.string.artwork_builtin_note),
            style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 24.tpx))
        PillButton(stringResource(R.string.artwork_builtin_back), Symbols.ARROW_BACK, { shell.back() }, Modifier.focusRequester(first), clickCue = Cue.BACK)
    }
}

/**
 * The SteamGridDB game the artwork comes from: its name, release year, id and the number of images of this tab. The name comes first: on the
 * compact stage, where the chip shares its row with the tabs, the count is dropped while the whole name would not fit beside it.
 */
@Composable
private fun GameChip(match: SgdbGame?, busy: Boolean, appName: String, imageCount: Int?, tab: ArtTab, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val compact = stage.shape == StageShape.COMPACT
    val shape = RoundedCornerShape(22.px)
    val name = when {
        busy -> stringResource(R.string.artwork_chip_looking, appName)
        match != null -> match.name.ifEmpty { stringResource(R.string.artwork_chip_game_id, match.id) }
        else -> stringResource(R.string.artwork_chip_not_found)
    }
    val nameStyle = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx)
    val countStyle = TextStyle(color = colors.text2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 16.tpx)
    val count = if (match != null && imageCount != null) pluralStringResource(tab.count, imageCount, imageCount) else null
    BoxWithConstraints(modifier.height(64.px).clip(shape).glass(colors.surface).border(1.px, colors.line, shape).padding(start = 14.px, end = 22.px)) {
        val showCount = count != null && (!compact || run {
            val measurer = rememberTextMeasurer()
            val k = stage.dpPerPx * LocalDensity.current.density
            val widths = remember(name, count, nameStyle, countStyle, k) {
                measurer.measure(name, nameStyle, maxLines = 1).size.width / k to measurer.measure(count, countStyle, maxLines = 1).size.width / k
            }
            // The chip's room for the name and the count: its width less the 40 px disc and the gap after it; the count's pill pads its text by 14 px
            ArtToolbarLayout.showsCount(maxWidth.value / stage.dpPerPx - 40f - 16f, widths.first, widths.second + 28f, 16f)
        })
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.px)) {
            Box(Modifier.size(40.px).clip(CircleShape).background(colors.accent2.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
                Symbol(Symbols.SPORTS_ESPORTS, 24.tpx, colors.accent2, filled = true)
            }
            BasicText(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = nameStyle)
            // The year and the game id are left out on the compact stage: the name and the count of images matter
            if (match != null && !compact) {
                match.year?.let { MetaChip("$it") }
                MetaChip("#${match.id}")
            }
            if (showCount && count != null) MetaChip(count)
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    val colors = LocalDelosColors.current
    BasicText(
        text, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(colors.surface2).padding(horizontal = 14.px, vertical = 4.px),
        style = TextStyle(color = colors.text2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 16.tpx),
    )
}

/** The accent count of active filters on the Filter pill. */
@Composable
private fun CountBadge(count: Int) {
    val colors = LocalDelosColors.current
    Box(Modifier.size(32.px).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
        BasicText("$count", style = TextStyle(color = colors.onAccent, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 18.tpx))
    }
}

/** The text of the filter strip's line, shared with the compact toolbar, which has to know how wide that line is. */
private object FilterStripMetrics {
    @Composable
    fun countStyle() = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx)

    @Composable
    fun sortStyle() = TextStyle(fontFamily = Figtree, fontSize = 21.tpx)

    /** The count's pill (its text padded by 20 px on each side), 12 px, the sort: stage px. */
    fun lineWidth(count: Float, sort: Float): Float = count + 40f + 12f + sort
}

/**
 * "10 of 41 covers" and the active filters as chips (A removes one, Reset removes them all); without a filter, the sort. The
 * chips share the one gliding focus plate ([RowGlideHost]). [scrolls] (the compact toolbar, where the strip has what the pills leave): one
 * row that scrolls with faded ends when the chips need more.
 */
@Composable
private fun FilterStrip(
    tab: ArtTab, filters: ArtFilters, ready: Choices.Ready, refreshing: Boolean,
    onClear: (ActiveFilter) -> Unit, onReset: () -> Unit, modifier: Modifier, scrolls: Boolean = false,
) {
    val colors = LocalDelosColors.current
    RowGlideHost(
        ring = FocusRing(3f, 3f), corner = 999f, focusScale = 1.05f, fill = null, glow = null,
        modifier = modifier,
    ) {
        val line: @Composable RowScope.() -> Unit = {
            val res = textResources()
            val tint = if (filters.active) colors.accent else colors.accent2
            val centred = Modifier.align(Alignment.CenterVertically)
            BasicText(
                tab.resultLine(res, ready.images.size, ready.total, all = !filters.active),
                centred.clip(CircleShape).background(tint.copy(alpha = .14f)).border(1.px, tint.copy(alpha = .45f), CircleShape).padding(horizontal = 20.px, vertical = 7.px),
                style = FilterStripMetrics.countStyle().copy(color = colors.accent3),
            )
            if (refreshing) Box(centred) { Spinner(26f) }
            if (!filters.active) {
                BasicText(stringResource(filters.sort.first), centred, style = FilterStripMetrics.sortStyle().copy(color = colors.text3))
            } else {
                ArtFilterRules.chips(filters).forEach { chip ->
                    FilterStripChip(chip.label(res), Symbols.CLOSE, centred) { onClear(chip) }
                }
                FilterStripChip(stringResource(R.string.filters_art_chips_reset), Symbols.RESET, centred, onReset)
            }
        }
        if (scrolls) {
            // The chips' focus scale and ring (about 8 px) have their room above and below while the row scrolls, and 24 px at its faded ends
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { ScrollRow(gap = 12f, ringRoom = 12f, content = line) }
        } else {
            Row(Modifier.fillMaxSize().padding(horizontal = 3.px), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px), content = line)
        }
    }
}

@Composable
private fun FilterStripChip(label: String, icon: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalDelosColors.current
    Row(
        modifier
            .focusEffects(onClick = onClick, corner = 999f, focusScale = 1.05f, ring = FocusRing(3f, 3f), glow = null, glide = true)
            .clip(CircleShape).background(colors.surface2).border(1.px, colors.line, CircleShape)
            .height(44.px).padding(horizontal = 20.px),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.px),
    ) {
        BasicText(label, style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx))
        Symbol(icon, 22.tpx, colors.text2)
    }
}

/** No image in the grid: what the filters left, or that SteamGridDB has none; the first choice stays. */
@Composable
private fun NoImages(tab: ArtTab, filters: ArtFilters, ready: Choices.Ready, gameName: String, match: SgdbGame?, onReset: () -> Unit, onChange: () -> Unit) {
    val colors = LocalDelosColors.current
    val res = textResources()
    Column(
        Modifier.fillMaxSize().padding(start = (if (LocalStage.current.shape == StageShape.COMPACT) 0 else 300).px, top = 40.px),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.px),
    ) {
        if (filters.active) {
            Symbol(Symbols.SEARCH, 90.tpx, colors.accent, filled = true)
            BasicText(stringResource(tab.none), style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 34.tpx, textAlign = TextAlign.Center))
            val summary = ArtFilterRules.chips(filters).joinToString(" · ") { it.label(res) }
            val explanation = ready.total?.let { pluralStringResource(tab.has, it, match?.name?.ifEmpty { null } ?: gameName, it) } ?: stringResource(tab.unknown)
            BasicText(explanation, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx, textAlign = TextAlign.Center), modifier = Modifier.width(minOf(820f, LocalStage.current.stageWidth - 160f).px))
            BasicText(stringResource(R.string.artwork_filtered_active, summary), style = TextStyle(color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx, textAlign = TextAlign.Center), modifier = Modifier.width(minOf(820f, LocalStage.current.stageWidth - 160f).px))
            Row(horizontalArrangement = Arrangement.spacedBy(16.px)) {
                PillButton(stringResource(R.string.filters_art_reset), Symbols.RESET, onReset, compact = true, trailing = { GlyphButton(Glyph.X, 32) })
                PillButton(stringResource(R.string.filters_art_change), Symbols.FILTER, onChange, compact = true, clickCue = null, trailing = { GlyphButton(Glyph.Y, 32) })
            }
        } else {
            BasicText(stringResource(tab.empty), style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 22.tpx))
        }
    }
}

/** The strip about the focused image: its style, kind, whether it is in use, and what SteamGridDB tells (size, file, score, date). */
@Composable
private fun InfoStrip(item: Described, tab: ArtTab, chosen: Int?, modifier: Modifier) {
    val res = textResources()
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(26.px)
    Row(
        modifier.clip(shape).glass(colors.surface).border(1.px, colors.line, shape).padding(horizontal = 30.cpx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(44.cpx),
    ) {
        val title: String
        val fields: List<Pair<String, String>>
        var animated = false
        val inUse: Boolean
        when (item) {
            Described.FirstChoice -> {
                title = stringResource(if (tab == ArtTab.LOGO) R.string.artwork_first_name_only else R.string.artwork_first_from_pc)
                fields = if (tab == ArtTab.LOGO) listOf(
                    stringResource(R.string.artwork_fact_source) to stringResource(R.string.artwork_fact_name_source),
                    stringResource(R.string.artwork_fact_shows) to stringResource(R.string.artwork_fact_name_shows),
                ) else listOf(
                    stringResource(R.string.artwork_fact_source) to stringResource(R.string.artwork_fact_pc_source),
                    stringResource(R.string.artwork_fact_shows) to stringResource(R.string.artwork_fact_pc_shows),
                )
                inUse = chosen == NONE
            }
            is Described.Image -> {
                title = ArtworkInfo.title(res, item.image, stringResource(tab.label))
                fields = ArtworkInfo.facts(item.image).map { factText(res, it) }
                animated = item.image.animated
                inUse = item.image.id == chosen
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.px)) {
            BasicText(title, maxLines = 1, style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 24.tpx))
            if (item is Described.Image) Row(horizontalArrangement = Arrangement.spacedBy(8.px)) {
                StripBadge(stringResource(if (animated) R.string.artwork_animated else R.string.artwork_static), if (animated) Symbols.ANIMATED else null, accent = false)
                if (inUse) StripBadge(stringResource(R.string.artwork_in_use), Symbols.CHECK, accent = true)
            } else if (inUse) StripBadge(stringResource(R.string.artwork_in_use), Symbols.CHECK, accent = true)
        }
        fields.forEach { (label, value) ->
            Column {
                BasicText(label, style = TextStyle(color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.tpx, letterSpacing = .12.em))
                BasicText(value, maxLines = 1, style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx))
            }
        }
    }
}

/** The label and the value of an info strip fact, in the language of [res]. */
private fun factText(res: Resources, fact: ArtworkFact): Pair<String, String> = when (fact) {
    is ArtworkFact.Size -> res.getString(R.string.artwork_fact_size) to res.getString(R.string.artwork_fact_size_value, fact.width, fact.height)
    is ArtworkFact.File -> res.getString(R.string.artwork_fact_file) to fact.format
    is ArtworkFact.Score -> res.getString(R.string.artwork_fact_score) to when {
        fact.score != null && fact.author != null -> res.getString(R.string.artwork_fact_score_by, fact.score, fact.author)
        fact.score != null -> res.getString(R.string.artwork_fact_score_only, fact.score)
        else -> res.getString(R.string.artwork_fact_by_only, fact.author.orEmpty())
    }
    is ArtworkFact.Added -> res.getString(R.string.artwork_fact_added) to ArtworkInfo.date(fact.epochSeconds, res.configuration.locales[0])
}

@Composable
private fun StripBadge(text: String, icon: String?, accent: Boolean) {
    val colors = LocalDelosColors.current
    val content = if (accent) colors.onAccent else colors.text2
    Row(
        Modifier.clip(CircleShape).background(if (accent) colors.accent else colors.surface2).padding(horizontal = 12.px, vertical = 3.px),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.px),
    ) {
        if (icon != null) Symbol(icon, 16.tpx, content, filled = true)
        BasicText(text, style = TextStyle(color = content, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 14.tpx))
    }
}

/**
 * The filter sheet (Y or the Filter pill): it grows from the toolbar's Filter pill ([popEnter] / [popExit]) over a dimmed page,
 * the grid below keeps updating as choices are made. The focus stays inside while it is open and starts on the first row's
 * selected option; the options share the filter chips' gliding plate ([RowGlideHost]). [top] is the sheet's y and [bottom] the hint
 * bar's room: the compact sheet lies over the toolbar, has the live count in place of its title and scrolls its options when the page
 * is too short for them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilterSheetLayer(
    open: Boolean, tab: ArtTab, filters: ArtFilters, ready: Choices.Ready?, refreshing: Boolean, top: Float, bottom: Float,
    onChange: (ArtFilters) -> Unit, onReset: () -> Unit, onClose: () -> Unit,
) {
    val colors = LocalDelosColors.current
    val res = textResources()
    val reduced = LocalReducedMotion.current
    val shape = RoundedCornerShape(40.px)
    val stage = LocalStage.current
    val compactSheet = stage.shape == StageShape.COMPACT
    val margin = SettingsLayout.margin(stage)
    val kind = tab.kind
    // The header's pills are focusable: their glow (a 70 px circle around the 64 px pill, times the focus scale) needs 44 px above them
    // inside the sheet's clip
    val padTop = ArtworkLayout.SHEET_PAD_TOP
    val padBottom = if (compactSheet) 20f else 22f
    AnimatedVisibility(open, enter = fadeIn(tween(Motion.EXIT, easing = Motion.Ease)), exit = fadeOut(tween(Motion.EXIT, easing = Motion.Ease))) {
        Box(Modifier.fillMaxSize().background(Color(0x73050A14)).pointerInput(Unit) { detectTapGestures { onClose() } })
    }
    AnimatedVisibility(
        open,
        modifier = Modifier.offset(margin.px, top.px),
        enter = popEnter(TransformOrigin(.92f, 0f), reduced),
        exit = popExit(TransformOrigin(.92f, 0f), reduced),
    ) {
        val requesters = remember { HashMap<String, FocusRequester>() }
        fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
        var panelFocused by remember { mutableStateOf(false) }
        // The first row's selected option takes the focus whenever the sheet opens
        val startKey = if (ArtFilterRules.hasType(kind)) "type:${filters.type}" else "style:${filters.style ?: ""}"
        if (open) InitialFocus(requester(startKey))
        // Reset disables itself once nothing is filtered; if it had the focus, the focus goes back to the first option
        var wasActive by remember { mutableStateOf(filters.active) }
        LaunchedEffect(filters.active) {
            if (wasActive && !filters.active) {
                withFrameNanos { }
                if (!panelFocused) runCatching { requester(startKey).requestFocus() }
            }
            wasActive = filters.active
        }
        Column(
            Modifier
                .width((stage.stageWidth - 2 * margin).px)
                .clip(shape).glass(colors.surface3).border(1.px, colors.line, shape)
                .padding(start = 40.cpx, end = 40.cpx, top = padTop.px, bottom = padBottom.px)
                .onFocusChanged { panelFocused = it.hasFocus }
                .focusProperties { onExit = { if (open) cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.px),
        ) {
            val live: @Composable () -> Unit = {
                if (ready != null) {
                    BasicText(
                        tab.resultLine(res, ready.images.size, ready.total, all = false),
                        Modifier.clip(CircleShape).background(colors.accent.copy(alpha = .16f)).border(1.px, colors.accent.copy(alpha = .45f), CircleShape).padding(horizontal = 22.px, vertical = 8.px),
                        style = TextStyle(color = colors.accent3, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx),
                    )
                }
            }
            // Compact: the live count stands where the title would not fit beside the two pills, which keep the room a focused one grows into
            Row(Modifier.fillMaxWidth().padding(bottom = 8.px), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((if (compactSheet) ArtworkLayout.PILL_GAP else 24f).px)) {
                if (!compactSheet) BasicText(stringResource(R.string.filters_art_title), style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 34.tpx))
                live()
                if (refreshing) Spinner(30f)
                Spacer(Modifier.weight(1f))
                val active = filters.active
                PillButton(
                    stringResource(R.string.filters_art_reset), Symbols.RESET, onReset,
                    Modifier.alpha(if (active) 1f else .4f).then(if (active) Modifier else Modifier.focusProperties { canFocus = false }),
                    compact = true, clickCue = null, trailing = { GlyphButton(Glyph.X, 32) },
                )
                PillButton(stringResource(R.string.filters_art_done), Symbols.CHECK, onClose, compact = true, clickCue = Cue.BACK, trailing = { GlyphButton(Glyph.Y, 32) })
            }
            // The compact sheet may be taller than the page leaves it: its options scroll, fading at an edge they continue behind. The header
            // above them is the padding, the 64 px pills and 16 px
            val optionsScroll = rememberScrollState()
            val optionsMax = (stage.stageHeight - top - bottom - 8f - padTop - 64f - 16f - padBottom).coerceAtLeast(120f)
            // A focused option of the scrolling sheet stays clear of the faded edges
            val k = stage.dpPerPx * LocalDensity.current.density
            val optionsIntoView = rememberMarginBringIntoView(optionsScroll, before = 24f * k, after = 40f * k, stagePx = k)
            RowGlideHost(
                ring = FocusRing(3f, 3f), corner = 999f, focusScale = 1.05f, fill = null, glow = null,
                modifier = if (compactSheet) Modifier.heightIn(max = optionsMax.px).focusRoom(horizontal = 12.px).fadeEdges(stage, optionsScroll, 16f, 32f) else Modifier,
            ) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides optionsIntoView) {
                Column(
                    // 12 px beside and 8 px above and below the rows: the room of a focused chip's ring inside the scrolling layer
                    (if (compactSheet) Modifier.verticalScroll(optionsScroll).padding(horizontal = 12.px, vertical = 8.px) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(6.px),
                ) {
                    if (ArtFilterRules.hasType(kind)) {
                        SheetRow(Symbols.MOVIE, stringResource(R.string.filters_art_row_type)) {
                            ArtType.entries.forEach { type ->
                                FilterChip(stringResource(type.label), filters.type == type, { onChange(filters.copy(type = type)) }, Modifier.focusRequester(requester("type:$type")))
                            }
                        }
                        SheetDivider()
                    }
                    SheetRow(Symbols.STYLE, stringResource(R.string.filters_art_row_style)) {
                        FilterChip(stringResource(R.string.filters_art_any), filters.style == null, { onChange(filters.copy(style = null)) }, Modifier.focusRequester(requester("style:")))
                        ArtFilterRules.styles(kind).forEach { style ->
                            FilterChip(ArtFilterRules.styleLabel(res, style), filters.style == style, { onChange(filters.copy(style = style)) }, Modifier.focusRequester(requester("style:$style")))
                        }
                    }
                    if (ArtFilterRules.dimensions(kind).isNotEmpty()) {
                        SheetDivider()
                        SheetRow(Symbols.DIMENSIONS, stringResource(R.string.filters_art_row_dimensions)) {
                            FilterChip(stringResource(R.string.filters_art_any), filters.dimensions == null, { onChange(filters.copy(dimensions = null)) })
                            ArtFilterRules.dimensions(kind).forEach { dimensions ->
                                FilterChip(ArtFilterRules.dimensionsLabel(dimensions), filters.dimensions == dimensions, { onChange(filters.copy(dimensions = dimensions)) })
                            }
                        }
                    }
                    SheetDivider()
                    SheetRow(Symbols.SORT, stringResource(R.string.filters_art_row_sort)) {
                        ArtSort.entries.forEach { sort ->
                            FilterChip(stringResource(sort.label), filters.sort == sort, { onChange(filters.copy(sort = sort)) })
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(icon: String, title: String, options: @Composable () -> Unit) {
    val colors = LocalDelosColors.current
    val compact = LocalStage.current.shape == StageShape.COMPACT
    // Compact: 4 px above and below, so a focused chip (its 5 % and its ring) keeps 12 px to the chips of the next row
    Row(Modifier.fillMaxWidth().padding(vertical = (if (compact) 4 else 2).px), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((if (compact) 20 else 30).px)) {
        Row(Modifier.width((if (compact) 310 else 270).px), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((if (compact) 14 else 18).px)) {
            Box(Modifier.size((if (compact) 44 else 52).px).clip(CircleShape).background(colors.accent.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                Symbol(icon, 28.tpx, colors.accent2, filled = true)
            }
            BasicText(title, style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 26.tpx))
        }
        // A row that scrolls keeps the chips' ring above and below (the fade's layer would cut it)
        ScrollRow(gap = 14f * LocalStage.current.compactScale, ringRoom = 12f) { options() }
    }
}

@Composable
private fun SheetDivider() {
    Box(Modifier.fillMaxWidth().height(1.px).background(LocalDelosColors.current.line))
}

/** The first choice: no SteamGridDB image (the PC's artwork, or the name instead of a logo). */
@Composable
private fun NoneTile(pc: Pc, app: App, tab: ArtTab, tileWidth: Float, inUse: Boolean, loading: Boolean, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    val boxArt by rememberBoxArt(LocalBoxArt.current, pc, app)
    Tile(tab, tileWidth, inUse, loading, if (tab == ArtTab.LOGO) stringResource(R.string.artwork_first_name_only) else stringResource(R.string.artwork_first_from_pc), animated = false, modifier, onFocused, onClick) {
        val img = boxArt
        when {
            tab == ArtTab.LOGO -> NameAsLogo(app.name)
            img != null -> Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> {
                val art = appArt(pc, app)
                // The glyph of the 1920 stage's tile; in proportion on the smaller tiles of the compact grid
                PlaceholderArt(art.hue, art.hue2, art.icon, Modifier.fillMaxSize(), glyphSize = if (tileWidth >= 200f) 120f else tileWidth * .6f)
            }
        }
    }
}

@Composable
private fun ChoiceTile(
    image: SgdbImage, tab: ArtTab, tileWidth: Float, inUse: Boolean, loading: Boolean, playing: Boolean, ticker: AnimationTicker,
    modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit,
) {
    val store = LocalArtwork.current
    val thumb by rememberThumbnail(store, image.previewUrl)
    Tile(tab, tileWidth, inUse, loading, null, image.animated, modifier, onFocused, onClick) {
        Crossfade(thumb, animationSpec = tween(300), label = "thumb") { img ->
            if (img != null) {
                Image(
                    img, null,
                    if (tab == ArtTab.LOGO) Modifier.fillMaxSize().padding(18.px) else Modifier.fillMaxSize(),
                    contentScale = if (tab == ArtTab.LOGO) ContentScale.Fit else ContentScale.Crop,
                )
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(36f, color = LocalDelosColors.current.text3) }
        }
        if (image.animated) AnimatedTileLayer(image, playing, ticker)
    }
}

/**
 * The playing animation of an animated image over its first frame: its animated thumbnail (else the full file when it is a
 * small WebP) is fetched once and kept in a bounded cache, decoded at tile size while [playing] and released when it stops.
 * Tiles share one [ticker], so the screen draws once per frame however many play.
 */
@Composable
private fun AnimatedTileLayer(image: SgdbImage, playing: Boolean, ticker: AnimationTicker) {
    val store = LocalArtwork.current
    val file by produceState(Thumbnails.cachedAnimation(image), image.id, playing) {
        // Fetched the first time the tile is allowed to play, then kept
        if (value == null && playing && !Thumbnails.hasNoAnimation(image)) value = Thumbnails.loadAnimation(store.http, image)
    }
    val source = remember(file) { file?.let { AnimatedSource.OfFile(it) } }
    val drawable = rememberAnimatedDrawable(source, playing, AnimatedPlayback.TILE_WIDTH, ticker)
    if (drawable != null) AnimatedDrawableView(drawable, ticker, Modifier.fillMaxSize())
    // The animation is a whole file: show how far it is while it downloads
    if (file == null && playing && !Thumbnails.hasNoAnimation(image)) TileDownloadBar(Thumbnails.progress[image.previewUrl])
}

/** A slim bar at the foot of a tile: [progress] of its download, a small spinner while the size is not known yet. */
@Composable
private fun TileDownloadBar(progress: Float?) {
    val colors = LocalDelosColors.current
    Box(Modifier.fillMaxSize()) {
        if (progress == null) {
            Box(Modifier.align(Alignment.BottomEnd).padding(12.px)) { Spinner(26f) }
        } else {
            val shown by animateFloatAsState(progress, tween(Motion.FADE, easing = Motion.Ease), label = "tileDownload")
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.px, vertical = 14.px).height(8.px).clip(CircleShape).background(Color(0x99050A14))) {
                Box(Modifier.fillMaxWidth(shown.coerceAtLeast(.04f)).fillMaxHeight().clip(CircleShape).background(colors.accent))
            }
        }
    }
}

/** A choice in the grid: the image in [tab]'s shape, the "In use" badge, an "Animated" badge, a spinner while [loading], an optional caption. */
@Composable
private fun Tile(
    tab: ArtTab, tileWidth: Float, inUse: Boolean, loading: Boolean, caption: String?, animated: Boolean, modifier: Modifier,
    onFocused: () -> Unit, onClick: () -> Unit, content: @Composable () -> Unit,
) {
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(20.px)
    Box(
        modifier
            .focusEffects(
                onClick, corner = 20f, focusScale = 1.06f, ring = FocusRing(4f, 4f), glow = FocusGlow(18f, 50f, -12f), clickCue = null,
                onFocusChange = { if (it) onFocused() },
            )
            .fillMaxWidth()
            .aspectRatio(tab.ratio)
            .clip(shape)
            .background(colors.surface2)
            .then(if (inUse) Modifier.border(3.px, colors.accent, shape) else Modifier.border(1.px, colors.line, shape)),
    ) {
        content()
        if (caption != null) {
            BasicText(
                caption,
                style = TextStyle(color = Color.White, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 18.tpx, textAlign = TextAlign.Center),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xA6050A14)).padding(vertical = 8.px),
            )
        }
        if (loading) {
            Box(Modifier.fillMaxSize().background(Color(0x99050A14)), contentAlignment = Alignment.Center) { Spinner(48f) }
        }
        if (inUse) {
            Row(
                Modifier.align(Alignment.TopStart).padding(12.px).clip(CircleShape).background(colors.accent).padding(horizontal = 12.px, vertical = 4.px),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.px),
            ) {
                Symbol(Symbols.CHECK, 20.tpx, colors.onAccent)
                // A cover that is animated too has room for the two badges only when "In use" is the check alone
                if (!animated || tab != ArtTab.COVER || tileWidth >= ArtworkLayout.COVER_BADGES_MIN) {
                    BasicText(stringResource(R.string.artwork_in_use), style = TextStyle(color = colors.onAccent, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 16.tpx))
                }
            }
        }
        if (animated) {
            // Icon only on the narrow covers, where "In use" takes the room, and on a tile too small for the label (beside "In use")
            val label = tab != ArtTab.COVER && tileWidth >= (if (inUse) ArtworkLayout.BOTH_LABELS_MIN else ArtworkLayout.ANIMATED_LABEL_MIN)
            Row(
                Modifier.align(Alignment.TopEnd).padding(12.px).clip(CircleShape).background(Color(0xB3050A14)).padding(horizontal = if (label) 12.px else 6.px, vertical = 4.px),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.px),
            ) {
                Symbol(Symbols.ANIMATED, 20.tpx, colors.accent2, filled = true)
                if (label) BasicText(stringResource(R.string.artwork_animated), style = TextStyle(color = Color.White, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 16.tpx))
            }
        }
    }
}

/** The game's name where it has no logo, as on the home screen. */
@Composable
private fun NameAsLogo(name: String) {
    val colors = LocalDelosColors.current
    Box(Modifier.fillMaxSize().padding(18.px), contentAlignment = Alignment.Center) {
        BasicText(
            name, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 26.tpx, textAlign = TextAlign.Center),
        )
    }
}

@Composable
private fun GridMessage(icon: String, title: String, text: String?) {
    val colors = LocalDelosColors.current
    Column(Modifier.fillMaxSize().padding(top = 80.px), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.px)) {
        Symbol(icon, 90.tpx, colors.accent, filled = true)
        BasicText(title, style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 34.tpx, textAlign = TextAlign.Center))
        if (text != null) BasicText(text, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx, textAlign = TextAlign.Center), modifier = Modifier.width(minOf(820f, LocalStage.current.stageWidth - 160f).px))
    }
}

private fun apply(res: Resources, scope: CoroutineScope, shell: ShellState, feedback: Feedback, store: ArtworkStore, pc: Pc, app: App, tab: ArtTab, image: SgdbImage?) {
    feedback.play(Cue.SELECT)
    scope.launch {
        if (image != null) shell.showToast(res.getString(tab.downloading), Symbols.DOWNLOAD)
        runCatching { store.apply(pc, app, tab.kind, image) }
            .onSuccess { shell.showToast(res.getString(tab.updated), Symbols.CHECK_CIRCLE) }
            .onFailure {
                feedback.play(Cue.ERROR)
                shell.showToast(sgdbErrorText(res, it, R.string.artwork_download_failed), Symbols.ERROR)
            }
    }
}

/** "Search for another name": the shared text dialog; the games found for the name come as a menu (see [changeGame]). */
private fun searchDialog(res: Resources, scope: CoroutineScope, shell: ShellState, tracker: FocusTracker, feedback: Feedback, store: ArtworkStore, pc: Pc, app: App, term: String) {
    feedback.play(Cue.OPEN)
    shell.openLayer(
        Layer.Dialog(
            DialogSpec(
                res.getString(R.string.artwork_search_title), res.getString(R.string.artwork_search_note),
                listOf(DialogField(Symbols.SEARCH, term, res.getString(R.string.artwork_search_hint), maxLength = 80)), res.getString(R.string.artwork_search_action),
            ) { values ->
                val next = values.first().trim()
                if (next.isEmpty()) false else {
                    changeGame(res, scope, shell, tracker, feedback, store, pc, app, next)
                    true
                }
            }
        ),
        tracker,
    )
}

/** The SteamGridDB games found for [term], and searching under another name; a chosen game re-matches the artwork. */
private fun changeGame(res: Resources, scope: CoroutineScope, shell: ShellState, tracker: FocusTracker, feedback: Feedback, store: ArtworkStore, pc: Pc, app: App, term: String) {
    scope.launch {
        shell.showToast(res.getString(R.string.artwork_searching_toast, term), Symbols.SEARCH)
        val found = runCatching { store.search(term) }.getOrElse {
            feedback.play(Cue.ERROR)
            shell.showToast(sgdbErrorText(res, it, R.string.artwork_unreachable), Symbols.ERROR)
            return@launch
        }
        val items = found.take(8).map { game ->
            MenuItem(Symbols.SPORTS_ESPORTS, game.year?.let { res.getString(R.string.artwork_game_with_year, game.name, it) } ?: game.name) {
                scope.launch {
                    shell.showToast(res.getString(R.string.artwork_downloading_game_toast, game.name), Symbols.DOWNLOAD)
                    runCatching { store.setGame(pc, app, game) }
                        .onSuccess { shell.showToast(res.getString(R.string.artwork_matched_toast, game.name), Symbols.CHECK_CIRCLE) }
                        .onFailure { shell.showToast(sgdbErrorText(res, it, R.string.artwork_download_failed), Symbols.ERROR) }
                }
            }
        } + MenuItem(Symbols.SEARCH_NAME, res.getString(R.string.artwork_search_other)) { searchDialog(res, scope, shell, tracker, feedback, store, pc, app, term) }
        feedback.play(Cue.OPEN)
        shell.openLayer(Layer.Context(ContextMenu(if (found.isEmpty()) res.getString(R.string.artwork_found_none, term) else res.getString(R.string.artwork_found_title), items), tracker.bounds()), tracker)
    }
}
