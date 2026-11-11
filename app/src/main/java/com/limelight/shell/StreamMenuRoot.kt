package com.limelight.shell

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.shell.ui.textResources
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.limelight.binding.video.UpscalerStatus
import com.limelight.preferences.UpscalerMode
import com.limelight.shell.settings.StreamMenuHelp
import com.limelight.shell.ui.label
import com.limelight.utils.RumblePresets
import com.limelight.shell.StreamMenuLayout as L
import com.limelight.shell.StreamMenuMotion as M
import com.limelight.shell.ui.EaseSpring
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.rememberFocusedListNavigation
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.EntranceStagger
import com.limelight.shell.ui.LocalEntranceStagger
import com.limelight.shell.ui.Motion
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.icon
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * How the menu starts: on the dock ([MenuPage.MAIN]) with the focus on the Resume tile and its entrance, as in a
 * stream; the debug preview opens it at another [page], [focus] tile and popover [row], with or without the entrance.
 */
internal data class StreamMenuStart(
    val page: MenuPage = MenuPage.MAIN,
    val focus: BarItem = BarItem.RESUME,
    val row: Int = 0,
    val animate: Boolean = true,
)

/**
 * The whole stream menu in one composable (the dock, its popover and their motion), so a window of its own can
 * host it, with the stream behind it however that window draws it. It reads and does everything through [host];
 * [actions] are the controller input an external display forwards, [closeRequests] a close from outside (the
 * shortcut's second press).
 *
 * The motion is prototype B's (docs/GUI.md, "Stream menu"): the dock enters and leaves on two clocks, the ring glides,
 * the focused tile scales, the caption slides in, the dock sinks while a popover is open, the popover grows out of its
 * caret, its rows enter one after the other and another tile's page morphs the card instead of replacing it. With
 * reduced motion everything but the fades is switched off.
 */
@Composable
internal fun StreamMenuRoot(
    host: StreamMenuHost,
    actions: Flow<ExternalMenuAction>,
    closeRequests: Flow<Unit>,
    start: StreamMenuStart = StreamMenuStart(),
) {
    val res = textResources()
    var page by remember { mutableStateOf(start.page) }
    // The page of the popover that is open or was open last: it stays while the popover animates out
    var shownPage by remember { mutableStateOf(if (start.page != MenuPage.MAIN) start.page else MenuPage.MORE) }
    if (page != MenuPage.MAIN) shownPage = page
    // The page whose rows are drawn: another page of the same tile replaces them after the old rows have left
    var contentPage by remember { mutableStateOf(shownPage) }
    var leaving by remember { mutableStateOf(false) }
    var titleIn by remember { mutableStateOf(false) }
    // True when [page] was reached while a popover was open (it morphs), false when the popover opens from the dock
    var morph by remember { mutableStateOf(false) }
    // Each time the popover opens from the dock its card appears where its tile is; only a page change while it is open slides it
    var session by remember { mutableIntStateOf(0) }
    // The rows of a page enter one after the other, but not in a menu that starts open without its entrance
    var navigated by remember { mutableStateOf(start.animate) }
    var helpKey by remember(contentPage) { mutableStateOf(pageHelpKey(contentPage)) }
    // The tile whose help the caption shows and that wears the focus: the focused one, and the one that opened the popover while the focus is in it
    var barHelpItem by remember { mutableStateOf(start.page.barItem() ?: start.focus) }
    var focusedItem by remember { mutableStateOf<BarItem?>(null) }
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
    // The popover: present from opening until it has animated out. Its geometry snaps when it opens from the dock
    // (after its content reported its size), and follows its target with a spring when another page replaces it.
    var popPresent by remember { mutableStateOf(start.page != MenuPage.MAIN) }
    val geo = remember { PopoverGeometry(popoverPlacement(shownPage)!!) }
    var natural by remember { mutableFloatStateOf(0f) }
    var snapNext by remember { mutableStateOf(start.page != MenuPage.MAIN) }
    var geoReady by remember { mutableStateOf(false) }
    val popScale = remember { Animatable(if (start.page != MenuPage.MAIN) 1f else L.POPOVER_HIDDEN_SCALE) }
    val popAlpha = remember { Animatable(if (start.page != MenuPage.MAIN) 1f else 0f) }
    var closing by remember { mutableStateOf(false) }
    val popOpen = page != MenuPage.MAIN && !closing

    // The clocks of the dock's entrance and exit, milliseconds; the gradient, the scrim and the dock's sinking
    val enterClock = remember { Animatable(if (start.animate) 0f else M.ENTER_TOTAL.toFloat()) }
    val exitClock = remember { Animatable(0f) }
    val gradient = remember { Animatable(if (start.animate) 0f else 1f) }
    val scrim = remember { Animatable(if (start.page != MenuPage.MAIN) 1f else 0f) }
    val dockShift = remember { Animatable(if (start.page != MenuPage.MAIN) L.DOCK_SHIFT else 0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (start.animate) enterClock.animateTo(M.ENTER_TOTAL.toFloat(), tween(M.ENTER_TOTAL, easing = LinearEasing))
    }
    // While the Upscaler page is open the stream is shown sharp (from the moment it opens, which feels better than waiting for
    // the toggle), so the changes to the picture can be judged; it blurs again when the page closes (both eased, never a jump)
    val sharp = remember { Animatable(1f) }
    LaunchedEffect(Unit) { snapshotFlow { gradient.value * sharp.value }.collect { host.setBackdrop(it) } }
    LaunchedEffect(closing) {
        gradient.animateTo(if (closing) 0f else 1f, tween(if (closing) M.CLOSE else Motion.GRADIENT, easing = Motion.Ease))
    }
    LaunchedEffect(popOpen) {
        coroutineScope {
            launch { scrim.animateTo(if (popOpen) 1f else 0f, tween(Motion.SCRIM, easing = Motion.Ease)) }
            launch {
                val target = if (popOpen) L.DOCK_SHIFT else 0f
                if (reducedMotion) dockShift.snapTo(target) else dockShift.animateTo(target, tween(M.DOCK, easing = com.limelight.shell.ui.EaseOut))
            }
        }
    }
    fun close(then: () -> Unit = {}) {
        if (closing) return
        closing = true
        scope.launch {
            exitClock.animateTo(M.CLOSE.toFloat(), tween(M.CLOSE, easing = LinearEasing))
            host.dismiss()
            host.runWhenStreamFocused(then)
        }
    }
    fun go(exit: PageExit) {
        navigated = true
        morph = exit.page != MenuPage.MAIN && page != MenuPage.MAIN
        entryRow = exit.focusRow
        pendingCircle = exit.focusCircle
        page = exit.page
    }
    // B: one level up, to the tile on the dock; on the dock it resumes (closes the menu)
    fun back() {
        val exit = pageAfterBack(page)
        if (exit == null) close() else go(exit)
    }
    // A tile that opens a page; its own tile again closes the popover; another tile's page morphs the card
    fun openPage(next: MenuPage) {
        if (page == next) pageAfterDismiss(page)?.let { go(it) }
        else {
            navigated = true
            morph = page != MenuPage.MAIN
            if (page == MenuPage.MAIN) {
                // Opening from the dock: the card appears where its tile is, sized to its page
                session++
                contentPage = next
                natural = 0f
                snapNext = true
                geoReady = false
                // The card is at its tile before its first layout (an undispatched snap runs right here)
                scope.launch(start = CoroutineStart.UNDISPATCHED) { geo.place(popoverPlacement(next)!!, snap = true) }
            }
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
    LaunchedEffect(page, closing) {
        sharp.animateTo(if (page == MenuPage.UPSCALER && !closing) 0f else 1f, tween(Motion.GRADIENT, easing = Motion.Ease))
    }
    // Composed already visible-bound, so the dock's first tile exists when the focus is requested
    if (start.page == MenuPage.MAIN) InitialFocus(circleFocus.getValue(start.focus))

    val circles = listOf(
        BarCircleSpec(BarItem.RESUME, Symbols.RESUME, res.getString(R.string.stream_menu_tile_resume)) { close() },
        // The Delos keyboard takes the controller, so the menu closes first. A toggle without a state word: only the accent shows it
        BarCircleSpec(BarItem.KEYBOARD, Symbols.KEYBOARD, res.getString(R.string.stream_menu_tile_keyboard), on = host.fullKeyboardOn, showState = false) {
            close { host.toggleFullKeyboard() }
        },
        BarCircleSpec(BarItem.OVERLAY, Symbols.OVERLAY, res.getString(R.string.stream_menu_tile_overlay), on = host.overlayOn,
            state = res.getString(if (!host.overlayOn) R.string.common_off else if (host.overlayCompact) R.string.stream_menu_overlay_compact else R.string.common_on)) {
            toggle { host.toggleOverlay() }
        },
        BarCircleSpec(BarItem.BRIGHTNESS, Symbols.BRIGHTNESS_MEDIUM, res.getString(R.string.stream_menu_tile_brightness), opensPage = true) { openPage(MenuPage.BRIGHTNESS) },
        BarCircleSpec(BarItem.BITRATE, Symbols.NETWORK_CHECK, res.getString(R.string.stream_menu_tile_bitrate), opensPage = true) { openPage(MenuPage.BITRATE) },
        BarCircleSpec(BarItem.UPSCALER, Symbols.UPSCALER, res.getString(if (supersamplingMode) R.string.stream_menu_tile_supersampling else R.string.stream_menu_tile_upscaler),
            on = host.upscalerRequested, state = upscalerStatus.pillText(res), opensPage = true) { openPage(MenuPage.UPSCALER) },
        BarCircleSpec(BarItem.MORE, Symbols.MORE_HORIZ, res.getString(R.string.stream_menu_tile_more), opensPage = true) { openPage(MenuPage.MORE) },
        BarCircleSpec(BarItem.DISCONNECT, Symbols.LOGOUT, res.getString(R.string.stream_menu_tile_disconnect)) { close { host.leave(quit = false) } },
        BarCircleSpec(BarItem.QUIT, Symbols.POWER_SETTINGS_NEW, res.getString(R.string.stream_menu_tile_quit)) { close { host.leave(quit = true) } },
    )
    // A rumble preset the device cannot run, or whose technique failed in the stream, says so in its help line
    fun helpText(key: String): String = if (key == RECOMMENDED_ROW) {
        helpLineText(host.linkGuidance?.streamHelp(res, System.currentTimeMillis()).orEmpty())
    } else helpLineText(
        streamMenuHelp(res, key, upscalerStatus, host.upscalerMode, host.mouseMode, host.rememberMouseMode, (host.bitrateResult as? BitrateResult.Failed)?.reason?.text(res)) +
            (StreamMenuHelp.rumblePreset(key)?.let { host.rumbleProblemText(res, it) }?.let { "\n" + res.getString(R.string.stream_menu_rumble_problem, it) }.orEmpty())
    )
    val barHelpKey = circleHelpKey(barHelpItem, supersamplingMode)

    LaunchedEffect(popOpen) {
        if (popOpen) {
            popPresent = true
        } else {
            coroutineScope {
                launch { popScale.animateTo(L.POPOVER_HIDDEN_SCALE, if (reducedMotion) tween(0) else tween(Motion.POP_OUT, easing = Motion.In)) }
                launch { popAlpha.animateTo(0f, tween(Motion.POP_OUT, easing = Motion.Ease)) }
            }
            popPresent = false
            geoReady = false
        }
    }
    val placement = popoverPlacement(shownPage)!!
    LaunchedEffect(placement, session) {
        val snap = snapNext || reducedMotion
        geo.place(placement, snap)
    }
    LaunchedEffect(natural, session) {
        if (natural <= 0f) return@LaunchedEffect
        val snap = snapNext || reducedMotion
        geo.fit(natural, snap)
        snapNext = false
        geoReady = true
    }
    LaunchedEffect(popOpen, geoReady) {
        if (popOpen && geoReady) {
            coroutineScope {
                launch { if (reducedMotion) popScale.snapTo(1f) else popScale.animateTo(1f, tween(Motion.POP_IN, easing = EaseSpring)) }
                launch { popAlpha.animateTo(1f, tween(Motion.CARD_FADE_IN, easing = Motion.Ease)) }
            }
        }
    }
    // Another page of the same tile: the old rows rise out, then the new rows are drawn and the card follows their size
    LaunchedEffect(page) {
        if (page == MenuPage.MAIN || contentPage == page) return@LaunchedEffect
        if (!morph) {
            contentPage = page
            return@LaunchedEffect
        }
        leaving = true
        delay(M.MORPH_SWAP)
        titleIn = true
        contentPage = page
        leaving = false
        delay(50)
        titleIn = false
    }

    CompositionLocalProvider(LocalStreamHelp provides { helpKey = it }) {
        Box(Modifier.fillMaxSize()) {
            // A tap anywhere else closes the popover; it never resumes the stream (only Resume and B do)
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { pageAfterDismiss(page)?.let { go(it) } } })
            StreamMenuBackdrop(gradientAlpha = { gradient.value }, scrimAlpha = { scrim.value })

            // Everything else sits on a 1920-wide box centered on the stage and as high as the real stage, bottom-aligned: the
            // dock and the popover keep their distance from the bottom edge, a taller stage gives the popover more room
            val stageHeight = LocalStage.current.stageHeight
            Box(Modifier.align(Alignment.BottomCenter).size(L.STAGE_WIDTH.px, stageHeight.px)) {
                StreamMenuDock(
                    title = host.gameName,
                    circles = circles,
                    requesters = circleFocus,
                    focusItem = focusedItem ?: page.barItem() ?: barHelpItem,
                    firstItem = start.page.barItem() ?: start.focus,
                    popOpen = popOpen,
                    caption = helpText(barHelpKey),
                    enter = { enterClock.value },
                    exit = { exitClock.value },
                    dockShift = { dockShift.value },
                    onCircleFocused = { item, focused ->
                        if (focused) {
                            focusedItem = item
                            barHelpItem = item
                        } else if (focusedItem == item) focusedItem = null
                    },
                    modifier = Modifier.offset(0.px, (stageHeight - L.STAGE_HEIGHT + L.DOCK_TOP).px),
                )

                // The popover sits above the tile that opened it, its caret pointing down at that tile
                if (popPresent) {
                    val navigation = key(session, contentPage) {
                        pageFocusKeys(host, contentPage, supersamplingMode)?.let { keys ->
                            rememberFocusedListNavigation(listOf(PopoverBackFocusKey) + keys, allowUpExit = false)
                        }
                    }
                    StreamMenuPopover(
                        geo = geo,
                        scale = { popScale.value },
                        // Nothing shows until the card has its size (the first frames of an opening popover are empty)
                        alpha = { if (geoReady) popAlpha.value else 0f },
                        active = popOpen,
                        navigation = navigation,
                        onNaturalHeight = { natural = it },
                        modifier = Modifier.align(Alignment.BottomStart).padding(bottom = L.POPOVER_BOTTOM.px),
                    ) {
                        key(session, contentPage) {
                            val stagger = remember { EntranceStagger(enabled = navigated) }
                            CompositionLocalProvider(LocalEntranceStagger provides stagger) {
                                PopoverPage(
                                    title = pageTitle(res, contentPage, supersamplingMode),
                                    help = helpText(helpKey),
                                    onBack = { back() },
                                    leaving = leaving,
                                    titleIn = titleIn,
                                    navigation = navigation,
                                ) {
                                    val first = remember { FocusRequester() }
                                    InitialFocus(first)
                                    PreviewRow(start, contentPage)
                                    // [changes] redraws the rows after a toggle or a slider step (their values are not state)
                                    PageRows(
                                        host, contentPage, first, entryRow, supersamplingMode, supersamplingLevel, upscalerStatus, changes,
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

/** Only genuinely linear pages use ordered navigation; wrapping key/command pills keep spatial 2D moves. */
private fun pageFocusKeys(host: StreamMenuHost, page: MenuPage, supersamplingMode: Boolean): List<String>? =
    when (page) {
        MenuPage.MAIN, MenuPage.KEYS, MenuPage.COMMANDS -> null
        MenuPage.MORE -> buildList {
            add(MoreRow.KEYS)
            add(MenuKey.SEND_CLIPBOARD)
            add(MenuKey.GET_CLIPBOARD)
            add(MoreRow.COMMANDS)
            add(MenuKey.TASK_MANAGER)
            add(MenuKey.ZOOM)
            if (host.allowChangeMouseMode) add(MoreRow.MOUSE)
            add(MenuKey.ONSCREEN_CONTROLS)
            add(MoreRow.ANDROID_KEYBOARD)
            add(MenuKey.TOUCH_SENSITIVITY)
            add(MoreRow.RUMBLE)
            if (host.gyroSmoothingAvailable) add(MoreRow.GYRO)
        }
        MenuPage.BRIGHTNESS -> listOf(MenuKey.SCREEN_BRIGHTNESS)
        MenuPage.BITRATE -> listOfNotNull(MenuKey.BITRATE, RECOMMENDED_ROW.takeIf { host.linkGuidance != null }, MenuKey.APPLY)
        MenuPage.UPSCALER -> buildList {
            add(MenuKey.ENABLED)
            if (showsSupersamplingQuality(supersamplingMode, host.upscalerMode)) add(MenuKey.SUPERSAMPLING_QUALITY)
            add(MenuKey.MODE)
            add(MenuKey.SHARPNESS)
        }
        MenuPage.MOUSE -> listOf(MenuKey.TOUCHSCREEN_MODE, MenuKey.LOCAL_MOUSE_CURSOR)
        MenuPage.RUMBLE -> RumblePresets.values().map { StreamMenuHelp.rumbleKey(it) }
    }

/** A row of a page: [key] names it for the focus order and the help lookup, [label] is the text on screen. */
@Composable
private fun KeyedRow(
    icon: String,
    @StringRes label: Int,
    key: String,
    value: RowValue,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    help: String = key,
) = MenuRow(icon, stringResource(label), value, modifier, enabled = enabled, help = help, key = key)

/** The rows (or pills) of [page]: everything the classic Artemis quick menu offered that the dock does not include. */
@Composable
private fun PageRows(
    host: StreamMenuHost,
    page: MenuPage,
    first: FocusRequester,
    entryRow: String?,
    supersamplingMode: Boolean,
    supersamplingLevel: Int,
    upscalerStatus: UpscalerStatus,
    tick: Int,
    openPage: (MenuPage) -> Unit,
    close: (() -> Unit) -> Unit,
    toggle: (() -> Unit) -> Unit,
    onLevel: (Int) -> Unit,
    changesTick: () -> Unit,
) {
    val colors = LocalDelosColors.current
    val res = textResources()
    @Suppress("UNUSED_EXPRESSION") tick
    when (page) {
        MenuPage.MAIN -> Unit
        MenuPage.MORE -> {
            fun entry(label: String) = if (label == (entryRow ?: MoreRow.KEYS)) Modifier.focusRequester(first) else Modifier
            KeyedRow(Symbols.KEYBOARD_COMMAND_KEY, R.string.stream_menu_row_send_keys, MoreRow.KEYS, RowValue.Action { openPage(MenuPage.KEYS) }, entry(MoreRow.KEYS))
            KeyedRow(Symbols.SEND, R.string.stream_menu_row_send_clipboard, MenuKey.SEND_CLIPBOARD, RowValue.Action { close { host.sendClipboard() } })
            KeyedRow(Symbols.RECEIVE, R.string.stream_menu_row_get_clipboard, MenuKey.GET_CLIPBOARD, RowValue.Action { close { host.getClipboard() } })
            KeyedRow(Symbols.TERMINAL, R.string.stream_menu_row_commands, MoreRow.COMMANDS, RowValue.Action { openPage(MenuPage.COMMANDS) }, entry(MoreRow.COMMANDS))
            KeyedRow(Symbols.TASK_MANAGER, R.string.stream_menu_row_task_manager, MenuKey.TASK_MANAGER, RowValue.Action { close { host.sendTaskManager() } })
            KeyedRow(Symbols.ZOOM_IN, R.string.stream_menu_row_zoom, MenuKey.ZOOM, RowValue.Toggle(host.zoomOn) { toggle { host.toggleZoom() } })
            if (host.allowChangeMouseMode) KeyedRow(Symbols.MOUSE, R.string.stream_menu_row_mouse_mode, MoreRow.MOUSE, RowValue.Action { openPage(MenuPage.MOUSE) }, entry(MoreRow.MOUSE))
            KeyedRow(Symbols.JOYSTICK, R.string.stream_menu_row_onscreen_controls, MenuKey.ONSCREEN_CONTROLS, RowValue.Toggle(host.onscreenControlsOn) { toggle { host.toggleOnscreenControls() } })
            KeyedRow(Symbols.ANDROID_KEYBOARD, R.string.stream_menu_row_android_keyboard, MoreRow.ANDROID_KEYBOARD, RowValue.Action { close { host.toggleSystemKeyboard() } })
            KeyedRow(Symbols.TOUCH_SENSITIVITY, R.string.stream_menu_row_touch_sensitivity, MenuKey.TOUCH_SENSITIVITY, RowValue.Toggle(host.touchSensitivityOn) { toggle { host.toggleTouchSensitivity() } })
            KeyedRow(Symbols.VIBRATION, R.string.stream_menu_row_rumble_feel, MoreRow.RUMBLE, RowValue.Text(host.rumblePreset?.label(res) ?: stringResource(R.string.stream_menu_rumble_custom)) { openPage(MenuPage.RUMBLE) }, entry(MoreRow.RUMBLE))
            if (host.gyroSmoothingAvailable) {
                // Applies at once and is kept for this game (never the profile, which is the Settings slider's); changesTick recomposes the value
                KeyedRow(Symbols.SMOOTHING, R.string.stream_menu_row_gyro_smoothing, MoreRow.GYRO, RowValue.Slider(host.gyroSmoothing, 0, 100, 5, " %", minText = stringResource(R.string.stream_menu_gyro_off, 0)) {
                    host.setGyroSmoothing(it)
                    changesTick()
                }, enabled = host.gyroSmoothingEnabled, help = gyroSmoothingHelpKey(host.gyroSmoothingEnabled))
            }
        }
        MenuPage.BRIGHTNESS -> KeyedRow(
            Symbols.BRIGHTNESS_MEDIUM, R.string.stream_menu_row_screen_brightness, MenuKey.SCREEN_BRIGHTNESS,
            RowValue.Slider(host.brightnessPercent, 5, 100, 5, " %") { toggle { host.setBrightness(it) } },
            Modifier.focusRequester(first),
        )
        MenuPage.BITRATE -> {
            var requested by remember { mutableIntStateOf(host.bitrateKbps.coerceIn(1000, 1000000)) }
            KeyedRow(Symbols.NETWORK_CHECK, R.string.stream_menu_row_bitrate, MenuKey.BITRATE,
                RowValue.Slider(requested, 1000, 1000000, 1000, " Mbps", 1000) { requested = it }, // l10n-ignore
                Modifier.focusRequester(first), enabled = !host.bitrateBusy)
            // PyroWave only: the recommendation with its reason, and the stream's own drop hint, refreshed every second.
            // A fills the slider; nothing is sent until Apply.
            var linkTick by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) { while (true) { delay(1000); linkTick++ } }
            @Suppress("UNUSED_EXPRESSION") linkTick
            host.linkGuidance?.let { guidance ->
                val recommended = guidance.recommendation.kbps.coerceIn(1000, 1000000)
                MenuRow(Symbols.RECOMMEND, guidance.line(res), RowValue.Text(stringResource(R.string.stream_menu_bitrate_fill)) { requested = recommended },
                    enabled = !host.bitrateBusy, help = RECOMMENDED_ROW, key = RECOMMENDED_ROW)
            }
            // The outcome stands on the right of the row; a failure explains itself in the help
            val apply = { host.applyBitrate(requested) { requested = it } }
            val status = bitrateStatusText(res, host.bitrateBusy, host.bitrateResult)
            KeyedRow(Symbols.CHECK, R.string.stream_menu_row_apply, MenuKey.APPLY, if (status == null) RowValue.Action(apply) else RowValue.Text(status, apply),
                enabled = !host.bitrateBusy, help = MenuKey.APPLY_BITRATE)
        }
        MenuPage.UPSCALER -> {
            val available = host.upscalerAvailable
            KeyedRow(Symbols.UPSCALER, R.string.stream_menu_row_enabled, MenuKey.ENABLED, RowValue.Toggle(host.upscalerRequested) {
                host.setUpscalingEnabled(it)
                changesTick()
            }, Modifier.focusRequester(first), enabled = available && !upscalerStatus.isChanging,
                help = if (supersamplingMode) MenuKey.SUPERSAMPLING_ON else MenuKey.UPSCALING)
            if (showsSupersamplingQuality(supersamplingMode, host.upscalerMode)) {
                val level = supersamplingLevel.coerceIn(1, 2)
                KeyedRow(Symbols.HIGH_QUALITY, R.string.stream_menu_row_supersampling_quality, MenuKey.SUPERSAMPLING_QUALITY,
                    RowValue.Choice(if (level == 1) "1.33× (1440p)" else "2× (2160p)", { direction -> // l10n-ignore
                        host.setSupersamplingLevel(if (direction < 0) 1 else 2)
                        onLevel(host.supersamplingLevel)
                    }), enabled = available)
            }
            val upscalerMode = host.upscalerMode
            KeyedRow(Symbols.UPSCALER_MODE, R.string.stream_menu_row_mode, MenuKey.MODE, RowValue.Choice(res.getString(UpscalerMode.fromIndex(upscalerMode).labelRes()), { direction ->
                host.setUpscalingMode(nextUpscalerMode(upscalerMode, direction))
                changesTick()
            }), enabled = available)
            KeyedRow(Symbols.SHARPNESS, R.string.stream_menu_row_sharpness, MenuKey.SHARPNESS, RowValue.Slider(host.upscalerSharpness, 0, 100, 5, " %") {
                host.setUpscalingSharpness(it)
                changesTick()
            }, enabled = available)
        }
        MenuPage.MOUSE -> {
            val modes = remember { host.mouseModes() }
            val selected = modes.indexOfFirst { it.value == host.mouseMode.toString() }.coerceAtLeast(0)
            KeyedRow(Symbols.TOUCH_APP, R.string.stream_menu_row_touchscreen_mode, MenuKey.TOUCHSCREEN_MODE, RowValue.Choice(modes[selected].label, { dir ->
                toggle { host.setMouseMode(modes[Math.floorMod(selected + dir, modes.size)].value.toInt()) }
            }), Modifier.focusRequester(first))
            KeyedRow(Symbols.CURSOR, R.string.stream_menu_row_local_cursor, MenuKey.LOCAL_MOUSE_CURSOR, RowValue.Toggle(host.localMouseCursorVisible) {
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
                    stringResource(R.string.stream_menu_no_commands),
                    style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = L.HELP_SIZE.tpx, textAlign = TextAlign.Center),
                    modifier = Modifier.focusRequester(first).focusable().padding(horizontal = 12.px, vertical = 16.px),
                )
            } else {
                PopoverPills(commands, first, command = true) { i -> close { host.runServerCommand(i) } }
            }
        }
        // The laboratory's presets, switched live. A selection applies at once, plays no sample and keeps
        // the menu open so presets can be compared back to back; B returns to More.
        MenuPage.RUMBLE -> {
            val presets = RumblePresets.values()
            val active = host.rumblePreset
            // The focus starts on the active preset (on the first one while the preset is Custom)
            val start = remember { host.rumblePreset ?: presets.first() }
            presets.forEach { preset ->
                MenuRow(
                    preset.icon(), preset.label(res), RowValue.Action { toggle { host.selectRumblePreset(preset) } },
                    if (preset == start) Modifier.focusRequester(first) else Modifier,
                    confirmation = if (host.rumbleHasProblem(preset)) stringResource(R.string.stream_menu_rumble_problem_word) else if (preset == active) stringResource(R.string.stream_menu_rumble_active) else null,
                    help = StreamMenuHelp.rumbleKey(preset),
                    key = StreamMenuHelp.rumbleKey(preset),
                )
            }
        }
    }
}
