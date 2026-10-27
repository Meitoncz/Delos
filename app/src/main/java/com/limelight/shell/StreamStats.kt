package com.limelight.shell

import android.app.Activity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import com.limelight.shell.data.ProfileStore
import com.limelight.shell.settings.AppearanceStore
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.StageMetrics
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import java.util.Locale

/** Where the statistics panel sits (Settings → Display → Statistics position); kept with the profile. */
enum class StatsPosition(val label: String, val top: Boolean, val start: Boolean) {
    TOP_LEFT("Top left", true, true), TOP_RIGHT("Top right", true, false),
    BOTTOM_LEFT("Bottom left", false, true), BOTTOM_RIGHT("Bottom right", false, false);

    companion object {
        const val KEY = "ui_stats_position"

        fun read(prefs: android.content.SharedPreferences): StatsPosition =
            entries.firstOrNull { it.name == prefs.getString(KEY, null) } ?: TOP_LEFT
    }
}

/** The stream's numbers for the Delos statistics panel, filled by the decoder about once a second. */
class StreamStats(
    val width: Int,
    val height: Int,
    val fps: Float,
    /** Frames arriving from the network and frames drawn, per second. */
    val incomingFps: Float,
    val renderingFps: Float,
    val codec: String,
    val hdr: Boolean,
    /** The decoder's name on the device. */
    val decoder: String,
    /** Actual active SGSR target, null while post-processing is off. */
    val scaleDescription: String?,
    val lossPercent: Float,
    val networkMs: Int,
    val networkVarianceMs: Int,
    val decodeMs: Float,
    /** GPU post-processing after decode (upscaling and final presentation shader). */
    val processingMs: Float,
    /** The PC's own processing time per frame; all -1 when the PC does not report it. */
    val hostMinMs: Float,
    val hostMaxMs: Float,
    val hostMs: Float,
    /** Data received and sent in KB per second, -1 when the device does not count it. */
    val bandwidthKb: Float,
    /** The frame rate of the slowest 1% of recent frames, -1 while too few to tell. */
    val onePercentLowFps: Float = -1f,
) {
    /** The delay summed up: network round trip, the PC's processing when it reports it, and decoding. */
    val totalMs: Float get() = networkMs + maxOf(hostMs, 0f) + decodeMs + processingMs
}

/**
 * The statistics panel of streams started from the new UI (the prototype's `.perf`), with everything the
 * classic overlay shows. Full: a dark glass box in a corner (top left unless chosen otherwise) with the
 * picture (resolution, FPS, codec) and a list of the details. Compact (the classic Lite mode): one line
 * at the top center. It lives in the stream's performance overlay container, so showing and hiding it
 * with the overlay setting or the stream menu's Overlay works as before.
 */
class StreamStatsPanel private constructor(
    private val view: ComposeView,
    private val container: ViewGroup,
    private val position: StatsPosition,
    compact: Boolean,
) {
    private val stats = mutableStateOf<StreamStats?>(null)
    private val compactState = mutableStateOf(compact)

    /** Called from the decoder's thread. */
    fun update(next: StreamStats) {
        view.post { stats.value = next }
    }

    /** Switches between the full panel and the compact line (the stream menu's Overlay). */
    fun setCompact(compact: Boolean) {
        compactState.value = compact
        applyGravity()
    }

    /** The overlay container sits at the top of the stream; at the bottom for the full panel's bottom corners. */
    private fun applyGravity() {
        (container.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
            it.gravity = if (position.top || compactState.value) android.view.Gravity.TOP else android.view.Gravity.BOTTOM
            container.layoutParams = it
        }
    }

    @Composable
    private fun Content() {
        val s = stats.value ?: return
        val compact = compactState.value
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 40.px, vertical = if (compact) 20.px else 32.px),
            contentAlignment = when {
                compact -> Alignment.TopCenter
                position.start -> Alignment.TopStart
                else -> Alignment.TopEnd
            },
        ) {
            val glass = Modifier.clip(RoundedCornerShape(if (compact) 999.px else 18.px))
                .background(Color.Black.copy(alpha = .55f))
                .border(1.px, Color.White.copy(alpha = .10f), RoundedCornerShape(if (compact) 999.px else 18.px))
            if (compact) CompactLine(s, glass) else FullPanel(s, glass)
        }
    }

    /** The classic Lite line: FPS, bandwidth, network and decoding delay, lost frames. */
    @Composable
    private fun CompactLine(s: StreamStats, modifier: Modifier) {
        BasicText(
            buildAnnotatedString {
                withStyle(SpanStyle(color = FPS_GREEN, fontWeight = FontWeight.SemiBold)) { append("${s.fps.toInt()} FPS") }
                if (s.onePercentLowFps >= 0f) append("  ·  1% low ${s.onePercentLowFps.toInt()}")
                if (s.bandwidthKb >= 0f) append("  ·  ${bandwidth(s.bandwidthKb)}")
                append("  ·  Network ${s.networkMs} ms  ·  Decode ${format(s.decodeMs)} ms  ·  Total ${format(s.totalMs)} ms  ·  Lost ${format(s.lossPercent)} %")
            },
            modifier.padding(horizontal = 24.px, vertical = 8.px),
            style = TextStyle(color = PANEL_TEXT, fontFamily = Figtree, fontSize = 18.tpx, lineHeight = 1.4.em, fontFeatureSettings = "tnum"),
        )
    }

    @Composable
    private fun FullPanel(s: StreamStats, modifier: Modifier) {
        Column(modifier.padding(horizontal = 22.px, vertical = 16.px), verticalArrangement = Arrangement.spacedBy(10.px)) {
            BasicText(
                buildAnnotatedString {
                    append("${s.width} × ${s.height} · ")
                    withStyle(SpanStyle(color = FPS_GREEN, fontWeight = FontWeight.SemiBold)) { append("${format(s.fps)} FPS") }
                    append(" · ${s.codec}${if (s.hdr) " HDR" else ""}")
                },
                style = TextStyle(color = PANEL_TEXT, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx, fontFeatureSettings = "tnum"),
            )
            if (s.scaleDescription != null) {
                BasicText(
                    s.scaleDescription,
                    style = TextStyle(color = PANEL_TEXT.copy(alpha = .75f), fontFamily = Figtree,
                        fontSize = 17.tpx, fontFeatureSettings = "tnum"),
                )
            }
            val rows = buildList {
                add("Decoder" to s.decoder)
                add("Incoming" to "${format(s.incomingFps)} FPS")
                add("Rendering" to "${format(s.renderingFps)} FPS")
                if (s.onePercentLowFps >= 0f) add("1% low" to "${format(s.onePercentLowFps)} FPS")
                add("Network" to "${s.networkMs} ms ± ${s.networkVarianceMs} ms")
                if (s.hostMs >= 0f) add("PC processing" to "${format(s.hostMs)} ms (${format(s.hostMinMs)}–${format(s.hostMaxMs)})")
                add("Decoding" to "${format(s.decodeMs, 2)} ms")
                if (s.processingMs > 0f) add("Upscaling" to "${format(s.processingMs, 2)} ms")
                add("Total latency" to "${format(s.totalMs)} ms")
                if (s.bandwidthKb >= 0f) add("Bandwidth" to bandwidth(s.bandwidthKb))
                add("Lost frames" to "${format(s.lossPercent, 2)} %")
            }
            val label = TextStyle(color = PANEL_TEXT.copy(alpha = .6f), fontFamily = Figtree, fontSize = 17.tpx, lineHeight = 1.5.em)
            val value = label.copy(color = PANEL_TEXT, fontFeatureSettings = "tnum")
            Row(horizontalArrangement = Arrangement.spacedBy(24.px)) {
                Column { rows.forEach { BasicText(it.first, style = label) } }
                Column { rows.forEach { BasicText(it.second, style = value) } }
            }
        }
    }

    companion object {
        @JvmStatic
        fun attach(activity: Activity, container: ViewGroup, compact: Boolean): StreamStatsPanel {
            val view = ComposeView(activity)
            val settings = ProfileStore.currentSettings(activity)
            val appearance = AppearanceStore(activity) { settings }.appearance.value
            val panel = StreamStatsPanel(view, container, StatsPosition.read(settings), compact)
            panel.applyGravity()
            val metrics = activity.resources.displayMetrics
            // Stage pixels of the 1920 × 1080 prototype, like the rest of the new UI
            val dpPerPx = minOf(metrics.widthPixels / 1920f, metrics.heightPixels / 1080f) / metrics.density
            view.setContent {
                CompositionLocalProvider(LocalStage provides StageMetrics(dpPerPx, appearance.uiScale / 100f)) {
                    panel.Content()
                }
            }
            container.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return panel
        }

        private fun format(value: Float, decimals: Int = 1) = String.format(Locale.US, "%.${decimals}f", value)

        // Bytes like the classic overlay, and bits like bitrate settings (the KB are KiB)
        private fun bandwidth(kb: Float): String {
            val megabits = kb * 1024f * 8f / 1_000_000f
            val bytes = if (kb >= 1000f) "${format(kb / 1024f)} MB/s" else "${kb.toInt()} KB/s"
            val bits = if (megabits >= 100f) "${megabits.toInt()} Mb/s" else "${format(megabits)} Mb/s"
            return "$bytes · $bits"
        }

        private val PANEL_TEXT = Color(0xFFE8F0FF)
        private val FPS_GREEN = Color(0xFF7EE2A8)
    }
}
