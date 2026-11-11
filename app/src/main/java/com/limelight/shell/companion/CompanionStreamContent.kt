package com.limelight.shell.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.limelight.R
import com.limelight.preferences.UpscalerMode
import com.limelight.shell.StreamMenuHost
import com.limelight.shell.bitrateStatusText
import com.limelight.shell.showsSupersamplingQuality
import com.limelight.shell.pillText
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LowRed
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
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
        Column(Modifier.fillMaxSize().padding(start = 44.px, end = 44.px, top = 22.px, bottom = 28.px), verticalArrangement = Arrangement.spacedBy(18.px)) {
            Heading(state)
            StatsCard(state)
            if (menuShown) {
                if (!isTight()) Eyebrow(stringResource(R.string.companion_menu_heading))
                if (commands != null) {
                    Tiles(commands.menu, Modifier.weight(1f), run, onKeyboard = { typing = true }, onResume = { menuShown = false }, onSheet = { sheet = it })
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Body(stringResource(R.string.companion_menu_unavailable), color = LocalDelosColors.current.text3, maxLines = 3, align = TextAlign.Center)
                    }
                }
            } else {
                Rest(Modifier.weight(1f).fillMaxWidth()) { menuShown = true }
            }
        }
        val menu = commands?.menu
        if (sheet != null && menu != null) SheetHost(sheet!!, menu, run, onNavigate = { sheet = it }, onClose = { sheet = null })
    }
}

@Composable
private fun Heading(state: CompanionState.Stream) {
    val colors = LocalDelosColors.current
    val readout = StatsReadout.of(state.stats)
    Column(verticalArrangement = Arrangement.spacedBy(6.px)) {
        // A short panel (the 4:3 ones) has no room for the small caps line: the statistics say it is a stream
        if (!isTight()) Eyebrow(stringResource(R.string.companion_streaming))
        BasicText(
            state.gameName ?: stringResource(R.string.companion_streaming),
            style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 38.tpx),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Body(readout.picture ?: state.pcName.orEmpty(), color = colors.text2)
    }
}

/** FPS, network delay, decode time and the bitrate asked for: four values the player glances at. */
@Composable
private fun StatsCard(state: CompanionState.Stream) {
    val colors = LocalDelosColors.current
    val readout = StatsReadout.of(state.stats)
    val bitrate = state.commands?.menu?.bitrateKbps?.let { "${it / 1000} Mbps" } ?: "\u2013" // l10n-ignore
    Row(Modifier.fillMaxWidth().card().padding(horizontal = 26.px, vertical = if (isTight()) 10.px else 18.px), verticalAlignment = Alignment.CenterVertically) {
        val cells = listOf(
            readout.fps to "FPS", // l10n-ignore
            readout.network to stringResource(R.string.stream_stats_network),
            readout.decode to stringResource(R.string.stream_stats_decoding),
            bitrate to stringResource(R.string.stream_menu_tile_bitrate),
        )
        cells.forEachIndexed { i, (value, label) ->
            if (i > 0) Box(Modifier.padding(horizontal = 14.px).size(width = 1.px, height = 70.px).background(colors.line))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.px)) {
                BasicText(
                    value,
                    style = TextStyle(color = colors.accent2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 36.tpx, fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Body(label, color = colors.text2)
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (isTight()) 10.px else 14.px)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.px)) {
            Tile(stringResource(R.string.stream_menu_tile_resume), Symbols.RESUME, onResume, Modifier.weight(1f), TileStyle.PRIMARY)
            Tile(stringResource(R.string.stream_menu_tile_keyboard), Symbols.KEYBOARD, onKeyboard, Modifier.weight(1f))
            Tile(stringResource(R.string.stream_menu_tile_overlay), Symbols.OVERLAY, { run { menu.toggleOverlay() } }, Modifier.weight(1f), pill = overlay, on = menu.overlayOn)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.px)) {
            Tile(stringResource(R.string.stream_menu_tile_brightness), Symbols.BRIGHTNESS_MEDIUM, { onSheet(Sheet.BRIGHTNESS) }, Modifier.weight(1f))
            Tile(stringResource(R.string.stream_menu_tile_bitrate), Symbols.NETWORK_CHECK, { onSheet(Sheet.BITRATE) }, Modifier.weight(1f))
            Tile(
                stringResource(if (menu.supersamplingMode) R.string.stream_menu_tile_supersampling else R.string.stream_menu_tile_upscaler),
                Symbols.UPSCALER, { onSheet(Sheet.UPSCALER) }, Modifier.weight(1f), pill = status.pillText(res), on = menu.upscalerRequested,
            )
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = if (isTight()) 4.px else 14.px), horizontalArrangement = Arrangement.spacedBy(14.px)) {
            Tile(stringResource(R.string.stream_menu_tile_more), Symbols.MORE_HORIZ, { onSheet(Sheet.MORE) }, Modifier.weight(1f))
            Tile(stringResource(R.string.stream_menu_tile_disconnect), Symbols.LOGOUT, { run { menu.leave(quit = false) } }, Modifier.weight(1f), TileStyle.DANGER)
            Tile(stringResource(R.string.stream_menu_tile_quit), Symbols.POWER_SETTINGS_NEW, { run { menu.leave(quit = true) } }, Modifier.weight(1f), TileStyle.DANGER)
        }
    }
}

/** The panel is short in stage pixels (a 4:3 panel like 960 × 720): the stream state drops its small caps lines and lays tiles out flat. */
@Composable
private fun isTight(): Boolean = com.limelight.shell.ui.LocalStage.current.stageHeight < 920f

private enum class TileStyle { NORMAL, PRIMARY, DANGER }

@Composable
private fun Tile(label: String, icon: String, onClick: () -> Unit, modifier: Modifier, style: TileStyle = TileStyle.NORMAL, pill: String? = null, on: Boolean = false) {
    val colors = LocalDelosColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(24.px)
    val (fill, content, line) = when (style) {
        TileStyle.PRIMARY -> Triple(Brush.linearGradient(listOf(colors.accent, colors.accent)), colors.onAccent, colors.accent)
        TileStyle.DANGER -> Triple(Brush.linearGradient(listOf(LowRed.copy(alpha = .13f), LowRed.copy(alpha = .13f))), LowRed, LowRed.copy(alpha = .5f))
        TileStyle.NORMAL -> Triple(Brush.linearGradient(listOf(colors.surface2, colors.surface)), colors.text, colors.line)
    }
    Box(
        modifier
            .fillMaxHeight()
            .padding(top = if (pressed) 2.px else 0.px)
            .clip(shape)
            .background(fill)
            .border(if (on) 3.px else 1.px, if (on) colors.accent else line, shape)
            .clickable(source, null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.px, vertical = 16.px),
    ) {
        if (isTight()) {
            // Short tile: the icon beside the name, the state under it
            Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(16.px), verticalAlignment = Alignment.CenterVertically) {
                Symbol(icon, 44.tpx, if (style == TileStyle.NORMAL) colors.accent2 else content)
                Column {
                    BasicText(label, style = TextStyle(color = content, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 30.tpx), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (pill != null) BasicText(pill, style = TextStyle(color = if (on) colors.accent else colors.text2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = MIN_TEXT.tpx), maxLines = 1)
                }
            }
            return@Box
        }
        Symbol(icon, 48.tpx, if (style == TileStyle.NORMAL) colors.accent2 else content, Modifier.align(Alignment.TopStart))
        // The tile's state (Overlay: Off / On / Compact, Upscaler: its status) as the menu on the main panel shows it
        if (pill != null) {
            BasicText(
                pill,
                modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(if (on) colors.accent else colors.surface3).padding(horizontal = 16.px, vertical = 4.px),
                style = TextStyle(color = if (on) colors.onAccent else colors.text2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = MIN_TEXT.tpx),
                maxLines = 1,
            )
        }
        BasicText(
            label,
            modifier = Modifier.align(Alignment.BottomStart),
            style = TextStyle(color = content, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 32.tpx),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The tiles tucked away (Resume): a quiet screen with one button to bring them back. */
@Composable
private fun Rest(modifier: Modifier, onOpen: () -> Unit) {
    val colors = LocalDelosColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.px, Alignment.CenterVertically)) {
        Box(Modifier.size(120.px).clip(CircleShape).background(colors.accent.copy(alpha = .16f)).border(2.px, colors.accent.copy(alpha = .5f), CircleShape), contentAlignment = Alignment.Center) {
            Symbol(Symbols.RESUME, 56.tpx, colors.accent)
        }
        BasicText(
            stringResource(R.string.companion_back_to_main),
            style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 34.tpx, textAlign = TextAlign.Center),
        )
        Body(stringResource(R.string.companion_back_hint), color = colors.text2, maxLines = 2, align = TextAlign.Center)
        CompanionButton(stringResource(R.string.companion_open_menu), onOpen, Modifier.height(88.px), ButtonStyle.SECONDARY)
    }
}

// ---- Sheets ----

/** A page over the tiles: a scrim that closes on a tap and a card with a title and the Close button. */
@Composable
private fun SheetFrame(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalDelosColors.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)).pointerInput(Unit) { detectTapGestures { onClose() } }.padding(horizontal = 32.px, vertical = 28.px),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(30.px)
        Column(
            Modifier.widthIn(max = 1000.px).fillMaxWidth().clip(shape).background(colors.bg2).border(1.px, colors.line, shape)
                // A tap on the card is the card's, not the scrim's
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(30.px),
            verticalArrangement = Arrangement.spacedBy(20.px),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    title,
                    modifier = Modifier.weight(1f),
                    style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 34.tpx),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CompanionButton(stringResource(R.string.companion_close), onClose, Modifier.height(MIN_TARGET.px + 12.px), icon = Symbols.CLOSE)
            }
            content()
        }
    }
}

@Composable
private fun SheetHost(sheet: Sheet, menu: StreamMenuHost, run: (() -> Unit) -> Unit, onNavigate: (Sheet?) -> Unit, onClose: () -> Unit) {
    val back = { onNavigate(Sheet.MORE) }
    when (sheet) {
        Sheet.BRIGHTNESS -> SheetFrame(stringResource(R.string.stream_menu_title_brightness), onClose) { BrightnessPage(menu, run) }
        Sheet.BITRATE -> SheetFrame(stringResource(R.string.stream_menu_title_bitrate), onClose) { BitratePage(menu, run) }
        Sheet.UPSCALER -> SheetFrame(stringResource(R.string.stream_menu_title_upscaler), onClose) { UpscalerPage(menu, run) }
        Sheet.MORE -> SheetFrame(stringResource(R.string.stream_menu_title_more), onClose) { MorePage(menu, run, onNavigate, onClose) }
        Sheet.KEYS -> SheetFrame(stringResource(R.string.stream_menu_title_keys), back) {
            ChoiceList(menu.specialKeys) { i -> run { menu.sendSpecialKey(i) } }
        }
        Sheet.COMMANDS -> SheetFrame(stringResource(R.string.stream_menu_title_commands), back) {
            val commands = menu.serverCommands
            if (commands.isEmpty()) Body(stringResource(R.string.stream_menu_no_commands), maxLines = 4)
            else ChoiceList(commands) { i -> run { menu.runServerCommand(i) }; onClose() }
        }
        Sheet.MOUSE -> SheetFrame(stringResource(R.string.stream_menu_title_mouse), back) { MousePage(menu, run) }
    }
}

@Composable
private fun ValueLine(label: String, value: String) {
    val colors = LocalDelosColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, style = TextStyle(color = colors.text, fontFamily = Figtree, fontSize = 34.tpx))
        BasicText(value, style = TextStyle(color = colors.accent2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 34.tpx, fontFeatureSettings = "tnum"))
    }
}

@Composable
private fun BrightnessPage(menu: StreamMenuHost, run: (() -> Unit) -> Unit) {
    var value by remember { mutableIntStateOf(menu.brightnessPercent) }
    fun set(v: Int) {
        val next = v.coerceIn(5, 100)
        if (next != value) {
            value = next
            run { menu.setBrightness(next) }
        }
    }
    ValueLine(stringResource(R.string.stream_menu_row_screen_brightness), "$value %") // l10n-ignore
    Stepper({ set(value - 5) }, { set(value + 5) }) {
        TouchSlider(value, 5, 100, 5, onChange = ::set, onCommit = {})
    }
}

@Composable
private fun BitratePage(menu: StreamMenuHost, run: (() -> Unit) -> Unit) {
    val res = textResources()
    val colors = LocalDelosColors.current
    var requested by remember { mutableIntStateOf((menu.bitrateKbps / 1000).coerceIn(1, 1000)) }
    val max = maxOf(300, requested)
    val busy = menu.bitrateBusy
    ValueLine(stringResource(R.string.stream_menu_row_bitrate), "$requested Mbps") // l10n-ignore
    Stepper({ requested = (requested - 5).coerceAtLeast(1) }, { requested = (requested + 5).coerceAtMost(1000) }) {
        TouchSlider(requested.coerceAtMost(max), 1, max, 1, onChange = { requested = it }, onCommit = {}, enabled = !busy)
    }
    menu.linkGuidance?.let { guidance ->
        val recommended = (guidance.recommendation.kbps / 1000).coerceIn(1, 1000)
        CompanionButton(guidance.line(res), { requested = recommended }, Modifier.fillMaxWidth(), ButtonStyle.SECONDARY, icon = Symbols.RECOMMEND, enabled = !busy, textSize = MIN_TEXT, maxLines = 2)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.px), verticalAlignment = Alignment.CenterVertically) {
        CompanionButton(stringResource(R.string.stream_menu_row_apply), { run { menu.applyBitrate(requested * 1000) { requested = it / 1000 } } }, Modifier.height(88.px).widthIn(min = 240.px), ButtonStyle.PRIMARY, icon = Symbols.CHECK, enabled = !busy)
        bitrateStatusText(res, busy, menu.bitrateResult)?.let { Body(it, color = colors.text2, maxLines = 2, modifier = Modifier.weight(1f)) }
    }
}

@Composable
private fun UpscalerPage(menu: StreamMenuHost, run: (() -> Unit) -> Unit) {
    val available = menu.upscalerAvailable && !menu.upscalerStatus.isChanging
    val mode = menu.upscalerMode
    CompanionButton(
        stringResource(R.string.stream_menu_row_enabled) + ": " + stringResource(if (menu.upscalerRequested) R.string.common_on else R.string.common_off),
        { run { menu.setUpscalingEnabled(!menu.upscalerRequested) } }, Modifier.fillMaxWidth().height(80.px),
        if (menu.upscalerRequested) ButtonStyle.PRIMARY else ButtonStyle.NORMAL, icon = Symbols.UPSCALER, enabled = available,
    )
    Eyebrow(stringResource(R.string.stream_menu_row_mode))
    Column(verticalArrangement = Arrangement.spacedBy(10.px)) {
        UpscalerMode.values().toList().chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.px)) {
                pair.forEach { m ->
                    CompanionButton(stringResource(m.labelRes()), { run { menu.setUpscalingMode(m.index()) } }, Modifier.weight(1f).height(MIN_TARGET.px + 8.px), selected = m.index() == mode, enabled = menu.upscalerAvailable, textSize = MIN_TEXT)
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
    if (showsSupersamplingQuality(menu.supersamplingMode, mode)) {
        Eyebrow(stringResource(R.string.stream_menu_row_supersampling_quality))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.px)) {
            val level = menu.supersamplingLevel.coerceIn(1, 2)
            CompanionButton("1.33\u00d7 (1440p)", { run { menu.setSupersamplingLevel(1) } }, Modifier.weight(1f).height(MIN_TARGET.px + 8.px), selected = level == 1, enabled = menu.upscalerAvailable, textSize = MIN_TEXT) // l10n-ignore
            CompanionButton("2\u00d7 (2160p)", { run { menu.setSupersamplingLevel(2) } }, Modifier.weight(1f).height(MIN_TARGET.px + 8.px), selected = level == 2, enabled = menu.upscalerAvailable, textSize = MIN_TEXT) // l10n-ignore
        }
    }
    ValueLine(stringResource(R.string.stream_menu_row_sharpness), "${menu.upscalerSharpness} %") // l10n-ignore
    Stepper({ run { menu.setUpscalingSharpness((menu.upscalerSharpness - 5).coerceAtLeast(0)) } }, { run { menu.setUpscalingSharpness((menu.upscalerSharpness + 5).coerceAtMost(100)) } }) {
        TouchSlider(menu.upscalerSharpness, 0, 100, 5, onChange = { run { menu.setUpscalingSharpness(it) } }, onCommit = {}, enabled = menu.upscalerAvailable)
    }
}

private class MoreEntry(val label: Int, val icon: String, val on: Boolean? = null, val action: () -> Unit)

@Composable
private fun MorePage(menu: StreamMenuHost, run: (() -> Unit) -> Unit, onNavigate: (Sheet?) -> Unit, onClose: () -> Unit) {
    val entries = buildList {
        add(MoreEntry(R.string.stream_menu_row_send_keys, Symbols.KEYBOARD_COMMAND_KEY) { onNavigate(Sheet.KEYS) })
        add(MoreEntry(R.string.stream_menu_row_commands, Symbols.TERMINAL) { onNavigate(Sheet.COMMANDS) })
        add(MoreEntry(R.string.stream_menu_row_send_clipboard, Symbols.SEND) { run { menu.sendClipboard() }; onClose() })
        add(MoreEntry(R.string.stream_menu_row_get_clipboard, Symbols.RECEIVE) { run { menu.getClipboard() }; onClose() })
        add(MoreEntry(R.string.stream_menu_row_task_manager, Symbols.TASK_MANAGER) { run { menu.sendTaskManager() }; onClose() })
        add(MoreEntry(R.string.stream_menu_row_zoom, Symbols.ZOOM_IN, menu.zoomOn) { run { menu.toggleZoom() } })
        if (menu.allowChangeMouseMode) add(MoreEntry(R.string.stream_menu_row_mouse_mode, Symbols.MOUSE) { onNavigate(Sheet.MOUSE) })
        add(MoreEntry(R.string.stream_menu_row_onscreen_controls, Symbols.JOYSTICK, menu.onscreenControlsOn) { run { menu.toggleOnscreenControls() } })
        add(MoreEntry(R.string.stream_menu_row_android_keyboard, Symbols.ANDROID_KEYBOARD) { run { menu.toggleSystemKeyboard() }; onClose() })
        add(MoreEntry(R.string.stream_menu_row_touch_sensitivity, Symbols.TOUCH_SENSITIVITY, menu.touchSensitivityOn) { run { menu.toggleTouchSensitivity() } })
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.px)) {
        entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.px)) {
                pair.forEach { e ->
                    CompanionButton(stringResource(e.label), e.action, Modifier.weight(1f).height(MIN_TARGET.px + 12.px), icon = e.icon, selected = e.on == true, textSize = MIN_TEXT)
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MousePage(menu: StreamMenuHost, run: (() -> Unit) -> Unit) {
    val modes = remember { menu.mouseModes() }
    val selected = menu.mouseMode.toString()
    Column(verticalArrangement = Arrangement.spacedBy(10.px)) {
        modes.forEach { m ->
            CompanionButton(m.label, { run { menu.setMouseMode(m.value.toInt()) } }, Modifier.fillMaxWidth().height(MIN_TARGET.px + 8.px), selected = m.value == selected, textSize = MIN_TEXT)
        }
    }
    CompanionButton(
        stringResource(R.string.stream_menu_row_local_cursor), { run { menu.toggleLocalMouseCursor() } }, Modifier.fillMaxWidth().height(MIN_TARGET.px + 8.px),
        icon = Symbols.CURSOR, selected = menu.localMouseCursorVisible, textSize = MIN_TEXT,
    )
}

/** A scrolling list of choices (the special keys, the PC's commands): one wide button each, two to a row. */
@Composable
private fun ChoiceList(items: List<String>, onChoose: (Int) -> Unit) {
    Column(Modifier.heightIn(max = 520.px).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.px)) {
        items.withIndex().chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.px)) {
                pair.forEach { (i, label) -> CompanionButton(label, { onChoose(i) }, Modifier.weight(1f).height(MIN_TARGET.px + 8.px), textSize = MIN_TEXT) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}
