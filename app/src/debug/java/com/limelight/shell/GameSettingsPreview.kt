package com.limelight.shell

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.limelight.shell.screens.GameBanner
import com.limelight.shell.screens.GameHeader
import com.limelight.shell.screens.GameSettingsLayout
import com.limelight.shell.screens.GameSettingsPanel
import com.limelight.shell.screens.SettingsLayout
import com.limelight.shell.screens.ShoulderLabel
import com.limelight.shell.screens.TabPill
import com.limelight.shell.settings.SettingKind
import com.limelight.shell.settings.SettingSpec
import com.limelight.shell.settings.SettingsTab
import com.limelight.shell.settings.impact
import com.limelight.shell.system.BatteryStatus
import com.limelight.shell.system.WifiStatus
import com.limelight.shell.ui.GameArt
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.fadeEdges
import com.limelight.shell.ui.StatusBar
import com.limelight.shell.ui.ZoomedBar
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.PillButton
import com.limelight.shell.ui.RowOverride
import com.limelight.shell.ui.RowValue
import com.limelight.shell.ui.SelectionPillHost
import com.limelight.shell.ui.SettingRow
import com.limelight.shell.ui.SettingRowList
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.focusedListBoundary
import com.limelight.shell.ui.focusedListRow
import com.limelight.shell.ui.px
import com.limelight.shell.ui.rememberFocusedListNavigation
import kotlinx.coroutines.delay

/**
 * Debug builds only: the per-game settings screen's look with fixed data (no PC, no stores): the banner with the placeholder art, the
 * title block, the tabs with their counts, four rows (two custom, with the override marker and the reset affordance) and the panel.
 * `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen game_settings`
 * with `--ez preview_touch true` (round reset buttons) and `--ez preview_auto true` (the focus steps down the rows 1.2 s apart, then
 * stops on the last one). The D-pad moves the focus; X resets nothing here.
 */
@OptIn(ExperimentalFoundationApi::class)
internal object GameSettingsPreview {
    fun from(intent: Intent): (@Composable () -> Unit)? {
        if (intent.getStringExtra("preview_screen") != "game_settings") return null
        val touch = intent.getBooleanExtra("preview_touch", false)
        val auto = intent.getBooleanExtra("preview_auto", false)
        return { Preview(touch, auto) }
    }

    private val previewValues = mapOf<String, Any>("list_resolution" to "1920x1080", "list_fps" to "120", "seekbar_bitrate_kbps" to 60000,
        "video_format" to "forceh265", "checkbox_sgsr" to true, "list_sgsr_quality" to "temporal")

    private class Row(val key: String, val label: String, val value: RowValue, val profile: String?, val spec: SettingSpec)

    @Composable
    private fun Preview(touch: Boolean, auto: Boolean) {
        val colors = LocalDelosColors.current
        val rows = remember {
            fun spec(key: String, label: String, kind: SettingKind) =
                SettingSpec(key, label, "The size of the picture the PC renders and encodes.", "c", "s", SettingsTab.STREAM, kind, null)
            listOf(
                Row("res", "Resolution", RowValue.Choice("1280 × 720", {}), "1920 × 1080", spec("list_resolution", "Resolution", SettingKind.CHOICE)),
                Row("fps", "Frame rate", RowValue.Choice("60 FPS", {}), null, spec("list_fps", "Frame rate", SettingKind.CHOICE)),
                Row("br", "Video bitrate", RowValue.Slider(60, 5, 150, 1, " Mbps") {}, "40 Mbps", spec("seekbar_bitrate_kbps", "Video bitrate", SettingKind.SLIDER)),
                Row("codec", "Video codec", RowValue.Choice("HEVC", {}), null, spec("video_format", "Video codec", SettingKind.CHOICE)),
            )
        }
        val navigation = rememberFocusedListNavigation(rows.map { it.key })
        var focused by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            delay(300)
            navigation.requester(rows[0].key).requestFocus()
            if (!auto) return@LaunchedEffect
            for (i in 1 until rows.size) { delay(1200); navigation.requester(rows[i].key).requestFocus() }
        }
        Box(Modifier.fillMaxSize().background(colors.bg)) {
            CompositionLocalProvider(LocalFocusTracker provides remember { FocusTracker() }) {
                GameBanner(GameArt("preview", 210f, 245f, Symbols.SPORTS_ESPORTS), null, 0)
                GameHeader("Gaming PC", "Elden Ring", 2, Modifier.offset(SettingsLayout.TITLE_X.px, GameSettingsLayout.HEAD_TOP.px)
                    .width((LocalStage.current.stageWidth - 2 * SettingsLayout.TITLE_X).px).height(GameSettingsLayout.HEAD_HEIGHT.px)) {
                    PillButton("Reset all", Symbols.RESET, {}, compact = true)
                }
                SelectionPillHost(Modifier.offset(y = GameSettingsLayout.TABS_Y.px).fillMaxWidth().height(SettingsLayout.TABS_HEIGHT.px)) {
                    ShoulderLabel("L1")
                    TabPill(Symbols.CAST, "Stream", true, Modifier.padding(horizontal = 5.px), 2) {}
                    TabPill(Symbols.MONITOR, "Display", false, Modifier.padding(horizontal = 5.px), 0) {}
                    ShoulderLabel("R1")
                }
                SettingRowList(Modifier.padding(start = GameSettingsLayout.LIST_X.px, top = GameSettingsLayout.LIST_TOP.px, bottom = 96.px)
                    .width((LocalStage.current.stageWidth - GameSettingsLayout.LIST_WIDTH_LESS).px).fillMaxHeight().fadeEdges(LocalStage.current, top = 14f, bottom = 70f)) {
                    Column(Modifier.fillMaxSize().focusedListBoundary(navigation).padding(start = 18.px, end = 18.px, top = GameSettingsLayout.LIST_INNER_TOP.px),
                        verticalArrangement = Arrangement.spacedBy(10.px)) {
                        rows.forEachIndexed { i, row ->
                            SettingRow(Symbols.SETTINGS, row.label, row.value, Modifier.focusedListRow(navigation, row.key),
                                description = "From profile".takeIf { row.profile == null },
                                override = row.profile?.let { RowOverride("Profile: $it", touch) {} },
                                impact = impact(row.spec.key, previewValues),
                                onFocused = { focused = i })
                        }
                    }
                }
                // The persistent status bar of the app: the header and the tabs must clear it
                ZoomedBar(1f) { StatusBar(null, null, false, WifiStatus(3), BatteryStatus(.8f, false), "21:08") }
                val row = rows[focused]
                GameSettingsPanel(
                    Modifier.align(Alignment.TopEnd).padding(top = GameSettingsLayout.PANEL_TOP.px, end = SettingsLayout.TITLE_X.px, bottom = 96.px)
                        .width(520.px).fillMaxHeight(),
                    game = "Elden Ring", focused = row.spec, isCustom = row.profile != null, touch = touch,
                    currentLabel = "60 FPS", profileLabel = row.profile, detail = row.spec.description, streamCustom = 2, displayCustom = 0,
                )
            }
        }
    }
}
