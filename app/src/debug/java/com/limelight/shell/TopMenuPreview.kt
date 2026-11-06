package com.limelight.shell

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.NavButton
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.TopNav
import com.limelight.shell.ui.px
import kotlinx.coroutines.delay

/**
 * Debug builds only: the home screen's top menu (Settings, Profile, Devices) with its transient gliding pill on a plain
 * background, started with
 * `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen top_menu`
 * and optionally `--ez preview_auto true` (moves the focus every 1.2 s: right, right, left, left, ...; the first button is focused
 * at once, which shows no pill, as focus coming in from outside). The D-pad, the stick and taps move the focus as on the real screen.
 */
internal object TopMenuPreview {
    fun from(intent: Intent): (@Composable () -> Unit)? {
        if (intent.getStringExtra("preview_screen") != "top_menu") return null
        val auto = intent.getBooleanExtra("preview_auto", false)
        return { Preview(auto) }
    }

    @Composable
    private fun Preview(auto: Boolean) {
        val focus = LocalFocusManager.current
        val buttons = listOf(
            NavButton(Symbols.SETTINGS, "Settings", false) {},
            NavButton(Symbols.ACCOUNT_CIRCLE, "Profile", false) {},
            NavButton(Symbols.DEVICES, "Devices", false) {},
        )
        LaunchedEffect(Unit) {
            delay(400)
            focus.moveFocus(FocusDirection.Next)
        }
        LaunchedEffect(auto) {
            if (!auto) return@LaunchedEffect
            delay(1600)
            var direction = FocusDirection.Right
            var step = 0
            while (true) {
                focus.moveFocus(direction)
                step++
                // Right, right, left, left: every move is between two buttons
                if (step == 2) direction = FocusDirection.Left
                if (step == 4) { direction = FocusDirection.Right; step = 0 }
                delay(1200)
            }
        }
        Box(Modifier.fillMaxSize().background(LocalDelosColors.current.bg)) {
            CompositionLocalProvider(LocalFocusTracker provides remember { FocusTracker() }) {
                TopNav(buttons, Modifier.align(Alignment.TopCenter).offset(y = 40.px))
            }
        }
    }
}
