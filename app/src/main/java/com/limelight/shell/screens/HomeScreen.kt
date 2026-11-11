package com.limelight.shell.screens

import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.platform.LocalDensity
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.shell.ui.textResources
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
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.content.SharedPreferences
import androidx.compose.runtime.collectAsState
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.GameSettingsStore
import com.limelight.shell.ui.accent3OnDark
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
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
import com.limelight.shell.companion.PublishCompanionGame
import com.limelight.shell.Layer
import com.limelight.shell.LocalFocusTracker
import com.limelight.shell.LocalGameActions
import com.limelight.shell.LocalShell
import com.limelight.shell.HeaderTint
import com.limelight.shell.MenuHeader
import com.limelight.shell.MenuItem
import com.limelight.shell.data.App
import com.limelight.shell.data.Library
import com.limelight.shell.Screen
import com.limelight.shell.data.ArtKind
import com.limelight.shell.ui.Spinner
import com.limelight.shell.ui.HeroBitmaps
import com.limelight.shell.data.LocalArtwork
import com.limelight.shell.data.LocalBoxArt
import com.limelight.shell.data.rememberAnimatedArtwork
import com.limelight.shell.data.rememberArtwork
import com.limelight.shell.data.rememberArtworkResult
import com.limelight.shell.data.Pc
import com.limelight.shell.data.PcRepository
import com.limelight.shell.data.TileArtSource
import com.limelight.shell.data.rememberBoxArt
import com.limelight.shell.data.rememberLookupResolved
import com.limelight.shell.data.tileArtSource
import com.limelight.shell.data.LocalGameMetadata
import com.limelight.shell.data.NativeHdr
import com.limelight.shell.data.BundledArtwork
import com.limelight.shell.settings.Appearance
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.EntranceStagger
import com.limelight.shell.ui.LocalEntranceStagger
import com.limelight.shell.ui.Motion
import com.limelight.shell.ui.staggerEntrance
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.GameArt
import com.limelight.shell.ui.menuDimCoverFilter
import com.limelight.shell.ui.CoverImage
import com.limelight.shell.ui.AnimatedArtLayer
import com.limelight.shell.ui.rememberAnimationTicker
import kotlin.math.roundToInt
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.Compact
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.compact
import com.limelight.shell.ui.PlaceholderArt
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.fadeMask
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.glass
import com.limelight.shell.ui.focusPulse
import com.limelight.shell.ui.hsl
import com.limelight.shell.ui.gameHeaderTint
import com.limelight.shell.ui.px
import com.limelight.shell.ui.topGenres
import com.limelight.shell.ui.topThemes
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Hues and icon of an app's placeholder art, derived from its name. */
fun appArt(pc: Pc, app: App, image: androidx.compose.ui.graphics.ImageBitmap? = null, animated: java.io.File? = null): GameArt {
    val hue = ((app.name.hashCode() and 0x7fffffff) % 360).toFloat()
    val icon = when (BundledArtwork.kind(app)) {
        BundledArtwork.Kind.DESKTOP -> Symbols.DESKTOP_APP
        BundledArtwork.Kind.REMOTE_INPUT -> Symbols.REMOTE_INPUT
        BundledArtwork.Kind.REMOTE_MONITOR -> Symbols.REMOTE_MONITOR
        BundledArtwork.Kind.STEAM_BIG_PICTURE -> Symbols.STEAM
        null -> Symbols.SPORTS_ESPORTS
    }
    return GameArt("${pc.uuid}/${app.id}", hue, (hue + 35f) % 360f, icon, image, animated)
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
 * background, in one scrolling row.
 */
@Composable
fun HomeScreen(library: Library, appearance: Appearance, repo: PcRepository, profileStore: ProfileStore, onMetadata: (Pc, App) -> Unit) {
    val shell = LocalShell.current
    val hasGames = library is Library.Ready && library.apps.isNotEmpty()
    // Without games there is no game background
    LaunchedEffect(hasGames) { if (!hasGames) shell.hero = null }
    when (library) {
        Library.Loading -> HomeMessage(Symbols.LOADING, stringResource(R.string.home_loading), null)
        Library.NoPc -> HomeMessage(Symbols.DEVICES, stringResource(R.string.home_no_pc_title), stringResource(R.string.home_no_pc_note))
        is Library.Offline -> HomeMessage(Symbols.WIFI_OFF, stringResource(R.string.home_offline_title, library.pc.name), stringResource(R.string.home_offline_note))
        is Library.Ready -> if (hasGames) {
            val metadata = LocalGameMetadata.current
            val apps = library.apps.filter { metadata.filters.matches(it, metadata.entry(library.pc, it).data) }
            if (apps.isEmpty()) FilteredLibraryEmpty() else GameLibrary(library, apps, appearance, repo, profileStore, onMetadata)
        } else {
            HomeMessage(Symbols.SPORTS_ESPORTS, stringResource(R.string.home_empty_title, library.pc.name), stringResource(R.string.home_empty_note))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameLibrary(library: Library.Ready, apps: List<App>, appearance: Appearance, repo: PcRepository, profileStore: ProfileStore, onMetadata: (Pc, App) -> Unit) {
    val shell = LocalShell.current
    val res = textResources()
    // The remembered tile callbacks below outlive a language change
    val currentRes by rememberUpdatedState(res)
    val feedback = LocalFeedback.current
    val stage = LocalStage.current
    val boxArt = LocalBoxArt.current
    val pc = library.pc
    val uiScale = appearance.uiScale / 100f

    val focusedIndex = apps.indexOfFirst { homeKey(pc, it) == shell.homeGame }.takeIf { it >= 0 } ?: 0
    val focused = apps[focusedIndex]
    val metadata = LocalGameMetadata.current
    LaunchedEffect(metadata, pc.uuid, focused.id, focused.name) { metadata.ensure(pc, focused) }
    val artwork = LocalArtwork.current
    val heroImage by rememberArtwork(artwork, pc, focused, ArtKind.HERO)
    // The hero's file when it is an animated WebP: it plays as the game background (heroImage is its first frame)
    val heroAnimation by rememberAnimatedArtwork(artwork, pc, focused, ArtKind.HERO)
    // The game's colors come from its cover, which its tile hands to HeroBitmaps.analyzeCover
    val coverImage by rememberArtwork(artwork, pc, focused, ArtKind.COVER)
    // The local/built-in hero, else the cover, then the PC's artwork for ordinary games.
    val focusedResolved by rememberLookupResolved(artwork, pc, focused)
    val boxSource = tileArtSource(artwork.lookupExpected(focused), focusedResolved, coverImage != null) == TileArtSource.BOX_ART
    val boxImage by rememberBoxArt(boxArt, pc, focused, load = boxSource)
    val image = heroImage ?: coverImage ?: boxImage.takeIf { boxSource }
    // The game background follows the focus with a short delay, so it does not flicker while scrolling fast
    LaunchedEffect(homeKey(pc, focused), image, heroAnimation) {
        delay(140)
        shell.hero = appArt(pc, focused, image, heroAnimation.takeIf { heroImage != null })
    }

    val requesters = remember(pc.uuid) { HashMap<Int, FocusRequester>() }
    fun requester(app: App) = requesters.getOrPut(app.id) { FocusRequester() }
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val windowInfo = LocalWindowInfo.current
    // Animated artwork (covers, the wide cover, the logo) plays only for the games on screen, and only while nothing covers
    // or stops it; one ticker draws every playing tile
    val artMotion = rememberArtworkMotion { it == Screen.Home }
    val visibleGames = remember {
        derivedStateOf { rowState.layoutInfo.visibleItemsInfo.map { it.key }.filterIsInstance<Int>().toSet() }
    }
    val ticker = rememberAnimationTicker(artMotion.value)
    val dashboardArt = remember(ticker, artMotion, visibleGames) { DashboardArt(ticker, artMotion, visibleGames) }

    // Initial focus when the library appears: jump to the focused game, focus it silently.
    // Not keyed on the focused game: moving the focus is animated by anchor() alone.
    LaunchedEffect(pc.uuid) {
        if (shell.homeGame != homeKey(pc, focused)) shell.homeGame = homeKey(pc, focused)
        rowState.scrollToItem(focusedIndex)
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
    val onTileFocused: (Int, App) -> Unit = remember(pc.uuid, scope) {
        { index, app ->
            shell.homeGame = homeKey(pc, app)
            // Keep the focused tile at the left edge
            scope.launch {
                val info = rowState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                if (info == null) rowState.scrollToItem(index) else scroll(rowState, info.offset.toFloat(), reducedMotion)
            }
        }
    }
    val running = library.apps.firstOrNull { it.running }
    // The companion screen of a dual-screen device shows the focused game and its buttons
    PublishCompanionGame(pc, focused, image, running, profileStore)
    val onHide: (App, Boolean) -> Unit = remember(pc.uuid) {
        { app, hide ->
            repo.setHidden(pc.uuid, app.id, hide)
            if (hide) shell.showToast(currentRes.getString(R.string.home_hidden_toast, app.name), Symbols.VISIBILITY_OFF)
        }
    }

    // The games with their own settings (docs/GUI.md): read once per change of the library, profile or stored settings, not per tile
    val customGames = rememberCustomGames(profileStore, pc, library.apps)

    // The covers' sizes: the prototype's, or the compact stage's, which lays the whole screen out top-down (HomeCompact)
    val compact = stage.compact
    val home = remember(stage.stageWidth, stage.stageHeight, stage.shape, stage.safe, uiScale) {
        if (stage.compact) HomeCompact.layout(stage.stageWidth, stage.stageHeight, uiScale, stage.safe.top, stage.safe.bottom) else null
    }
    val covers = remember(home) { if (home != null) CoverGeometry(home.coverScale, HomeCompact.rowStart(home.coverScale)) else StandardCovers }

    val tile: @Composable (Int, App) -> Unit = { index, app ->
        GameTile(
            pc, app, index = index,
            custom = app.id in customGames,
            badge = when {
                app.running -> TileBadge.RUNNING
                index == 0 && library.lastPlayed == app.id -> TileBadge.LAST_PLAYED
                else -> null
            },
            hidden = app.id in library.hidden,
            running = running,
            matchGameColors = appearance.matchGameColors && !appearance.materialYou,
            requester = requester(app),
            geometry = covers,
            onFocused = onTileFocused, onHide = onHide,
            onMetadata = onMetadata,
        )
    }

    // The covers of the first screenful enter one after the other (docs/GUI.md, "Motion"); the ones a scroll brings in later are just there
    val intro = remember(pc.uuid) { EntranceStagger(enabled = true, cap = Motion.STAGGER_CAP, lateAfterMs = Motion.STAGGER_WINDOW) }
    CompositionLocalProvider(LocalDashboardArt provides dashboardArt) { Box(Modifier.fillMaxSize()) {
        // Behind an open context menu the info block dims to 40 %
        val infoDim by animateFloatAsState(if (shell.layer is Layer.Context) .4f else 1f, tween(Motion.FADE, easing = Motion.Ease), label = "infoDim")
        // A compact stage lays the block out between the status bar and the covers (HomeCompact)
        val infoStart = if (compact) Compact.SIDE else 150f
        val infoTop = home?.titleTop ?: 150f
        GameInfo(pc, focused, home,
            // The block (logo, GAME INFO label and pill row) sits high enough that the pills clear the focused cover
            modifier = Modifier.graphicsLayer { alpha = infoDim }.padding(start = infoStart.px, top = infoTop.px), reducedMotion = appearance.reducedMotion,
            // The same condition as the animated background's: the genre takes the game's colors only while it does
            matchGameColors = appearance.matchGameColors && !appearance.materialYou)
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll, LocalEntranceStagger provides intro) {
            // At the end the row stops with the last cover at the right edge, so the right fade goes away there; at its start nothing lies
            // beyond the left edge, so the first cover's ring and glow are not faded either
            val endFade by animateFloatAsState(if (rowState.canScrollForward) 1f else 0f, tween(300), label = "rowEndFade")
            val startFade by animateFloatAsState(if (rowState.canScrollBackward) 1f else 0f, tween(300), label = "rowStartFade")
            val density = LocalDensity.current.density
            // Faded out for a moment while a long wrap jumps close to the other end
            val rowAlpha = remember { Animatable(1f) }
            // The spring at the end of a wrap: the row overshoots the end a little and settles back
            val rowBounce = remember { Animatable(0f) }
            LazyRow(
                state = rowState,
                modifier = Modifier
                    .fillMaxSize()
                    // The scroll area reaches down to the stage's bottom edge: the focused cover's glow (about 110 px under a 450 px cover) is never cut
                    .padding(top = (if (home != null) home.coverTop - COMPACT_ROW_PAD else 430f - (uiScale - 1f) * 92f).px)
                    .graphicsLayer {
                        alpha = rowAlpha.value
                        translationX = rowBounce.value
                    }
                    .fadeMask(stage, vertical = false, startClear = 0f, startSolid = 120f * startFade, endSolid = (if (compact) HomeCompact.FADE_SOLID else 260f) * endFade, endClear = (if (compact) HomeCompact.FADE_CLEAR else 20f) * endFade)
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
                            fun now() = covers.itemStart(rowState.firstVisibleItemIndex) * k + rowState.firstVisibleItemScrollOffset
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
                            val end = (covers.start + covers.itemStart(apps.lastIndex) + (if (apps.size == 1) covers.wide else covers.tile) + covers.endPadding) * k -
                                rowState.layoutInfo.viewportSize.width
                            val delta = minOf(covers.itemStart(target) * k, end.coerceAtLeast(0f)) - now()
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
                contentPadding = PaddingValues(
                    start = covers.start.px, end = covers.endPadding.px, top = (if (compact) COMPACT_ROW_PAD else 90f).px,
                    bottom = (if (home != null) stage.stageHeight - home.coverBottom else 110f).px,
                ),
                horizontalArrangement = Arrangement.spacedBy(covers.gap.px),
            ) {
                itemsIndexed(apps, key = { _, app -> app.id }) { i, app -> tile(i, app) }
            }
        }
    } }
}

/** Row layout: the wide first cover, the others and the gap between them, in stage pixels. */
private const val ROW_TILE = 300f
private const val ROW_HEIGHT = 450f
private const val ROW_GAP = 44f

/** The wide first cover is as wide as three covers and the two gaps between them. */
private const val ROW_WIDE = 3 * ROW_TILE + 2 * ROW_GAP

/** A compact stage: the room above the row's covers for the focus scale, the ring, the pulse and the glow (the scroll area would cut them). */
private const val COMPACT_ROW_PAD = 48f

/**
 * The size of the covers and where the row starts, stage pixels: a cover is [scale] times the prototype's 300 x 450 (the wide first one
 * 3 covers and 2 gaps wide, so it keeps its ratio too), its gap and corner scale with it, and [start] is the first cover's left edge.
 * Everywhere but on a compact stage the scale is 1 and the start 150, which are the prototype's numbers.
 */
private class CoverGeometry(val scale: Float, val start: Float) {
    val tile = ROW_TILE * scale
    val height = ROW_HEIGHT * scale
    /** Between two covers: the prototype's 44 scaled, on a compact stage at least the room a focused cover needs ([HomeCompact.coverGap]). */
    val gap = if (scale < 1f) HomeCompact.coverGap(scale) else ROW_GAP * scale
    val wide = 3 * tile + 2 * ROW_GAP * scale
    val corner = 26f * scale

    /**
     * The right margin at the end of the row, mirroring the start: the focused wide first cover grows only to
     * the left (so it never covers its neighbour), which brings its left edge closer to the screen's edge,
     * while the other covers grow evenly. So the last cover, focused, is as far from the right edge as the
     * first game, focused, is from the left one (about 97 px at the prototype's size).
     */
    val endPadding = start - wide * (TILE_FOCUS_SCALE - 1f) + tile * (TILE_FOCUS_SCALE - 1f) / 2f

    /** Where item [index] starts in the row, from the start of its content (stage pixels). */
    fun itemStart(index: Int): Float = if (index == 0) 0f else wide + gap + (index - 1) * (tile + gap)
}

/** The prototype's covers: a compact stage has its own ([HomeCompact]). */
private val StandardCovers = CoverGeometry(1f, 150f)

/** A wrap round the row glides over the last this many covers (the rest is a quick jump). */
private const val WRAP_GLIDE_ITEMS = 12

/** How much a cover grows when focused (focusEffects' default). */
private const val TILE_FOCUS_SCALE = 1.055f

private suspend fun scroll(state: ScrollableState, delta: Float, reducedMotion: Boolean) {
    if (kotlin.math.abs(delta) < 1f) return
    if (reducedMotion) state.scrollBy(delta) else state.animateScrollBy(delta, FastScroll)
}

/**
 * The ids of [apps] with custom per-game settings in the current profile ([GameSettingsStore.customAppIds]), recomputed when the
 * profile switches or any of the profile's stored settings change (the per-game screen writing or resetting an override).
 */
@Composable
private fun rememberCustomGames(profileStore: ProfileStore, pc: Pc, apps: List<App>): Set<Int> {
    val profiles by profileStore.profiles.collectAsState()
    var version by remember(pc.uuid, profiles.currentId) { mutableIntStateOf(0) }
    DisposableEffect(pc.uuid, profiles.currentId) {
        val base = profileStore.settings()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        base.registerOnSharedPreferenceChangeListener(listener)
        onDispose { base.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(pc.uuid, profiles.currentId, apps, version) {
        GameSettingsStore.customAppIds(profileStore.settings().all, pc.uuid, apps.map { it.id })
    }
}

/** The badge on a cover's top-left corner. */
enum class TileBadge(val icon: String, @StringRes val label: Int) {
    RUNNING(Symbols.RUNNING, R.string.home_badge_running),
    LAST_PLAYED(Symbols.HISTORY, R.string.home_badge_last_played),
}

/**
 * The context menu of a game, depending on what runs on the PC: this game (resume / quit), another
 * game (quit that one and start this one, or go back to it) or nothing (start). No confirmation
 * dialogs: choosing "Quit" in this menu is the confirmation.
 */
private fun gameMenu(
    res: Resources,
    pc: Pc, app: App, hidden: Boolean, running: App?, image: ImageBitmap?, actions: GameActions, onHide: (App, Boolean) -> Unit,
    /** The facts the header states under the game's name. */
    lastPlayed: Boolean,
    custom: Boolean,
    /** The icon color of the header: the game's main color. */
    tint: () -> HeaderTint,
    /** Opens the artwork screen. */
    onArtwork: () -> Unit,
    onSettings: () -> Unit,
    onMetadata: () -> Unit,
) =
    ContextMenu(
        app.name,
        header = gameMenuHeader(res, app, hidden, running, tint, lastPlayed, custom),
        items = buildList {
            when {
                app.running -> {
                    add(MenuItem(Symbols.RESUME, res.getString(R.string.home_menu_resume)) { actions.start(pc, app, image) })
                    add(MenuItem(Symbols.POWER_SETTINGS_NEW, res.getString(R.string.home_menu_quit), danger = true) { actions.quit(pc, app) })
                }
                running != null -> {
                    add(MenuItem(Symbols.SWAP_GAME, res.getString(R.string.home_menu_quit_and_start, running.name), danger = true) { actions.quitAndStart(pc, running, app, image) })
                    add(MenuItem(Symbols.RESUME, res.getString(R.string.home_menu_resume_other, running.name)) { actions.start(pc, running, null) })
                }
                else -> add(MenuItem(Symbols.PLAY_ARROW, res.getString(R.string.home_menu_start)) { actions.start(pc, app, image) })
            }
            if (BundledArtwork.kind(app) == null) {
                add(MenuItem(Symbols.PHOTO_LIBRARY, res.getString(R.string.home_menu_artwork), action = onArtwork))
                add(MenuItem(Symbols.METADATA, res.getString(R.string.home_menu_metadata), action = onMetadata))
            }
            add(MenuItem(Symbols.SETTINGS, res.getString(R.string.home_menu_settings), action = onSettings))
            add(
                if (hidden) MenuItem(Symbols.VISIBILITY, res.getString(R.string.home_menu_show)) { onHide(app, false) }
                else MenuItem(Symbols.VISIBILITY_OFF, res.getString(R.string.home_menu_hide)) { onHide(app, true) }
            )
        },
    )

/** The game menu's header: an icon in the game's main color, whether it runs, and what else is worth a line (last played, custom settings, another game running, hidden). */
private fun gameMenuHeader(res: Resources, app: App, hidden: Boolean, running: App?, tint: () -> HeaderTint, lastPlayed: Boolean, custom: Boolean): MenuHeader {
    val other = running?.takeIf { it.id != app.id }
    return MenuHeader(
        status = res.getString(if (app.running) R.string.home_header_running else R.string.home_header_game),
        tint = tint,
        running = app.running,
        meta = listOfNotNull(
            res.getString(R.string.home_header_last_played).takeIf { lastPlayed },
            other?.let { res.getString(R.string.home_header_other_running, it.name) },
            res.getString(R.string.home_header_custom).takeIf { custom },
            res.getString(R.string.home_header_hidden).takeIf { hidden },
        ).reduceOrNull { facts, next -> res.getString(R.string.home_meta_join, facts, next) },
    )
}

@Composable
private fun GameInfo(pc: Pc, app: App, home: HomeCompactLayout?, modifier: Modifier, reducedMotion: Boolean, matchGameColors: Boolean) {
    val colors = LocalDelosColors.current
    val art = appArt(pc, app)
    // A compact stage shows the title smaller in a shorter box ([home] says how tall), without the "Game info" label, and lets the pills use the whole width
    val stage = LocalStage.current
    val compact = home != null
    val titleBox = home?.titleBox ?: 200f
    // The logo keeps 10 px of air above and below in its box, 4 in a box that is only as tall as the title
    val logoPad = if (home != null && home.titleBox < 100f) 4f else 10f
    val logoHeight = if (home != null) home.titleBox - 2f * logoPad else 180f
    val logoWidth = if (compact) minOf(900f, stage.stageWidth - 2f * Compact.SIDE) else 900f
    Column(modifier) {
        val logo by rememberArtworkResult(LocalArtwork.current, pc, app, ArtKind.LOGO)
        val logoImage = logo.image
        // The SteamGridDB logo stands in for the icon and the name
        if (logoImage != null) Box(Modifier.height(titleBox.px).padding(vertical = logoPad.px), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.gameTitleEntrance(homeKey(pc, app), logoImage, reducedMotion)) {
                Image(
                    logoImage, contentDescription = app.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                    modifier = Modifier.height(logoHeight.px).widthIn(max = logoWidth.px),
                )
                // The chosen animated logo plays over its first frame, fitted into the same box (start-aligned like the still)
                val artwork = LocalArtwork.current
                val animatedArt = LocalDashboardArt.current
                val animation by rememberAnimatedArtwork(artwork, pc, app, ArtKind.LOGO)
                if (animatedArt != null && animation != null) {
                    val k = LocalStage.current.dpPerPx * LocalDensity.current.density
                    AnimatedArtLayer(
                        animation, animatedArt.motionOn, animatedArt.ticker, (logoWidth * k).roundToInt(), (logoHeight * k).roundToInt(),
                        Modifier.matchParentSize(), fit = true, alignX = 0f, stamp = artwork.version.intValue,
                    )
                }
            }
        // A compact stage stands the title on the bottom of its box, as a logo fills it: the gap to the pills under it is the same for every game
        } else if (logo.resolved) Row(Modifier.height(titleBox.px), verticalAlignment = if (compact) Alignment.Bottom else Alignment.CenterVertically) {
            val size = when {
                app.name.length > 22 -> if (compact) 44 else 56
                app.name.length > 13 -> if (compact) 50 else 68
                else -> if (compact) 60 else 84
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
                // A compact stage's box may be lower than the line of the title: the line is centred on it, not cut to it
                modifier = Modifier.padding(end = (if (compact) Compact.SIDE else 150f).px)
                    .then(if (compact) Modifier.wrapContentHeight(unbounded = true) else Modifier)
                    .gameTitleEntrance(homeKey(pc, app), null, reducedMotion),
            )
        } else Box(Modifier.height(titleBox.px)) // Keep the title space while the logo lookup is pending.
        val metadata = LocalGameMetadata.current.entry(pc, app).data
        // Up to two genres and two themes: Steam's user tags when the game has them, else IGDB's lists (the filter
        // still offers every IGDB genre and theme); the facts come first
        val genres = remember(metadata) {
            topGenres(metadata?.candidate?.genres.orEmpty().filter { it.name.isNotBlank() }, metadata?.steamTags.orEmpty())
        }
        val themes = remember(metadata) {
            topThemes(metadata?.candidate?.themes.orEmpty().filter { it.name.isNotBlank() }, metadata?.steamTags.orEmpty())
        }
        // The colors of the animated background: the cover's, the game background's while those are unknown
        val gameColors = if (matchGameColors) HeroBitmaps.gameColors(homeKey(pc, app)) else null
        val hdrKind = hdrBadgeKind(metadata)
        val completion = metadata?.completion?.takeIf { it.seconds > 0 }
        val virtualDisplay = BundledArtwork.kind(app) == BundledArtwork.Kind.DESKTOP && pc.details.vDisplaySupported
        val hasTags = genres.isNotEmpty() || themes.isNotEmpty()
        // The title above the pills is shown only while there is at least one pill under it
        val showLabel = !compact && (hasTags || app.running || hdrKind != null || completion != null || virtualDisplay)
        if (showLabel) BasicText(
            stringResource(if (BundledArtwork.kind(app) != null) R.string.home_info_app else R.string.home_info_game),
            modifier = Modifier.padding(top = 10.px),
            style = TextStyle(
                color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.SemiBold,
                fontSize = 17.tpx, letterSpacing = .14.em,
            ),
        )
        InfoRow(
            Modifier.padding(top = if (home != null) home.titleGap.px else if (showLabel) 8.px else 10.px).widthIn(max = (if (compact) stage.stageWidth - 2f * Compact.SIDE else 1620f).px),
            hasTags = hasTags,
            tags = {
                if (genres.isNotEmpty()) GenrePill(genres, gameColors)
                if (themes.isNotEmpty()) ThemePill(themes, gameColors)
            },
        ) {
            if (app.running) Chip(Symbols.RUNNING, stringResource(R.string.home_chip_running_on, pc.name))
            when (hdrKind) {
                NativeHdr.HDR -> NativeHdrChip()
                NativeHdr.SDR -> Chip(Symbols.SDR, "SDR")
                NativeHdr.UNKNOWN -> Chip(Symbols.HDR_UNKNOWN, stringResource(R.string.metadata_hdr_unverified))
                null -> Unit
            }
            if (completion != null) Chip(Symbols.COMPLETION_TIME, stringResource(R.string.home_chip_hltb, completionText(textResources(), completion.seconds)))
            if (virtualDisplay) Chip(Symbols.SCREEN_SHARE, stringResource(R.string.home_chip_virtual_display))
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

/** An info pill (icon and label) in the glass style, shared by the dashboard and the game settings header. */
@Composable
internal fun Chip(icon: String, label: String, iconTint: Color? = null) {
    val colors = LocalDelosColors.current
    Pill(listOf(PillEntry(icon, label)), colors.text2, iconTint ?: colors.text2, Modifier.glass(colors.surface), colors.line)
}

/**
 * The shape and size of every info pill; [fill] is what lies behind its content, [border] its 1 px outline. The
 * [entries] (icon and label each) sit in one pill, set apart by a thin vertical bar in the text color.
 */
@Composable
internal fun Pill(entries: List<PillEntry>, textColor: Color, iconColor: Color, fill: Modifier, border: Color) {
    Row(
        Modifier
            .clip(CircleShape)
            .then(fill)
            .border(1.px, border, CircleShape)
            .padding(horizontal = 16.px, vertical = 6.px),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) {
                Box(
                    Modifier.padding(horizontal = 12.px).width(1.5.px).height(18.px).clip(CircleShape)
                        .background(textColor.copy(alpha = .55f)),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.px)) {
                Symbol(entry.icon, 22.tpx, iconColor)
                BasicText(entry.label, style = TextStyle(color = textColor, fontFamily = Figtree, fontSize = 19.tpx))
            }
        }
    }
}

/** A game cover: the PC's box art, or procedural placeholder art with the title until it is loaded. */
@Composable
private fun GameTile(
    pc: Pc,
    app: App,
    index: Int,
    /** The game has custom per-game settings: a small icon in the top-right corner. */
    custom: Boolean,
    badge: TileBadge?,
    hidden: Boolean,
    running: App?,
    matchGameColors: Boolean,
    requester: FocusRequester,
    geometry: CoverGeometry,
    onFocused: (Int, App) -> Unit,
    onHide: (App, Boolean) -> Unit,
    onMetadata: (Pc, App) -> Unit,
) {
    val res = textResources()
    val locales = LocalConfiguration.current.locales
    val customDescription = stringResource(R.string.home_tile_custom_description)
    val colors = LocalDelosColors.current
    val boxArt = LocalBoxArt.current
    val actions = LocalGameActions.current
    val shell = LocalShell.current
    val tracker = LocalFocusTracker.current
    val feedback = LocalFeedback.current
    val artwork = LocalArtwork.current
    val cover by rememberArtwork(artwork, pc, app, ArtKind.COVER)
    val wideCover by rememberArtwork(artwork, pc, app, ArtKind.WIDE)
    val art = appArt(pc, app)
    val wide = index == 0
    // While SteamGridDB's lookup of the game is pending the tile shows its placeholder, not the host's box art (which would be
    // replaced by the cover a moment later); box art is loaded only when no cover exists or no lookup is expected
    val lookupResolved by rememberLookupResolved(artwork, pc, app)
    val source = tileArtSource(artwork.lookupExpected(app), lookupResolved, (if (wide) wideCover else cover) != null)
    val boxImage by rememberBoxArt(boxArt, pc, app, load = source == TileArtSource.BOX_ART)
    val box = boxImage.takeIf { source == TileArtSource.BOX_ART }
    val pending = source == TileArtSource.PLACEHOLDER
    // The launch scene shows the portrait cover; the wide first tile its own wide cover
    val image = cover ?: box
    val shown = if (wide) wideCover ?: box else image
    val onArtwork = { shell.navigate(Screen.Artwork(pc, app)) }
    // The menu header's icon takes the game's main color: its cover's, worked out here as soon as the cover is decoded (not when
    // the game is focused or the menu opens), so it is there from the menu's first frame; the placeholder art's hue while the
    // game has no artwork. The animated background and the info block read the same colors
    val key = homeKey(pc, app)
    LaunchedEffect(key, image) {
        image?.let { withContext(Dispatchers.Default) { HeroBitmaps.analyzeCover(key, it) } }
    }
    val menu = remember(pc, app, hidden, running, image, pending, onMetadata, badge, custom, matchGameColors, locales) {
        gameMenu(
            res,
            pc, app, hidden, running, image, actions, onHide, lastPlayed = badge == TileBadge.LAST_PLAYED, custom = custom,
            tint = { gameHeaderTint(matchGameColors, image != null || pending, HeroBitmaps.coverKnown(key), HeroBitmaps.coverHues(key), art.hue) },
            onArtwork = onArtwork,
            onSettings = { shell.navigate(Screen.GameSettings(pc, app)) },
            onMetadata = { onMetadata(pc, app) },
        )
    }
    var focused by remember { mutableStateOf(false) }
    // The covers beside an open context menu dim (the prototype's `.menu-open`); the focused one keeps its colors
    val menuDim = animateFloatAsState(if (shell.layer is Layer.Context) 1f else 0f, tween(Motion.FADE, easing = Motion.Ease), label = "menuDim")
    // Read while drawing: the dimming animation redraws the cover, it does not recompose the tile
    val coverFilter = { if (focused) null else menuDimCoverFilter(menuDim.value) }
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
                onClick = activate, corner = geometry.corner, sweep = true, contextMenu = menu,
                clickCue = if (running != null && running.id != app.id) null else Cue.SELECT,
                origin = if (wide) TransformOrigin(1f, .5f) else TransformOrigin.Center,
                onFocusChange = { if (it) onFocused(index, app) },
                // The cover stays lit (ring, scale, colors, pulse) while its context menu is open
                onLookChange = { focused = it },
            )
            // The online pulse of the start screen, around the selected game
            .then(if (focused) Modifier.focusPulse(geometry.corner) else Modifier)
            .staggerEntrance()
            .size((if (wide) geometry.wide else geometry.tile).px, geometry.height.px)
            .clip(RoundedCornerShape(geometry.corner.px))
            .background(colors.surface2),
    ) {
        Crossfade(shown, animationSpec = tween(400), label = "cover") { img ->
            if (img != null) {
                CoverImage(img, app.name, Modifier.fillMaxSize(), coverFilter)
            } else {
                PlaceholderArt(
                    art.hue, art.hue2, art.icon,
                    modifier = Modifier.fillMaxSize(),
                    glyphSize = (if (wide) 420f else 320f) * geometry.scale,
                    glyphRight = (if (wide) 40f else -36f) * geometry.scale,
                    glyphTop = if (wide) .04f else .24f,
                    title = app.name,
                    // A compact stage's small covers set the placeholder's title at most 20 % smaller
                    titleSize = (if (wide) 72f else 34f) * geometry.scale.coerceAtLeast(.8f).coerceAtMost(1f),
                    titleInset = (if (wide) 44f else 26f) * geometry.scale.coerceAtLeast(.7f),
                    titleBottom = (if (wide) 40f else 26f) * geometry.scale,
                    titleFit = geometry.scale < 1f,
                )
            }
        }
        // The chosen animated cover plays over its still first frame, cropped and muted exactly like it (the idle filter), only while
        // the tile is on screen; decoded no bigger than the tile (its focused size) needs
        val animatedArt = LocalDashboardArt.current
        val animation by rememberAnimatedArtwork(artwork, pc, app, if (wide) ArtKind.WIDE else ArtKind.COVER)
        val playing by remember(animatedArt, app.id) { derivedStateOf { animatedArt?.plays(app.id) == true } }
        if (animatedArt != null && animation != null && (if (wide) wideCover else cover) != null) {
            val k = LocalStage.current.dpPerPx * LocalDensity.current.density * TILE_FOCUS_SCALE
            AnimatedArtLayer(
                animation, playing, animatedArt.ticker, ((if (wide) geometry.wide else geometry.tile) * k).roundToInt(), (geometry.height * k).roundToInt(),
                Modifier.fillMaxSize(), colorFilter = coverFilter, stamp = artwork.version.intValue,
            )
        }
        if (artwork.isBusy(pc, app)) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(16.px).size(56.px).clip(CircleShape).background(Color(0x8C050A14)),
                contentAlignment = Alignment.Center,
            ) { Spinner(32f) }
        } else if (custom) {
            // Above the (idle-filtered) art; the busy spinner takes this corner while it shows
            // The same pill as the top-left badge (dark glass, white filled glyph), icon only so it never meets that badge on a narrow cover
            Box(
                Modifier.align(Alignment.TopEnd).padding(18.px).clip(CircleShape).background(Color(0x8C050A14))
                    .padding(horizontal = 14.px, vertical = 6.px)
                    .semantics { contentDescription = customDescription },
                contentAlignment = Alignment.Center,
            ) { Symbol(Symbols.TUNE, 22.tpx, Color.White, filled = true) }
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
                BasicText(stringResource(badge.label), style = TextStyle(color = Color.White, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 18.tpx))
            }
        }
    }
}

/** A centered message for the empty, loading and offline states. */
@Composable
private fun HomeMessage(icon: String, title: String, text: String?) {
    val colors = LocalDelosColors.current
    // A compact stage keeps the message off the edges and sets its icon and headline smaller
    val compact = LocalStage.current.compact
    Box(Modifier.fillMaxSize().then(if (compact) Modifier.padding(horizontal = Compact.MARGIN.px) else Modifier), contentAlignment = Alignment.Center) {
        Column(Modifier.staggerEntrance(0, Motion.MENU_DELAY), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.px)) {
            Symbol(icon, (if (compact) 84 else 110).tpx, colors.accent, filled = true)
            BasicText(title, style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = (if (compact) 42 else 52).tpx, textAlign = if (compact) TextAlign.Center else TextAlign.Unspecified))
            if (text != null) {
                BasicText(text, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 26.tpx, lineHeight = 1.45.em, textAlign = TextAlign.Center))
            }
        }
    }
}
