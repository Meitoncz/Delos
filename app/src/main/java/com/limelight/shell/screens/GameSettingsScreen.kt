package com.limelight.shell.screens

import android.content.SharedPreferences
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limelight.shell.ContextMenu
import com.limelight.shell.LocalShell
import com.limelight.shell.MenuItem
import com.limelight.shell.Screen
import com.limelight.shell.data.ArtKind
import com.limelight.shell.data.LocalArtwork
import com.limelight.shell.data.LocalBoxArt
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.data.rememberArtwork
import com.limelight.shell.data.rememberBoxArt
import com.limelight.shell.settings.*
import com.limelight.shell.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** A small game-specific version of the settings screen, using the same rows, tabs and focus rules. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameSettingsScreen(screen: Screen.GameSettings, profiles: ProfileStore, appearance: Appearance) {
    val shell = LocalShell.current
    val colors = LocalDelosColors.current
    val feedback = LocalFeedback.current
    val activity = LocalActivity.current
    val profile by profiles.profiles.collectAsStateWithLifecycle()
    val game = remember(screen.pc.uuid, screen.app.id, profile.currentId) {
        GameSettingsStore(profiles.settings(), screen.pc.uuid, screen.app.id)
    }
    val stream = remember(game) { StreamSettingsStore(profiles.catalog) { game.preferences } }
    DisposableEffect(stream) { onDispose { stream.close() } }
    val values by stream.values.collectAsStateWithLifecycle()
    var caps by remember(activity) { mutableStateOf<SettingsCapabilities?>(null) }
    LaunchedEffect(activity) {
        if (activity != null) caps = withContext(Dispatchers.Default) { SettingsCapabilities.read(activity) }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val keys = if (tab == 0) GameSettingsStore.STREAM_KEYS else GameSettingsStore.DISPLAY_KEYS
    val rows = keys.mapNotNull { key -> profiles.catalog.all.firstOrNull { it.key == key } }
        .filter { it.key !in (caps?.hidden ?: emptySet()) }
    var focusedKey by remember(tab) { mutableStateOf<String?>(null) }
    val focused = rows.firstOrNull { it.key == focusedKey }
    // The effective values do not change when an override equals the value it replaces, so the source
    // labels follow the stored overrides instead.
    var overrideVersion by remember(game) { mutableIntStateOf(0) }
    DisposableEffect(game) {
        val base = profiles.settings()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> overrideVersion++ }
        base.registerOnSharedPreferenceChangeListener(listener)
        onDispose { base.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val custom = remember(game, overrideVersion) { game.overrides().keys }
    val first = remember(tab, game) { FocusRequester() }
    val transfer = remember { FocusRequester() }
    val window = LocalWindowInfo.current
    val scroll = remember(tab) { LazyListState() }
    val stage = LocalStage.current
    val k = stage.dpPerPx * LocalDensity.current.density
    val bringIntoView = remember(k) { MarginBringIntoView(before = 72f * k, after = 110f * k) }
    fun changeTab(next: Int) {
        if (next == tab) return
        transfer.requestFocus()
        tab = next
    }
    DisposableEffect(shell, tab) {
        val handler: (Int) -> Unit = { dir ->
            changeTab(Math.floorMod(tab + dir, 2))
            feedback.play(Cue.MOVE)
        }
        shell.onShoulder = handler
        onDispose { if (shell.onShoulder === handler) shell.onShoulder = null }
    }
    LaunchedEffect(tab, first, caps) {
        if (caps == null) return@LaunchedEffect
        snapshotFlow { window.isWindowFocused }.first { it }
        withFrameNanos { }
        withFrameNanos { }
        feedback.silenceNextFocus()
        runCatching { first.requestFocus() }
    }
    fun reset(key: String) {
        game.reset(key)
        shell.showToast("Using the profile value", Symbols.RESET)
    }
    val cover by rememberArtwork(LocalArtwork.current, screen.pc, screen.app, ArtKind.COVER)
    val box by rememberBoxArt(LocalBoxArt.current, screen.pc, screen.app)
    val art = appArt(screen.pc, screen.app)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.size(0.px).focusRequester(transfer).focusable())
        Column(Modifier.offset(150.px, 122.px).width(1120.px)) {
            BasicText("Game settings", style = TextStyle(color = colors.text, fontFamily = Unbounded,
                fontWeight = FontWeight.SemiBold, fontSize = 46.tpx))
            BasicText("${screen.app.name} · ${screen.pc.name}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx),
                modifier = Modifier.padding(top = 12.px))
        }
        PillButton("Use profile for all", Symbols.RESET, {
            game.resetAll()
            shell.showToast("This game now follows the profile", Symbols.RESET)
        }, Modifier.align(Alignment.TopEnd).padding(top = 130.px, end = 150.px))
        Row(Modifier.offset(x = 150.px, y = 238.px), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.px)) {
            ShoulderLabel("L1")
            TabPill(Symbols.CAST, "Stream", tab == 0, Modifier) { changeTab(0) }
            TabPill(Symbols.MONITOR, "Display", tab == 1, Modifier) { changeTab(1) }
            ShoulderLabel("R1")
        }
        Row(Modifier.fillMaxSize().padding(start = 150.px, end = 150.px, top = 336.px,
            bottom = (96f + (appearance.uiScale / 100f - 1f) * 92f).px),
            horizontalArrangement = Arrangement.spacedBy(56.px)) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
                key(tab, game) {
                    LazyColumn(state = scroll, modifier = Modifier.weight(1f).fadeEdges(stage, top = 14f, bottom = 70f),
                        contentPadding = PaddingValues(start = 18.px, end = 18.px, top = 18.px, bottom = 140.px),
                        verticalArrangement = Arrangement.spacedBy(10.px)) {
                        itemsIndexed(rows, key = { _, spec -> spec.key }) { index, spec ->
                            val current = values[spec.key] ?: spec.default
                            val value = when (spec.kind) {
                                SettingKind.TOGGLE -> RowValue.Toggle(current as? Boolean ?: false) { stream.write(spec, it) }
                                SettingKind.CHOICE -> {
                                    val choices = caps?.choices(spec, values) ?: spec.choices
                                    RowValue.Choice(spec.choiceLabel(current, choices), { dir ->
                                        nextSettingChoice(current, choices, dir)?.let { stream.write(spec, it.value) }
                                    })
                                }
                                SettingKind.SLIDER -> RowValue.Slider((current as? Number)?.toInt() ?: spec.min,
                                    spec.min, spec.max, spec.step, spec.unit, spec.divisor) { stream.write(spec, it) }
                                else -> RowValue.Info(current?.toString().orEmpty())
                            }
                            val menu = ContextMenu(spec.label, listOf(MenuItem(Symbols.RESET, "Use profile value") { reset(spec.key) }))
                            SettingRow(spec.icon, spec.label, value,
                                if (index == 0) Modifier.focusRequester(first) else Modifier,
                                description = if (spec.key in custom) "Custom for this game" else "From profile",
                                enabled = caps != null && stream.enabled(spec, caps), contextMenu = menu,
                                onFocused = { focusedKey = spec.key })
                        }
                    }
                }
            }
            Column(Modifier.width(460.px), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.px)) {
                val shape = RoundedCornerShape(24.px)
                Box(Modifier.width(240.px).height(360.px).clip(shape).background(colors.bg2).border(1.px, colors.line, shape)) {
                    val image = cover ?: box
                    if (image != null) Image(image, screen.app.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else PlaceholderArt(art.hue, art.hue2, art.icon, Modifier.fillMaxSize(), glyphSize = 160f)
                }
                val detail = focused?.let { SettingsHelp.details(it, values, caps?.choices(it, values) ?: it.choices) }
                Column(Modifier.weight(1f).fillMaxWidth().padding(8.px),
                    verticalArrangement = Arrangement.spacedBy(10.px)) {
                    BasicText(focused?.label ?: "Your game, your settings", style = TextStyle(color = colors.text,
                        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 24.tpx))
                    AutoScrollingHelp(focusedKey to detail, Modifier.weight(1f)) {
                        BasicText(detail?.takeIf { it.isNotBlank() }
                            ?: "Only this game uses these changes. Everything else follows your profile.",
                            style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 20.tpx, lineHeight = 1.35.em))
                    }
                }
                PillButton("Use profile value", Symbols.RESET, { focused?.let { reset(it.key) } })
                BasicText("Saved automatically · applies next stream", style = TextStyle(color = colors.text3,
                    fontFamily = Figtree, fontSize = 17.tpx))
            }
        }
    }
}
