package com.limelight.shell

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.GameMenu
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.focusable
import androidx.compose.runtime.key
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import com.limelight.nvstream.http.HostHttpResponseException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.limelight.Game
import com.limelight.utils.ExternalDisplayControlActivity
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.settings.GameSettingsStore
import com.limelight.shell.settings.upscalerSaveValues
import com.limelight.shell.settings.StreamMenuSettings
import com.limelight.shell.settings.SettingsHelp
import com.limelight.shell.settings.StreamMenuHelp
import com.limelight.shell.ui.EaseOut
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.Feedback
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.SettingRow
import com.limelight.shell.ui.Stage
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.px
import com.limelight.shell.ui.systemAccentHue
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The Delos stream menu (the prototype's `#quickmenu`): a compact bar at the bottom of the stream with the
 * game's name and one row of circles (Resume, Keyboard, Overlay, Brightness, Bitrate, Upscaler, More,
 * Disconnect, Quit game); the pages behind the circles open in a popover above the bar. It opens with the
 * buttons set in Delos (L3 + R3 by default), a swipe up from the bottom edge (unless turned off) and every
 * way the classic menu opened. It is its own window, so the controller does not reach the game while it is
 * open. Created by Game (a small "Delos:" hook).
 */
class StreamMenu(private val game: Game) {
    private val swipe = StreamMenuSettings.swipe(game)

    /**
     * The shortcut is complete; the menu opens (or, when open, closes) once its buttons are let go (the
     * game sees them released).
     */
    private val shortcut = ButtonShortcut(StreamMenuSettings.buttons(game)) {
        if (!isOpen) show()
        else if (menuReady) hide()
    }
    private var edgeGesture = false
    private var edgeStartY = 0f
    private var dialog: ComponentDialog? = null
    private var menuReady = false
    private val externalActions = MutableSharedFlow<ExternalMenuAction>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val analogNavigation = DirectionalRepeater()

    val isOpen: Boolean get() = dialog?.isShowing == true

    /** Returns whether the stream (or the open menu) must not see [event] (the shortcut's final button). */
    fun onKey(event: KeyEvent): Boolean = shortcut.onKey(event.keyCode, event.action, event.repeatCount)

    /**
     * Accepts controller and keyboard input from the activity on the built-in display. Android
     * deliberately sends untargeted controller input only to the globally focused display, so an
     * external-display menu cannot receive it through its own window. We forward semantic UI actions
     * instead of injecting synthetic key events into the TV window. While the menu is open it owns
     * every key event, as its focused window does on-device: nothing reaches the stream.
     */
    fun onExternalKey(event: KeyEvent): Boolean {
        if (onKey(event)) return true
        if (!isOpen) return false

        externalMenuKeyAction(event.keyCode, event.action, event.repeatCount, event.isCanceled)
            ?.let { externalActions.tryEmit(it) }
        return true
    }

    /** Converts analog movement into focus actions at a controlled repeat rate, without fake keys. */
    fun onExternalMotion(event: MotionEvent): Boolean {
        if (!isOpen || event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return true

        val horizontal = strongestAxis(event, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_X)
        val vertical = strongestAxis(event, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_Y)
        val direction = when {
            kotlin.math.abs(horizontal) > kotlin.math.abs(vertical) && horizontal <= -ANALOG_THRESHOLD -> MenuDirection.LEFT
            kotlin.math.abs(horizontal) > kotlin.math.abs(vertical) && horizontal >= ANALOG_THRESHOLD -> MenuDirection.RIGHT
            vertical <= -ANALOG_THRESHOLD -> MenuDirection.UP
            vertical >= ANALOG_THRESHOLD -> MenuDirection.DOWN
            else -> null
        }
        if (analogNavigation.shouldMove(direction, event.eventTime)) {
            externalActions.tryEmit(ExternalMenuAction.Move(direction!!, analogNavigation.repeatCount))
        }
        return true
    }

    private fun strongestAxis(event: MotionEvent, primary: Int, fallback: Int): Float {
        val primaryValue = event.getAxisValue(primary)
        val fallbackValue = event.getAxisValue(fallback)
        return if (kotlin.math.abs(primaryValue) >= kotlin.math.abs(fallbackValue)) primaryValue else fallbackValue
    }

    /**
     * A swipe up from the bottom edge opens the menu. Touches that start at that edge are kept from the
     * game while it is decided.
     */
    fun onTouch(event: MotionEvent): Boolean {
        if (!swipe) return false
        val height = game.window.decorView.height.takeIf { it > 0 } ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeGesture = event.y >= height * (1f - EDGE)
                edgeStartY = event.y
                return edgeGesture
            }
            MotionEvent.ACTION_MOVE -> {
                if (!edgeGesture) return false
                if (edgeStartY - event.y >= height * DISTANCE && !isOpen) show()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = edgeGesture
                edgeGesture = false
                return was
            }
        }
        return edgeGesture
    }

    fun show() {
        if (isOpen || game.isFinishing) return
        val feedback = Feedback(game)
        val d = ComponentDialog(game, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN)
            attributes = attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }
        val appearance = AppearanceStore(game) { ProfileStore.currentSettings(game) }.appearance.value
        val colors = delosColors(appearance, systemAccentHue(game))
        val title = game.intent.getStringExtra(Game.EXTRA_APP_NAME) ?: "Stream"
        menuReady = false
        val composeView = ComposeView(d.context).apply {
            setContent {
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    menuReady = true
                }
                CompositionLocalProvider(LocalDelosColors provides colors, LocalFeedback provides feedback, LocalFocusTracker provides remember { FocusTracker() }, LocalReducedMotion provides appearance.reducedMotion) {
                    Stage(textScale = appearance.uiScale / 100f) { MenuPanel(title, d) }
                }
            }
        }
        d.setContentView(composeView)
        // While the menu is open the buttons reach its window, not the stream: the shortcut closes it again
        d.setOnKeyListener { _, _, event -> onKey(event) }
        d.setOnDismissListener {
            feedback.release()
            analogNavigation.reset()
            menuReady = false
            dialog = null
        }
        dialog = d
        d.show()
        d.window?.let { w ->
            WindowCompat.getInsetsController(w, w.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        feedback.view = d.window?.decorView
        feedback.play(com.limelight.shell.ui.Cue.OPEN)
    }

    fun hide() {
        dialog?.dismiss()
    }

    /** The external display can disappear without giving its dialog a normal dismiss sequence. */
    fun closeForDisplayRemoval() {
        hide()
    }

    private var bitrateBusy by mutableStateOf(false)
    private var bitrateMessage by mutableStateOf<String?>(null)

    // Delos: the stream's brightness in percent for this stream, -1 = the system's (from Moonlight X's
    // brightness slider)
    private var brightness = -1

    private fun brightnessPercent(): Int {
        if (brightness >= 0) return brightness
        val system = runCatching { Settings.System.getInt(game.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        return (system * 100 / 255).coerceIn(5, 100) / 5 * 5
    }

    /**
     * Sets this device's screen brightness while the stream runs: the stream window's (and the menu's, so
     * the change shows at once), or the controller Activity's when the stream is on an external display,
     * whose window brightness the TV ignores.
     */
    private fun applyBrightness(percent: Int, menu: ComponentDialog?) {
        brightness = percent
        if (game.isOnExternalDisplay()) {
            ExternalDisplayControlActivity.setStreamBrightness(percent / 100f)
            return
        }
        for (window in listOfNotNull(game.window, menu?.window)) {
            window.attributes = window.attributes.apply { screenBrightness = percent / 100f }
        }
    }

    /** Disconnect or Quit game: back into the new UI through the portal. */
    private fun leave(quit: Boolean) {
        val end = if (quit) Runnable { game.quitWithoutAsking() } else Runnable { game.disconnect() }
        game.blockInput()
        StreamExit.play(game, quit, end)
    }

    /** Runs [action] once the stream window has the focus again (after the menu closed), like GameMenu. */
    private fun whenGameFocused(action: () -> Unit) {
        if (game.isFinishing) return
        if (!game.hasWindowFocus()) {
            game.window.decorView.postDelayed({ whenGameFocused(action) }, 50)
            return
        }
        action()
    }

    /** Asks the host for [requested] kbps (the Bitrate popover's Apply) and reports what it accepted. */
    private fun applyBitrate(requested: Int, accepted: (Int) -> Unit) {
        if (bitrateBusy) return
        if (!game.connected) {
            bitrateMessage = "The stream is not connected, so the bitrate cannot be changed."
            return
        }
        bitrateBusy = true
        bitrateMessage = null
        game.lifecycleScope.launch {
            try {
                val applied = withContext(Dispatchers.IO) { game.conn.setRuntimeBitrate(requested) }
                if (game.connected && !game.isFinishing) {
                    val limited = applied < requested
                    game.prefConfig.bitrate = applied
                    accepted(applied)
                    bitrateMessage = "Host accepted ${applied / 1000} Mbps" +
                        if (limited) " (limited by the host)" else ""
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                bitrateMessage = when (e) {
                    is FileNotFoundException -> "The host does not support live bitrate changes, or the stream has ended."
                    is HostHttpResponseException -> if (e.errorCode == 403) "The host did not allow this change." else "The host could not apply the change: ${e.message}"
                    else -> "The bitrate was not confirmed. Check the connection and try again."
                }
            } finally { bitrateBusy = false }
        }
    }

    @Composable
    private fun MenuPanel(title: String, d: ComponentDialog) {
        var page by remember { mutableStateOf(MenuPage.MAIN) }
        // The popover's page while it animates out after [page] went back to the bar
        var shownPage by remember { mutableStateOf(MenuPage.MORE) }
        if (page != MenuPage.MAIN) shownPage = page
        var helpKey by remember(page) { mutableStateOf(pageHelpKey(page)) }
        // The row of More that gets the focus when More shows again (the row that opened a page above it)
        var entryRow by remember { mutableStateOf<String?>(null) }
        val circleFocus = remember { BarItem.entries.associateWith { FocusRequester() } }
        var pendingCircle by remember { mutableStateOf<BarItem?>(null) }
        LaunchedEffect(pendingCircle) {
            val item = pendingCircle ?: return@LaunchedEffect
            runCatching { circleFocus.getValue(item).requestFocus() }
            pendingCircle = null
        }
        fun close(then: () -> Unit = {}) {
            d.dismiss()
            whenGameFocused(then)
        }
        fun go(exit: PageExit) {
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
                entryRow = null
                page = next
            }
        }
        BackHandler(enabled = page != MenuPage.MAIN) { back() }
        val focusManager = LocalFocusManager.current
        val focusTracker = LocalFocusTracker.current
        LaunchedEffect(d) {
            externalActions.collect { action ->
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
        val prefs = game.prefConfig
        val upscalerStatus by produceState(game.upscalingStatus) {
            while (true) { value = game.upscalingStatus; delay(250) }
        }
        val supersamplingMode by produceState(game.isSupersamplingMode) {
            while (true) { value = game.isSupersamplingMode; delay(250) }
        }
        // The level the renderer reports: it follows a press at once, and the poll shows a later correction (the
        // renderer found that 2× does not fit). Reading the level the last frame applied instead made the row
        // show the old value after a press, until the next press redrew it.
        var supersamplingLevel by remember { mutableIntStateOf(game.supersamplingLevel) }
        LaunchedEffect(Unit) { while (true) { supersamplingLevel = game.supersamplingLevel; delay(250) } }
        @Suppress("UNUSED_EXPRESSION") changes // subscribes to it
        val classic = game.gameMenuCallbacks as? GameMenu
        val colors = LocalDelosColors.current
        val stage = LocalStage.current
        val reducedMotion = LocalReducedMotion.current
        val slide = with(LocalDensity.current) { 60.px.roundToPx() }
        // Composed already visible-bound, so the bar's first circle exists when the focus is requested
        val barEnter = remember { MutableTransitionState(false).apply { targetState = true } }
        val resume = circleFocus.getValue(BarItem.RESUME)
        InitialFocus(resume)

        val circles = listOf(
            BarCircleSpec(BarItem.RESUME, Symbols.RESUME, "Resume") { close() },
            BarCircleSpec(BarItem.KEYBOARD, Symbols.KEYBOARD, "Keyboard") { close { game.toggleKeyboard() } },
            BarCircleSpec(BarItem.OVERLAY, Symbols.OVERLAY, "Overlay", on = prefs.enablePerfOverlay,
                state = if (!prefs.enablePerfOverlay) "Off" else if (prefs.enablePerfOverlayLite) "Compact" else "On") {
                toggle { game.toggleHUD() }
            },
            BarCircleSpec(BarItem.BRIGHTNESS, Symbols.BRIGHTNESS_MEDIUM, "Brightness") { openPage(MenuPage.BRIGHTNESS) },
            BarCircleSpec(BarItem.BITRATE, Symbols.NETWORK_CHECK, "Bitrate") { openPage(MenuPage.BITRATE) },
            BarCircleSpec(BarItem.UPSCALER, Symbols.UPSCALER, if (supersamplingMode) "Supersampling" else "Upscaler",
                on = game.isUpscalingRequested, state = upscalerCircleState(upscalerStatus)) { openPage(MenuPage.UPSCALER) },
            BarCircleSpec(BarItem.MORE, Symbols.MORE_HORIZ, "More") { openPage(MenuPage.MORE) },
            BarCircleSpec(BarItem.DISCONNECT, Symbols.LOGOUT, "Disconnect") { close { leave(quit = false) } },
            BarCircleSpec(BarItem.QUIT, Symbols.POWER_SETTINGS_NEW, "Quit game") { close { leave(quit = true) } },
        )
        val modeValue = if (prefs.sgsrTemporalQuality) "temporal-quality" else if (prefs.sgsrTemporal) "temporal" else if (prefs.sgsrHighQuality) "high" else "standard"
        val helpText = when (helpKey) {
            "Touchscreen mode" -> SettingsHelp.choice("mouse_mode_list", game.activeMouseMode.toString()) +
                "\n\n" + if (prefs.rememberMouseMode) "Applies now and is saved."
                else "Applies now; this stream only."
            "Mode" -> SettingsHelp.choice("list_sgsr_quality", modeValue) + "\n\n" + StreamMenuHelp.description(helpKey)
            "Upscaler", "Upscaling", "Supersampling" -> "$upscalerStatus\n\n${StreamMenuHelp.description(helpKey)}"
            else -> StreamMenuHelp.description(helpKey)
        }

        CompositionLocalProvider(LocalStreamHelp provides { helpKey = it }) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val bar = barFrame(maxWidth.value / stage.dpPerPx, maxHeight.value / stage.dpPerPx)
                val limits = popoverFrame(bar, Float.MAX_VALUE)
                // A tap anywhere else closes the popover; it never resumes the stream (only Resume and B do)
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { pageAfterDismiss(page)?.let { go(it) } } })
                AnimatedVisibility(barEnter, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(350, easing = EaseOut)), exit = fadeOut()) {
                    StreamMenuGradient()
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = StreamMenuLayout.SAFE_MARGIN.px).width(bar.width.px),
                    horizontalAlignment = Alignment.End,
                ) {
                    AnimatedVisibility(
                        page != MenuPage.MAIN,
                        enter = fadeIn(tween(300, easing = EaseOut)) +
                            (if (reducedMotion) EnterTransition.None else scaleIn(tween(400, easing = EaseOut), initialScale = .96f, transformOrigin = TransformOrigin(1f, 1f))),
                        exit = fadeOut(tween(200, easing = EaseOut)) +
                            (if (reducedMotion) ExitTransition.None else scaleOut(tween(250, easing = EaseOut), targetScale = .96f, transformOrigin = TransformOrigin(1f, 1f))),
                    ) {
                        StreamMenuPopover(
                            title = pageTitle(shownPage, supersamplingMode),
                            width = limits.width,
                            maxHeight = limits.height,
                            active = page != MenuPage.MAIN,
                            onBack = { back() },
                            modifier = Modifier.padding(bottom = StreamMenuLayout.POPOVER_GAP.px),
                        ) {
                            // Redraws the rows after a toggle or a slider step (their values are not state)
                            @Suppress("UNUSED_EXPRESSION") changes
                            key(shownPage) {
                                val first = remember { FocusRequester() }
                                InitialFocus(first)
                                when (shownPage) {
                                    MenuPage.MAIN -> Unit
                                    // Everything the classic Artemis quick menu offered that the bar does not
                                    MenuPage.MORE -> {
                                        fun entry(label: String) = if (label == (entryRow ?: MoreRow.KEYS)) Modifier.focusRequester(first) else Modifier
                                        MenuRow(Symbols.KEYBOARD_COMMAND_KEY, MoreRow.KEYS, RowValue.Action { openPage(MenuPage.KEYS) }, entry(MoreRow.KEYS))
                                        MenuRow(Symbols.UPLOAD, "Send clipboard", RowValue.Action { close { game.sendClipboard(true) } })
                                        MenuRow(Symbols.DOWNLOAD, "Get clipboard", RowValue.Action { close { game.getClipboard(0) } })
                                        MenuRow(Symbols.TERMINAL, MoreRow.COMMANDS, RowValue.Action { openPage(MenuPage.COMMANDS) }, entry(MoreRow.COMMANDS))
                                        MenuRow(Symbols.TASK_MANAGER, "Task manager", RowValue.Action {
                                            close { game.sendKeys(shortArrayOf(KeyboardTranslator.VK_LCONTROL.toShort(), KeyboardTranslator.VK_LSHIFT.toShort(), KeyboardTranslator.VK_ESCAPE.toShort())) }
                                        })
                                        MenuRow(Symbols.ZOOM_IN, "Zoom", RowValue.Toggle(game.isZoomModeEnabled) { toggle { game.toggleZoomMode() } })
                                        if (game.allowChangeMouseMode) MenuRow(Symbols.MOUSE, MoreRow.MOUSE, RowValue.Action { openPage(MenuPage.MOUSE) }, entry(MoreRow.MOUSE))
                                        MenuRow(Symbols.JOYSTICK, "On-screen controls", RowValue.Toggle(prefs.onscreenController) { toggle { game.toggleVirtualController() } })
                                        MenuRow(Symbols.KEYBOARD_FULL, "Full keyboard", RowValue.Action { toggle { game.toggleFullKeyboard() } })
                                        MenuRow(Symbols.KEY_LAYOUT, "Key layout", RowValue.Action { toggle { game.toggleKeyboardController() } })
                                        MenuRow(Symbols.TOUCH_APP, "Touch sensitivity", RowValue.Toggle(prefs.enableTouchSensitivity) { toggle { game.switchTouchSensitivity() } })
                                        MenuRow(Symbols.SCREEN_ROTATION, "Rotate", RowValue.Action { close { game.rotateScreen() } })
                                    }
                                    MenuPage.BRIGHTNESS -> MenuRow(
                                        Symbols.BRIGHTNESS_MEDIUM, "Screen brightness",
                                        RowValue.Slider(brightnessPercent(), 5, 100, 5, " %") { toggle { applyBrightness(it, d) } },
                                        Modifier.focusRequester(first),
                                    )
                                    MenuPage.BITRATE -> {
                                        var requested by remember { mutableIntStateOf(prefs.bitrate.coerceIn(1000, 1000000)) }
                                        MenuRow(Symbols.NETWORK_CHECK, "Bitrate",
                                            RowValue.Slider(requested, 1000, 1000000, 1000, " Mbps", 1000) { requested = it },
                                            Modifier.focusRequester(first), enabled = !bitrateBusy)
                                        MenuRow(Symbols.CHECK, if (bitrateBusy) "Applying…" else "Apply",
                                            RowValue.Action { applyBitrate(requested) { requested = it } },
                                            description = bitrateMessage ?: "Vibepollo is required. The host may limit the requested bitrate.",
                                            enabled = !bitrateBusy, help = "Apply bitrate")
                                    }
                                    MenuPage.UPSCALER -> {
                                        val available = game.isPyroWave || com.limelight.binding.video.SgsrRenderer.isSupported(game)
                                        MenuRow(Symbols.UPSCALER, if (supersamplingMode) "Supersampling" else "Upscaling", RowValue.Toggle(game.isUpscalingRequested) {
                                            game.setUpscalingEnabled(it)
                                            changes++
                                        }, Modifier.focusRequester(first), enabled = available && upscalerStatus !in listOf("Starting", "Stopping"))
                                        if (supersamplingMode) {
                                            val level = supersamplingLevel.coerceIn(1, 2)
                                            MenuRow(Symbols.HIGH_QUALITY, "Supersampling quality",
                                                RowValue.Choice(if (level == 1) "1× (1440p)" else "2× (2160p)", { direction ->
                                                    game.setSupersamplingLevel(if (direction < 0) 1 else 2)
                                                    supersamplingLevel = game.supersamplingLevel
                                                    changes++
                                                }), enabled = available)
                                        }
                                        val upscalerMode = if (prefs.sgsrTemporalQuality) 3 else if (prefs.sgsrTemporal) 2 else if (prefs.sgsrHighQuality) 1 else 0
                                        MenuRow(Symbols.UPSCALER_MODE, "Mode", RowValue.Choice(listOf("SGSR 1 Standard", "SGSR 1 High Quality", "SGSR 2 Video", "SGSR 2 Video Quality")[upscalerMode], { direction ->
                                            game.setUpscalingMode((upscalerMode + direction + 4) % 4)
                                            changes++
                                        }), enabled = available)
                                        MenuRow(Symbols.SHARPNESS, "Sharpness", RowValue.Slider(prefs.sgsrSharpness, 0, 100, 5, " %") {
                                            game.setUpscalingSharpness(it)
                                            changes++
                                        }, enabled = available)
                                        // The one way a menu change outlives the session: the live values become this game's own settings.
                                        // A stream started without its launch identity has no game to save for, so the row is not offered.
                                        val saveTarget = remember { GameSettingsStore.forLaunch(game) }
                                        if (saveTarget != null) {
                                            var savedTick by remember { mutableIntStateOf(0) }
                                            LaunchedEffect(savedTick) { if (savedTick > 0) { delay(2500); savedTick = 0 } }
                                            MenuRow(if (savedTick > 0) Symbols.CHECK_CIRCLE else Symbols.REMEMBER, "Save for this game", RowValue.Action {
                                                saveTarget.save(upscalerSaveValues(game.isUpscalingRequested, upscalerMode, prefs.sgsrSharpness, game.supersamplingLevel))
                                                savedTick++
                                            }, description = if (savedTick > 0) "Saved for $title" else "Applies to future launches of this game",
                                                enabled = available)
                                        }
                                    }
                                    MenuPage.MOUSE -> {
                                        val modes = remember { com.limelight.shell.settings.SettingsCatalog(game).all.first { it.key == "mouse_mode_list" }.choices }
                                            .filter { !game.isOnExternalDisplay || it.value in setOf("2", "3", "4") }
                                        val selected = modes.indexOfFirst { it.value == game.activeMouseMode.toString() }.coerceAtLeast(0)
                                        MenuRow(Symbols.TOUCH_APP, "Touchscreen mode", RowValue.Choice(modes[selected].label, { dir ->
                                            toggle { game.setStreamMouseMode(modes[Math.floorMod(selected + dir, modes.size)].value.toInt()) }
                                        }), Modifier.focusRequester(first))
                                        MenuRow(Symbols.CURSOR, "Local mouse cursor", RowValue.Toggle(game.isLocalMouseCursorVisible) {
                                            toggle { game.toggleMouseLocalCursor() }
                                        })
                                    }
                                    MenuPage.KEYS -> PopoverPills(classic?.specialKeyOptions().orEmpty().map { it.label }, first) { i ->
                                        val option = classic?.specialKeyOptions()?.getOrNull(i)
                                        d.dismiss()
                                        if (option != null) classic?.run(option)
                                    }
                                    MenuPage.COMMANDS -> {
                                        val commands = game.serverCmds.orEmpty()
                                        if (commands.isEmpty()) {
                                            BasicText(
                                                "No commands are set up on the PC. Vibepollo can add them in its web UI.",
                                                style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 21.tpx, textAlign = TextAlign.Center),
                                                modifier = Modifier.focusRequester(first).focusable().padding(horizontal = 12.px, vertical = 16.px),
                                            )
                                        } else {
                                            PopoverPills(commands, first, command = true) { i -> close { game.sendExecServerCmd(i) } }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(
                        barEnter,
                        enter = fadeIn(tween(350, easing = EaseOut)) +
                            (if (reducedMotion) EnterTransition.None else slideInVertically(tween(400, easing = EaseOut)) { slide }),
                        exit = fadeOut(),
                    ) {
                        StreamMenuBar(
                            title = title,
                            backLabel = if (page == MenuPage.MAIN) "Resume" else "Back",
                            circles = circles,
                            requesters = circleFocus,
                            selected = page.barItem(),
                            helpTitle = helpKey.removePrefix("Keys: ").removePrefix("Command: "),
                            helpText = helpText,
                            width = bar.width,
                        )
                    }
                }
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
        SettingRow(icon, label, value, modifier, description = description, enabled = enabled, dense = true, onFocused = { show(help) })
    }

    private companion object {
        /** The bottom strip a swipe has to start in, and how far up it has to go (share of the height). */
        const val EDGE = .06f
        const val DISTANCE = .12f
        const val ANALOG_THRESHOLD = .55f
    }
}

internal enum class MenuDirection { UP, DOWN, LEFT, RIGHT }

internal sealed interface ExternalMenuAction {
    data class Move(val direction: MenuDirection, val repeatCount: Int) : ExternalMenuAction
    data object Activate : ExternalMenuAction
    data object Back : ExternalMenuAction
}

/**
 * The menu action a key event triggers, or null when it triggers none: either the key means nothing
 * to the menu, or this is the wrong phase (moves act on press, Activate and Back on release, and a
 * cancelled release does nothing).
 */
internal fun externalMenuKeyAction(keyCode: Int, keyAction: Int, repeatCount: Int, canceled: Boolean): ExternalMenuAction? {
    val action = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> ExternalMenuAction.Move(MenuDirection.UP, repeatCount)
        KeyEvent.KEYCODE_DPAD_DOWN -> ExternalMenuAction.Move(MenuDirection.DOWN, repeatCount)
        KeyEvent.KEYCODE_DPAD_LEFT -> ExternalMenuAction.Move(MenuDirection.LEFT, repeatCount)
        KeyEvent.KEYCODE_DPAD_RIGHT -> ExternalMenuAction.Move(MenuDirection.RIGHT, repeatCount)
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ExternalMenuAction.Activate
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> ExternalMenuAction.Back
        else -> return null
    }
    val trigger = when (action) {
        is ExternalMenuAction.Move -> keyAction == KeyEvent.ACTION_DOWN
        ExternalMenuAction.Activate, ExternalMenuAction.Back -> keyAction == KeyEvent.ACTION_UP && !canceled
    }
    return if (trigger) action else null
}

/** Stable edge/repeat policy shared by every analog direction. */
internal class DirectionalRepeater(
    private val initialDelayMs: Long = 350,
    private val repeatIntervalMs: Long = 100,
) {
    private var direction: MenuDirection? = null
    private var nextRepeatAt = 0L
    var repeatCount = 0
        private set

    fun shouldMove(next: MenuDirection?, eventTime: Long): Boolean {
        if (next == null) {
            reset()
            return false
        }
        if (next != direction) {
            direction = next
            repeatCount = 0
            nextRepeatAt = eventTime + initialDelayMs
            return true
        }
        if (eventTime < nextRepeatAt) return false
        repeatCount++
        nextRepeatAt = eventTime + repeatIntervalMs
        return true
    }

    fun reset() {
        direction = null
        repeatCount = 0
        nextRepeatAt = 0L
    }
}
