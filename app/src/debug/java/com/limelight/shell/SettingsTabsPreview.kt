package com.limelight.shell

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.limelight.shell.screens.ShoulderLabel
import com.limelight.shell.screens.TabPill
import com.limelight.shell.settings.SettingsTab
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.SelectionPillHost
import com.limelight.shell.ui.px
import kotlinx.coroutines.delay

/**
 * Debug builds only: the settings category tabs with their gliding pill on a plain background, started with
 * `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen settings_tabs`
 * and optionally `--ez preview_auto true` (steps to the next tab every 1.2 s, wrapping). L1 / R1 step through the tabs with
 * wrap-around and a tap selects one; the D-pad and the stick do nothing, the tabs are touch only (docs/GUI.md, "Settings tabs").
 */
internal object SettingsTabsPreview {
    fun from(intent: Intent): (@Composable () -> Unit)? {
        if (intent.getStringExtra("preview_screen") != "settings_tabs") return null
        val auto = intent.getBooleanExtra("preview_auto", false)
        return { Preview(auto) }
    }

    @Composable
    private fun Preview(auto: Boolean) {
        val tabs = SettingsTab.entries
        var index by remember { mutableIntStateOf(0) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        LaunchedEffect(auto) {
            if (!auto) return@LaunchedEffect
            while (true) {
                delay(1200)
                index = (index + 1) % tabs.size
            }
        }
        Box(Modifier.fillMaxSize().background(LocalDelosColors.current.bg)
            .focusRequester(focus).focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = when (e.key) {
                    Key.ButtonL1 -> -1
                    Key.ButtonR1 -> 1
                    else -> return@onKeyEvent false
                }
                index = (index + step + tabs.size) % tabs.size
                true
            }) {
            SelectionPillHost(Modifier.offset(y = 206.px).fillMaxWidth().height(66.px)) {
                ShoulderLabel("L1")
                tabs.forEachIndexed { i, tab ->
                    TabPill(tab.icon, tab.label, i == index, Modifier.padding(horizontal = 5.px)) { index = i }
                }
                ShoulderLabel("R1")
            }
        }
    }
}
