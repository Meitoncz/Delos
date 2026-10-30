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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusable
import androidx.compose.runtime.key
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
import com.limelight.shell.ui.PillButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.limelight.Game
import com.limelight.utils.ExternalDisplayControlActivity
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.settings.StreamMenuSettings
import com.limelight.shell.settings.SettingsHelp
import com.limelight.shell.settings.StreamMenuHelp
import com.limelight.shell.ui.InformationPanel
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.Feedback
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.FocusGlow
import com.limelight.shell.ui.FocusRing
import com.limelight.shell.ui.Glyph
import com.limelight.shell.ui.GlyphButton
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.SettingRow
import com.limelight.shell.ui.Stage
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.px
import com.limelight.shell.ui.systemAccentHue
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

private val LocalStreamHelp = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * The Delos stream menu (the prototype's `#quickmenu`): a panel at the bottom of the stream with the
 * game's name and Resume, Keyboard, Overlay, More (the classic Artemis quick menu), Disconnect and
 * Quit game. It opens with the buttons set in Delos (L3 + R3 by default), a swipe up from the bottom
 * edge (unless turned off) and every way the classic menu opened. It is its own window, so the
 * controller does not reach the game while it is open. Created by Game (a small "Delos:" hook).
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

    private enum class Page { MAIN, MORE, KEYS, COMMANDS, BRIGHTNESS, BITRATE, UPSCALER, MOUSE }
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

    /** Disconnect or Quit game: back into the new UI through the portal when the stream came from there. */
    private fun leave(quit: Boolean) {
        val end = if (quit) Runnable { game.quitWithoutAsking() } else Runnable { game.disconnect() }
        if (!StreamExit.applies(game)) {
            end.run()
            return
        }
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

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun MenuPanel(title: String, d: ComponentDialog) {
        var page by remember { mutableStateOf(Page.MAIN) }
        var helpKey by remember(page) { mutableStateOf(when (page) {
            Page.MAIN -> "Resume"
            Page.MORE -> "More"
            Page.KEYS -> "Send keys"
            Page.COMMANDS -> "Commands"
            Page.BRIGHTNESS -> "Screen brightness"
            Page.BITRATE -> "Bitrate"
            Page.UPSCALER -> "Upscaler"
            Page.MOUSE -> "Touchscreen mode"
        }) }
        // B on a sub page goes back to the main page; on the main page it resumes (closes the menu)
        BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }
        fun close(then: () -> Unit = {}) {
            d.dismiss()
            whenGameFocused(then)
        }
        val focusManager = LocalFocusManager.current
        val focusTracker = LocalFocusTracker.current
        LaunchedEffect(d) {
            externalActions.collect { action ->
                when (action) {
                    ExternalMenuAction.Activate -> focusTracker.activate()
                    ExternalMenuAction.Back -> if (page == Page.MAIN) close() else page = Page.MAIN
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            // linear-gradient(transparent, rgba(0, 0, 0, .75) 40%)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color.Transparent, .4f to Color.Black.copy(alpha = .75f), 1f to Color.Black.copy(alpha = .75f)))
                    .padding(top = 90.px, bottom = 56.px),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(26.px),
            ) {
                val heading = when (page) {
                    Page.MAIN -> title
                    Page.MORE -> "More"
                    Page.KEYS -> "Send keys"
                    Page.COMMANDS -> "Server commands"
                    Page.BRIGHTNESS -> "Brightness"
                    Page.BITRATE -> "Stream bitrate"
                    Page.UPSCALER -> if (supersamplingMode) "Supersampling" else "Snapdragon Game Super Resolution"
                    Page.MOUSE -> "Mouse and touch"
                }
                BasicText(heading, style = TextStyle(color = Color.White, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 30.tpx, textAlign = TextAlign.Center))
                Row(Modifier.fillMaxWidth().padding(horizontal = 110.px),
                    horizontalArrangement = Arrangement.spacedBy(40.px), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        CompositionLocalProvider(LocalStreamHelp provides { helpKey = it }) {
                            key(page) {
                                val first = remember { FocusRequester() }
                                InitialFocus(first)
                                when (page) {
                                    Page.MAIN -> FlowRow(maxItemsInEachRow = 5,
                                        horizontalArrangement = Arrangement.spacedBy(38.px, Alignment.CenterHorizontally),
                                        verticalArrangement = Arrangement.spacedBy(26.px)) {
                                        MenuCircle(Symbols.RESUME, "Resume", Modifier.focusRequester(first)) { close() }
                                        MenuCircle(Symbols.KEYBOARD, "Keyboard") { close { game.toggleKeyboard() } }
                                        MenuCircle(Symbols.OVERLAY, "Overlay", on = prefs.enablePerfOverlay, state = if (!prefs.enablePerfOverlay) "Off" else if (prefs.enablePerfOverlayLite) "Compact" else "On") {
                                            toggle { game.toggleHUD() }
                                        }
                                        MenuCircle(Symbols.BRIGHTNESS_MEDIUM, "Brightness", state = "${brightnessPercent()} %") { page = Page.BRIGHTNESS }
                                        MenuCircle(Symbols.NETWORK_CHECK, "Bitrate") { page = Page.BITRATE }
                                        MenuCircle(Symbols.UPSCALER, if (supersamplingMode) "Supersampling" else "Upscaler",
                                            on = game.isUpscalingRequested, state = upscalerStatus) { page = Page.UPSCALER }
                                        MenuCircle(Symbols.MORE_HORIZ, "More") { page = Page.MORE }
                                        MenuCircle(Symbols.LOGOUT, "Disconnect") { close { leave(quit = false) } }
                                        MenuCircle(Symbols.POWER_SETTINGS_NEW, "Quit game") { close { leave(quit = true) } }
                                    }
                                    // Everything the classic Artemis quick menu offered that the main page does not
                                    Page.MORE -> Column(verticalArrangement = Arrangement.spacedBy(26.px), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(38.px)) {
                                            MenuCircle(Symbols.KEYBOARD_COMMAND_KEY, "Send keys", Modifier.focusRequester(first)) { page = Page.KEYS }
                                            MenuCircle(Symbols.UPLOAD, "Send clipboard") { close { game.sendClipboard(true) } }
                                            MenuCircle(Symbols.DOWNLOAD, "Get clipboard") { close { game.getClipboard(0) } }
                                            MenuCircle(Symbols.TERMINAL, "Commands") { page = Page.COMMANDS }
                                            MenuCircle(Symbols.TASK_MANAGER, "Task manager") {
                                                close { game.sendKeys(shortArrayOf(KeyboardTranslator.VK_LCONTROL.toShort(), KeyboardTranslator.VK_LSHIFT.toShort(), KeyboardTranslator.VK_ESCAPE.toShort())) }
                                            }
                                            MenuCircle(Symbols.ZOOM_IN, "Zoom", on = game.isZoomModeEnabled) { toggle { game.toggleZoomMode() } }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(38.px)) {
                                            if (game.allowChangeMouseMode) MenuCircle(Symbols.MOUSE, "Mouse mode") { page = Page.MOUSE }
                                            MenuCircle(Symbols.JOYSTICK, "On-screen controls", on = prefs.onscreenController) { toggle { game.toggleVirtualController() } }
                                            MenuCircle(Symbols.KEYBOARD_FULL, "Full keyboard") { toggle { game.toggleFullKeyboard() } }
                                            MenuCircle(Symbols.KEY_LAYOUT, "Key layout") { toggle { game.toggleKeyboardController() } }
                                            MenuCircle(Symbols.TOUCH_APP, "Touch sensitivity", on = prefs.enableTouchSensitivity) { toggle { game.switchTouchSensitivity() } }
                                            MenuCircle(Symbols.SCREEN_ROTATION, "Rotate") { close { game.rotateScreen() } }
                                        }
                                    }
                                    Page.BRIGHTNESS -> Box(Modifier.width(900.px)) {
                                        SettingRow(
                                            Symbols.BRIGHTNESS_MEDIUM, "Screen brightness",
                                            RowValue.Slider(brightnessPercent(), 5, 100, 5, " %") { toggle { applyBrightness(it, d) } },
                                            Modifier.focusRequester(first),
                                            onFocused = { helpKey = "Screen brightness" },
                                        )
                                    }
                                    Page.BITRATE -> {
                                        var requested by remember { mutableIntStateOf(prefs.bitrate.coerceIn(1000, 1000000)) }
                                        Column(Modifier.width(1000.px), horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(22.px)) {
                                            SettingRow(Symbols.NETWORK_CHECK, "Bitrate",
                                                RowValue.Slider(requested, 1000, 1000000, 1000, " Mbps", 1000) { requested = it },
                                                Modifier.focusRequester(first), enabled = !bitrateBusy,
                                                onFocused = { helpKey = "Bitrate" })
                                            PillButton(if (bitrateBusy) "Applying…" else "Apply", Symbols.CHECK, {
                                                if (!bitrateBusy && game.connected) {
                                                    bitrateBusy = true
                                                    bitrateMessage = null
                                                    game.lifecycleScope.launch {
                                                        try {
                                                            val applied = withContext(Dispatchers.IO) { game.conn.setRuntimeBitrate(requested) }
                                                            if (game.connected && !game.isFinishing) {
                                                                val limited = applied < requested
                                                                prefs.bitrate = applied
                                                                requested = applied
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
                                                } else if (!bitrateBusy) {
                                                    bitrateMessage = "The stream is not connected, so the bitrate cannot be changed."
                                                }
                                            }, Modifier.onFocusChanged { if (it.isFocused) helpKey = "Apply bitrate" })
                                            BasicText(bitrateMessage ?: "Vibepollo is required. The host may limit the requested bitrate.",
                                                style = TextStyle(color = Color.White.copy(alpha = .8f), fontFamily = Figtree,
                                                    fontSize = 22.tpx, textAlign = TextAlign.Center))
                                        }
                                    }
                                    Page.UPSCALER -> Column(Modifier.width(1100.px), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(22.px)) {
                                        val available = game.isPyroWave || com.limelight.binding.video.SgsrRenderer.isSupported(game)
                                        SettingRow(Symbols.UPSCALER, if (supersamplingMode) "Supersampling" else "Upscaling", RowValue.Toggle(game.isUpscalingRequested) {
                                            game.setUpscalingEnabled(it)
                                            changes++
                                        }, Modifier.focusRequester(first), enabled = available && upscalerStatus !in listOf("Starting", "Stopping"),
                                            onFocused = { helpKey = if (supersamplingMode) "Supersampling" else "Upscaling" })
                                        if (supersamplingMode) {
                                            val level = supersamplingLevel.coerceIn(1, 2)
                                            SettingRow(Symbols.HIGH_QUALITY, "Supersampling quality",
                                                RowValue.Choice(if (level == 1) "1× (1440p)" else "2× (2160p)", { direction ->
                                                    game.setSupersamplingLevel(if (direction < 0) 1 else 2)
                                                    supersamplingLevel = game.supersamplingLevel
                                                    changes++
                                                }), enabled = available,
                                                onFocused = { helpKey = "Supersampling quality" })
                                        }
                                        val upscalerMode = if (prefs.sgsrTemporalQuality) 3 else if (prefs.sgsrTemporal) 2 else if (prefs.sgsrHighQuality) 1 else 0
                                        SettingRow(Symbols.UPSCALER_MODE, "Mode", RowValue.Choice(listOf("SGSR 1 Standard", "SGSR 1 High Quality", "SGSR 2 Video", "SGSR 2 Video Quality")[upscalerMode], { direction ->
                                            game.setUpscalingMode((upscalerMode + direction + 4) % 4)
                                            changes++
                                        }), enabled = available,
                                            onFocused = { helpKey = "Mode" })
                                        SettingRow(Symbols.SHARPNESS, "Sharpness", RowValue.Slider(prefs.sgsrSharpness, 0, 100, 5, " %") {
                                            game.setUpscalingSharpness(it)
                                            changes++
                                        }, enabled = available,
                                            onFocused = { helpKey = "Sharpness" })
                                    }
                                    Page.MOUSE -> Column(Modifier.width(1000.px), verticalArrangement = Arrangement.spacedBy(22.px)) {
                                        val modes = remember { com.limelight.shell.settings.SettingsCatalog(game).all.first { it.key == "mouse_mode_list" }.choices }
                                            .filter { !game.isOnExternalDisplay || it.value in setOf("2", "3", "4") }
                                        val selected = modes.indexOfFirst { it.value == game.activeMouseMode.toString() }.coerceAtLeast(0)
                                        SettingRow(Symbols.TOUCH_APP, "Touchscreen mode", RowValue.Choice(modes[selected].label, { dir ->
                                            toggle { game.setStreamMouseMode(modes[Math.floorMod(selected + dir, modes.size)].value.toInt()) }
                                        }), Modifier.focusRequester(first), onFocused = { helpKey = "Touchscreen mode" })
                                        SettingRow(Symbols.CURSOR, "Local mouse cursor", RowValue.Toggle(game.isLocalMouseCursorVisible) {
                                            toggle { game.toggleMouseLocalCursor() }
                                        }, onFocused = { helpKey = "Local mouse cursor" })
                                    }
                                    Page.KEYS -> PillGrid(classic?.specialKeyOptions().orEmpty().map { it.label }, first) { i ->
                                        val option = classic?.specialKeyOptions()?.getOrNull(i)
                                        d.dismiss()
                                        if (option != null) classic?.run(option)
                                    }
                                    Page.COMMANDS -> {
                                        val commands = game.serverCmds.orEmpty()
                                        if (commands.isEmpty()) {
                                            BasicText(
                                                "No commands are set up on the PC. Vibepollo can add them in its web UI.",
                                                style = TextStyle(color = Color.White.copy(alpha = .8f), fontFamily = Figtree, fontSize = 24.tpx, textAlign = TextAlign.Center),
                                                modifier = Modifier.focusRequester(first).focusable(),
                                            )
                                        } else {
                                            PillGrid(commands, first, command = true) { i -> close { game.sendExecServerCmd(i) } }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val modeValue = if (prefs.sgsrTemporalQuality) "temporal-quality" else if (prefs.sgsrTemporal) "temporal" else if (prefs.sgsrHighQuality) "high" else "standard"
                    val helpText = when (helpKey) {
                        "Touchscreen mode" -> SettingsHelp.choice("mouse_mode_list", game.activeMouseMode.toString()) +
                            "\n\n" + if (prefs.rememberMouseMode) "Applies now and is saved (Remember mouse mode is on)."
                            else "Applies now and lasts for this stream (Remember mouse mode is off)."
                        "Mode" -> SettingsHelp.choice("list_sgsr_quality", modeValue) + "\n\n" + StreamMenuHelp.description(helpKey)
                        "Upscaler", "Upscaling", "Supersampling" -> "$upscalerStatus\n\n${StreamMenuHelp.description(helpKey)}"
                        else -> StreamMenuHelp.description(helpKey)
                    }
                    InformationPanel(helpKey.removePrefix("Keys: ").removePrefix("Command: "), helpText,
                        Modifier.width(460.px).height(520.px))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(28.px), verticalAlignment = Alignment.CenterVertically) {
                    MenuHint(Glyph.A, "Select")
                    MenuHint(Glyph.B, if (page == Page.MAIN) "Resume" else "Back")
                }
            }
        }
    }

    /** Text choices (key combinations, server commands) as pills that wrap to the next line. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun PillGrid(labels: List<String>, first: FocusRequester, command: Boolean = false, onSelect: (Int) -> Unit) {
        val help = LocalStreamHelp.current
        FlowRow(
            Modifier.widthIn(max = 1500.px),
            horizontalArrangement = Arrangement.spacedBy(16.px, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(16.px),
        ) {
            labels.forEachIndexed { i, label ->
                var focused by remember { mutableStateOf(false) }
                BasicText(
                    label,
                    style = TextStyle(color = Color.White, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx),
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .focusEffects({ onSelect(i) }, corner = 999f, focusScale = 1.06f, ring = FocusRing(4f, 4f), glow = FocusGlow(14f, 40f, -12f), onFocusChange = {
                            focused = it
                            if (it) help((if (command) "Command: " else "Keys: ") + label)
                        })
                        .clip(CircleShape)
                        .background(if (focused) Color(0xFF2E3A52) else Color(0xD91E283C))
                        .border(1.px, Color.White.copy(alpha = .12f), CircleShape)
                        .padding(horizontal = 28.px, vertical = 16.px),
                )
            }
        }
    }

    /** `#quickmenu .circle`: 112 px on a dark glass, white icon and label. */
    @Composable
    /**
     * [on]: a toggle's state (filled with the accent and "On" under the label), null for actions;
     * [state] replaces "On" / "Off" for toggles with more steps.
     */
    private fun MenuCircle(icon: String, label: String, modifier: Modifier = Modifier, on: Boolean? = null, state: String? = null, onClick: () -> Unit) {
        val colors = LocalDelosColors.current
        val help = LocalStreamHelp.current
        var focused by remember { mutableStateOf(false) }
        Column(Modifier.width(164.px), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.px)) {
            Box(
                modifier
                    .focusEffects(onClick, corner = 999f, focusScale = 1.16f, ring = FocusRing(5f, 5f), glow = FocusGlow(20f, 50f, -14f), onFocusChange = {
                        focused = it
                        if (it) help(label)
                    })
                    .size(112.px)
                    .clip(CircleShape)
                    .background(if (on == true) colors.accent else Color(0xD91E283C))
                    .border(1.px, Color.White.copy(alpha = .12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(icon, 48.tpx, if (on == true) colors.onAccent else Color.White, filled = focused || on == true)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.px)) {
                BasicText(label, style = TextStyle(color = Color.White, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx, textAlign = TextAlign.Center))
                if (on != null) {
                    BasicText(
                        state ?: if (on) "On" else "Off",
                        style = TextStyle(color = if (on) colors.accent else Color.White.copy(alpha = .55f), fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 17.tpx),
                    )
                }
            }
        }
    }

    @Composable
    private fun MenuHint(glyph: Glyph, label: String) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
            GlyphButton(glyph)
            BasicText(label, style = TextStyle(color = Color.White.copy(alpha = .8f), fontFamily = Figtree, fontSize = 21.tpx))
        }
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
