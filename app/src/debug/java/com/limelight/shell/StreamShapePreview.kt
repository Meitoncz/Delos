package com.limelight.shell

import android.content.Intent
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView
import com.limelight.binding.input.virtual_controller.VirtualController
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.ui.VideoFit

/**
 * Debug builds only: the stream's own geometry on the drawn game scene, for the layout sheets (docs/GUI.md, "Screen shapes"). Started with
 * `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_screen <name>`:
 * - `stream_osc`: the real on-screen controls (`VirtualController`, the default layout, never the user's saved one) over the video rectangle.
 *   `--es preview_saved_from <W>x<H>` first lays the default layout out on a screen of that size and carries it over to this one the way a saved
 *   layout is loaded (`OscGeometry.mapSaved`). The settings are fixed (Guide shown, 90 % opacity), not read: that would load the native library,
 *   which the emulator cannot.
 * - `stream_video`: only the video rectangle. `--es preview_state <fit|fill|stretch>` (default fit) and `--es preview_video <W>x<H>`
 *   (the stream size, default 1920x1080); `VideoFit` does the maths, the outline is what the container measures to, bars are dark.
 * Combine with `--es preview_shape <W>x<H>@<dpi>`. The controls are laid out for the pixel size of the box they are in (the simulated panel
 * scaled by the box's factor), so proportions are the panel's.
 */
internal object StreamShapePreview {
    private val SIZE = Regex("""(\d{2,5})x(\d{2,5})""")

    private fun size(intent: Intent, key: String): Pair<Int, Int>? =
        intent.getStringExtra(key)?.let { SIZE.matchEntire(it.trim()) }?.destructured?.let { (w, h) -> w.toInt() to h.toInt() }

    fun from(intent: Intent): (@Composable () -> Unit)? {
        val video = size(intent, "preview_video") ?: (1920 to 1080)
        val mode = intent.getStringExtra("preview_state") ?: "fit"
        val savedFrom = size(intent, "preview_saved_from")
        return when (intent.getStringExtra("preview_screen")) {
            "stream_osc" -> { { Controls(video, savedFrom) } }
            "stream_video" -> { { VideoOnly(video, mode) } }
            else -> null
        }
    }

    @Composable
    private fun VideoOnly(video: Pair<Int, Int>, mode: String) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            GameScene()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Video(constraints.maxWidth, constraints.maxHeight, video, mode, label = true)
            }
        }
    }

    @Composable
    private fun Video(width: Int, height: Int, video: Pair<Int, Int>, mode: String, label: Boolean) {
        val aspect = video.first.toDouble() / video.second
        val stretch = mode == "stretch"
        val fill = mode == "fill"
        val measured = if (stretch) intArrayOf(width, height) else VideoFit.measure(width, height, aspect, fill)
        val visible = if (stretch) intArrayOf(0, 0, width, height) else VideoFit.visibleRect(width, height, aspect, fill, false)
        Canvas(Modifier.fillMaxSize()) {
            // Bars: everything outside the visible video
            val bar = Color(0xEE0A0C10)
            drawRect(bar, Offset.Zero, Size(size.width, visible[1].toFloat()))
            drawRect(bar, Offset(0f, visible[3].toFloat()), Size(size.width, size.height - visible[3]))
            drawRect(bar, Offset(0f, visible[1].toFloat()), Size(visible[0].toFloat(), (visible[3] - visible[1]).toFloat()))
            drawRect(bar, Offset(visible[2].toFloat(), visible[1].toFloat()), Size(size.width - visible[2], (visible[3] - visible[1]).toFloat()))
            // What the container measures to (larger than the screen in Fill)
            val left = (width - measured[0]) / 2f
            val top = (height - measured[1]) / 2f
            drawRect(Color(0xFFFFE066), Offset(left, top), Size(measured[0].toFloat(), measured[1].toFloat()), style = Stroke(width = 3f))
            drawRect(
                Brush.linearGradient(listOf(Color(0x00FFFFFF), Color(0x33FFFFFF))),
                Offset(visible[0].toFloat(), visible[1].toFloat()),
                Size((visible[2] - visible[0]).toFloat(), (visible[3] - visible[1]).toFloat()),
            )
        }
        if (label) {
            BasicText(
                "${video.first}x${video.second} $mode  ·  container ${measured[0]}x${measured[1]}  ·  visible ${visible[2] - visible[0]}x${visible[3] - visible[1]} of ${width}x$height",
                style = TextStyle(color = Color.White),
                modifier = Modifier,
            )
        }
    }

    @Composable
    private fun Controls(video: Pair<Int, Int>, savedFrom: Pair<Int, Int>?) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            GameScene()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                Video(width, height, video, "fit", label = false)
                val context = LocalContext.current
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        FrameLayout(context).also { layout ->
                            layOutControls(layout, width, height, savedFrom)
                        }
                    },
                )
            }
        }
    }

    /** The default controls on [width] x [height], or those of [savedFrom] carried over to it. */
    private fun layOutControls(layout: FrameLayout, width: Int, height: Int, savedFrom: Pair<Int, Int>?) {
        val context = layout.context
        val config = PreferenceConfiguration().apply {
            showGuideButton = true
            oscOpacity = 90
        }
        val controller = VirtualController(null, layout, context)
        if (savedFrom == null) {
            controller.layOutDefault(width, height, config)
        } else {
            // What a layout saved on [savedFrom] stores, then loaded here
            val saved = VirtualController(null, FrameLayout(context), context)
            saved.layOutDefault(savedFrom.first, savedFrom.second, config)
            val configs = saved.elements.map { it.configuration }
            controller.layOutDefault(width, height, config)
            controller.elements.zip(configs).forEach { (element, stored) -> element.loadConfiguration(stored) }
        }
        controller.show()
    }
}
