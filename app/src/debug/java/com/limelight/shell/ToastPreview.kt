package com.limelight.shell

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import com.limelight.shell.ui.Symbols
import com.limelight.utils.ToastKind
import kotlinx.coroutines.delay

/**
 * Debug builds only: the console toast over the drawn game scene, started with
 * `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen toast`
 * and optionally `--es preview_kind <info|success|warning|error|action>`, `--ei preview_double 1` (a second toast 350 ms later, to see the
 * replace: the first leaves, the second enters 120 ms after) and `--ei preview_long 1` (a message that needs the ellipsis). A tap on the scene
 * (or Y with a controller, which is also Undo on the `action` toast) shows it again. The toast itself is the shell's own host, handled
 * by `ShellActivity` before the shell (docs/GUI.md, "Toasts").
 */
internal object ToastPreview {
    fun from(intent: Intent, shell: ShellState): (@Composable () -> Unit)? {
        if (intent.getStringExtra("preview_screen") != "toast") return null
        val kind = intent.getStringExtra("preview_kind") ?: "info"
        val double = intent.getIntExtra("preview_double", 0) == 1
        val long = intent.getIntExtra("preview_long", 0) == 1
        return { ToastPreviewScreen(shell, kind, double, long) }
    }
}

private fun fire(shell: ShellState, kind: String, long: Boolean) {
    val longText = "This server does not support clipboard sync. Please install or upgrade your server to the latest version of Apollo, " +
        "then restart the stream so that the clipboard can be shared again between the PC and this device."
    when (kind) {
        "success" -> shell.showToast(if (long) longText else "Artwork ready for 17 games", kind = ToastKind.SUCCESS)
        "warning" -> shell.showToast(if (long) longText else "The virtual display is not ready on GAMING-PC · streaming the main display", kind = ToastKind.WARNING)
        "error" -> shell.showToast(if (long) longText else "GAMING-PC cannot be reached", kind = ToastKind.ERROR)
        "action" -> shell.showToast(
            if (long) longText else "Anna was deleted", Symbols.DELETE,
            ToastAction("Undo") { shell.showToast("Anna is back", Symbols.CHECK_CIRCLE) },
        )
        else -> shell.showToast(if (long) longText else "Pan/Zoom Mode Enabled", kind = ToastKind.INFO)
    }
}

@Composable
private fun ToastPreviewScreen(shell: ShellState, kind: String, double: Boolean, long: Boolean) {
    val focus = remember { FocusRequester() }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        if (round == 0) focus.requestFocus()
        delay(400)
        fire(shell, kind, long)
        if (double) {
            delay(350)
            fire(shell, if (kind == "success") "error" else "success", false)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
                    if (!shell.runToastAction()) round++
                    true
                } else false
            }
            .pointerInput(Unit) { detectTapGestures { round++ } },
    ) { GameScene() }
}
