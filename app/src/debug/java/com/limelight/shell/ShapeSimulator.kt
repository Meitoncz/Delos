package com.limelight.shell

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import com.limelight.shell.ui.LocalCutoutPx
import com.limelight.shell.ui.StagePlanner
import kotlin.math.roundToInt

/**
 * Debug builds only: draws the whole content (the shell, or any `preview_screen`) as if the screen were another panel, started with
 * `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_shape <W>x<H>@<dpi>`
 * (the pixels and the dpi of the simulated panel, for example `1240x1080@420`) and optionally `--es preview_cutout <L>,<T>,<R>,<B>`
 * (display cutout insets in pixels of the simulated panel, drawn as a notch and fed to the stage like the system's).
 *
 * The content sits in a centered box of that pixel size (scaled down uniformly when it is larger than the real screen) with
 * `LocalDensity` set to the simulated density, so dp and stage maths come out as on that panel. Everything outside the box is black;
 * a caption at the bottom-left, outside the box, names the simulated size, density, dp size, stage and shape, so a screenshot says what it shows.
 * Combines with `preview_screen` and `preview_state` (docs/GUI.md, "Screen shapes").
 */
internal class ShapeSimulator private constructor(
    private val widthPx: Int,
    private val heightPx: Int,
    private val dpi: Int,
    private val cutout: IntRect,
) {
    @Composable
    fun Host(content: @Composable () -> Unit) {
        val real = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val captionPx = CAPTION_PX
            val availableWidth = constraints.maxWidth
            val availableHeight = (constraints.maxHeight - captionPx).coerceAtLeast(1)
            val k = minOf(1f, availableWidth / widthPx.toFloat(), availableHeight / heightPx.toFloat())
            val boxWidth = (widthPx * k).roundToInt()
            val boxHeight = (heightPx * k).roundToInt()
            // Pixels of this box are k times the simulated panel's: the density follows, so dp (and the stage) are those of the panel
            val simulated = Density(dpi / 160f * k, real.fontScale)
            val widthDp = widthPx / (dpi / 160f)
            val heightDp = heightPx / (dpi / 160f)
            val plan = StagePlanner.plan(widthDp, heightDp)
            val boxModifier = with(real) { Modifier.size(boxWidth.toDp(), boxHeight.toDp()) }
            Box(Modifier.align(Alignment.TopCenter).offset(y = with(real) { ((availableHeight - boxHeight) / 2).toDp() })) {
                Box(boxModifier.clipToBounds()) {
                    val scaledCutout = remember(cutout, k) {
                        IntRect(
                            (cutout.left * k).roundToInt(), (cutout.top * k).roundToInt(),
                            (cutout.right * k).roundToInt(), (cutout.bottom * k).roundToInt(),
                        )
                    }
                    CompositionLocalProvider(LocalDensity provides simulated, LocalCutoutPx provides scaledCutout) { content() }
                    // In the real density: the notches are sized in real pixels
                    Notches(scaledCutout, boxWidth, boxHeight, real)
                }
            }
            val caption = buildString {
                append("${widthPx}x$heightPx @ $dpi dpi  ·  ${widthDp.roundToInt()}x${heightDp.roundToInt()} dp  ·  stage ")
                append("${plan.stageWidth.roundToInt()}x${plan.stageHeight.roundToInt()} (${"%.3f".format(java.util.Locale.US, plan.dpPerPx)} dp/px)  ·  ${plan.shape}")
                if (k < 1f) append("  ·  shown at ${"%.2f".format(java.util.Locale.US, k)}x")
                if (cutout != IntRect.Zero) append("  ·  cutout ${cutout.left},${cutout.top},${cutout.right},${cutout.bottom}")
            }
            BasicText(
                caption,
                style = TextStyle(color = Color(0xFFD0D4DC), fontSize = with(real) { (captionPx * 0.5f).toSp() }),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = with(real) { 16.toDp() }, bottom = with(real) { (captionPx * 0.25f).toDp() }),
            )
        }
    }

    @Composable
    private fun BoxScope.Notches(inset: IntRect, boxWidth: Int, boxHeight: Int, real: Density) {
        val line = Modifier.border(with(real) { 2.toDp() }, Color(0x88FF40C0))
        with(real) {
            if (inset.left > 0) Box(Modifier.offset(y = (boxHeight * 0.35f).toDp()).size(inset.left.toDp(), (boxHeight * 0.3f).toDp()).background(Color.Black).then(line))
            if (inset.right > 0) Box(Modifier.align(Alignment.TopEnd).offset(y = (boxHeight * 0.35f).toDp()).size(inset.right.toDp(), (boxHeight * 0.3f).toDp()).background(Color.Black).then(line))
            if (inset.top > 0) Box(Modifier.align(Alignment.TopStart).offset(x = (boxWidth * 0.35f).toDp()).size((boxWidth * 0.3f).toDp(), inset.top.toDp()).background(Color.Black).then(line))
            if (inset.bottom > 0) Box(Modifier.align(Alignment.BottomStart).offset(x = (boxWidth * 0.35f).toDp()).size((boxWidth * 0.3f).toDp(), inset.bottom.toDp()).background(Color.Black).then(line))
        }
    }

    companion object {
        /** Height of the strip under the box that holds the caption, in real pixels. */
        private const val CAPTION_PX = 44

        private val SHAPE = Regex("""(\d{2,5})x(\d{2,5})@(\d{2,4})""")

        fun from(intent: Intent): ShapeSimulator? {
            val spec = intent.getStringExtra("preview_shape") ?: return null
            val m = SHAPE.matchEntire(spec.trim()) ?: return null
            val (w, h, dpi) = m.destructured
            val cutout = intent.getStringExtra("preview_cutout")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                ?.takeIf { it.size == 4 }?.let { IntRect(it[0], it[1], it[2], it[3]) } ?: IntRect.Zero
            return ShapeSimulator(w.toInt(), h.toInt(), dpi.toInt().coerceAtLeast(60), cutout)
        }
    }
}
