package com.limelight.shell.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.limelight.R
import com.limelight.preferences.UpscalerMode
import com.limelight.shell.StreamMenuHost
import com.limelight.shell.bitrateStatusText
import com.limelight.shell.pillText
import com.limelight.shell.showsSupersamplingQuality
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.LowRed
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.px
import com.limelight.shell.ui.textResources
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.delay

/** The pages that open over the tiles (what the stream menu opens in popovers). */
private enum class Sheet { BRIGHTNESS, BITRATE, UPSCALER, MORE, KEYS, COMMANDS, MOUSE }

/**
 * The stream's companion: the statistics and the stream menu as touch tiles, or the keyboard ([startTyping], the Keyboard tile).
 * Every tile calls the handler of its twin in the menu on the main panel ([StreamCommands.menu]); nothing asks for a confirmation
 * (Disconnect and Quit game sit in the last row, set apart from the tiles used all the time, like the stream menu's last two
 * tiles). The menu's getters cannot be observed, so the screen reads them again after every command and once a second.
 *
 * Nothing here scrolls (docs/DUAL_SCREEN.md, "No scrolling, ever"): the tiles page and every sheet are one [PageCard] whose blocks [FitColumn]
 * measures; the tiles take the height the heading and the statistics leave, and the sheets lay their controls out at their natural size (64 px
 * targets) and give long lists a grid with Previous / Next ([PagedGrid]).
 */
@Composable
internal fun StreamContent(state: CompanionState.Stream, startTyping: Boolean, startSheet: String? = null) {
    var typing by remember { mutableStateOf(startTyping) }
    var menuShown by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(Sheet.entries.firstOrNull { it.name.equals(startSheet, ignoreCase = true) }) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            revision++
        }
    }
    if (typing) {
        TypingContent(state, onMenu = { typing = false; menuShown = true })
        return
    }
    @Suppress("UNUSED_EXPRESSION") revision
    val commands = state.commands
    val run: (() -> Unit) -> Unit = { it(); revision++ }
    Box(Modifier.fillMaxSize()) {
        PageCard { fit ->
            val colors = LocalDelosColors.current
            val readout = StatsReadout.of(state.stats)
            val streaming = stringResource(R.string.companion_streaming)
            val title = CompanionFit.title(state.gameName.orEmpty(), streaming, fit.budget.innerWidth - HelpFit.HEADING_CHROME, fit.metrics)
            PageHeading(Symbols.PLAY_ARROW, title, readout.picture ?: state.pcName)
            if (menuShown) {
                StatsBar(state)
                if (commands != null) {
                    Tiles(commands.menu, Modifier.fills(minHeight = TILES_MIN.px), run, onKeyboard = { typing = true }, onResume = { menuShown = false }, onSheet = { sheet = it })
                } else {
                    Filler()
                    BodyText(stringResource(R.string.companion_menu_unavailable), Modifier.fillMaxWidth(), color = colors.text3, maxLines = 3)
                    Filler()
                }
            } else {
                BodyText(stringResource(R.string.companion_back_to_main), color = colors.text, weight = FontWeight.SemiBold)
                BodyText(stringResource(R.string.companion_back_hint), Modifier.optional(60), color = colors.text2, maxLines = 2)
                Filler()
                CompanionButton(stringResource(R.string.companion_open_menu), { menuShown = true }, Modifier.fillMaxWidth().height(MIN_TARGET.px), ButtonStyle.SECONDARY, textSize = MIN_TEXT)
            }
        }
        val menu = commands?.menu
        if (sheet != null && menu != null) SheetHost(sheet!!, menu, run, onNavigate = { sheet = it }, onClose = { sheet = null })
    }
}

/** The least height of the tiles' grid: three rows of 64 px targets and the gaps between them (stage px). */
private const val TILES_MIN = 3 * 64 + 2 * 10

/** FPS, network delay, decode time and the bitrate asked for: four values the player glances at. */
@Composable
private fun StatsBar(state: CompanionState.Stream) {
    val colors = LocalDelosColors.current
    val readout = StatsReadout.of(state.stats)
    val bitrate = state.commands?.menu?.bitrateKbps?.let { "${it / 1000} Mbps" } ?: "\u2013" // l10n-ignore
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val cells = listOf(
            readout.fps to "FPS", // l10n-ignore
            readout.network to stringResource(R.string.stream_stats_network),
            readout.decode to stringResource(R.string.stream_stats_decoding),
            bitrate to stringResource(R.string.stream_menu_tile_bitrate),
        )
        cells.forEach { (value, label) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    value,
                    style = TextStyle(color = colors.accent2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 34.tpx, fontFeatureSettings = "tnum"),
                    maxLines = 1,
                )
                BodyText(label, color = colors.text, maxLines = 1)
            }
        }
    }
}

/** The nine tiles of the stream menu, in the menu's order, three to a row. The last row (More, Disconnect, Quit game) is set apart. */
@Composable
private fun Tiles(
    menu: StreamMenuHost,
    modifier: Modifier,
    run: (() -> Unit) -> Unit,
    onKeyboard: () -> Unit,
    onResume: () -> Unit,
    onSheet: (Sheet) -> Unit,
) {
    val res = textResources()
    val status = menu.upscalerStatus
    val overlay = if (!menu.overlayOn) stringResource(R.string.common_off) else if (menu.overlayCompact) stringResource(R.string.stream_menu_overlay_compact) else stringResource(R.string.common_on)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.px)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.px)) {
            Tile(stringResource(R.string.stream_menu_tile_resume), Symbols.RESUME, onResume, Modifier.weight(1f), TileStyle.PRIMARY)
            Tile(stringResource(R.string.stream_menu_tile_keyboard), Symbols.KEYBOARD, onKeyboard, Modifier.weight(1f))
            Tile(stringResource(R.string.stream_menu_tile_overlay), Symbols.OVERLAY, { run { menu.toggleOverlay() } }, Modifier.weight(1f), pill = overlay, on = menu.overlayOn)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.px)) {
            Tile(stringResource(R.string.stream_menu_tile_brightness), Symbols.BRIGHTNESS_MEDIUM, { onSheet(Sheet.BRIGHTNESS) }, Modifier.weight(1f))
            Tile(stringResource(R.string.stream_menu_tile_bitrate), Symbols.NETWORK_CHECK, { onSheet(Sheet.BITRATE) }, Modifier.weight(1f))
            Tile(
                stringResource(if (menu.supersamplingMode) R.string.stream_menu_tile_supersampling else R.string.stream_menu_tile_upscaler),
                Symbols.UPSCALER, { onSheet(Sheet.UPSCALER) }, Modifier.weight(1f), pill = status.pillText(res), on = menu.upscalerRequested,
            )
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.px)) {
            Tile(stringResource(R.string.stream_menu_tile_more), Symbols.MORE_HORIZ, { onSheet(Sheet.MORE) }, Modifier.weight(1f))
            Tile(stringResource(R.string.stream_menu_tile_disconnect), Symbols.LOGOUT, { run { menu.leave(quit = false) } }, Modifier.weight(1f), TileStyle.DANGER)
            Tile(stringResource(R.string.stream_menu_tile_quit), Symbols.POWER_SETTINGS_NEW, { run { menu.leave(quit = true) } }, Modifier.weight(1f), TileStyle.DANGER)
        }
    }
}

private enum class TileStyle { NORMAL, PRIMARY, DANGER }

/** A tile shorter than this (stage px) lays its icon beside its name: the room of a 3:2 or 4:3 panel. */
private const val COMPACT_TILE = 140f

/** A compact tile this tall or taller also has room for the state under the name. */
private const val COMPACT_TILE_STATE = 84f

@Composable
private fun Tile(label: String, icon: String, onClick: () -> Unit, modifier: Modifier, style: TileStyle = TileStyle.NORMAL, pill: String? = null, on: Boolean = false) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(24.px)
    val (fill, content, line) = when (style) {
        TileStyle.PRIMARY -> Triple(Brush.linearGradient(listOf(colors.accent, colors.accent)), colors.onAccent, colors.accent)
        TileStyle.DANGER -> Triple(Brush.linearGradient(listOf(LowRed.copy(alpha = .13f), LowRed.copy(alpha = .13f))), LowRed, LowRed.copy(alpha = .5f))
        TileStyle.NORMAL -> Triple(Brush.linearGradient(listOf(colors.surface2, colors.surface)), colors.text, colors.line)
    }
    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .padding(top = if (pressed) 2.px else 0.px)
            .clip(shape)
            .background(fill)
            .border(if (on) 3.px else 1.px, if (on) colors.accent else line, shape)
            .clickable(source, null, role = Role.Button, onClick = onClick),
    ) {
        val height = maxHeight.value / stage.dpPerPx
        if (height < COMPACT_TILE) {
            // A short tile: the icon beside the name, the state under it when there is room for a second line
            Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 18.px), horizontalArrangement = Arrangement.spacedBy(14.px), verticalAlignment = Alignment.CenterVertically) {
                Symbol(icon, 40.tpx, if (style == TileStyle.NORMAL) colors.accent2 else content)
                Column {
                    BasicText(label, style = TextStyle(color = content, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 30.tpx), maxLines = 1)
                    if (pill != null && height >= COMPACT_TILE_STATE) {
                        BasicText(pill, style = TextStyle(color = if (on) colors.accent else colors.text2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = MIN_TEXT.tpx), maxLines = 1)
                    }
                }
            }
            return@BoxWithConstraints
        }
        Symbol(icon, 48.tpx, if (style == TileStyle.NORMAL) colors.accent2 else content, Modifier.align(Alignment.TopStart).padding(start = 22.px, top = 16.px))
        // The tile's state (Overlay: Off / On / Compact, Upscaler: its status) as the menu on the main panel shows it
        if (pill != null) {
            BasicText(
                pill,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 18.px, top = 16.px).clip(CircleShape).background(if (on) colors.accent else colors.surface3).padding(horizontal = 16.px, vertical = 4.px),
                style = TextStyle(color = if (on) colors.onAccent else colors.text2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = MIN_TEXT.tpx),
                maxLines = 1,
            )
        }
        BasicText(
            label,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 22.px, bottom = 16.px),
            style = TextStyle(color = content, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 32.tpx),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

// ---- Sheets ----

/**
 * A page over the tiles: the same card as every page, on the page color so the tiles do not show through, with the sheet's title, [extra] (a control
 * that lives in the header: the upscaler's switch) and the Close button in one row; a tap beside the card closes it too. [content] fills the rest of the card.
 */
@Composable
private fun SheetFrame(title: String, icon: String, onClose: () -> Unit, extra: (@Composable () -> Unit)? = null, content: @Composable FitScope.(FitContext) -> Unit) {
    val colors = LocalDelosColors.current
    Box(Modifier.fillMaxSize().background(colors.bg).pointerInput(Unit) { detectTapGestures { onClose() } }) {
        PageCard(Modifier.pointerInput(Unit) { detectTapGestures { } }) { fit ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.px), verticalAlignment = Alignment.CenterVertically) {
                PageHeading(icon, title, null, Modifier.weight(1f))
                extra?.invoke()
                CompanionButton(stringResource(R.string.companion_close), onClose, Modifier.height(MIN_TARGET.px), icon = Symbols.CLOSE, textSize = MIN_TEXT)
            }
            content(fit)
        }
    }
}

@Composable
private fun SheetHost(sheet: Sheet, menu: StreamMenuHost, run: (() -> Unit) -> Unit, onNavigate: (Sheet?) -> Unit, onClose: () -> Unit) {
    val back = { onNavigate(Sheet.MORE) }
    when (sheet) {
        Sheet.BRIGHTNESS -> SheetFrame(stringResource(R.string.stream_menu_title_brightness), Symbols.BRIGHTNESS_MEDIUM, onClose) { BrightnessPage(menu, run) }
        Sheet.BITRATE -> SheetFrame(stringResource(R.string.stream_menu_title_bitrate), Symbols.NETWORK_CHECK, onClose) { BitratePage(menu, run) }
        Sheet.UPSCALER -> UpscalerSheet(menu, run, onClose)
        Sheet.MORE -> SheetFrame(stringResource(R.string.stream_menu_title_more), Symbols.MORE_HORIZ, onClose) { MorePage(menu, run, onNavigate, onClose) }
        Sheet.KEYS -> SheetFrame(stringResource(R.string.stream_menu_title_keys), Symbols.KEYBOARD_COMMAND_KEY, back) {
            PagedGrid(menu.specialKeys.mapIndexed { i, label -> GridItem(label) { run { menu.sendSpecialKey(i) } } }, Modifier.fills(minHeight = GRID_MIN.px))
        }
        Sheet.COMMANDS -> SheetFrame(stringResource(R.string.stream_menu_title_commands), Symbols.TERMINAL, back) {
            val commands = menu.serverCommands
            if (commands.isEmpty()) BodyText(stringResource(R.string.stream_menu_no_commands), color = LocalDelosColors.current.text2, maxLines = 4)
            else PagedGrid(commands.mapIndexed { i, label -> GridItem(label) { run { menu.runServerCommand(i) }; onClose() } }, Modifier.fills(minHeight = GRID_MIN.px))
        }
        Sheet.MOUSE -> SheetFrame(stringResource(R.string.stream_menu_title_mouse), Symbols.MOUSE, back) { MousePage(menu, run) }
    }
}

/** The least height of a list's grid: two rows of 64 px targets and the gap (stage px). A page of a long list always has at least this much. */
private const val GRID_MIN = 2 * 64 + 8

/** The label and its value on one line: "Screen brightness   70 %". */
@Composable
private fun ValueLine(label: String, value: String) {
    val colors = LocalDelosColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, style = TextStyle(color = colors.text, fontFamily = Figtree, fontSize = 34.tpx), maxLines = 1)
        BasicText(value, style = TextStyle(color = colors.accent2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 34.tpx, fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

@Composable
private fun FitScope.BrightnessPage(menu: StreamMenuHost, run: (() -> Unit) -> Unit) {
    var value by remember { mutableIntStateOf(menu.brightnessPercent) }
    fun set(v: Int) {
        val next = v.coerceIn(5, 100)
        if (next != value) {
            value = next
            run { menu.setBrightness(next) }
        }
    }
    ValueLine(stringResource(R.string.stream_menu_row_screen_brightness), "$value %") // l10n-ignore
    Stepper({ set(value - 5) }, { set(value + 5) }, size = MIN_TARGET) {
        TouchSlider(value, 5, 100, 5, onChange = ::set, onCommit = {}, height = MIN_TARGET)
    }
    Filler()
}

@Composable
private fun FitScope.BitratePage(menu: StreamMenuHost, run: (() -> Unit) -> Unit) {
    val res = textResources()
    val colors = LocalDelosColors.current
    var requested by remember { mutableIntStateOf((menu.bitrateKbps / 1000).coerceIn(1, 1000)) }
    val max = maxOf(300, requested)
    val busy = menu.bitrateBusy
    ValueLine(stringResource(R.string.stream_menu_row_bitrate), "$requested Mbps") // l10n-ignore
    Stepper({ requested = (requested - 5).coerceAtLeast(1) }, { requested = (requested + 5).coerceAtMost(1000) }, size = MIN_TARGET) {
        TouchSlider(requested.coerceAtMost(max), 1, max, 1, onChange = { requested = it }, onCommit = {}, enabled = !busy, height = MIN_TARGET)
    }
    menu.linkGuidance?.let { guidance ->
        val recommended = (guidance.recommendation.kbps / 1000).coerceIn(1, 1000)
        CompanionButton(guidance.line(res), { requested = recommended }, Modifier.fillMaxWidth(), ButtonStyle.SECONDARY, icon = Symbols.RECOMMEND, enabled = !busy, textSize = MIN_TEXT, maxLines = 2)
    }
    Filler()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.px), verticalAlignment = Alignment.CenterVertically) {
        CompanionButton(stringResource(R.string.stream_menu_row_apply), { run { menu.applyBitrate(requested * 1000) { requested = it / 1000 } } }, Modifier.height(MIN_TARGET.px).widthIn(min = 240.px), ButtonStyle.PRIMARY, icon = Symbols.CHECK, enabled = !busy, textSize = MIN_TEXT)
        bitrateStatusText(res, busy, menu.bitrateResult)?.let { BodyText(it, color = colors.text2, maxLines = 2, modifier = Modifier.weight(1f)) }
    }
}

/**
 * The upscaler's sheet: its on/off switch sits in the header beside Close, the modes are a grid of two columns, the supersampling quality (when the
 * mode has one) one row, and the sharpness one row of its name and value with the slider. The two small captions are the first to go when the panel is short.
 */
@Composable
private fun UpscalerSheet(menu: StreamMenuHost, run: (() -> Unit) -> Unit, onClose: () -> Unit) {
    val available = menu.upscalerAvailable && !menu.upscalerStatus.isChanging
    val mode = menu.upscalerMode
    SheetFrame(
        stringResource(R.string.stream_menu_title_upscaler), Symbols.UPSCALER, onClose,
        extra = {
            CompanionButton(
                stringResource(R.string.stream_menu_row_enabled) + ": " + stringResource(if (menu.upscalerRequested) R.string.common_on else R.string.common_off),
                { run { menu.setUpscalingEnabled(!menu.upscalerRequested) } }, Modifier.height(MIN_TARGET.px),
                if (menu.upscalerRequested) ButtonStyle.PRIMARY else ButtonStyle.NORMAL, icon = Symbols.UPSCALER, enabled = available, textSize = MIN_TEXT,
            )
        },
    ) {
        val colors = LocalDelosColors.current
        Eyebrow(stringResource(R.string.stream_menu_row_mode), Modifier.optional(10))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.px)) {
            UpscalerMode.values().toList().chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().height(MIN_TARGET.px), horizontalArrangement = Arrangement.spacedBy(8.px)) {
                    pair.forEach { m ->
                        CompanionButton(stringResource(m.labelRes()), { run { menu.setUpscalingMode(m.index()) } }, Modifier.weight(1f).fillMaxHeight(), selected = m.index() == mode, enabled = menu.upscalerAvailable, textSize = MIN_TEXT)
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
        if (showsSupersamplingQuality(menu.supersamplingMode, mode)) {
            Eyebrow(stringResource(R.string.stream_menu_row_supersampling_quality), Modifier.optional(20))
            Row(Modifier.fillMaxWidth().height(MIN_TARGET.px), horizontalArrangement = Arrangement.spacedBy(8.px)) {
                val level = menu.supersamplingLevel.coerceIn(1, 2)
                CompanionButton("1.33\u00d7 (1440p)", { run { menu.setSupersamplingLevel(1) } }, Modifier.weight(1f).fillMaxHeight(), selected = level == 1, enabled = menu.upscalerAvailable, textSize = MIN_TEXT) // l10n-ignore
                CompanionButton("2\u00d7 (2160p)", { run { menu.setSupersamplingLevel(2) } }, Modifier.weight(1f).fillMaxHeight(), selected = level == 2, enabled = menu.upscalerAvailable, textSize = MIN_TEXT) // l10n-ignore
            }
        }
        Filler()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.px), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                stringResource(R.string.stream_menu_row_sharpness) + " " + menu.upscalerSharpness + " %", // l10n-ignore
                style = TextStyle(color = colors.accent2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = MIN_TEXT.tpx, fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
            Stepper(
                { run { menu.setUpscalingSharpness((menu.upscalerSharpness - 5).coerceAtLeast(0)) } },
                { run { menu.setUpscalingSharpness((menu.upscalerSharpness + 5).coerceAtMost(100)) } },
                Modifier.weight(1f), size = MIN_TARGET,
            ) {
                TouchSlider(menu.upscalerSharpness, 0, 100, 5, onChange = { run { menu.setUpscalingSharpness(it) } }, onCommit = {}, enabled = menu.upscalerAvailable, height = MIN_TARGET)
            }
        }
    }
}

@Composable
private fun FitScope.MorePage(menu: StreamMenuHost, run: (() -> Unit) -> Unit, onNavigate: (Sheet?) -> Unit, onClose: () -> Unit) {
    val items = buildList {
        add(GridItem(stringResource(R.string.stream_menu_row_send_keys), Symbols.KEYBOARD_COMMAND_KEY) { onNavigate(Sheet.KEYS) })
        add(GridItem(stringResource(R.string.stream_menu_row_commands), Symbols.TERMINAL) { onNavigate(Sheet.COMMANDS) })
        add(GridItem(stringResource(R.string.stream_menu_row_send_clipboard), Symbols.SEND) { run { menu.sendClipboard() }; onClose() })
        add(GridItem(stringResource(R.string.stream_menu_row_get_clipboard), Symbols.RECEIVE) { run { menu.getClipboard() }; onClose() })
        add(GridItem(stringResource(R.string.stream_menu_row_task_manager), Symbols.TASK_MANAGER) { run { menu.sendTaskManager() }; onClose() })
        add(GridItem(stringResource(R.string.stream_menu_row_zoom), Symbols.ZOOM_IN, menu.zoomOn) { run { menu.toggleZoom() } })
        if (menu.allowChangeMouseMode) add(GridItem(stringResource(R.string.stream_menu_row_mouse_mode), Symbols.MOUSE) { onNavigate(Sheet.MOUSE) })
        add(GridItem(stringResource(R.string.stream_menu_row_onscreen_controls), Symbols.JOYSTICK, menu.onscreenControlsOn) { run { menu.toggleOnscreenControls() } })
        add(GridItem(stringResource(R.string.stream_menu_row_android_keyboard), Symbols.ANDROID_KEYBOARD) { run { menu.toggleSystemKeyboard() }; onClose() })
        add(GridItem(stringResource(R.string.stream_menu_row_touch_sensitivity), Symbols.TOUCH_SENSITIVITY, menu.touchSensitivityOn) { run { menu.toggleTouchSensitivity() } })
    }
    PagedGrid(items, Modifier.fills(minHeight = GRID_MIN.px), maxColumns = 2)
}

@Composable
private fun FitScope.MousePage(menu: StreamMenuHost, run: (() -> Unit) -> Unit) {
    val modes = remember { menu.mouseModes() }
    val selected = menu.mouseMode.toString()
    val items = modes.map { m -> GridItem(m.label, selected = m.value == selected) { run { menu.setMouseMode(m.value.toInt()) } } } +
        GridItem(stringResource(R.string.stream_menu_row_local_cursor), Symbols.CURSOR, menu.localMouseCursorVisible) { run { menu.toggleLocalMouseCursor() } }
    PagedGrid(items, Modifier.fills(minHeight = GRID_MIN.px), maxColumns = 2)
}
