package com.limelight.shell.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import com.limelight.shell.ui.MarginBringIntoView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.limelight.shell.ContextMenu
import com.limelight.shell.DialogField
import com.limelight.shell.DialogSpec
import com.limelight.shell.Layer
import com.limelight.shell.LocalFocusTracker
import com.limelight.shell.LocalShell
import com.limelight.shell.MenuItem
import com.limelight.shell.Screen
import com.limelight.shell.ShellState
import com.limelight.shell.FocusTracker
import com.limelight.shell.data.App
import com.limelight.shell.data.ArtKind
import com.limelight.shell.data.ArtworkStore
import com.limelight.shell.data.LocalArtwork
import com.limelight.shell.data.LocalBoxArt
import com.limelight.shell.data.NONE
import com.limelight.shell.data.Pc
import com.limelight.shell.data.SgdbGame
import com.limelight.shell.data.SgdbImage
import com.limelight.shell.data.rememberArtworkResult
import com.limelight.shell.data.BundledArtwork
import com.limelight.shell.data.rememberBoxArt
import com.limelight.shell.data.rememberThumbnail
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.Feedback
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.FocusGlow
import com.limelight.shell.ui.FocusRing
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.PillButton
import com.limelight.shell.ui.PlaceholderArt
import com.limelight.shell.ui.Spinner
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.fadeEdges
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.glass
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The tab of each kind on the artwork screen, with its grid and the shape of its images. */
private enum class ArtTab(val kind: ArtKind, val label: String, val icon: String, val columns: Int, val ratio: Float) {
    COVER(ArtKind.COVER, "Cover", Symbols.IMAGE, 5, 600f / 900f),
    WIDE(ArtKind.WIDE, "Wide cover", Symbols.ASPECT_RATIO, 3, 920f / 430f),
    HERO(ArtKind.HERO, "Background", Symbols.WALLPAPER, 2, 1920f / 620f),
    LOGO(ArtKind.LOGO, "Logo", Symbols.BADGE, 3, 2f),
    ;

    fun step(dir: Int) = entries[Math.floorMod(ordinal + dir, entries.size)]
}

/** What the grid of a tab shows. */
private sealed interface Choices {
    data object Loading : Choices
    data object NoKey : Choices
    data object NoGame : Choices
    data class Failed(val message: String) : Choices
    data class Ready(val images: List<SgdbImage>) : Choices
}

/**
 * The artwork of one game (the game's options → Artwork): a tab per kind (cover, wide cover, background,
 * logo, switched with L1 / R1), the image used now on the left and every SteamGridDB image to choose from
 * on the right, the first choice being the built-in/PC fallback (or the name instead of a logo). "Change
 * game" fixes a wrong match. Automatic downloads take the best-rated image of each kind.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtworkScreen(screen: Screen.Artwork) {
    val shell = LocalShell.current
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
    val version = store.version.intValue
    val match = remember(version) { store.matched(pc, app) }
    var busy by remember { mutableStateOf(false) }

    // Never looked up yet: find the game first (and download its best-rated images like the automatic run)
    LaunchedEffect(store.enabled) {
        if (store.enabled && store.eligible(app) && !store.searched(pc, app)) {
            busy = true
            runCatching { store.fetch(pc, app) }.onFailure { shell.showToast(it.message ?: "SteamGridDB is not reachable", Symbols.ERROR) }
            busy = false
        }
    }
    val choices by produceState<Choices>(Choices.Loading, tab, match?.id, store.enabled, busy) {
        value = Choices.Loading
        value = when {
            !store.enabled -> Choices.NoKey
            busy -> Choices.Loading
            match == null -> Choices.NoGame
            else -> runCatching { Choices.Ready(store.choices(pc, app, tab.kind)) }.getOrElse { Choices.Failed(it.message ?: "SteamGridDB is not reachable") }
        }
    }

    val focusTransfer = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    val window = LocalWindowInfo.current
    LaunchedEffect(tab, choices is Choices.Ready) {
        snapshotFlow { window.isWindowFocused }.first { it }
        withFrameNanos { }
        feedback.silenceNextFocus()
        runCatching { first.requestFocus() }
    }
    DisposableEffect(shell) {
        val handler: (Int) -> Unit = { dir ->
            focusTransfer.requestFocus()
            tab = tab.step(dir)
            feedback.play(Cue.MOVE)
        }
        shell.onShoulder = handler
        onDispose { if (shell.onShoulder === handler) shell.onShoulder = null }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.size(0.px).focusRequester(focusTransfer).focusable())
        Column(Modifier.offset(150.px, 92.px).width(1620.px)) {
            BasicText(
                "ARTWORK",
                style = TextStyle(color = colors.accent2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx, letterSpacing = .14.em),
            )
            BasicText(
                app.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 46.tpx),
            )
        }
        Row(Modifier.offset(y = 206.px).fillMaxWidth().height(66.px), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ShoulderLabel("L1")
            ArtTab.entries.forEach { next ->
                TabPill(next.icon, next.label, tab == next, Modifier.padding(horizontal = 5.px)) {
                    if (next != tab) {
                        focusTransfer.requestFocus()
                        tab = next
                    } else runCatching { first.requestFocus() }
                }
            }
            ShoulderLabel("R1")
        }
        Row(
            Modifier.fillMaxSize().padding(start = 150.px, end = 150.px, top = 312.px, bottom = 110.px),
            horizontalArrangement = Arrangement.spacedBy(56.px),
        ) {
            CurrentPanel(pc, app, tab, match, busy, Modifier.width(460.px)) {
                changeGame(scope, shell, tracker, feedback, store, pc, app, app.name)
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                when (val c = choices) {
                    Choices.Loading -> GridMessage(Symbols.REFRESH, "Looking on SteamGridDB…", null)
                    Choices.NoKey -> GridMessage(Symbols.KEY, "Add your SteamGridDB API key", "Settings → Covers → SteamGridDB API key. It is free on steamgriddb.com (Preferences → API).")
                    Choices.NoGame -> if (store.eligible(app)) GridMessage(Symbols.SEARCH, "${app.name} is not on SteamGridDB", "Change game finds it under another name.")
                    else GridMessage(Symbols.SEARCH, "Choose what ${app.name} looks like", "Change game: e.g. Windows, Steam or any game whose artwork you like. ${app.name} is not looked up automatically.")
                    is Choices.Failed -> GridMessage(Symbols.ERROR, "SteamGridDB is not reachable", c.message)
                    is Choices.Ready -> {
                        val chosen = remember(version, tab) { store.chosen(pc, app, tab.kind) }
                        val grid = rememberLazyGridState()
                        // The focused row keeps clear of the faded edges, like the settings rows
                        val k = stage.dpPerPx * LocalDensity.current.density
                        val bringIntoView = remember(k) { MarginBringIntoView(before = 70f * k, after = 110f * k) }
                        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(tab.columns),
                            state = grid,
                            modifier = Modifier.fillMaxSize().fadeEdges(stage, top = 40f, bottom = 80f),
                            contentPadding = PaddingValues(start = 18.px, end = 18.px, top = 44.px, bottom = 120.px),
                            horizontalArrangement = Arrangement.spacedBy(24.px),
                            verticalArrangement = Arrangement.spacedBy(24.px),
                        ) {
                            item(key = "none") {
                                NoneTile(pc, app, tab, inUse = chosen == NONE || chosen == null && match != null && c.images.isEmpty(), store.applying(pc, app, tab.kind) == NONE, Modifier.focusRequester(first)) {
                                    apply(scope, shell, feedback, store, pc, app, tab, null)
                                }
                            }
                            itemsIndexed(c.images, key = { _, image -> image.id }) { _, image ->
                                ChoiceTile(image, tab, inUse = image.id == chosen, loading = store.applying(pc, app, tab.kind) == image.id) {
                                    apply(scope, shell, feedback, store, pc, app, tab, image)
                                }
                            }
                        }
                        }
                        if (c.images.isEmpty()) {
                            Box(Modifier.fillMaxSize().padding(top = 360.px), contentAlignment = Alignment.TopCenter) {
                                BasicText("SteamGridDB has no ${tab.label.lowercase()} for this game yet.", style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 22.tpx))
                            }
                        }
                    }
                }
            }
        }
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
        BasicText("Built-in artwork - this app's artwork cannot be changed",
            style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 24.tpx))
        PillButton("Back", Symbols.ARROW_BACK, { shell.back() }, Modifier.focusRequester(first), clickCue = Cue.BACK)
    }
}

/** The image used now for [tab]'s kind ("Currently used"), where it comes from and the matched game. */
@Composable
private fun CurrentPanel(pc: Pc, app: App, tab: ArtTab, match: SgdbGame?, busy: Boolean, modifier: Modifier, onChangeGame: () -> Unit) {
    val colors = LocalDelosColors.current
    val store = LocalArtwork.current
    val own by rememberArtworkResult(store, pc, app, tab.kind)
    val cover by rememberArtworkResult(store, pc, app, ArtKind.COVER)
    val boxArt by rememberBoxArt(LocalBoxArt.current, pc, app)
    // What the home screen shows for this kind, in the same order of fallbacks
    val (image, source) = when {
        own.image != null -> own.image to if (own.bundledAsset != null) "Built-in" else "From SteamGridDB"
        tab == ArtTab.LOGO -> null to "The game's name, no logo"
        tab == ArtTab.HERO && cover.image != null -> cover.image to if (cover.bundledAsset != null) "Built-in" else "The SteamGridDB cover"
        boxArt != null -> boxArt to "From your PC (Vibepollo)"
        else -> null to "Placeholder art"
    }
    Column(
        modifier.clip(RoundedCornerShape(30.px)).glass(colors.surface).border(1.px, colors.line, RoundedCornerShape(30.px)).padding(28.px),
        verticalArrangement = Arrangement.spacedBy(18.px),
    ) {
        BasicText("CURRENTLY USED", style = TextStyle(color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 18.tpx, letterSpacing = .12.em))
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (tab == ArtTab.COVER) 1.1f else tab.ratio).clip(RoundedCornerShape(20.px)).background(colors.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(image, animationSpec = tween(300), label = "currentArt") { img ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        img != null && tab == ArtTab.COVER -> Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        img != null && tab == ArtTab.LOGO -> Image(img, null, Modifier.fillMaxSize().padding(24.px), contentScale = ContentScale.Fit)
                        img != null -> Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        tab == ArtTab.LOGO -> NameAsLogo(app.name)
                        else -> {
                            val art = appArt(pc, app)
                            PlaceholderArt(art.hue, art.hue2, art.icon, Modifier.fillMaxSize(), glyphSize = 160f)
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
            if (store.isBusy(pc, app)) Spinner(26f)
            BasicText(if (store.isBusy(pc, app)) "Downloading…" else source, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 21.tpx))
        }
        Box(Modifier.fillMaxWidth().height(1.px).background(colors.line))
        BasicText("STEAMGRIDDB GAME", style = TextStyle(color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 18.tpx, letterSpacing = .12.em))
        BasicText(
            when {
                busy -> "Looking for ${app.name}…"
                match != null -> match.name.ifEmpty { "Game #${match.id}" }
                else -> "Not found yet"
            },
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 24.tpx),
        )
        if (store.enabled) PillButton("Change game", Symbols.SEARCH, onChangeGame, clickCue = null)
    }
}

/** The first choice: no SteamGridDB image (the PC's artwork, or the name instead of a logo). */
@Composable
private fun NoneTile(pc: Pc, app: App, tab: ArtTab, inUse: Boolean, loading: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val boxArt by rememberBoxArt(LocalBoxArt.current, pc, app)
    Tile(tab, inUse, loading, if (tab == ArtTab.LOGO) "Name only" else "From your PC", modifier, onClick) {
        val img = boxArt
        when {
            tab == ArtTab.LOGO -> NameAsLogo(app.name)
            img != null -> Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> {
                val art = appArt(pc, app)
                PlaceholderArt(art.hue, art.hue2, art.icon, Modifier.fillMaxSize(), glyphSize = 120f)
            }
        }
    }
}

@Composable
private fun ChoiceTile(image: SgdbImage, tab: ArtTab, inUse: Boolean, loading: Boolean, onClick: () -> Unit) {
    val store = LocalArtwork.current
    val thumb by rememberThumbnail(store, image.thumb)
    Tile(tab, inUse, loading, null, Modifier, onClick) {
        Crossfade(thumb, animationSpec = tween(300), label = "thumb") { img ->
            if (img != null) {
                Image(
                    img, null,
                    if (tab == ArtTab.LOGO) Modifier.fillMaxSize().padding(18.px) else Modifier.fillMaxSize(),
                    contentScale = if (tab == ArtTab.LOGO) ContentScale.Fit else ContentScale.Crop,
                )
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(36f, color = LocalDelosColors.current.text3) }
        }
    }
}

/** A choice in the grid: the image in [tab]'s shape, the "In use" badge, a spinner while [loading], an optional caption. */
@Composable
private fun Tile(tab: ArtTab, inUse: Boolean, loading: Boolean, caption: String?, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(20.px)
    Box(
        modifier
            .focusEffects(onClick, corner = 20f, focusScale = 1.06f, ring = FocusRing(4f, 4f), glow = FocusGlow(18f, 50f, -12f), clickCue = null)
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
                Modifier.padding(12.px).clip(CircleShape).background(colors.accent).padding(horizontal = 12.px, vertical = 4.px),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.px),
            ) {
                Symbol(Symbols.CHECK, 20.tpx, colors.onAccent)
                BasicText("In use", style = TextStyle(color = colors.onAccent, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 16.tpx))
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
    Column(Modifier.fillMaxSize().padding(top = 120.px), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.px)) {
        Symbol(icon, 90.tpx, colors.accent, filled = true)
        BasicText(title, style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 34.tpx, textAlign = TextAlign.Center))
        if (text != null) BasicText(text, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx, textAlign = TextAlign.Center), modifier = Modifier.width(820.px))
    }
}

private fun apply(scope: CoroutineScope, shell: ShellState, feedback: Feedback, store: ArtworkStore, pc: Pc, app: App, tab: ArtTab, image: SgdbImage?) {
    feedback.play(Cue.SELECT)
    scope.launch {
        if (image != null) shell.showToast("Downloading the ${tab.label.lowercase()}…", Symbols.DOWNLOAD)
        runCatching { store.apply(pc, app, tab.kind, image) }
            .onSuccess { shell.showToast("${tab.label} updated", Symbols.CHECK_CIRCLE) }
            .onFailure {
                feedback.play(Cue.ERROR)
                shell.showToast(it.message ?: "The download failed", Symbols.ERROR)
            }
    }
}

/** "Change game": the SteamGridDB games found for [term], and searching under another name. */
private fun changeGame(scope: CoroutineScope, shell: ShellState, tracker: FocusTracker, feedback: Feedback, store: ArtworkStore, pc: Pc, app: App, term: String) {
    scope.launch {
        shell.showToast("Searching SteamGridDB for $term…", Symbols.SEARCH)
        val found = runCatching { store.search(term) }.getOrElse {
            feedback.play(Cue.ERROR)
            shell.showToast(it.message ?: "SteamGridDB is not reachable", Symbols.ERROR)
            return@launch
        }
        val items = found.take(8).map { game ->
            MenuItem(Symbols.SPORTS_ESPORTS, game.name + (game.year?.let { " ($it)" } ?: "")) {
                scope.launch {
                    shell.showToast("Downloading the artwork of ${game.name}…", Symbols.DOWNLOAD)
                    runCatching { store.setGame(pc, app, game) }
                        .onSuccess { shell.showToast("Matched with ${game.name}", Symbols.CHECK_CIRCLE) }
                        .onFailure { shell.showToast(it.message ?: "The download failed", Symbols.ERROR) }
                }
            }
        } + MenuItem(Symbols.EDIT, "Search for another name") {
            feedback.play(Cue.OPEN)
            shell.openLayer(
                Layer.Dialog(
                    DialogSpec(
                        "Search SteamGridDB", "The game's name as SteamGridDB knows it.",
                        listOf(DialogField(Symbols.SEARCH, term, "Game name", maxLength = 80)), "Search",
                    ) { values ->
                        val next = values.first().trim()
                        if (next.isEmpty()) false else {
                            changeGame(scope, shell, tracker, feedback, store, pc, app, next)
                            true
                        }
                    }
                ),
                tracker,
            )
        }
        feedback.play(Cue.OPEN)
        shell.openLayer(Layer.Context(ContextMenu(if (found.isEmpty()) "Nothing found for $term" else "Choose the game", items), tracker.bounds()), tracker)
    }
}
