package com.limelight.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import com.limelight.shell.ui.EaseOut
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.SettingRow
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * How the menu starts: on the bar ([MenuPage.MAIN]) with the focus on the Resume circle and its entrance, as in a
 * stream; the debug preview opens it at another [page], [focus] circle and popover [row], with or without the entrance.
 */
internal data class StreamMenuStart(
    val page: MenuPage = MenuPage.MAIN,
    val focus: BarItem = BarItem.RESUME,
    val row: Int = 0,
    val animate: Boolean = true,
)

/**
 * The whole stream menu in one composable (the bar, its popover and their motion), so a window of its own can
 * host it, with the stream behind it however that window draws it. It reads and does everything through [host];
 * [actions] are the controller input an external display forwards, [closeRequests] a close from outside (the
 * shortcut's second press).
 */
@Composable
internal fun StreamMenuRoot(
    host: StreamMenuHost,
    actions: Flow<ExternalMenuAction>,
    closeRequests: Flow<Unit>,
    start: StreamMenuStart = StreamMenuStart(),
) {
    var page by remember { mutableStateOf(start.page) }
    // The popover's page while it animates out after [page] went back to the bar
    var shownPage by remember { mutableStateOf(if (start.page != MenuPage.MAIN) start.page else MenuPage.MORE) }
    if (page != MenuPage.MAIN) shownPage = page
    // Each time the popover opens from the bar its card appears where its circle is; only a page change while it is open slides it
    var session by remember { mutableIntStateOf(0) }
    // The rows of a page enter one after the other, but not in a menu that starts open without its entrance
    var navigated by remember { mutableStateOf(start.animate) }
    var helpKey by remember(page) { mutableStateOf(pageHelpKey(page)) }
    // The circle whose help the bar shows: the focused one, and the one that opened the popover while the focus is in it
    var barHelpItem by remember { mutableStateOf(start.page.barItem() ?: start.focus) }
    // The row of More that gets the focus when More shows again (the row that opened a page above it)
    var entryRow by remember { mutableStateOf<String?>(null) }
    val circleFocus = remember { BarItem.entries.associateWith { FocusRequester() } }
    var pendingCircle by remember { mutableStateOf<BarItem?>(null) }
    LaunchedEffect(pendingCircle) {
        val item = pendingCircle ?: return@LaunchedEffect
        runCatching { circleFocus.getValue(item).requestFocus() }
        pendingCircle = null
    }
    val reducedMotion = LocalReducedMotion.current
    val stage = LocalStage.current
    val k = stage.dpPerPx * LocalDensity.current.density

    // Entrance and exit of the whole menu: 0 = gone, 1 = shown. The entrance is a spring, the exit is quicker.
    val shown = remember { Animatable(if (start.animate) 0f else 1f) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (start.animate) {
            if (reducedMotion) shown.animateTo(1f, tween(220, easing = EaseOut))
            else shown.animateTo(1f, spring(dampingRatio = .8f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    fun close(then: () -> Unit = {}) {
        if (closing) return
        closing = true
        scope.launch {
            shown.animateTo(0f, tween(if (reducedMotion) 150 else 200, easing = FastOutLinearInEasing))
            host.dismiss()
            host.runWhenStreamFocused(then)
        }
    }
    fun go(exit: PageExit) {
        navigated = true
        entryRow = exit.focusRow
        pendingCircle = exit.focusCircle
        page = exit.page
    }
    // B: one level up, to the circle on the bar; on the bar it resumes (closes the menu)
    fun back() {
        val exit = pageAfterBack(page)
        if (exit == null) close() else go(exit)
    }
    // A circle that opens a page; its own circle again closes the popover
    fun openPage(next: MenuPage) {
        if (page == next) pageAfterDismiss(page)?.let { go(it) }
        else {
            navigated = true
            if (page == MenuPage.MAIN) session++
            entryRow = null
            page = next
        }
    }
    BackHandler(enabled = !closing) { back() }
    val focusManager = LocalFocusManager.current
    val focusTracker = LocalFocusTracker.current
    LaunchedEffect(Unit) { closeRequests.collect { close() } }
    LaunchedEffect(Unit) {
        actions.collect { action ->
            when (action) {
                ExternalMenuAction.Activate -> focusTracker.activate()
                ExternalMenuAction.Back -> back()
                is ExternalMenuAction.Move -> {
                    val adjustment = when (action.direction) {
                        MenuDirection.LEFT -> -1
                        MenuDirection.RIGHT -> 1
                        else -> 0
                    }
                    if (adjustment == 0 || !focusTracker.adjust(adjustment, action.repeatCount)) {
                        focusManager.moveFocus(
                            when (action.direction) {
                                MenuDirection.UP -> FocusDirection.Up
                                MenuDirection.DOWN -> FocusDirection.Down
                                MenuDirection.LEFT -> FocusDirection.Left
                                MenuDirection.RIGHT -> FocusDirection.Right
                            }
                        )
                    }
                }
            }
        }
    }
    // Toggles keep the menu open; reading [changes] redraws their On / Off state after each one
    var changes by remember { mutableIntStateOf(0) }
    fun toggle(action: () -> Unit) {
        action()
        changes++
    }
    val upscalerStatus by produceState(host.upscalerStatus) {
        while (true) { value = host.upscalerStatus; delay(250) }
    }
    val supersamplingMode by produceState(host.supersamplingMode) {
        while (true) { value = host.supersamplingMode; delay(250) }
    }
    // The level the renderer reports: it follows a press at once, and the poll shows a later correction (the
    // renderer found that 2× does not fit). Reading the level the last frame applied instead made the row
    // show the old value after a press, until the next press redrew it.
    var supersamplingLevel by remember { mutableIntStateOf(host.supersamplingLevel) }
    LaunchedEffect(Unit) { while (true) { supersamplingLevel = host.supersamplingLevel; delay(250) } }
    @Suppress("UNUSED_EXPRESSION") changes // subscribes to it
    // Composed already visible-bound, so the bar's first circle exists when the focus is requested
    if (start.page == MenuPage.MAIN) InitialFocus(circleFocus.getValue(start.focus))

    val circles = listOf(
        BarCircleSpec(BarItem.RESUME, Symbols.RESUME, "Resume") { close() },
        BarCircleSpec(BarItem.KEYBOARD, Symbols.KEYBOARD, "Keyboard") { close { host.toggleKeyboard() } },
        BarCircleSpec(BarItem.OVERLAY, Symbols.OVERLAY, "Overlay", on = host.overlayOn,
            state = if (!host.overlayOn) "Off" else if (host.overlayCompact) "Compact" else "On") {
            toggle { host.toggleOverlay() }
        },
        BarCircleSpec(BarItem.BRIGHTNESS, Symbols.BRIGHTNESS_MEDIUM, "Brightness") { openPage(MenuPage.BRIGHTNESS) },
        BarCircleSpec(BarItem.BITRATE, Symbols.NETWORK_CHECK, "Bitrate") { openPage(MenuPage.BITRATE) },
        BarCircleSpec(BarItem.UPSCALER, Symbols.UPSCALER, circleHelpKey(BarItem.UPSCALER, supersamplingMode),
            on = host.upscalerRequested, state = upscalerCircleState(upscalerStatus)) { openPage(MenuPage.UPSCALER) },
        BarCircleSpec(BarItem.MORE, Symbols.MORE_HORIZ, "More") { openPage(MenuPage.MORE) },
        BarCircleSpec(BarItem.DISCONNECT, Symbols.LOGOUT, "Disconnect") { close { host.leave(quit = false) } },
        BarCircleSpec(BarItem.QUIT, Symbols.POWER_SETTINGS_NEW, "Quit game") { close { host.leave(quit = true) } },
    )
    fun helpText(key: String): String =
        helpLineText(streamMenuHelp(key, upscalerStatus, host.upscalerMode, host.mouseMode, host.rememberMouseMode))
    val barHelpKey = circleHelpKey(barHelpItem, supersamplingMode)

    CompositionLocalProvider(LocalStreamHelp provides { helpKey = it }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val areaWidth = maxWidth.value / stage.dpPerPx
            val areaHeight = maxHeight.value / stage.dpPerPx
            val bar = barFrame(areaWidth, areaHeight)
            // A tap anywhere else closes the popover; it never resumes the stream (only Resume and B do)
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { pageAfterDismiss(page)?.let { go(it) } } })
            StreamMenuGradient(Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) })

            // The popover sits above the circle that opened it, its caret pointing down at that circle
            val placement = popoverPlacement(shownPage, bar, areaWidth)!!
            val left = key(session) { animateFloatAsState(placement.left, spring(dampingRatio = .85f, stiffness = Spring.StiffnessMedium), label = "popoverLeft") }
            val caret = key(session) { animateFloatAsState(placement.caretX, spring(dampingRatio = .85f, stiffness = Spring.StiffnessMedium), label = "popoverCaret") }
            val caretLocal = caret.value - left.value
            val origin = TransformOrigin((caretLocal / placement.width).coerceIn(0f, 1f), 1f)
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) }) {
                AnimatedVisibility(
                    page != MenuPage.MAIN,
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = (areaHeight - bar.top + StreamMenuLayout.POPOVER_GAP).px)
                        .offset { IntOffset((left.value * k).roundToInt(), 0) },
                    enter = fadeIn(tween(220, easing = EaseOut)) +
                        (if (reducedMotion) EnterTransition.None
                        else scaleIn(spring(dampingRatio = .78f, stiffness = Spring.StiffnessMediumLow), initialScale = .9f, transformOrigin = origin)),
                    exit = fadeOut(tween(170, easing = EaseOut)) +
                        (if (reducedMotion) ExitTransition.None
                        else scaleOut(tween(220, easing = FastOutLinearInEasing), targetScale = .92f, transformOrigin = origin)),
                ) {
                    StreamMenuPopover(
                        width = placement.width,
                        caretX = caretLocal,
                        maxHeight = popoverMaxHeight(bar),
                        active = page != MenuPage.MAIN,
                    ) {
                        Crossfade(shownPage, animationSpec = tween(200, easing = EaseOut), label = "popoverPage") { current ->
                            val stagger = remember { RowStagger(enabled = navigated) }
                            CompositionLocalProvider(LocalRowStagger provides stagger) {
                                PopoverPage(
                                    title = pageTitle(current, supersamplingMode),
                                    footer = helpText(helpKey),
                                    footerLines = popoverFooterLines(current),
                                    onBack = { back() },
                                ) {
                                    val first = remember { FocusRequester() }
                                    InitialFocus(first)
                                    PreviewRow(start, current)
                                    // [changes] redraws the rows after a toggle or a slider step (their values are not state)
                                    PageRows(
                                        host, current, first, entryRow, supersamplingMode, supersamplingLevel, upscalerStatus, changes,
                                        openPage = { openPage(it) },
                                        close = { close(it) },
                                        toggle = { toggle(it) },
                                        onLevel = { supersamplingLevel = it; changes++ },
                                        changesTick = { changes++ },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            StreamMenuBar(
                title = host.gameName,
                backLabel = if (page == MenuPage.MAIN) "Resume" else "Back",
                circles = circles,
                requesters = circleFocus,
                selected = page.barItem(),
                initialItem = start.page.barItem() ?: start.focus,
                entrance = start.animate,
                helpTitle = barHelpKey,
                helpText = helpText(barHelpKey),
                width = bar.width,
                onCircleFocused = { barHelpItem = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = StreamMenuLayout.SAFE_MARGIN.px)
                    .graphicsLayer {
                        translationY = if (reducedMotion) 0f else (1f - shown.value) * 80f * k
                        alpha = shown.value.coerceIn(0f, 1f)
                    },
            )
        }
    }
}

/** The preview's starting row: after the page's first row got the focus, it moves down [StreamMenuStart.row] rows. */
@Composable
private fun PreviewRow(start: StreamMenuStart, current: MenuPage) {
    val focusManager = LocalFocusManager.current
    if (start.row <= 0 || current != start.page) return
    LaunchedEffect(Unit) {
        delay(500)
        repeat(start.row) {
            focusManager.moveFocus(FocusDirection.Down)
            delay(60)
        }
    }
}

/** A compact row of a popover: its label doubles as the help key unless [help] says otherwise. */
@Composable
private fun MenuRow(
    icon: String,
    label: String,
    value: RowValue,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    help: String = label,
) {
    val show = LocalStreamHelp.current
    SettingRow(icon, label, value, modifier.rowEntrance(), description = description, enabled = enabled, dense = true, onFocused = { show(help) })
}

/** The rows (or pills) of [page], everything the classic Artemis quick menu offered that the bar does not included. */
@Composable
private fun PageRows(
    host: StreamMenuHost,
    page: MenuPage,
    first: FocusRequester,
    entryRow: String?,
    supersamplingMode: Boolean,
    supersamplingLevel: Int,
    upscalerStatus: String,
    tick: Int,
    openPage: (MenuPage) -> Unit,
    close: (() -> Unit) -> Unit,
    toggle: (() -> Unit) -> Unit,
    onLevel: (Int) -> Unit,
    changesTick: () -> Unit,
) {
    val colors = LocalDelosColors.current
    @Suppress("UNUSED_EXPRESSION") tick
    when (page) {
        MenuPage.MAIN -> Unit
        MenuPage.MORE -> {
            fun entry(label: String) = if (label == (entryRow ?: MoreRow.KEYS)) Modifier.focusRequester(first) else Modifier
            MenuRow(Symbols.KEYBOARD_COMMAND_KEY, MoreRow.KEYS, RowValue.Action { openPage(MenuPage.KEYS) }, entry(MoreRow.KEYS))
            MenuRow(Symbols.UPLOAD, "Send clipboard", RowValue.Action { close { host.sendClipboard() } })
            MenuRow(Symbols.DOWNLOAD, "Get clipboard", RowValue.Action { close { host.getClipboard() } })
            MenuRow(Symbols.TERMINAL, MoreRow.COMMANDS, RowValue.Action { openPage(MenuPage.COMMANDS) }, entry(MoreRow.COMMANDS))
            MenuRow(Symbols.TASK_MANAGER, "Task manager", RowValue.Action { close { host.sendTaskManager() } })
            MenuRow(Symbols.ZOOM_IN, "Zoom", RowValue.Toggle(host.zoomOn) { toggle { host.toggleZoom() } })
            if (host.allowChangeMouseMode) MenuRow(Symbols.MOUSE, MoreRow.MOUSE, RowValue.Action { openPage(MenuPage.MOUSE) }, entry(MoreRow.MOUSE))
            MenuRow(Symbols.JOYSTICK, "On-screen controls", RowValue.Toggle(host.onscreenControlsOn) { toggle { host.toggleOnscreenControls() } })
            MenuRow(Symbols.KEYBOARD_FULL, "Full keyboard", RowValue.Action { toggle { host.toggleFullKeyboard() } })
            MenuRow(Symbols.KEY_LAYOUT, "Key layout", RowValue.Action { toggle { host.toggleKeyLayout() } })
            MenuRow(Symbols.TOUCH_APP, "Touch sensitivity", RowValue.Toggle(host.touchSensitivityOn) { toggle { host.toggleTouchSensitivity() } })
            MenuRow(Symbols.SCREEN_ROTATION, "Rotate", RowValue.Action { close { host.rotate() } })
        }
        MenuPage.BRIGHTNESS -> MenuRow(
            Symbols.BRIGHTNESS_MEDIUM, "Screen brightness",
            RowValue.Slider(host.brightnessPercent, 5, 100, 5, " %") { toggle { host.setBrightness(it) } },
            Modifier.focusRequester(first),
        )
        MenuPage.BITRATE -> {
            var requested by remember { mutableIntStateOf(host.bitrateKbps.coerceIn(1000, 1000000)) }
            MenuRow(Symbols.NETWORK_CHECK, "Bitrate",
                RowValue.Slider(requested, 1000, 1000000, 1000, " Mbps", 1000) { requested = it },
                Modifier.focusRequester(first), enabled = !host.bitrateBusy)
            MenuRow(Symbols.CHECK, if (host.bitrateBusy) "Applying…" else "Apply",
                RowValue.Action { host.applyBitrate(requested) { requested = it } },
                description = host.bitrateMessage ?: "Vibepollo is required. The host may limit the requested bitrate.",
                enabled = !host.bitrateBusy, help = "Apply bitrate")
        }
        MenuPage.UPSCALER -> {
            val available = host.upscalerAvailable
            MenuRow(Symbols.UPSCALER, if (supersamplingMode) "Supersampling" else "Upscaling", RowValue.Toggle(host.upscalerRequested) {
                host.setUpscalingEnabled(it)
                changesTick()
            }, Modifier.focusRequester(first), enabled = available && upscalerStatus !in listOf("Starting", "Stopping"),
                help = if (supersamplingMode) "Supersampling on" else "Upscaling")
            if (supersamplingMode) {
                val level = supersamplingLevel.coerceIn(1, 2)
                MenuRow(Symbols.HIGH_QUALITY, "Supersampling quality",
                    RowValue.Choice(if (level == 1) "1× (1440p)" else "2× (2160p)", { direction ->
                        host.setSupersamplingLevel(if (direction < 0) 1 else 2)
                        onLevel(host.supersamplingLevel)
                    }), enabled = available)
            }
            val upscalerMode = host.upscalerMode
            MenuRow(Symbols.UPSCALER_MODE, "Mode", RowValue.Choice(listOf("SGSR 1 Standard", "SGSR 1 High Quality", "SGSR 2 Video", "SGSR 2 Video Quality")[upscalerMode], { direction ->
                host.setUpscalingMode((upscalerMode + direction + 4) % 4)
                changesTick()
            }), enabled = available)
            MenuRow(Symbols.SHARPNESS, "Sharpness", RowValue.Slider(host.upscalerSharpness, 0, 100, 5, " %") {
                host.setUpscalingSharpness(it)
                changesTick()
            }, enabled = available)
            // The one way a menu change outlives the session: the live values become this game's own settings.
            // A stream started without its launch identity has no game to save for, so the row is not offered.
            if (host.canSaveUpscaler) {
                var savedTick by remember { mutableIntStateOf(0) }
                LaunchedEffect(savedTick) { if (savedTick > 0) { delay(2500); savedTick = 0 } }
                MenuRow(if (savedTick > 0) Symbols.CHECK_CIRCLE else Symbols.REMEMBER, "Save for this game", RowValue.Action {
                    host.saveUpscaler()
                    savedTick++
                }, description = if (savedTick > 0) "Saved for ${host.gameName}" else null, enabled = available)
            }
        }
        MenuPage.MOUSE -> {
            val modes = remember { host.mouseModes() }
            val selected = modes.indexOfFirst { it.value == host.mouseMode.toString() }.coerceAtLeast(0)
            MenuRow(Symbols.TOUCH_APP, "Touchscreen mode", RowValue.Choice(modes[selected].label, { dir ->
                toggle { host.setMouseMode(modes[Math.floorMod(selected + dir, modes.size)].value.toInt()) }
            }), Modifier.focusRequester(first))
            MenuRow(Symbols.CURSOR, "Local mouse cursor", RowValue.Toggle(host.localMouseCursorVisible) {
                toggle { host.toggleLocalMouseCursor() }
            })
        }
        MenuPage.KEYS -> PopoverPills(host.specialKeys, first) { i ->
            host.dismiss()
            host.sendSpecialKey(i)
        }
        MenuPage.COMMANDS -> {
            val commands = host.serverCommands
            if (commands.isEmpty()) {
                BasicText(
                    "No commands are set up on the PC. Vibepollo can add them in its web UI.",
                    style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx, textAlign = TextAlign.Center),
                    modifier = Modifier.focusRequester(first).focusable().padding(horizontal = 12.px, vertical = 16.px),
                )
            } else {
                PopoverPills(commands, first, command = true) { i -> close { host.runServerCommand(i) } }
            }
        }
    }
}
