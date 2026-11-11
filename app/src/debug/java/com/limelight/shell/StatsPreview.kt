package com.limelight.shell

import android.content.Intent
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import com.limelight.binding.video.SharpenCost
import com.limelight.binding.video.StageSummary

/**
 * Debug builds only: the stream statistics panel over the drawn game scene with fixed numbers, started with
 * `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen stream_stats`
 * and optionally `--es preview_state <full|compact>` (the full box in the top-left corner, or the compact line at the top centre) and
 * `--es preview_position <TOP_LEFT|TOP_RIGHT|BOTTOM_LEFT|BOTTOM_RIGHT>`. The panel is the real [StreamStatsPanel] (docs/GUI.md, "Screen shapes").
 */
internal object StatsPreview {
    fun from(intent: Intent): (@Composable () -> Unit)? {
        if (intent.getStringExtra("preview_screen") != "stream_stats") return null
        val compact = intent.getStringExtra("preview_state") == "compact"
        val position = StatsPosition.entries.firstOrNull { it.name == intent.getStringExtra("preview_position") } ?: StatsPosition.TOP_LEFT
        return { Preview(compact, position) }
    }

    private val STATS = StreamStats(
        width = 3840, height = 2160, fps = 60f, incomingFps = 59.9f, renderingFps = 59.8f, codec = "HEVC", hdr = true,
        decoder = "c2.qti.hevc.decoder", scaleDescription = "Upscaler SGSR 1.0 · 1920 × 1080 to 3840 × 2160",
        lossPercent = .12f, networkMs = 11, networkVarianceMs = 3, decodeMs = 4.2f, processingMs = 1.4f,
        hostMinMs = 3.1f, hostMaxMs = 9.8f, hostMs = 5.4f, bandwidthKb = 9800f, onePercentLowFps = 57f,
        sharpen = SharpenCost(true, .42f, .9f, .31f, .5f, "copy", 60), renderLatency = StageSummary(60, 6.1f, 9.2f, 14.8f),
    )

    @Composable
    private fun Preview(compact: Boolean, position: StatsPosition) {
        val context = LocalContext.current
        val panel = remember {
            StreamStatsPanel(ComposeView(context), FrameLayout(context), position, compact).also { it.show(STATS) }
        }
        Box(Modifier.fillMaxSize()) {
            GameScene()
            panel.Content()
        }
    }
}
