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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
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
    val bitrateHint = remember(values) {
        pyroWaveRecommendedBitrateKbps(values)?.let { "Recommended: ${it / 1000} Mbps" }
    }
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
    // The rows that can take the focus back from the side buttons: only composed ones have a live requester.
    val rowFocus = remember(tab, game) { mutableMapOf<String, FocusRequester>() }
    val composedRows = remember(tab, game) { mutableSetOf<String>() }
    fun lastRow(): FocusRequester? = focusedKey?.takeIf { it in composedRows }?.let { rowFocus[it] }
    val resetOne = remember { FocusRequester() }
    val resetAll = remember { FocusRequester() }
    val focusedCustom = focusedKey.let { it != null && it in custom }
    val canResetOne by rememberUpdatedState(focusedCustom)
    val canResetAll by rememberUpdatedState(custom.isNotEmpty())
    // Where Right (from a row that does not adjust) or Down (from the last row) goes: the first button that works.
    fun buttons(): FocusRequester = when {
        canResetOne -> resetOne
        canResetAll -> resetAll
        else -> FocusRequester.Default
    }
    fun backToRows() {
        val target = lastRow() ?: return
        feedback.silenceNextFocus()
        runCatching { target.requestFocus() }
    }
    fun reset(key: String) {
        game.reset(key)
        shell.showToast("Back to the profile value", Symbols.RESET)
    }
    val cover by rememberArtwork(LocalArtwork.current, screen.pc, screen.app, ArtKind.COVER)
    val box by rememberBoxArt(LocalBoxArt.current, screen.pc, screen.app)
    val art = appArt(screen.pc, screen.app)
    val bottom = 96f + (appearance.uiScale / 100f - 1f) * 92f
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.size(0.px).focusRequester(transfer).focusable())
        Column(Modifier.offset(150.px, 100.px).width(1120.px)) {
            BasicText("Game settings", style = TextStyle(color = colors.text, fontFamily = Unbounded,
                fontWeight = FontWeight.SemiBold, fontSize = 46.tpx))
            BasicText("${screen.app.name} · ${screen.pc.name}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx),
                modifier = Modifier.padding(top = 12.px))
            Row(Modifier.padding(top = 14.px)) {
                Chip(if (custom.isEmpty()) Symbols.CHECK_CIRCLE else Symbols.TUNE, customSettingsLabel(custom.size),
                    if (custom.isEmpty()) null else colors.accent3)
            }
        }
        Row(Modifier.offset(x = 150.px, y = 262.px), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.px)) {
            ShoulderLabel("L1")
            TabPill(Symbols.CAST, "Stream", tab == 0, Modifier) { changeTab(0) }
            TabPill(Symbols.MONITOR, "Display", tab == 1, Modifier.focusProperties { right = buttons() }) { changeTab(1) }
            ShoulderLabel("R1")
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
            key(tab, game) {
                LazyColumn(state = scroll,
                    modifier = Modifier.padding(start = 150.px, top = 336.px, bottom = bottom.px).width(1140.px).fillMaxHeight()
                        .fadeEdges(stage, top = 14f, bottom = 70f),
                    contentPadding = PaddingValues(start = 18.px, end = 18.px, top = 18.px, bottom = 140.px),
                    verticalArrangement = Arrangement.spacedBy(10.px)) {
                    itemsIndexed(rows, key = { _, spec -> spec.key }) { index, spec ->
                        DisposableEffect(spec.key) {
                            composedRows.add(spec.key)
                            onDispose { composedRows.remove(spec.key) }
                        }
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
                        val isCustom = spec.key in custom
                        val source = if (isCustom) "Custom for this game" else "From profile"
                        val menu = ContextMenu(spec.label, listOf(MenuItem(Symbols.RESET, "Reset to profile value") { reset(spec.key) }))
                        val requester = remember(spec.key, first) { rowFocus.getOrPut(spec.key) { if (index == 0) first else FocusRequester() } }
                        // Choice and slider rows use Left/Right to adjust, so the buttons are reached from the last row with Down
                        val rowModifier = Modifier.focusRequester(requester).focusProperties {
                            right = buttons()
                            if (index == rows.lastIndex) down = buttons()
                        }
                        SettingRow(spec.icon, spec.label, value, rowModifier,
                            description = if (spec.key == "seekbar_bitrate_kbps" && bitrateHint != null) "$source · $bitrateHint" else source,
                            descriptionColor = if (isCustom) colors.accent3 else null,
                            enabled = caps != null && stream.enabled(spec, caps), contextMenu = menu,
                            onFocused = { focusedKey = spec.key })
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(top = 100.px, end = 150.px, bottom = bottom.px).width(360.px).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.px)) {
            val shape = RoundedCornerShape(24.px)
            Box(Modifier.size(320.px, 480.px).clip(shape).background(colors.bg2).border(1.px, colors.line, shape)) {
                val image = cover ?: box
                if (image != null) Image(image, screen.app.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else PlaceholderArt(art.hue, art.hue2, art.icon, Modifier.fillMaxSize(), glyphSize = 210f)
            }
            val detail = focused?.let { SettingsHelp.details(it, values, caps?.choices(it, values) ?: it.choices) }
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.px),
                verticalArrangement = Arrangement.spacedBy(6.px)) {
                BasicText(focused?.label ?: "Your game, your settings", style = TextStyle(color = colors.text,
                    fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx))
                AutoScrollingHelp(focusedKey to detail, Modifier.weight(1f)) {
                    BasicText(detail?.takeIf { it.isNotBlank() }
                        ?: "Only this game uses these changes. Everything else follows your profile.",
                        style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 19.tpx, lineHeight = 1.35.em))
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.px)) {
                PillButton("Reset setting", Symbols.RESET, {
                    focusedKey?.let { reset(it) }
                    backToRows()
                }, Modifier.fillMaxWidth().focusRequester(resetOne).focusProperties {
                    left = lastRow() ?: FocusRequester.Default
                    up = lastRow() ?: FocusRequester.Default
                    if (canResetAll) down = resetAll
                }, compact = true, enabled = focusedCustom)
                PillButton("Reset all to profile", Symbols.RESET, {
                    game.resetAll()
                    shell.showToast("All settings follow the profile again", Symbols.RESET)
                    backToRows()
                }, Modifier.fillMaxWidth().focusRequester(resetAll).focusProperties {
                    left = lastRow() ?: FocusRequester.Default
                    up = if (canResetOne) resetOne else lastRow() ?: FocusRequester.Default
                }, compact = true, enabled = custom.isNotEmpty())
            }
            BasicText("Saved automatically · applies next stream", style = TextStyle(color = colors.text3,
                fontFamily = Figtree, fontSize = 16.tpx, textAlign = TextAlign.Center))
        }
    }
}

/** The header chip: how many of this game's settings differ from the profile. */
internal fun customSettingsLabel(count: Int): String = when {
    count <= 0 -> "Follows your profile"
    count == 1 -> "1 custom setting"
    else -> "$count custom settings"
}
