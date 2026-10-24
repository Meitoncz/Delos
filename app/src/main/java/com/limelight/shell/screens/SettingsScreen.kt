package com.limelight.shell.screens

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limelight.shell.LocalShell
import com.limelight.shell.Screen
import com.limelight.shell.data.Pc
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.Appearance
import com.limelight.shell.settings.SettingKind
import com.limelight.shell.settings.SettingsCapabilities
import com.limelight.shell.settings.SettingsTab
import com.limelight.shell.settings.Startup
import com.limelight.shell.settings.StreamSettingsStore
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.FocusGlow
import com.limelight.shell.ui.FocusRing
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.HeroBitmaps
import com.limelight.shell.ui.PlaceholderArt
import com.limelight.shell.ui.LocalBackdropHaze
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.backdropBlur
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.MarginBringIntoView
import com.limelight.shell.ui.PillButton
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.SettingRow
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.TextFieldPill
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.fadeEdges
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Cycles an enum by [dir] steps. */
inline fun <reified E : Enum<E>> E.step(dir: Int): E {
    val all = enumValues<E>()
    return all[Math.floorMod(ordinal + dir, all.size)]
}

/** The prototype's icon tabs, scrolling rows and description panel, backed by real preferences. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    appearance: Appearance,
    update: ((Appearance) -> Appearance) -> Unit,
    startup: Startup,
    updateStartup: ((Startup) -> Startup) -> Unit,
    pcs: List<Pc>,
    profiles: ProfileStore,
    search: Boolean = false,
) {
    val shell = LocalShell.current
    val feedback = LocalFeedback.current
    val colors = LocalDelosColors.current
    val activity = LocalActivity.current
    val stream = remember(profiles) { StreamSettingsStore(profiles.catalog, profiles::settings) }
    val profile by profiles.profiles.collectAsStateWithLifecycle()
    DisposableEffect(stream, profile.currentId) {
        stream.reload()
        onDispose { stream.close() }
    }
    val values by stream.values.collectAsStateWithLifecycle()
    var capabilities by remember(activity) { mutableStateOf<SettingsCapabilities?>(null) }
    LaunchedEffect(activity) {
        if (activity != null) capabilities = withContext(Dispatchers.Default) { SettingsCapabilities.read(activity) }
    }
    LaunchedEffect(capabilities, profile.currentId, values["checkbox_unlock_fps"]) { capabilities?.let(stream::normalize) }
    val act = settingsActions(profiles, stream)
    val classic = profiles.catalog.all.filter { spec ->
        spec.key !in RETIRED_KEYS && spec.key !in (capabilities?.hidden ?: emptySet()) &&
            (activity?.packageManager?.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN) != false ||
                spec.category !in setOf("category_onscreen_controls", "category_special_key_layout"))
    }.map { spec ->
        val current = values[spec.key] ?: spec.default
        val row = when (spec.kind) {
            SettingKind.TOGGLE -> RowValue.Toggle(current as? Boolean ?: false) { stream.write(spec, it) }
            SettingKind.CHOICE -> {
                val choices = capabilities?.choices(spec, values) ?: spec.choices
                val index = choices.indexOfFirst { it.value == current }.coerceAtLeast(0)
                RowValue.Choice(choices.getOrNull(index)?.label ?: current?.toString().orEmpty(), { dir ->
                    if (choices.isNotEmpty()) stream.write(spec, choices[Math.floorMod(index + dir, choices.size)].value)
                })
            }
            SettingKind.SLIDER -> RowValue.Slider((current as? Number)?.toInt() ?: spec.min, spec.min, spec.max, spec.step, spec.unit,
                divisor = spec.divisor, onChange = { stream.write(spec, it) })
            SettingKind.TEXT -> RowValue.Text(current?.toString().orEmpty()) { act(spec) }
            SettingKind.ACTION -> RowValue.Action { act(spec) }
        }
        SettingsItem(spec.key, spec.tab, spec.section, spec.icon, spec.label,
            if (spec.key == "list_languages") "Language used by the stream and classic interface. The new interface currently uses English."
            else if (spec.key == "checkbox_enable_hdr" && capabilities?.brokenHdr == true) "Update the firmware on your NVIDIA SHIELD Android TV to enable HDR."
            else spec.description, row, stream.enabled(spec, capabilities))
    }
    val all = nativeSettings(appearance, update, startup, updateStartup, pcs, profiles.settings()) + classic
    if (search) {
        SettingsSearch(all, appearance.uiScale / 100f)
        return
    }
    val first = remember { FocusRequester() }
    // This stable, zero-size target holds focus while a scrolled lazy list is replaced. Clearing
    // focus instead lets Compose's beyond-bounds focus search re-enter through the first tab.
    val focusTransfer = remember { FocusRequester() }
    val stage = LocalStage.current
    val k = stage.dpPerPx * LocalDensity.current.density
    // Keep the section heading (including its padding and the top fade) above its first row.
    // A smaller margin stops short of the list's start when moving up with the controller.
    val bringIntoView = remember(k) { MarginBringIntoView(before = 72f * k, after = 110f * k) }
    val tab = shell.settingsTab
    // Group sections so a category split across tabs still has one heading per tab.
    val rows = all.filter { it.tab == tab }.groupBy { it.section }.values.flatten()
    var focusedKey by remember(tab) { mutableStateOf<String?>(null) }
    val focused = rows.firstOrNull { it.key == focusedKey }
    val focusedForSearch by rememberUpdatedState(focusedKey)
    DisposableEffect(shell) {
        val handler: () -> Unit = {
            shell.settingsFocus = focusedForSearch
            feedback.play(Cue.OPEN)
            shell.navigate(Screen.SettingsSearch)
        }
        shell.onSettingsSearch = handler
        onDispose { if (shell.onSettingsSearch === handler) shell.onSettingsSearch = null }
    }
    val rowRequesters = remember(rows.map { it.key }) { rows.associate { it.key to FocusRequester() } }
    val scroll = remember(tab) { LazyListState() }
    val window = LocalWindowInfo.current
    val target = shell.settingsFocus?.takeIf { id -> rows.any { it.key == id } } ?: rows.firstOrNull()?.key
    LaunchedEffect(tab, target, rowRequesters) {
        val index = rows.indexOfFirst { it.key == target }
        if (index >= 0) {
            snapshotFlow { window.isWindowFocused }.first { it }
            val headings = rows.take(index + 1).map { it.section }.distinct().size
            scroll.scrollToItem(if (index == 0) 0 else index + headings)
            withFrameNanos { }
            withFrameNanos { }
            rowRequesters[target]?.let {
                // Tab selection/search already announced the action; assigning focus is silent.
                feedback.silenceNextFocus()
                runCatching { it.requestFocus() }
            }
        } else runCatching { first.requestFocus() }
    }
    DisposableEffect(shell, tab) {
        val handler: (Int) -> Unit = { dir ->
            focusTransfer.requestFocus()
            shell.settingsFocus = null
            shell.settingsTab = tab.step(dir)
            feedback.play(Cue.MOVE)
        }
        shell.onShoulder = handler
        onDispose { if (shell.onShoulder === handler) shell.onShoulder = null }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.size(0.px).focusRequester(focusTransfer).focusable())
        BasicText("Settings", style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 46.tpx),
            modifier = Modifier.offset(150.px, 124.px))
        PillButton("Search", Symbols.SEARCH, { shell.onSettingsSearch?.invoke() }, Modifier.offset(1530.px, 122.px), clickCue = null)
        Row(Modifier.offset(y = 206.px).fillMaxWidth().height(66.px), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ShoulderLabel("L1")
            SettingsTab.entries.forEach { next ->
                SettingsTabButton(next, tab == next, Modifier.padding(horizontal = 5.px)) {
                    if (next != tab) {
                        focusTransfer.requestFocus()
                        shell.settingsFocus = null
                        shell.settingsTab = next
                    } else rowRequesters[target]?.let {
                        feedback.silenceNextFocus()
                        runCatching { it.requestFocus() }
                    }
                }
            }
            ShoulderLabel("R1")
        }
        Row(Modifier.fillMaxSize().padding(start = 150.px, end = 150.px, top = 312.px, bottom = (96f + (appearance.uiScale / 100f - 1f) * 92f).px),
            horizontalArrangement = Arrangement.spacedBy(56.px)) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
                key(tab) {
                    LazyColumn(state = scroll, modifier = Modifier.weight(1f).fadeEdges(stage, top = 14f, bottom = 90f),
                        contentPadding = PaddingValues(start = 18.px, end = 18.px, top = 14.px, bottom = 140.px),
                        verticalArrangement = Arrangement.spacedBy(10.px)) {
                        var previous = ""
                        rows.forEach { row ->
                            if (row.section != previous) {
                                item("section-${row.section}") { SectionHeading(row.section) }
                                previous = row.section
                            }
                            item(row.key) {
                                SettingsItemRow(row, Modifier.focusRequester(rowRequesters.getValue(row.key))) { focusedKey = row.key }
                            }
                        }
                    }
                }
            }
            DescriptionPanel(tab, focused, Modifier.width(520.px), appearance)
        }
    }
}

@Composable
private fun SectionHeading(label: String) {
    BasicText(label.uppercase(Locale.ROOT), style = TextStyle(color = LocalDelosColors.current.text3, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 16.tpx, letterSpacing = .08.em),
        modifier = Modifier.padding(start = 26.px, top = 10.px, bottom = 2.px))
}

@Composable
private fun SettingsItemRow(item: SettingsItem, modifier: Modifier = Modifier, focused: () -> Unit = {}) {
    SettingRow(item.icon, item.label, item.value, modifier,
        description = item.description.takeIf { it.length <= 100 && it.isNotEmpty() }, enabled = item.enabled, onFocused = focused)
}

@Composable
private fun ShoulderLabel(label: String) {
    val colors = LocalDelosColors.current
    BasicText(label, style = TextStyle(color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 18.tpx),
        modifier = Modifier.padding(horizontal = 10.px).border(1.px, colors.line, RoundedCornerShape(10.px)).padding(horizontal = 12.px, vertical = 6.px))
}

@Composable
private fun SettingsTabButton(tab: SettingsTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalDelosColors.current
    var focused by remember { mutableStateOf(false) }
    Row(modifier.focusEffects(onClick, corner = 999f, focusScale = 1.08f, ring = FocusRing(0f, 0f), glow = FocusGlow(12f, 36f, -10f), onFocusChange = { focused = it })
        .clip(CircleShape).background(if (focused) colors.accent else if (selected) colors.surface2 else androidx.compose.ui.graphics.Color.Transparent)
        .height(66.px).padding(horizontal = 22.px),
        horizontalArrangement = Arrangement.spacedBy(12.px), verticalAlignment = Alignment.CenterVertically) {
        Symbol(tab.icon, 30.tpx, if (focused) colors.onAccent else if (selected) colors.accent else colors.text2, filled = focused || selected)
        BasicText(tab.label, style = TextStyle(color = if (focused) colors.onAccent else if (selected) colors.text else colors.text2,
            fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx))
    }
}

@Composable
private fun DescriptionPanel(tab: SettingsTab, focused: SettingsItem?, modifier: Modifier, appearance: Appearance) {
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(34.px)
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val k = LocalStage.current.dpPerPx * LocalDensity.current.density
    LaunchedEffect(focused?.key) { scroll.scrollTo(0) }
    Column(modifier.onPreviewKeyEvent { event ->
        val direction = when (event.key) { Key.DirectionDown -> 1; Key.DirectionUp -> -1; else -> 0 }
        val canScroll = if (direction > 0) scroll.canScrollForward else scroll.canScrollBackward
        if (direction != 0 && canScroll) {
            if (event.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(direction * 130f * k) }
            true
        } else false
    }.focusEffects(onClick = {}, corner = 34f, focusScale = 1f, clickCue = null)
        .clip(shape)
        // The same gentle frosted glass as the focused row
        .then(LocalBackdropHaze.current?.let { Modifier.backdropBlur(it, 8f, colors.surface) } ?: Modifier.background(colors.surface))
        .border(1.px, colors.line, shape).verticalScroll(scroll).padding(44.px),
        verticalArrangement = Arrangement.spacedBy(18.px)) {
        Box(Modifier.size(120.px).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
            Symbol(focused?.icon ?: tab.icon, 64.tpx, colors.onAccent, filled = true)
        }
        BasicText(focused?.label ?: tab.label, style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 30.tpx), modifier = Modifier.padding(top = 8.px))
        BasicText(focused?.description?.takeIf { it.isNotBlank() } ?: tab.description,
            style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx, lineHeight = 1.45.em))
        if (focused?.enabled == false) BasicText("Unavailable with the current settings or on this device.", style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 18.tpx))
        val rowValue = focused?.value
        if (rowValue is RowValue.Choice || rowValue is RowValue.Text) BasicText(if (rowValue is RowValue.Choice) rowValue.label else (rowValue as RowValue.Text).text,
            style = TextStyle(color = colors.accent, fontFamily = Figtree, fontSize = 22.tpx, lineHeight = 1.3.em))
        // A preview of the game background, only for the rows that change it
        if (focused?.key in GAME_BACKGROUND_ROWS) {
            val hero = LocalShell.current.hero
            val image by produceState<ImageBitmap?>(null, hero?.id, appearance.heroBlur) {
                value = hero?.image?.let { source -> withContext(Dispatchers.Default) { HeroBitmaps.render(hero.id, source, appearance.heroBlur) } }
            }
            Box(Modifier.fillMaxWidth().height(150.px).clip(RoundedCornerShape(20.px)).background(colors.bg2), contentAlignment = Alignment.Center) {
                if (appearance.heroBackground && hero != null) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = appearance.heroOpacity / 100f }) {
                        if (image != null) Image(image!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else PlaceholderArt(hero.hue, hero.hue2, hero.icon, Modifier.fillMaxSize(), glyphSize = 100f)
                    }
                }
                BasicText("Game background", style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx))
            }
        }
    }
}

/** Controller letter picker and typed search share one query and jump to the exact settings row. */
@Composable
private fun SettingsSearch(all: List<SettingsItem>, scale: Float) {
    val shell = LocalShell.current
    val colors = LocalDelosColors.current
    var query by rememberSaveable { mutableStateOf("") }
    val first = remember { FocusRequester() }
    InitialFocus(first)
    val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
    val results = all.filter { item -> words.all { "${item.label} ${item.description} ${item.section} ${item.tab.label}".lowercase(Locale.ROOT).contains(it) } }
    Column(Modifier.fillMaxSize().padding(start = 150.px, end = 150.px, top = 130.px, bottom = (96f * scale).px)) {
        BasicText("Find a setting", style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 46.tpx))
        BasicText("Pick letters with the controller, or select the field to type", style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 22.tpx), modifier = Modifier.padding(top = 14.px, bottom = 32.px))
        Row(horizontalArrangement = Arrangement.spacedBy(70.px), modifier = Modifier.weight(1f)) {
            Column(Modifier.width(820.px), verticalArrangement = Arrangement.spacedBy(18.px)) {
                TextFieldPill(query, { query = it }, Symbols.SEARCH, placeholder = "Search all settings", maxLength = 80, width = 790f)
                Column(Modifier.padding(top = 16.px), verticalArrangement = Arrangement.spacedBy(12.px)) {
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".chunked(9).forEachIndexed { row, letters ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.px)) {
                            letters.forEachIndexed { col, letter ->
                                LetterKey(letter.toString(), if (row == 0 && col == 0) Modifier.focusRequester(first) else Modifier) { query = (query + letter.lowercaseChar()).take(80) }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.px)) {
                    PillButton("Space", null, { query = (query + " ").take(80) })
                    PillButton("Delete", Symbols.ARROW_BACK, { query = query.dropLast(1) })
                    PillButton("Clear", Symbols.CLOSE, { query = "" })
                }
            }
            Column(Modifier.weight(1f)) {
                BasicText("${results.size} ${if (results.size == 1) "setting" else "settings"}", style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 22.tpx), modifier = Modifier.padding(bottom = 16.px))
                if (results.isEmpty()) BasicText("No matching settings", style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 26.tpx))
                key(query) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.px), contentPadding = PaddingValues(12.px)) {
                        itemsIndexed(results, key = { _, item -> item.key }) { _, item ->
                            SettingRow(item.icon, item.label, RowValue.Action {
                                shell.settingsTab = item.tab
                                shell.settingsFocus = item.key
                                shell.navigate(Screen.Settings)
                            }, description = "${item.tab.label} · ${item.section}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LetterKey(letter: String, modifier: Modifier, click: () -> Unit) {
    val colors = LocalDelosColors.current
    var focused by remember { mutableStateOf(false) }
    Box(modifier.size(74.px).focusEffects(click, corner = 18f, focusScale = 1.06f, onFocusChange = { focused = it })
        .clip(RoundedCornerShape(18.px)).background(if (focused) colors.surface2 else colors.surface), contentAlignment = Alignment.Center) {
        BasicText(letter, style = TextStyle(color = if (focused) colors.accent else colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 28.tpx))
    }
}

/**
 * Options of the classic UI only (its resume dialog and icon size). They go away with it in the
 * switch-over (6.9).
 */
private val RETIRED_KEYS = setOf(
    "checkbox_resume_without_confirm",
    "checkbox_small_icon_mode",
)

/** The rows the description panel previews the game background for. */
private val GAME_BACKGROUND_ROWS = setOf("ui_hero_background", "ui_hero_blur", "ui_hero_opacity")
