package com.limelight.shell.screens

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
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
import com.limelight.shell.settings.SettingsHelp
import com.limelight.shell.settings.choiceLabel
import com.limelight.shell.settings.nextSettingChoice
import com.limelight.shell.settings.pyroWaveRecommendedBitrateKbps
import com.limelight.shell.settings.SettingsTab
import com.limelight.shell.settings.Startup
import com.limelight.shell.settings.StreamSettingsStore
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.AutoScrollingHelp
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
import com.limelight.shell.ui.glass
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

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
    val bitrateHint = remember(values) {
        pyroWaveRecommendedBitrateKbps(values)?.let { "Recommended: ${it / 1000} Mbps" }
    }
    var capabilities by remember(activity) { mutableStateOf<SettingsCapabilities?>(null) }
    LaunchedEffect(activity) {
        if (activity != null) capabilities = withContext(Dispatchers.Default) { SettingsCapabilities.read(activity) }
    }
    LaunchedEffect(capabilities, profile.currentId, values["checkbox_unlock_fps"]) { capabilities?.let(stream::normalize) }
    val act = settingsActions(profiles, stream)
    val classic = profiles.catalog.visible.filter { spec ->
        spec.key !in (capabilities?.hidden ?: emptySet()) &&
            (activity?.packageManager?.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN) != false ||
                spec.category !in setOf("category_onscreen_controls", "category_special_key_layout"))
    }.map { spec ->
        val current = values[spec.key] ?: spec.default
        val row = when (spec.kind) {
            SettingKind.TOGGLE -> RowValue.Toggle(current as? Boolean ?: false) { stream.write(spec, it) }
            SettingKind.CHOICE -> {
                val choices = capabilities?.choices(spec, values) ?: spec.choices
                RowValue.Choice(spec.choiceLabel(current, choices), { dir ->
                    nextSettingChoice(current, choices, dir)?.let { stream.write(spec, it.value) }
                })
            }
            SettingKind.SLIDER -> RowValue.Slider((current as? Number)?.toInt() ?: spec.min, spec.min, spec.max, spec.step, spec.unit,
                divisor = spec.divisor, onChange = { stream.write(spec, it) })
            SettingKind.TEXT -> RowValue.Text(current?.toString().orEmpty()) { act(spec) }
            SettingKind.ACTION -> RowValue.Action { act(spec) }
        }
        val description = if (spec.key == "checkbox_enable_hdr" && capabilities?.brokenHdr == true)
            "Update the firmware on your NVIDIA SHIELD Android TV to enable HDR." else spec.description
        SettingsItem(spec.key, spec.tab, spec.section, spec.icon, spec.label,
            description, row, stream.enabled(spec, capabilities),
            detail = SettingsHelp.details(spec.copy(description = description), values, capabilities?.choices(spec, values) ?: spec.choices),
            searchTerms = settingSearchTerms(spec))
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
            modifier = Modifier.offset(SettingsLayout.TITLE_X.px, SettingsLayout.TITLE_Y.px))
        PillButton("Search", Symbols.SEARCH, { shell.onSettingsSearch?.invoke() }, Modifier.offset(1530.px, 122.px), clickCue = null)
        Row(Modifier.offset(y = SettingsLayout.TABS_Y.px).fillMaxWidth().height(SettingsLayout.TABS_HEIGHT.px), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ShoulderLabel("L1")
            SettingsTab.entries.forEach { next ->
                TabPill(next.icon, next.label, tab == next, Modifier.padding(horizontal = 5.px)) {
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
        Row(Modifier.fillMaxSize().padding(start = SettingsLayout.TITLE_X.px, end = SettingsLayout.TITLE_X.px, top = SettingsLayout.LIST_TOP.px, bottom = SettingsLayout.bottom(appearance).px),
            horizontalArrangement = Arrangement.spacedBy(56.px)) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView, com.limelight.shell.ui.LocalEntranceStagger provides com.limelight.shell.ui.rememberEntranceStagger(tab)) {
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
                                SettingsItemRow(row, Modifier.focusRequester(rowRequesters.getValue(row.key)),
                                    hint = if (row.key == "seekbar_bitrate_kbps") bitrateHint else null) { focusedKey = row.key }
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
private fun SettingsItemRow(item: SettingsItem, modifier: Modifier = Modifier, hint: String? = null, focused: () -> Unit = {}) {
    SettingRow(item.icon, item.label, item.value, modifier,
        description = hint, enabled = item.enabled, onFocused = focused)
}

/** The L1 / R1 hint beside a row of tabs. */
@Composable
internal fun ShoulderLabel(label: String) {
    val colors = LocalDelosColors.current
    BasicText(label, style = TextStyle(color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 18.tpx),
        modifier = Modifier.padding(horizontal = 10.px).border(1.px, colors.line, RoundedCornerShape(10.px)).padding(horizontal = 12.px, vertical = 6.px))
}

/** A tab of a row switched with L1 / R1 (the settings categories, the artwork kinds). */
@Composable
internal fun TabPill(icon: String, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalDelosColors.current
    var focused by remember { mutableStateOf(false) }
    Row(modifier.focusEffects(onClick, corner = 999f, focusScale = 1.08f, ring = FocusRing(0f, 0f), glow = FocusGlow(12f, 36f, -10f), onFocusChange = { focused = it })
        .clip(CircleShape)
        .then(if (focused) Modifier.background(colors.accent) else if (selected) Modifier.glass(colors.surface2) else Modifier)
        .height(66.px).padding(horizontal = 22.px),
        horizontalArrangement = Arrangement.spacedBy(12.px), verticalAlignment = Alignment.CenterVertically) {
        Symbol(icon, 30.tpx, if (focused) colors.onAccent else if (selected) colors.accent else colors.text2, filled = focused || selected)
        BasicText(label, style = TextStyle(color = if (focused) colors.onAccent else if (selected) colors.text else colors.text2,
            fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx))
    }
}

@Composable
private fun DescriptionPanel(tab: SettingsTab, focused: SettingsItem?, modifier: Modifier, appearance: Appearance) {
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(34.px)
    Column(modifier.clip(shape)
        // The same gentle frosted glass as the focused row
        .then(LocalBackdropHaze.current?.let { Modifier.backdropBlur(it, 8f, colors.surface) } ?: Modifier.background(colors.surface))
        .border(1.px, colors.line, shape).padding(44.px),
        verticalArrangement = Arrangement.spacedBy(18.px)) {
        Box(Modifier.size(120.px).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
            Symbol(focused?.icon ?: tab.icon, 64.tpx, colors.onAccent, filled = true)
        }
        BasicText(focused?.label ?: tab.label, style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 30.tpx), modifier = Modifier.padding(top = 8.px))
        AutoScrollingHelp(focused?.key to focused?.detail, Modifier.weight(1f)) {
            BasicText(focused?.detail?.takeIf { it.isNotBlank() } ?: tab.description,
                style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx, lineHeight = 1.45.em))
            if (focused?.enabled == false) BasicText("Unavailable with the current settings or on this device.", style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 18.tpx))
            val rowValue = focused?.value
            if (rowValue is RowValue.Text) BasicText(rowValue.text,
                style = TextStyle(color = colors.accent, fontFamily = Figtree, fontSize = 22.tpx, lineHeight = 1.3.em))
            // The palette's three colors, only for the palette row
            if (focused?.key == "ui_palette") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.px)) {
                    listOf(colors.accent to "Main", colors.accent2 to "Secondary", colors.accent3 to "Accent").forEach { (color, name) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.px)) {
                            Box(Modifier.size(width = 120.px, height = 72.px).clip(RoundedCornerShape(18.px)).background(color).border(1.px, colors.line, RoundedCornerShape(18.px)))
                            BasicText(name, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 18.tpx))
                        }
                    }
                }
            }
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
}

/** Controller letter picker and typed search share one query and jump to the exact settings row. */
@Composable
private fun SettingsSearch(all: List<SettingsItem>, scale: Float) {
    val shell = LocalShell.current
    val colors = LocalDelosColors.current
    val field = rememberTextFieldState()
    val query = field.text.toString()
    val first = remember { FocusRequester() }
    InitialFocus(first)
    val results = searchSettings(all, query)
    Column(Modifier.fillMaxSize().padding(start = 150.px, end = 150.px, top = 130.px, bottom = (96f * scale).px)) {
        BasicText("Find a setting", style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 46.tpx))
        BasicText("Pick letters with the controller, or select the field to type", style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 22.tpx), modifier = Modifier.padding(top = 14.px, bottom = 32.px))
        Row(horizontalArrangement = Arrangement.spacedBy(70.px), modifier = Modifier.weight(1f)) {
            Column(Modifier.width(820.px), verticalArrangement = Arrangement.spacedBy(18.px)) {
                TextFieldPill(field, Symbols.SEARCH, placeholder = "Search all settings", maxLength = 80, width = 790f)
                Column(Modifier.padding(top = 16.px), verticalArrangement = Arrangement.spacedBy(12.px)) {
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".chunked(9).forEachIndexed { row, letters ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.px)) {
                            letters.forEachIndexed { col, letter ->
                                LetterKey(letter.toString(), if (row == 0 && col == 0) Modifier.focusRequester(first) else Modifier) { field.setTextAndPlaceCursorAtEnd((query + letter.lowercaseChar()).take(80)) }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.px)) {
                    PillButton("Space", null, { field.setTextAndPlaceCursorAtEnd((query + " ").take(80)) })
                    PillButton("Delete", Symbols.ARROW_BACK, { field.setTextAndPlaceCursorAtEnd(query.dropLast(1)) })
                    PillButton("Clear", Symbols.CLOSE, { field.clearText() })
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

/** The rows the description panel previews the game background for. */
private val GAME_BACKGROUND_ROWS = setOf("ui_hero_background", "ui_hero_blur", "ui_hero_opacity")

/** Where the settings screens put their title, tab row and list; the per-game screen reuses it 1:1. */
internal object SettingsLayout {
    const val TITLE_X = 150f
    const val TITLE_Y = 124f
    const val TABS_Y = 206f
    const val TABS_HEIGHT = 66f
    const val LIST_TOP = 312f

    /** The room kept free at the bottom for the hint bar, which grows with the interface scale. */
    fun bottom(appearance: Appearance): Float = 96f + (appearance.uiScale / 100f - 1f) * 92f
}
