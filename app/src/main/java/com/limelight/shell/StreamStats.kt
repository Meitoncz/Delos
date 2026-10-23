package com.limelight.shell

import android.app.Activity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
    val codec: String,
    val hdr: Boolean,
    val lossPercent: Float,
    val networkMs: Int,
    val decodeMs: Float,
    /** The PC's own processing time per frame, -1 when the PC does not report it. */
    val hostMs: Float,
)

/**
 * The statistics panel of streams started from the new UI (the prototype's `.perf`): a dark glass box
 * in a corner (top left unless chosen otherwise) with the picture (resolution, FPS, codec) and the delays (network, decoding, the PC,
 * lost frames). It lives in the stream's performance overlay container, so showing and hiding it with
 * the overlay setting or the stream menu's Performance works as before.
 */
class StreamStatsPanel private constructor(private val view: ComposeView) {
    private val stats = mutableStateOf<StreamStats?>(null)

    /** Called from the decoder's thread. */
    fun update(next: StreamStats) {
        view.post { stats.value = next }
    }

    companion object {
        @JvmStatic
        fun attach(activity: Activity, container: ViewGroup): StreamStatsPanel {
            val view = ComposeView(activity)
            val panel = StreamStatsPanel(view)
            val settings = ProfileStore.currentSettings(activity)
            val appearance = AppearanceStore(activity) { settings }.appearance.value
            val position = StatsPosition.read(settings)
            // The overlay container sits at the top of the stream; at the bottom for the bottom corners
            (container.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
                it.gravity = if (position.top) android.view.Gravity.TOP else android.view.Gravity.BOTTOM
                container.layoutParams = it
            }
            val metrics = activity.resources.displayMetrics
            // Stage pixels of the 1920 × 1080 prototype, like the rest of the new UI
            val dpPerPx = minOf(metrics.widthPixels / 1920f, metrics.heightPixels / 1080f) / metrics.density
            view.setContent {
                CompositionLocalProvider(LocalStage provides StageMetrics(dpPerPx, appearance.uiScale / 100f)) {
                    val s = panel.stats.value ?: return@CompositionLocalProvider
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 40.px, vertical = 32.px),
                        contentAlignment = if (position.start) Alignment.TopStart else Alignment.TopEnd,
                    ) {
                        Column(
                            Modifier.clip(RoundedCornerShape(18.px)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 20.px, vertical = 14.px),
                            verticalArrangement = Arrangement.spacedBy(2.px),
                        ) {
                            val style = TextStyle(color = PANEL_TEXT, fontFamily = Figtree, fontSize = 19.tpx, lineHeight = 1.5.em, fontFeatureSettings = "tnum")
                            BasicText(
                                buildAnnotatedString {
                                    append("${s.width} × ${s.height} · ")
                                    withStyle(SpanStyle(color = FPS_GREEN, fontWeight = FontWeight.SemiBold)) { append("${s.fps.toInt()} FPS") }
                                    append(" · ${s.codec}${if (s.hdr) " HDR" else ""}")
                                },
                                style = style,
                            )
                            BasicText(
                                buildString {
                                    append("Network ${s.networkMs} ms · Decode ${format(s.decodeMs)} ms")
                                    if (s.hostMs >= 0f) append(" · PC ${format(s.hostMs)} ms")
                                    append(" · Lost ${format(s.lossPercent)} %")
                                },
                                style = style,
                            )
                        }
                    }
                }
            }
            container.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return panel
        }

        private fun format(value: Float) = String.format(Locale.US, "%.1f", value)

        private val PANEL_TEXT = Color(0xFFE8F0FF)
        private val FPS_GREEN = Color(0xFF7EE2A8)
    }
}
