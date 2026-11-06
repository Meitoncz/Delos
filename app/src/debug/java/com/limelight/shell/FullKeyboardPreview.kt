package com.limelight.shell

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.shell.keyboard.FullKeyboard
import com.limelight.shell.keyboard.FullKeyboardHost
import com.limelight.shell.keyboard.FullKeyboardState
import com.limelight.shell.keyboard.KeyHaptic
import com.limelight.shell.keyboard.KeyboardAlign
import com.limelight.shell.keyboard.KeyboardPrefs

/**
 * Debug builds only: the real Full keyboard on the drawn game scene, started with
 * `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen full_keyboard`
 * and optionally `--ei preview_opacity <0..100>`, `--es preview_align <left|center|right>` and, to try the size settings,
 * `--ez preview_custom_size true --ei preview_height <dp> --ei preview_width <dp>` (1000 = full width). Touch, the D-pad, the left
 * stick, A, B and X work as in a stream; the keys that would go to the PC are listed at the top left. A tap on the scene
 * opens the keyboard again. Handled by `ShellActivity` before the shell (docs/GUI.md, "Full keyboard").
 */
internal object FullKeyboardPreview {
    fun from(intent: Intent): (@Composable () -> Unit)? {
        if (intent.getStringExtra("preview_screen") != "full_keyboard") return null
        val prefs = KeyboardPrefs(
            autoFitDisabled = intent.getBooleanExtra("preview_custom_size", false),
            heightDp = intent.getIntExtra("preview_height", 200),
            widthDp = intent.getIntExtra("preview_width", 1000),
            align = KeyboardAlign.parse(intent.getStringExtra("preview_align")),
            opacityPercent = intent.getIntExtra("preview_opacity", 90).coerceIn(0, 100),
        )
        return { FullKeyboardPreviewScreen(prefs) }
    }
}

/** The keys the keyboard sends go to a list instead of a PC. */
private class PreviewHost : FullKeyboardHost {
    lateinit var state: FullKeyboardState
    var log by mutableStateOf("")

    override val stickyModifiers = true

    override fun sendKey(code: Int, down: Boolean) {
        val line = (if (down) "down  " else "up    ") + KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
        log = (listOf(line) + log.lines().filter { it.isNotEmpty() }).take(6).joinToString("\n")
    }

    override fun haptic(haptic: KeyHaptic) = Unit

    override fun close() = state.hide()
}

@Composable
private fun FullKeyboardPreviewScreen(prefs: KeyboardPrefs) {
    val host = remember { PreviewHost() }
    val state = remember { FullKeyboardState(host).also { it.prefs = prefs } }
    host.state = state
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        state.show()
        focus.requestFocus()
    }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { state.open && state.onControllerKey(it.nativeKeyEvent) },
    ) {
        GameScene()
        if (!state.open) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { state.show() } })
        Box(Modifier.align(Alignment.BottomStart)) { FullKeyboard(state) }
        BasicText(host.log, Modifier.padding(24.dp), style = TextStyle(color = Color.White, fontSize = 18.sp))
    }
}
