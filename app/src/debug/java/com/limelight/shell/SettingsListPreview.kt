package com.limelight.shell

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.SettingRow
import com.limelight.shell.ui.SettingRowList
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.rememberMarginBringIntoView
import com.limelight.shell.ui.fadeEdges
import com.limelight.shell.ui.px
import kotlinx.coroutines.delay

/**
 * Debug builds only: a long list of real settings rows (toggle, choice, slider, action; every third with a description) with
 * the settings list's scroll (`MarginBringIntoView`, margins 72 / 110) and its one focus ring that glides from row to row
 * (`SettingRowList`), started with
 * `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen settings_list`
 * and optionally `--ez preview_auto true`: the focus steps down 12 rows slowly (650 ms apart), then 3 rows quickly (150 ms, like a held
 * D-pad), then jumps back to the top, and rests. Without it the D-pad and the stick move the focus (docs/GUI.md, "Settings list scroll").
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
        val listState = rememberLazyListState()
        val bringIntoView = rememberMarginBringIntoView(listState, before = 72f * k, after = 110f * k, stagePx = k)
        val requesters = remember { List(ROWS) { FocusRequester() } }
        LaunchedEffect(Unit) {
            delay(300)
            requesters[0].requestFocus()
            if (!auto) return@LaunchedEffect
            delay(1200)
            for (i in 1..12) { delay(650); requesters[i].requestFocus() }
            delay(900)
            for (i in 13..15) { delay(150); requesters[i].requestFocus() }
            delay(1500)
            requesters[0].requestFocus()
        }
        Box(Modifier.fillMaxSize().background(colors.bg)) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView, LocalFocusTracker provides remember { FocusTracker() }) {
                // The same list the settings screens use: real rows, one ring that glides between them while the list scrolls
                SettingRowList(Modifier.fillMaxSize().padding(horizontal = 300.px, vertical = 150.px).fadeEdges(stage, top = 14f, bottom = 90f)) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState,
                        contentPadding = PaddingValues(18.px), verticalArrangement = Arrangement.spacedBy(10.px)) {
                        items(ROWS) { i ->
                            val value = when (i % 4) {
                                0 -> RowValue.Toggle(i % 8 == 0) {}
                                1 -> RowValue.Choice("Option ${i + 1}", {})
                                2 -> RowValue.Slider(40, 0, 100, 5, "%") {}
                                else -> RowValue.Action {}
                            }
                            SettingRow(Symbols.SETTINGS, "Row ${i + 1}", value, Modifier.focusRequester(requesters[i]),
                                description = if (i % 3 == 0) "A description that makes this row taller than its neighbours" else null)
                        }
                    }
                }
            }
        }
    }
}
