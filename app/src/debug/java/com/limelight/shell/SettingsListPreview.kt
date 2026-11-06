package com.limelight.shell

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.SettingRow
import com.limelight.shell.ui.SettingRowList
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.rememberMarginBringIntoView
import com.limelight.shell.ui.rememberFocusedListNavigation
import com.limelight.shell.ui.focusedListBoundary
import com.limelight.shell.ui.focusedListRow
import com.limelight.shell.ui.fadeEdges
import com.limelight.shell.ui.px
import kotlinx.coroutines.delay

/**
 * Debug builds only: a long list of real settings rows (toggle, choice, slider, action; every third with a description) with
 * the settings list's scroll (`MarginBringIntoView`, margins 72 / 110) and its one focus ring that glides from row to row
 * (`SettingRowList`), started with
 * `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen settings_list`
 * and optionally `--ez preview_auto true`: the focus steps down 12 rows slowly (650 ms apart), then 3 rows quickly (150 ms, like a held
 * D-pad), then sends 40 rapid Up key-downs from one hold and rests on the first row. Without it the D-pad and stick move focus.
 */
@OptIn(ExperimentalFoundationApi::class)
internal object SettingsListPreview {
    fun from(intent: Intent): (@Composable () -> Unit)? {
        if (intent.getStringExtra("preview_screen") != "settings_list") return null
        val auto = intent.getBooleanExtra("preview_auto", false)
        return { Preview(auto) }
    }

    private const val ROWS = 40

    @Composable
    private fun Preview(auto: Boolean) {
        val colors = LocalDelosColors.current
        val stage = LocalStage.current
        val k = stage.dpPerPx * LocalDensity.current.density
        val listState = rememberScrollState()
        val bringIntoView = rememberMarginBringIntoView(listState, before = 72f * k, after = 110f * k, stagePx = k)
        val keys = remember { List(ROWS) { it.toString() } }
        val navigation = rememberFocusedListNavigation(keys)
        val view = LocalView.current
        var focusedRow by remember { mutableIntStateOf(-1) }
        LaunchedEffect(Unit) {
            delay(300)
            navigation.requester(keys[0]).requestFocus()
            if (!auto) return@LaunchedEffect
            delay(1200)
            for (i in 1..12) { delay(650); navigation.requester(keys[i]).requestFocus() }
            delay(900)
            for (i in 13..15) { delay(150); navigation.requester(keys[i]).requestFocus() }
            delay(1500)
            // Exercise the real repeated-key boundary; never force the final focus back to row zero.
            val downTime = android.os.SystemClock.uptimeMillis()
            repeat(40) { repeatCount ->
                view.dispatchKeyEvent(android.view.KeyEvent(
                    downTime, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_UP, repeatCount,
                ))
                delay(50)
            }
            view.dispatchKeyEvent(android.view.KeyEvent(
                downTime, android.os.SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_DPAD_UP, 0,
            ))
            check(focusedRow == 0) { "Settings HOLD-UP ended at row $focusedRow instead of the first row" }
        }
        Box(Modifier.fillMaxSize().background(colors.bg)) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView, LocalFocusTracker provides remember { FocusTracker() }) {
                // The same list the settings screens use: real rows, one ring that glides between them while the list scrolls
                SettingRowList(Modifier.fillMaxSize().padding(horizontal = 300.px, vertical = 150.px).fadeEdges(stage, top = 14f, bottom = 90f)) {
                    Column(Modifier.fillMaxSize().focusedListBoundary(navigation).verticalScroll(listState).padding(18.px),
                        verticalArrangement = Arrangement.spacedBy(10.px)) {
                        repeat(ROWS) { i ->
                            val value = when (i % 4) {
                                0 -> RowValue.Toggle(i % 8 == 0) {}
                                1 -> RowValue.Choice("Option ${i + 1}", {})
                                2 -> RowValue.Slider(40, 0, 100, 5, "%") {}
                                else -> RowValue.Action {}
                            }
                            SettingRow(Symbols.SETTINGS, "Row ${i + 1}", value, Modifier.focusedListRow(navigation, keys[i]),
                                description = if (i % 3 == 0) "A description that makes this row taller than its neighbours" else null,
                                onFocused = { focusedRow = i })
                        }
                    }
                }
            }
        }
    }
}
