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
import com.limelight.binding.video.PyroWaveBacklog
import com.limelight.binding.video.FrameQueueStats
import com.limelight.binding.video.SharpenCost
import com.limelight.binding.video.StageSummary
import com.limelight.shell.link.LinkReading
import com.limelight.shell.link.oneDecimal
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
class StreamStats @JvmOverloads constructor(
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
    /** PyroWave only (null otherwise): where the video thread falls behind; shown in the full panel. */
    val backlog: PyroWaveBacklog? = null,
    /** What the Sharpen only pass cost in the last window; null while that mode is not running. */
    val sharpen: SharpenCost? = null,
    /** PyroWave only: the rolling reading of the link (null for other codecs and before the first sample); shown in the Link block. */
    val link: LinkReading? = null,
    /** SGSR output only (null otherwise): the frames waiting at the start of a draw, their age and the skipped ones. */
    val frameQueue: FrameQueueStats? = null,
    /** SGSR output only: frame available to display present over the last second; null when the platform gives no present time. */
    val renderLatency: StageSummary? = null,
) {
    /** The delay summed up: network round trip, the PC's processing when it reports it, and decoding. */
    val totalMs: Float get() = networkMs + maxOf(hostMs, 0f) + decodeMs + processingMs
}

/**
 * The Link block's receive row: the app's received rate against the bitrate asked for ("212 of 225 Mb/s"). The rate is
 * the whole app's traffic (audio, control and HTTP included), not video alone, so it can read slightly high.
 */
internal fun linkReceivingText(link: LinkReading): String {
    val target = link.targetKbps / 1000
    return if (link.rxKbps < 0f) "unknown of $target Mb/s" else "${(link.rxKbps / 1000f).toInt()} of $target Mb/s"
}

/**
 * The Sharpen pass row: the pass's average and worst time per frame, then the preparation the mode adds before
 * it (the frame copy, or PyroWave's colour conversion), and a note when only the CPU time of submitting
 * the draw could be measured (no GPU timer on the device).
 */
internal fun sharpenPassText(cost: SharpenCost): String = buildString {
    append(String.format(Locale.US, "%.2f ms avg / %.2f max", cost.passAvgMs, cost.passMaxMs))
    if (cost.prepareAvgMs >= 0f) append(String.format(Locale.US, " · %s %.2f ms", cost.prepareName, cost.prepareAvgMs))
    if (!cost.gpuTimed) append(" (CPU submit)")
}

/**
 * The Frame queue row of the SGSR output stage: how many decoder frames were waiting when a draw started (1 means it
 * kept up), how long the newest had waited, and how many older frames were skipped per second because a newer one was
 * ready, and, when the GPU had too much work in flight, how many frames per second waited for it and were replaced
 * without being drawn ("12.0 GPU busy /s" means the GPU work per frame is longer than the frame interval).
 */
internal fun frameQueueText(queue: FrameQueueStats): String = buildString {
    append(String.format(Locale.US, "%.1f avg / %d max waiting", queue.pendingAvg, queue.pendingMax))
    if (queue.ageAvgMs >= 0f) append(String.format(Locale.US, " · age %.1f / %.1f ms", queue.ageAvgMs, queue.ageMaxMs))
    append(String.format(Locale.US, " · %.1f skipped /s", queue.skippedPerSecond()))
    if (queue.busySkipped > 0) append(String.format(Locale.US, " · %.1f GPU busy /s", queue.busySkippedPerSecond()))
}

/**
 * The Render latency row: how long a decoded frame took from the moment the SGSR output got it until the display
 * presented it (average / 95th percentile / maximum over the last second). Shown only when the platform reports the
 * display's present time. It does not include the controller, network, host or decoding, so it is not the delay
 * between a button press and the changed picture.
 */
internal fun renderLatencyText(stage: StageSummary): String =
    String.format(Locale.US, "%.1f / %.1f / %.1f ms avg/p95/max", stage.avgMs, stage.p95Ms, stage.maxMs)

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
            Modifier.fillMaxWidth().padding(horizontal = 40.px, vertical = if (compact) StreamMenuLayout.STATS_COMPACT_TOP.px else 32.px),
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
            modifier.padding(horizontal = 24.px, vertical = StreamMenuLayout.STATS_COMPACT_PADDING_Y.px),
            style = TextStyle(color = PANEL_TEXT, fontFamily = Figtree, fontSize = StreamMenuLayout.STATS_COMPACT_TEXT_SIZE.tpx, lineHeight = StreamMenuLayout.STATS_COMPACT_LINE_HEIGHT.em, fontFeatureSettings = "tnum"),
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
                s.frameQueue?.let { add("Frame queue" to frameQueueText(it)) }
                // PyroWave shows the pass with its backlog, the others next to the other post-processing
                if (s.backlog == null) s.sharpen?.let { add("Sharpen pass" to sharpenPassText(it)) }
                s.renderLatency?.let { add("Render latency" to renderLatencyText(it)) }
                add("Total latency" to "${format(s.totalMs)} ms")
                if (s.bandwidthKb >= 0f) add("Bandwidth" to bandwidth(s.bandwidthKb))
                add("Lost frames" to "${format(s.lossPercent, 2)} %")
                s.backlog?.let { b ->
                    add("Backlog" to "")
                    add("Stall (recv thread)" to "${format(b.stallAvgMs)} / ${format(b.stallMaxMs)} ms avg/max")
                    add("Frame age at submit" to "${format(b.submitAgeAvgMs)} / ${format(b.submitAgeMaxMs)} ms avg/max")
                    add("Receive to present" to "${format(b.presentAgeAvgMs)} / ${format(b.presentAgeMaxMs)} ms avg/max")
                    add("Partial frames" to "${format(b.partialPerSecond)} /s")
                    add("Skipped" to "${format(b.skippedPerSecond)} /s")
                    s.sharpen?.let { add("Sharpen pass" to sharpenPassText(it)) }
                }
                s.link?.let { l ->
                    add("Link" to "")
                    add("Receiving" to linkReceivingText(l))
                    add("Partial / lost" to "${oneDecimal(l.partialPerSecond)} / ${oneDecimal(l.lostPerSecond)} frames/s")
                    add("Verdict" to l.text)
                }
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
