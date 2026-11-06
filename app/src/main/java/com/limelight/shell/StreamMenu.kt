package com.limelight.shell

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentDialog
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.limelight.Game
import com.limelight.binding.input.KeyboardTranslator
import com.limelight.nvstream.http.HostHttpResponseException
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.settings.GameSettingsStore
import com.limelight.shell.settings.StreamMenuSettings
import com.limelight.shell.settings.upscalerSaveValues
import com.limelight.shell.ui.Feedback
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.Stage
import com.limelight.shell.ui.delosColors
import com.limelight.shell.ui.systemAccentHue
import com.limelight.utils.ExternalDisplayControlActivity
import com.limelight.utils.RumblePreferences
import com.limelight.utils.RumbleCapabilities
import com.limelight.utils.RumblePresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * The Delos stream menu (the prototype's `#quickmenu`, "Console tiles"): over the lower part of the stream the game's
 * name and one row of nine glass tiles (Resume, Keyboard, Overlay, Brightness, Bitrate, Upscaler, More, Disconnect,
 * Quit game) with the complete help of the focused tile under them; the pages behind the tiles open in a popover above the tile that opened them. It opens with
 * the buttons set in Delos (L3 + R3 by default), a swipe up from the bottom edge (unless turned off) and every
 * way the classic menu opened. It is its own window, so the controller does not reach the game while it is
 * open. This class is that window and the bridge to the stream ([GameHost]); what is drawn is [StreamMenuRoot].
 * Created by Game (a small "Delos:" hook).
 */
class StreamMenu(private val game: Game) {
    private val swipe = StreamMenuSettings.swipe(game)

    /**
     * The shortcut is complete; the menu opens (or, when open, closes) once its buttons are let go (the
     * game sees them released).
     */
    private val shortcut = ButtonShortcut(StreamMenuSettings.buttons(game)) {
        if (!isOpen) show()
        else if (menuReady) requestClose()
    }
    private var edgeGesture = false
    private var edgeStartY = 0f
    private var dialog: ComponentDialog? = null
    private var menuReady = false
    private val externalActions = MutableSharedFlow<ExternalMenuAction>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val closeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
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
        val appearance = AppearanceStore(game) { ProfileStore.currentSettings(game) }.appearance.value
        val feedback = Feedback(game).also { it.hapticsEnabled = appearance.haptics }
        val d = ComponentDialog(game, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN)
            attributes = attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }
        val colors = delosColors(appearance, systemAccentHue(game))
        val host = GameHost(d)
        menuReady = false
        val composeView = ComposeView(d.context).apply {
            setContent {
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    menuReady = true
                }
                CompositionLocalProvider(LocalDelosColors provides colors, LocalFeedback provides feedback, LocalFocusTracker provides remember { FocusTracker() }, LocalReducedMotion provides appearance.reducedMotion) {
                    Stage(textScale = appearance.uiScale / 100f) { StreamMenuRoot(host, externalActions, closeRequests) }
                }
            }
        }
        // The stream behind the menu is blurred, so the statistics overlay moves into the menu's window while it is open: it
        // stays in front of the blur, sharp (and goes back to the stream's layout when the menu closes)
        val overlay = game.performanceOverlayContainer
        val overlayHome = overlay.parent as? ViewGroup
        val overlayIndex = overlayHome?.indexOfChild(overlay) ?: -1
        val overlayParams = overlay.layoutParams
        val root = FrameLayout(d.context)
        root.addView(composeView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (overlayHome != null) {
            overlayHome.removeView(overlay)
            root.addView(overlay, overlayParams)
        }
        d.setContentView(root)
        // While the menu is open the buttons reach its window, not the stream: the shortcut closes it again
        d.setOnKeyListener { _, _, event -> onKey(event) }
        d.setOnDismissListener {
            if (overlayHome != null) {
                (overlay.parent as? ViewGroup)?.removeView(overlay)
                overlayHome.addView(overlay, overlayIndex.coerceIn(0, overlayHome.childCount), overlayParams)
            }
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

    /** Closes the open menu the way its own Resume does, with its exit (the shortcut's second press). */
    private fun requestClose() {
        if (closeRequests.subscriptionCount.value > 0) closeRequests.tryEmit(Unit) else hide()
    }

    /** The external display can disappear without giving its dialog a normal dismiss sequence. */
    fun closeForDisplayRemoval() {
        hide()
    }

    private var bitrateBusy by mutableStateOf(false)
    private var bitrateResult by mutableStateOf<BitrateResult?>(null)

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

    /** Runs [action] once the stream window has the focus again (after the menu closed). */
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
            bitrateResult = BitrateResult.Failed(BitrateFailure.NOT_CONNECTED)
            return
        }
        bitrateBusy = true
        bitrateResult = null
        game.lifecycleScope.launch {
            try {
                val applied = withContext(Dispatchers.IO) { game.conn.setRuntimeBitrate(requested) }
                if (game.connected && !game.isFinishing) {
                    game.prefConfig.bitrate = applied
                    accepted(applied)
                    bitrateResult = if (applied < requested) BitrateResult.Limited(applied) else BitrateResult.Accepted(applied)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                bitrateResult = BitrateResult.Failed(when (e) {
                    is FileNotFoundException -> BitrateFailure.UNSUPPORTED
                    is HostHttpResponseException -> if (e.errorCode == 403) BitrateFailure.DENIED else BitrateFailure.rejected(e.message)
                    else -> BitrateFailure.UNCONFIRMED
                })
            } finally { bitrateBusy = false }
        }
    }

    /** What the menu's composables read from and do to the stream: the live [Game], with [menu] as the menu's own window. */
    private inner class GameHost(private val menu: ComponentDialog) : StreamMenuHost {
        private val prefs get() = game.prefConfig
        private val saveTarget by lazy { GameSettingsStore.forLaunch(game) }
        private val classic get() = game.gameMenuCallbacks

        override val gameName: String = game.intent.getStringExtra(Game.EXTRA_APP_NAME) ?: "Stream"
        override fun dismiss() { menu.dismiss() }

        // The whole stream behind the menu is blurred (the way leaving a stream does it): a window can only blur what lies
        // behind its whole rectangle, so not the tiles one by one. Android 12 and newer, with window blur enabled.
        private val blurRadius = run {
            val m = game.resources.displayMetrics
            50f * minOf(m.widthPixels / 1920f, m.heightPixels / 1080f)
        }
        private val blurOn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && game.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
        init {
            if (blurOn) menu.window?.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
        override fun setBackdrop(amount: Float) {
            if (!blurOn || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val w = menu.window ?: return
            w.attributes = w.attributes.apply { blurBehindRadius = (amount * blurRadius).toInt() }
        }
        override fun runWhenStreamFocused(action: () -> Unit) = whenGameFocused(action)

        override val overlayOn get() = prefs.enablePerfOverlay
        override val overlayCompact get() = prefs.enablePerfOverlayLite
        override fun toggleOverlay() { game.toggleHUD() }
        override fun toggleKeyboard() { game.toggleKeyboard() }

        override val brightnessPercent get() = this@StreamMenu.brightnessPercent()
        override fun setBrightness(percent: Int) { applyBrightness(percent, menu) }

        override val bitrateKbps get() = prefs.bitrate
        override val bitrateBusy get() = this@StreamMenu.bitrateBusy
        override val bitrateResult get() = this@StreamMenu.bitrateResult
        override fun applyBitrate(requested: Int, accepted: (Int) -> Unit) = this@StreamMenu.applyBitrate(requested, accepted)

        override val upscalerAvailable get() = game.isPyroWave || com.limelight.binding.video.SgsrRenderer.isSupported(game)
        override val upscalerRequested get() = game.isUpscalingRequested
        override val upscalerStatus get() = game.upscalingStatus
        override val supersamplingMode get() = game.isSupersamplingMode
        override val supersamplingLevel get() = game.supersamplingLevel
        override val upscalerMode get() = if (prefs.sgsrTemporalQuality) 3 else if (prefs.sgsrTemporal) 2 else if (prefs.sgsrHighQuality) 1 else 0
        override val upscalerSharpness get() = prefs.sgsrSharpness
        override fun setUpscalingEnabled(on: Boolean) { game.setUpscalingEnabled(on) }
        override fun setUpscalingMode(mode: Int) { game.setUpscalingMode(mode) }
        override fun setUpscalingSharpness(percent: Int) { game.setUpscalingSharpness(percent) }
        override fun setSupersamplingLevel(level: Int) { game.setSupersamplingLevel(level) }
        override val canSaveUpscaler get() = saveTarget != null
        override fun saveUpscaler() {
            saveTarget?.save(upscalerSaveValues(game.isUpscalingRequested, upscalerMode, prefs.sgsrSharpness, game.supersamplingLevel))
        }

        override fun sendClipboard() { game.sendClipboard(true) }
        override fun getClipboard() { game.getClipboard(0) }
        override fun sendTaskManager() { game.sendKeys(shortArrayOf(KeyboardTranslator.VK_LCONTROL.toShort(), KeyboardTranslator.VK_LSHIFT.toShort(), KeyboardTranslator.VK_ESCAPE.toShort())) }
        override val zoomOn get() = game.isZoomModeEnabled
        override fun toggleZoom() { game.toggleZoomMode() }
        override val onscreenControlsOn get() = prefs.onscreenController
        override fun toggleOnscreenControls() { game.toggleVirtualController() }
        override fun toggleFullKeyboard() { game.toggleFullKeyboard() }
        override fun toggleKeyLayout() { game.toggleKeyboardController() }
        override val touchSensitivityOn get() = prefs.enableTouchSensitivity
        override fun toggleTouchSensitivity() { game.switchTouchSensitivity() }
        override fun rotate() { game.rotateScreen() }

        // TEMPORARY (#66): the laboratory's preset logic, live. RumblePreferences saves the whole preset in one edit; the
        // stream's ControllerHandler reads prefConfig at every rumble event, so the next event uses the new preset.
        private val rumbleSettings by lazy { ProfileStore.currentSettings(game) }
        override val rumblePreset get() = RumblePreferences.activePreset(rumbleSettings)
        override fun rumbleUnavailable(preset: RumblePresets) =
            RumbleCapabilities.get(game).unavailableReason(preset.profile.technique)
        override fun selectRumblePreset(preset: RumblePresets) {
            if (rumbleUnavailable(preset) != null) return
            RumblePreferences.applyPreset(rumbleSettings, preset)
            prefs.rumbleProfile = preset.profile
            prefs.rumbleTranslatorEnabled = preset.enabled
        }

        override val allowChangeMouseMode get() = game.allowChangeMouseMode
        override val mouseMode get() = game.activeMouseMode
        override val rememberMouseMode get() = prefs.rememberMouseMode
        override fun mouseModes() = com.limelight.shell.settings.SettingsCatalog(game).all.first { it.key == "mouse_mode_list" }.choices
            .filter { !game.isOnExternalDisplay || it.value in setOf("2", "3", "4") }
        override fun setMouseMode(mode: Int) { game.setStreamMouseMode(mode) }
        override val localMouseCursorVisible get() = game.isLocalMouseCursorVisible
        override fun toggleLocalMouseCursor() { game.toggleMouseLocalCursor() }

        override val specialKeys get() = classic?.specialKeyOptions().orEmpty().map { it.label }
        override fun sendSpecialKey(index: Int) {
            val option = classic?.specialKeyOptions()?.getOrNull(index) ?: return
            classic?.run(option)
        }
        override val serverCommands get() = game.serverCmds.orEmpty()
        override fun runServerCommand(index: Int) { game.sendExecServerCmd(index) }

        override fun leave(quit: Boolean) { this@StreamMenu.leave(quit) }
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
