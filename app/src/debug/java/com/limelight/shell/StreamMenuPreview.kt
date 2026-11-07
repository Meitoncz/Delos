package com.limelight.shell

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import com.limelight.preferences.UpscalerMode
import com.limelight.shell.settings.SettingChoice
import com.limelight.utils.RumblePresets
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.PI
import kotlin.math.sin

/**
 * Debug builds only: the real stream menu on a drawn game scene with fake state, started with
 * `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen stream_menu`
 * and optionally `--es preview_page <main|upscaler|bitrate|brightness|more|keys|commands|mouse|rumble>`, `--ei preview_focus <tile 0..8>`,
 * `--ei preview_row <row of the page to focus>`, `--ei preview_upscaler_mode <0..4, the Mode row's position; 4 = Sharpen only>`
 * and `--ez preview_animate true` (plays the entrance; without it the menu is already open). Handled by `ShellActivity`
 * before the shell, so no profile is needed (docs/GUI.md, "Stream menu preview").
 */
internal object StreamMenuPreview {
    fun from(intent: Intent): (@Composable () -> Unit)? {
        if (intent.getStringExtra("preview_screen") != "stream_menu") return null
        val page = when (intent.getStringExtra("preview_page")) {
            "more" -> MenuPage.MORE
            "keys" -> MenuPage.KEYS
            "commands" -> MenuPage.COMMANDS
            "brightness" -> MenuPage.BRIGHTNESS
            "bitrate" -> MenuPage.BITRATE
            "upscaler" -> MenuPage.UPSCALER
            "mouse" -> MenuPage.MOUSE
            "rumble" -> MenuPage.RUMBLE
            else -> MenuPage.MAIN
        }
        val focus = BarItem.entries.getOrElse(intent.getIntExtra("preview_focus", 0)) { BarItem.RESUME }
        val start = StreamMenuStart(page, focus, intent.getIntExtra("preview_row", 0), intent.getBooleanExtra("preview_animate", false))
        val upscalerMode = intent.getIntExtra("preview_upscaler_mode", 2)
        return { StreamMenuPreviewScreen(start, upscalerMode) }
    }
}

/** The menu over the scene; closing it (Resume, B) leaves the scene alone, a tap on it opens the menu again. */
@Composable
private fun StreamMenuPreviewScreen(start: StreamMenuStart, upscalerMode: Int) {
    val host = remember { FakeStreamMenuHost(upscalerMode) }
    var open by remember { mutableStateOf(true) }
    var session by remember { mutableIntStateOf(0) }
    host.onDismiss = { open = false }
    Box(Modifier.fillMaxSize()) {
        GameScene()
        if (open) {
            key(session) {
                CompositionLocalProvider(LocalFocusTracker provides remember { FocusTracker() }) {
                    StreamMenuRoot(host, emptyFlow(), emptyFlow(), if (session == 0) start else StreamMenuStart())
                }
            }
        } else {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { session++; open = true } })
        }
    }
}

/** A sunset over hills, so the menu (and the Full keyboard preview) is seen on a picture like in a stream. */
@Composable
internal fun GameScene() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF191347), .35f to Color(0xFF50237E), .55f to Color(0xFFE5518C), .68f to Color(0xFFFF9F5A), .75f to Color(0xFFFFD27A),
            )
        )
        fun hills(base: Float, amplitude: Float, wavelength: Float, phase: Float, color: Color) {
            val path = Path().apply {
                moveTo(0f, size.height)
                var x = 0f
                while (x <= size.width + 8f) {
                    lineTo(x, size.height * base - amplitude * size.height * (sin((x / size.width) * 2f * PI.toFloat() * wavelength + phase)).toFloat())
                    x += 8f
                }
                lineTo(size.width, size.height)
                close()
            }
            drawPath(path, color)
        }
        hills(.70f, .05f, 1.3f, 0.6f, Color(0xFF8A4A9E))
        hills(.76f, .045f, 1.8f, 2.1f, Color(0xFF5A2A78))
        hills(.84f, .04f, 1.1f, 4.0f, Color(0xFF2E1647))
        drawCircle(Color(0x55FFFFFF), 3f, Offset(size.width * .5f, size.height * .40f))
    }
}

/** The stream menu's [StreamMenuHost] with made-up values: every row works, nothing reaches a stream. */
internal class FakeStreamMenuHost(initialUpscalerMode: Int = 2) : StreamMenuHost {
    var onDismiss: () -> Unit = {}
    private val handler = Handler(Looper.getMainLooper())

    override val gameName = "Returnal"
    override fun dismiss() = onDismiss()
    override fun runWhenStreamFocused(action: () -> Unit) = action()

    override var overlayOn = true
    override var overlayCompact = false
    override fun toggleOverlay() {
        if (!overlayOn) overlayOn = true
        else if (!overlayCompact) overlayCompact = true
        else { overlayOn = false; overlayCompact = false }
    }
    override fun toggleSystemKeyboard() = Unit

    override var brightnessPercent = 70
    override fun setBrightness(percent: Int) { brightnessPercent = percent }

    override var bitrateKbps = 50000
    override var bitrateBusy by mutableStateOf(false)
    override var bitrateResult by mutableStateOf<BitrateResult?>(null)
    // A tested Wi-Fi link that carries less than the 225 Mbps guide, so the Bitrate page shows its recommendation row
    override val linkGuidance: com.limelight.shell.link.LinkGuidance? get() = com.limelight.shell.link.linkGuidance(
        225_000, 60, com.limelight.nvstream.http.PyroWaveHostInfo.NONE,
        com.limelight.shell.link.LinkTest(180_000, System.currentTimeMillis() - 86_400_000L, com.limelight.shell.link.LinkTransport.WIFI, 50, 1392),
    )
    override fun applyBitrate(requested: Int, accepted: (Int) -> Unit) {
        if (bitrateBusy) return
        bitrateBusy = true
        bitrateResult = null
        handler.postDelayed({
            val applied = minOf(requested, 80000)
            bitrateKbps = applied
            accepted(applied)
            bitrateResult = if (applied < requested) BitrateResult.Limited(applied) else BitrateResult.Accepted(applied)
            bitrateBusy = false
        }, 700)
    }

    override val upscalerAvailable = true
    override var upscalerRequested = true
    override val upscalerStatus get() = if (upscalerRequested) "On: " + UpscalerMode.fromIndex(upscalerMode).label() else "Off"
    // Like the renderer: a mode that does not scale has nothing to supersample
    override val supersamplingMode get() = UpscalerMode.fromIndex(upscalerMode).scales()
    private var level = 1
    override val supersamplingLevel get() = level
    override var upscalerMode = UpscalerMode.fromIndex(initialUpscalerMode).index()
    override var upscalerSharpness = 50
    override fun setUpscalingEnabled(on: Boolean) { upscalerRequested = on }
    override fun setUpscalingMode(mode: Int) { upscalerMode = mode }
    override fun setUpscalingSharpness(percent: Int) { upscalerSharpness = percent }
    override fun setSupersamplingLevel(level: Int) { this.level = level }
    override val canSaveUpscaler = true
    override fun saveUpscaler() = Unit

    override fun sendClipboard() = Unit
    override fun getClipboard() = Unit
    override fun sendTaskManager() = Unit
    override var zoomOn = false
    override fun toggleZoom() { zoomOn = !zoomOn }
    override var onscreenControlsOn = false
    override fun toggleOnscreenControls() { onscreenControlsOn = !onscreenControlsOn }
    override var fullKeyboardOn = false
    override fun toggleFullKeyboard() { fullKeyboardOn = !fullKeyboardOn }
    override var touchSensitivityOn = false
    override fun toggleTouchSensitivity() { touchSensitivityOn = !touchSensitivityOn }

    // Live rumble feel selection state
    override var rumblePreset by mutableStateOf<RumblePresets?>(RumblePresets.ORIGINAL)
    override fun selectRumblePreset(preset: RumblePresets) { rumblePreset = preset }

    override val allowChangeMouseMode = true
    private var mode = 0
    override val mouseMode get() = mode
    override val rememberMouseMode = false
    override fun mouseModes() = listOf(
        SettingChoice("Multi-touch", "0"), SettingChoice("Trackpad", "1"), SettingChoice("Relative", "2"),
        SettingChoice("Classic mouse", "3"), SettingChoice("Disabled", "4"), SettingChoice("Multi-touch, buttons swapped", "5"),
    )
    override fun setMouseMode(mode: Int) { this.mode = mode }
    override var localMouseCursorVisible = false
    override fun toggleLocalMouseCursor() { localMouseCursorVisible = !localMouseCursorVisible }

    override val specialKeys = listOf(
        "Esc", "Tab", "Ctrl + C", "Ctrl + V", "Ctrl + Alt + Delete", "Alt + Tab", "Alt + F4", "Win", "Win + D", "Win + Tab",
        "Print Screen", "F1", "F5", "F11", "F12", "Ctrl + Shift + Esc",
    )
    override fun sendSpecialKey(index: Int) = Unit
    override val serverCommands = listOf("Restart the game", "Open Steam Big Picture", "Sleep the PC", "Toggle HDR")
    override fun runServerCommand(index: Int) = Unit

    override fun leave(quit: Boolean) = Unit
}
