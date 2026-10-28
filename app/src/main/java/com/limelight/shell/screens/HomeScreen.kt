package com.limelight.shell.screens

import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.limelight.shell.ContextMenu
import com.limelight.shell.GameActions
import com.limelight.shell.Layer
import com.limelight.shell.LocalFocusTracker
import com.limelight.shell.LocalGameActions
import com.limelight.shell.LocalShell
import com.limelight.shell.MenuItem
import com.limelight.shell.data.App
import com.limelight.shell.data.Library
import com.limelight.shell.Screen
import com.limelight.shell.data.ArtKind
import com.limelight.shell.ui.Spinner
import com.limelight.shell.ui.HeroBitmaps
import com.limelight.shell.data.LocalArtwork
import com.limelight.shell.data.LocalBoxArt
import com.limelight.shell.data.rememberArtwork
import com.limelight.shell.data.rememberArtworkResult
import com.limelight.shell.data.Pc
import com.limelight.shell.data.PcRepository
import com.limelight.shell.data.rememberBoxArt
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.HomeLayout
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.GameArt
import com.limelight.shell.ui.IdleCoverFilter
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.PlaceholderArt
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.fadeMask
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.glass
import com.limelight.shell.ui.focusPulse
import com.limelight.shell.ui.hsl
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Hues and icon of an app's placeholder art, derived from its name. */
fun appArt(pc: Pc, app: App, image: androidx.compose.ui.graphics.ImageBitmap? = null): GameArt {
    val hue = ((app.name.hashCode() and 0x7fffffff) % 360).toFloat()
    val icon = if (app.name.equals("Desktop", ignoreCase = true)) Symbols.DESKTOP_WINDOWS else Symbols.SPORTS_ESPORTS
    return GameArt("${pc.uuid}/${app.id}", hue, (hue + 35f) % 360f, icon, image)
}

fun homeKey(pc: Pc, app: App) = "${pc.uuid}/${app.id}"

/** Lazy containers must not scroll on their own when the focus moves: the home screen anchors the focused tile itself. */
@OptIn(ExperimentalFoundationApi::class)
private object NoAutoScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

/** The prototype's library scroll: easeOutCubic over 200 ms. */
private val FastScroll = tween<Float>(200, easing = CubicBezierEasing(.33f, 1f, .68f, 1f))

/**
 * The home screen (step 6.4): the games of the active PC with their covers, the focused game's info and
 * background, in the grid or row layout (Settings → Appearance → Home layout).
 */
@Composable
fun HomeScreen(library: Library, appearance: Appearance, repo: PcRepository) {
    val shell = LocalShell.current
    val hasGames = library is Library.Ready && library.apps.isNotEmpty()
    // Without games there is no game background
    LaunchedEffect(hasGames) { if (!hasGames) shell.hero = null }
    when (library) {
        Library.Loading -> HomeMessage(Symbols.REFRESH, "Looking for your PCs…", null)
        Library.NoPc -> HomeMessage(Symbols.DEVICES, "No PC paired yet", "For now, pair a PC in the classic Delos app.\nPairing in the new UI comes in step 6.6.")
        is Library.Offline -> HomeMessage(Symbols.POWER, "${library.pc.name} is offline", "Start the PC, or wake it from the classic Delos app.\nWaking it from here comes in step 6.6.")
        is Library.Ready -> if (hasGames) {
            GameLibrary(library, appearance, repo)
        } else {
            HomeMessage(Symbols.SPORTS_ESPORTS, "No games on ${library.pc.name}", "Add apps in Vibepollo's web UI, they show up here automatically.")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameLibrary(library: Library.Ready, appearance: Appearance, repo: PcRepository) {
    val shell = LocalShell.current
    val feedback = LocalFeedback.current
    val stage = LocalStage.current
    val boxArt = LocalBoxArt.current
    val pc = library.pc
    val apps = library.apps
    val row = appearance.homeLayout == HomeLayout.ROW
    val uiScale = appearance.uiScale / 100f

    val focusedIndex = apps.indexOfFirst { homeKey(pc, it) == shell.homeGame }.takeIf { it >= 0 } ?: 0
    val focused = apps[focusedIndex]
    val artwork = LocalArtwork.current
    val boxImage by rememberBoxArt(boxArt, pc, focused)
    val heroImage by rememberArtwork(artwork, pc, focused, ArtKind.HERO)
    val coverImage by rememberArtwork(artwork, pc, focused, ArtKind.COVER)
    // The SteamGridDB hero, else the cover (SteamGridDB, then the PC's)
    val image = heroImage ?: coverImage ?: boxImage
    // The animated background takes its colors from the cover
    val coverForColors = coverImage ?: boxImage
    LaunchedEffect(homeKey(pc, focused), coverForColors) {
        coverForColors?.let { cover -> withContext(Dispatchers.Default) { HeroBitmaps.analyzeCover(homeKey(pc, focused), cover) } }
    }
    // The game background follows the focus with a short delay, so it does not flicker while scrolling fast
    LaunchedEffect(homeKey(pc, focused), image) {
        delay(140)
        shell.hero = appArt(pc, focused, image)
    }

    val requesters = remember(pc.uuid) { HashMap<Int, FocusRequester>() }
    fun requester(app: App) = requesters.getOrPut(app.id) { FocusRequester() }
    val gridState = rememberLazyGridState()
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val windowInfo = LocalWindowInfo.current

    // Initial focus when the library appears or the layout changes: jump to the focused game, focus it silently.
    // Not keyed on the focused game: moving the focus is animated by anchor() alone.
    LaunchedEffect(pc.uuid, row) {
        if (shell.homeGame != homeKey(pc, focused)) shell.homeGame = homeKey(pc, focused)
        if (row) rowState.scrollToItem(focusedIndex) else gridState.scrollToItem(focusedIndex)
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        if (shell.layer == null) {
            feedback.silenceNextFocus()
            runCatching { requester(focused).requestFocus() }
        }
    }

    // The focused game disappeared (e.g. hidden): its neighbour takes the focus
    val gone = shell.homeGame != null && apps.none { homeKey(pc, it) == shell.homeGame }
    var lastIndex by remember { mutableIntStateOf(focusedIndex) }
    SideEffect { if (!gone) lastIndex = focusedIndex }
    LaunchedEffect(gone, apps) {
        if (!gone) return@LaunchedEffect
        val next = apps[lastIndex.coerceAtMost(apps.lastIndex)]
        shell.homeGame = homeKey(pc, next)
        withFrameNanos { }
        if (shell.layer == null) {
            feedback.silenceNextFocus()
            runCatching { requester(next).requestFocus() }
        }
    }

    // If the element that had the focus before a menu opened is gone, the focused game takes it back
    val currentFocused by rememberUpdatedState(focused)
    DisposableEffect(shell) {
        shell.restoreScreenFocus = { runCatching { requester(currentFocused).requestFocus() }.getOrDefault(false) }
        onDispose { shell.restoreScreenFocus = null }
    }

    // Tile callbacks are remembered, so moving the focus recomposes only the two tiles whose focus changed
    val reducedMotion by rememberUpdatedState(appearance.reducedMotion)
    val onTileFocused: (Int, App) -> Unit = remember(pc.uuid, row, scope) {
        { index, app ->
            shell.homeGame = homeKey(pc, app)
            // Keep the focused tile at the anchor: the grid row at the top, the row tile at the left edge
            scope.launch {
                if (row) {
                    val info = rowState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                    if (info == null) rowState.scrollToItem(index) else scroll(rowState, info.offset.toFloat(), reducedMotion)
                } else {
                    val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                    if (info == null) gridState.scrollToItem(index) else scroll(gridState, info.offset.y.toFloat(), reducedMotion)
                }
            }
        }
    }
    val running = apps.firstOrNull { it.running }
    val onHide: (App, Boolean) -> Unit = remember(pc.uuid) {
        { app, hide ->
            repo.setHidden(pc.uuid, app.id, hide)
            if (hide) shell.showToast("${app.name} is hidden · the side menu shows hidden games", Symbols.VISIBILITY_OFF)
        }
    }

    val tile: @Composable (Int, App) -> Unit = { index, app ->
        GameTile(
            pc, app, index = index,
            badge = when {
                app.running -> TileBadge.RUNNING
                index == 0 && library.lastPlayed == app.id -> TileBadge.LAST_PLAYED
                else -> null
            },
            hidden = app.id in library.hidden,
            running = running,
            requester = requester(app),
            onFocused = onTileFocused, onHide = onHide,
        )
    }

    Box(Modifier.fillMaxSize()) {
        GameInfo(pc, focused,
            modifier = Modifier.padding(start = 150.px, top = if (row) 210.px else 116.px), reducedMotion = appearance.reducedMotion)
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
            if (row) {
                // At the end the row stops with the last cover at the right edge, so the right fade goes away there
                val endFade by animateFloatAsState(if (rowState.canScrollForward) 1f else 0f, tween(300), label = "rowEndFade")
                val density = LocalDensity.current.density
                // Faded out for a moment while a long wrap jumps close to the other end
                val rowAlpha = remember { Animatable(1f) }
                // The spring at the end of a wrap: the row overshoots the end a little and settles back
                val rowBounce = remember { Animatable(0f) }
                LazyRow(
                    state = rowState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = (430f - (uiScale - 1f) * 92f).px, bottom = 20.px)
                        .graphicsLayer {
                            alpha = rowAlpha.value
                            translationX = rowBounce.value
                        }
                        .fadeMask(stage, vertical = false, startClear = 0f, startSolid = 120f, endSolid = 20f + 240f * endFade, endClear = 20f * endFade)
                        // Left on the first game goes round to the last one, right on the last one back to the first
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || apps.size < 2) return@onPreviewKeyEvent false
                            val index = apps.indexOfFirst { homeKey(pc, it) == shell.homeGame }
                            val target = when {
                                event.key == Key.DirectionLeft && index == 0 -> apps.lastIndex
                                event.key == Key.DirectionRight && index == apps.lastIndex -> 0
                                else -> return@onPreviewKeyEvent false
                            }
                            scope.launch {
                                val k = stage.dpPerPx * density
                                fun now() = rowItemStart(rowState.firstVisibleItemIndex) * k + rowState.firstVisibleItemScrollOffset
                                // Far away: fade out, jump a few covers short of the other end and fade in again, so the
                                // glide does not have to load every cover on the way
                                if (!reducedMotion && kotlin.math.abs(target - rowState.firstVisibleItemIndex) > WRAP_GLIDE_ITEMS) {
                                    rowAlpha.animateTo(0f, tween(110))
                                    rowState.scrollToItem(if (target == 0) WRAP_GLIDE_ITEMS else (target - WRAP_GLIDE_ITEMS).coerceAtLeast(0))
                                    launch { rowAlpha.animateTo(1f, tween(260)) }
                                }
                                // Glide there without slowing down (the row stops at its ends by itself), overshoot the end on
                                // a spring and settle back, then move the focus
                                // Toward the end the row stops before the target reaches the left: aim exactly at that end, so
                                // the glide does not run on for nothing
                                val end = (150f + rowItemStart(apps.lastIndex) + (if (apps.size == 1) ROW_WIDE else ROW_TILE) + ROW_END_PADDING) * k -
                                    rowState.layoutInfo.viewportSize.width
                                val delta = minOf(rowItemStart(target) * k, end.coerceAtLeast(0f)) - now()
                                if (reducedMotion) rowState.scrollBy(delta) else {
                                    rowState.animateScrollBy(delta, tween(360, easing = CubicBezierEasing(.45f, .05f, .8f, .65f)))
                                    rowAlpha.snapTo(1f)
                                    // Arriving at the last cover the content moves left, at the first one right
                                    val speed = (kotlin.math.abs(delta) * 1.75f / .36f).coerceAtMost(4200f * k)
                                    launch {
                                        rowBounce.animateTo(
                                            0f, spring(dampingRatio = .36f, stiffness = 340f),
                                            initialVelocity = if (target == 0) speed else -speed,
                                        )
                                    }
                                }
                                rowAlpha.snapTo(1f)
                                withFrameNanos { }
                                runCatching { requester(apps[target]).requestFocus() }
                            }
                            true
                        },
                    // At the end the last cover (focused) is as far from the right edge as the first game (focused) is
                    // from the left one
                    contentPadding = PaddingValues(start = 150.px, end = ROW_END_PADDING.px, top = 90.px, bottom = 90.px),
                    horizontalArrangement = Arrangement.spacedBy(28.px),
                ) {
                    itemsIndexed(apps, key = { _, app -> app.id }) { i, app -> tile(i, app) }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 292.px)
                        // The top fade starts below the info chips; the bottom fade runs under the hint bar
                        .fadeMask(stage, vertical = true, startClear = 34f, startSolid = 90f, endSolid = 230f, endClear = 10f),
                    contentPadding = PaddingValues(start = 154.px, end = 154.px, top = 98.px, bottom = 260.px),
                    horizontalArrangement = Arrangement.spacedBy(28.px),
                    verticalArrangement = Arrangement.spacedBy(28.px),
                ) {
                    itemsIndexed(apps, key = { _, app -> app.id }, span = { i, _ -> GridItemSpan(if (i == 0) 3 else 1) }) { i, app -> tile(i, app) }
                }
            }
        }
    }
}

/** Row layout: the wide first cover, the others and the gap between them, in stage pixels. */
private const val ROW_WIDE = 956f
private const val ROW_TILE = 300f
private const val ROW_GAP = 28f

/** Where item [index] starts in the row, from the start of its content (stage pixels). */
private fun rowItemStart(index: Int): Float = if (index == 0) 0f else ROW_WIDE + ROW_GAP + (index - 1) * (ROW_TILE + ROW_GAP)

/** A wrap round the row glides over the last this many covers (the rest is a quick jump). */
private const val WRAP_GLIDE_ITEMS = 12

/** How much a cover grows when focused (focusEffects' default). */
private const val TILE_FOCUS_SCALE = 1.055f

/**
 * The right margin at the end of the row, mirroring the start: the focused wide first cover grows only to
 * the left (so it never covers its neighbour), which brings its left edge closer to the screen's edge,
 * while the other covers grow evenly. So the last cover, focused, is as far from the right edge as the
 * first game, focused, is from the left one (about 97 px).
 */
private const val ROW_END_PADDING = 150f - ROW_WIDE * (TILE_FOCUS_SCALE - 1f) + ROW_TILE * (TILE_FOCUS_SCALE - 1f) / 2f

private suspend fun scroll(state: ScrollableState, delta: Float, reducedMotion: Boolean) {
    if (kotlin.math.abs(delta) < 1f) return
    if (reducedMotion) state.scrollBy(delta) else state.animateScrollBy(delta, FastScroll)
}

/** The badge on a cover's top-left corner. */
enum class TileBadge(val icon: String, val label: String) {
    RUNNING(Symbols.PLAY_ARROW, "Running"),
    LAST_PLAYED(Symbols.HISTORY, "Last played"),
}

/**
 * The context menu of a game, depending on what runs on the PC: this game (resume / quit), another
 * game (quit that one and start this one, or go back to it) or nothing (start). No confirmation
 * dialogs: choosing "Quit" in this menu is the confirmation.
 */
private fun gameMenu(
    pc: Pc, app: App, hidden: Boolean, running: App?, image: ImageBitmap?, actions: GameActions, onHide: (App, Boolean) -> Unit,
    /** Opens the artwork screen. */
    onArtwork: () -> Unit,
    onSettings: () -> Unit,
) =
    ContextMenu(
        app.name,
        buildList {
            when {
                app.running -> {
                    add(MenuItem(Symbols.RESUME, "Resume") { actions.start(pc, app, image) })
                    add(MenuItem(Symbols.POWER_SETTINGS_NEW, "Quit game") { actions.quit(pc, app) })
                }
                running != null -> {
                    add(MenuItem(Symbols.SWAP_HORIZ, "Quit ${running.name} and start") { actions.quitAndStart(pc, running, app, image) })
                    add(MenuItem(Symbols.RESUME, "Resume ${running.name}") { actions.start(pc, running, null) })
                }
                else -> add(MenuItem(Symbols.PLAY_ARROW, "Start game") { actions.start(pc, app, image) })
            }
            add(MenuItem(Symbols.PHOTO_LIBRARY, "Artwork", onArtwork))
            add(MenuItem(Symbols.SETTINGS, "Game settings", onSettings))
            add(
                if (hidden) MenuItem(Symbols.VISIBILITY, "Show game") { onHide(app, false) }
                else MenuItem(Symbols.VISIBILITY_OFF, "Hide game") { onHide(app, true) }
            )
        },
    )

@Composable
private fun GameInfo(pc: Pc, app: App, modifier: Modifier, reducedMotion: Boolean) {
    val colors = LocalDelosColors.current
    val art = appArt(pc, app)
    Column(modifier) {
        val logo by rememberArtworkResult(LocalArtwork.current, pc, app, ArtKind.LOGO)
        val logoImage = logo.image
        // The SteamGridDB logo stands in for the icon and the name
        if (logoImage != null) Box(Modifier.height(200.px).padding(vertical = 10.px), contentAlignment = Alignment.CenterStart) {
            Image(
                logoImage, contentDescription = app.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                modifier = Modifier.height(180.px).widthIn(max = 900.px)
                    .gameTitleEntrance(homeKey(pc, app), logoImage, reducedMotion),
            )
        } else if (logo.resolved) Row(Modifier.height(200.px), verticalAlignment = Alignment.CenterVertically) {
            val size = when {
                app.name.length > 22 -> 56
                app.name.length > 13 -> 68
                else -> 84
            }
            BasicText(
                app.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    // The color of the game background (its main hue once rendered), else the game's own
                    brush = Brush.verticalGradient(.25f to colors.text, 1f to hsl(HeroBitmaps.hue(homeKey(pc, app)) ?: art.hue2, 70f, 58f)),
                    fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = size.tpx, letterSpacing = (-.01).em,
                ),
                modifier = Modifier.padding(end = 150.px).gameTitleEntrance(homeKey(pc, app), null, reducedMotion),
            )
        } else Box(Modifier.height(200.px)) // Keep the title space while the logo lookup is pending.
        Row(Modifier.padding(top = 10.px), horizontalArrangement = Arrangement.spacedBy(12.px)) {
            if (app.running) Chip(Symbols.PLAY_ARROW, "Running on ${pc.name}") else Chip(Symbols.COMPUTER, pc.name)
            if (app.hdr) Chip(Symbols.HDR_ON, "HDR")
            if (art.icon == Symbols.DESKTOP_WINDOWS && pc.details.vDisplaySupported) Chip(Symbols.SCREEN_SHARE, "Virtual display")
        }
    }
}

/** Both artwork logos and confirmed text fallbacks share the same selection entrance. */
@Composable
private fun Modifier.gameTitleEntrance(game: String, image: ImageBitmap?, reducedMotion: Boolean): Modifier {
    val pixelsPerStagePixel = LocalStage.current.dpPerPx * LocalDensity.current.density
    val entrance = remember(game, image, reducedMotion) { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(entrance) {
        entrance.animateTo(1f, tween(800, easing = CubicBezierEasing(.3f, 0f, .2f, 1f)))
    }
    return graphicsLayer {
        val progress = entrance.value
        // Reveal the title before the movement ends, so its slow settling stays visible.
        alpha = (progress * 1.5f).coerceIn(0f, 1f)
        translationX = (1f - progress) * 32f * pixelsPerStagePixel
    }
}

@Composable
private fun Chip(icon: String, label: String) {
    val colors = LocalDelosColors.current
    Row(
        Modifier
            .clip(CircleShape)
            .glass(colors.surface)
            .border(1.px, colors.line, CircleShape)
            .padding(horizontal = 16.px, vertical = 6.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.px),
    ) {
        Symbol(icon, 22.tpx, colors.text2)
        BasicText(label, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 19.tpx))
    }
}

/** A game cover: the PC's box art, or procedural placeholder art with the title until it is loaded. */
@Composable
private fun GameTile(
    pc: Pc,
    app: App,
    index: Int,
    badge: TileBadge?,
    hidden: Boolean,
    running: App?,
    requester: FocusRequester,
    onFocused: (Int, App) -> Unit,
    onHide: (App, Boolean) -> Unit,
) {
    val colors = LocalDelosColors.current
    val boxArt = LocalBoxArt.current
    val actions = LocalGameActions.current
    val shell = LocalShell.current
    val tracker = LocalFocusTracker.current
    val feedback = LocalFeedback.current
    val artwork = LocalArtwork.current
    val boxImage by rememberBoxArt(boxArt, pc, app)
    val cover by rememberArtwork(artwork, pc, app, ArtKind.COVER)
    val wideCover by rememberArtwork(artwork, pc, app, ArtKind.WIDE)
    val art = appArt(pc, app)
    val wide = index == 0
    // The launch scene shows the portrait cover; the wide first tile its own wide cover
    val image = cover ?: boxImage
    val shown = if (wide) wideCover ?: boxImage else image
    val onArtwork = { shell.navigate(Screen.Artwork(pc, app)) }
    val menu = remember(pc, app, hidden, running, image) {
        gameMenu(pc, app, hidden, running, image, actions, onHide, onArtwork) { shell.navigate(Screen.GameSettings(pc, app)) }
    }
    var focused by remember { mutableStateOf(false) }
    // A starts (or resumes) the game; while another game runs on the PC it offers the choices instead
    val activate = {
        if (running != null && running.id != app.id) {
            feedback.play(Cue.OPEN)
            shell.openLayer(Layer.Context(menu, tracker.bounds()), tracker)
        } else {
            actions.start(pc, app, image)
        }
    }
    Box(
        Modifier
            .focusRequester(requester)
            .focusEffects(
                onClick = activate, corner = 26f, sweep = true, contextMenu = menu,
                clickCue = if (running != null && running.id != app.id) null else Cue.SELECT,
                origin = if (wide) TransformOrigin(1f, .5f) else TransformOrigin.Center,
                onFocusChange = {
                    focused = it
                    if (it) onFocused(index, app)
                },
            )
            // The online pulse of the start screen, around the selected game
            .then(if (focused) Modifier.focusPulse(26f) else Modifier)
            .size(if (wide) 956.px else 300.px, 450.px)
            .clip(RoundedCornerShape(26.px))
            .background(colors.surface2),
    ) {
        Crossfade(shown, animationSpec = tween(400), label = "cover") { img ->
            if (img != null) {
                Image(
                    img, contentDescription = app.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    colorFilter = if (focused) null else IdleCoverFilter,
                )
            } else {
                PlaceholderArt(
                    art.hue, art.hue2, art.icon,
                    modifier = Modifier.fillMaxSize(),
                    glyphSize = if (wide) 420f else 320f,
                    glyphRight = if (wide) 40f else -36f,
                    glyphTop = if (wide) .04f else .24f,
                    title = app.name,
                    titleSize = if (wide) 72f else 34f,
                    titleInset = if (wide) 44f else 26f,
                    titleBottom = if (wide) 40f else 26f,
                )
            }
        }
        if (artwork.isBusy(pc, app)) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(16.px).size(56.px).clip(CircleShape).background(Color(0x8C050A14)),
                contentAlignment = Alignment.Center,
            ) { Spinner(32f) }
        }
        if (badge != null) {
            Row(
                Modifier
                    .padding(18.px)
                    .clip(CircleShape)
                    .background(Color(0x8C050A14))
                    .padding(horizontal = 14.px, vertical = 6.px),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.px),
            ) {
                Symbol(badge.icon, 22.tpx, Color.White, filled = true)
                BasicText(badge.label, style = TextStyle(color = Color.White, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 18.tpx))
            }
        }
    }
}

/** A centered message for the empty, loading and offline states. */
@Composable
private fun HomeMessage(icon: String, title: String, text: String?) {
    val colors = LocalDelosColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.px)) {
            Symbol(icon, 110.tpx, colors.accent, filled = true)
            BasicText(title, style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 52.tpx))
            if (text != null) {
                BasicText(text, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 26.tpx, lineHeight = 1.45.em, textAlign = TextAlign.Center))
            }
        }
    }
}
