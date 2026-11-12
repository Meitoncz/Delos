package com.limelight.shell

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import com.limelight.shell.companion.CompanionContent
import com.limelight.shell.companion.CompanionState
import com.limelight.shell.companion.CompanionTheme
import com.limelight.shell.settings.Appearance
import com.limelight.shell.ui.LocalCutoutPx
import com.limelight.shell.ui.StagePlanner
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * Debug builds only: draws the whole content (the shell, or any `preview_screen`) as if the screen were another panel, chosen in Settings →
 * About → Debug info → Screen shape simulator ([ShapeSimulatorMenu], docs/GUI.md "Screen shapes") or started with
 * `adb shell am start -n io.github.meitoncz.delos.debug/com.limelight.shell.ShellActivity --es preview_shape <W>x<H>@<dpi>`
 * (the pixels and the dpi of the simulated panel, for example `1240x1080@420`) and optionally `--es preview_cutout <L>,<T>,<R>,<B>`
 * (display cutout insets in pixels of the simulated panel, drawn as a notch and fed to the stage like the system's).
 *
 * The content sits in a centered box of that pixel size (scaled down uniformly when it is larger than the real screen) with
 * `LocalDensity` set to the simulated density, so dp and stage maths come out as on that panel. Everything outside the box is black;
 * a strip at the bottom, outside the box, names the simulated size, density, dp size, stage and shape and carries a chip that says a simulation
 * is on (a tap on it ends it), so a screenshot says what it shows and it is never forgotten. A simulated companion display is drawn next to
 * (or under) the main panel, scaled with it, as the second window of a dual-screen device would be (the same content as `CompanionActivity`). While nothing is simulated the same boxes fill the screen without
 * any change of size or density, so the content keeps its state when a simulation starts or ends.
 * Combines with `preview_screen` and `preview_state` (docs/GUI.md, "Screen shapes").
 */
internal class ShapeSimulator private constructor(private val fake: CompanionState?) {
    @Composable
    fun Host(shell: ShellState, appearance: StateFlow<Appearance>, content: @Composable () -> Unit) {
        val real = LocalDensity.current
        val context = LocalContext.current
        val main = ShapeSimulation.main
        val cutout = ShapeSimulation.cutout
        val companion = ShapeSimulation.companion
        val active = ShapeSimulation.active
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val stripPx = if (active) STRIP_PX else 0
            val availableWidth = constraints.maxWidth
            val availableHeight = (constraints.maxHeight - stripPx).coerceAtLeast(1)
            val realDpi = real.density * 160f
            // The real screen is the main panel when none is simulated (a cutout or a companion can still be on)
            val mainWidth = main?.widthPx ?: availableWidth
            val mainHeight = main?.heightPx ?: availableHeight
            val mainDpi = main?.dpi?.toFloat() ?: realDpi
            // Two panels go side by side or stacked, whichever shows them larger; labels and a divider sit between them
            val label = if (companion != null) LABEL_PX else 0
            val gap = if (companion != null) GAP_PX else 0
            val cw = companion?.widthPx ?: 0
            val ch = companion?.heightPx ?: 0
            val kSide = minOf(1f, (availableWidth - gap) / (mainWidth + cw).toFloat(), (availableHeight - label) / maxOf(mainHeight, ch).toFloat())
            val kStack = minOf(1f, availableWidth / maxOf(mainWidth, cw).toFloat(), (availableHeight - gap - 2 * label) / (mainHeight + ch).toFloat())
            val sideBySide = companion == null || kSide >= kStack
            val k = if (sideBySide) kSide else kStack
            val boxWidth = (mainWidth * k).roundToInt()
            val boxHeight = (mainHeight * k).roundToInt()
            val companionWidth = (cw * k).roundToInt()
            val companionHeight = (ch * k).roundToInt()
            // Pixels of a box are k times the simulated panel's: the density follows, so dp (and the stage) are those of the panel
            val simulated = Density(mainDpi / 160f * k, real.fontScale)
            val widthDp = mainWidth / (mainDpi / 160f)
            val heightDp = mainHeight / (mainDpi / 160f)
            val plan = StagePlanner.plan(widthDp, heightDp)
            val scaledCutout = IntRect(
                (cutout.inset.left * k).roundToInt(), (cutout.inset.top * k).roundToInt(),
                (cutout.inset.right * k).roundToInt(), (cutout.inset.bottom * k).roundToInt(),
            )
            Layout(
                modifier = Modifier.align(Alignment.TopStart),
                content = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (companion != null) PanelLabel("MAIN  ·  ${main?.label ?: "Real screen"}  ·  ${mainWidth}x$mainHeight @ ${mainDpi.roundToInt()}", boxWidth, real)
                        Box(Modifier.pixelSize(boxWidth, boxHeight).clipToBounds()) {
                            // The real screen's own cutout stays in force while no panel and no cutout is simulated
                            val cutoutPx = if (main == null && cutout == SimCutout.NONE) null else scaledCutout
                            CompositionLocalProvider(LocalDensity provides simulated, LocalCutoutPx provides cutoutPx) { content() }
                            // In the real density: the notches are sized in real pixels
                            Notches(scaledCutout, cutout.widthFraction, boxWidth, boxHeight, real)
                        }
                    }
                    if (companion != null) {
                        Box(Modifier.background(DIVIDER))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PanelLabel("COMPANION  ·  ${companion.label}  ·  ${companion.sizeLabel}", companionWidth, real)
                            // The second window of a dual-screen device: the same content as CompanionActivity; touch only, key focus stays with the main panel
                            Box(Modifier.pixelSize(companionWidth, companionHeight).clipToBounds().focusProperties { canFocus = false }) {
                                val companionDensity = Density(companion.dpi / 160f * k, real.fontScale)
                                CompositionLocalProvider(LocalDensity provides companionDensity, LocalCutoutPx provides IntRect.Zero) {
                                    CompanionTheme({ appearance }) {
                                        CompanionContent(
                                            fake ?: (CompanionPreview.liveShell ?: shell).companionState(),
                                            inWindow = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            ) { measurables, constraints ->
                val panels = listOf(measurables[0], measurables.getOrNull(2)).filterNotNull().map { it.measure(Constraints()) }
                val width = constraints.maxWidth
                val height = availableHeight
                val total = if (sideBySide) panels.sumOf { it.width } + gap else panels.maxOf { it.width }
                val totalHeight = if (sideBySide) panels.maxOf { it.height } else panels.sumOf { it.height } + gap
                val divider = measurables.getOrNull(1)?.measure(
                    if (sideBySide) Constraints.fixed(DIVIDER_PX, totalHeight) else Constraints.fixed(total, DIVIDER_PX),
                )
                layout(width, height) {
                    val left = (width - total) / 2
                    val top = (height - totalHeight) / 2
                    var x = left
                    var y = top
                    panels.forEachIndexed { i, panel ->
                        if (sideBySide) panel.place(x, top + (totalHeight - panel.height) / 2) else panel.place(left + (total - panel.width) / 2, y)
                        if (sideBySide) x += panel.width + gap else y += panel.height + gap
                        if (i == 0 && divider != null) {
                            if (sideBySide) divider.place(x - gap / 2 - DIVIDER_PX / 2, top) else divider.place(left, y - gap / 2 - DIVIDER_PX / 2)
                        }
                    }
                }
            }
            if (active) {
                val caption = buildString {
                    append("${mainWidth}x$mainHeight @ ${mainDpi.roundToInt()} dpi  ·  ${widthDp.roundToInt()}x${heightDp.roundToInt()} dp  ·  stage ")
                    append("${plan.stageWidth.roundToInt()}x${plan.stageHeight.roundToInt()} (${"%.3f".format(java.util.Locale.US, plan.dpPerPx)} dp/px)  ·  ${plan.shape}")
                    if (k < 1f) append("  ·  shown at ${"%.2f".format(java.util.Locale.US, k)}x")
                    if (cutout != SimCutout.NONE) append("  ·  cutout ${cutout.inset.left},${cutout.inset.top},${cutout.inset.right},${cutout.inset.bottom}")
                    if (companion != null) append("  ·  companion ${companion.sizeLabel}, ${if (sideBySide) "side by side" else "stacked"}")
                }
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(with(real) { STRIP_PX.toDp() }).padding(horizontal = with(real) { 16.toDp() }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(with(real) { 16.toDp() }),
                ) {
                    BasicText(
                        caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = Color(0xFFD0D4DC), fontSize = with(real) { (STRIP_PX * 0.5f).toSp() }),
                        modifier = Modifier.weight(1f),
                    )
                    Chip(if (companion != null) "Dual screen" else main?.label ?: "Real screen", real) { stop(context) }
                }
            }
        }
    }

    /** The name of a panel, above it, in the real density. */
    @Composable
    private fun PanelLabel(text: String, width: Int, real: Density) {
        Box(Modifier.pixelSize(width, LABEL_PX), contentAlignment = Alignment.Center) {
            BasicText(
                text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color(0xFFFF8AD8), fontWeight = FontWeight.SemiBold, fontSize = with(real) { (LABEL_PX * 0.55f).toSp() }),
            )
        }
    }

    /** The small chip that says a simulation is on; a tap on it ends the simulation (never focusable, so it cannot take a controller's focus). */
    @Composable
    private fun Chip(name: String, real: Density, onTap: () -> Unit) {
        val shape = RoundedCornerShape(with(real) { STRIP_PX.toDp() / 2 })
        with(real) {
            Box(
                Modifier.clip(shape).background(Color(0xFFFF40C0)).pointerInput(Unit) { detectTapGestures { onTap() } }
                    .padding(horizontal = 14.toDp(), vertical = 4.toDp()),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "SIMULATED  ·  $name  ·  tap to end", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Color.Black, fontWeight = FontWeight.Bold, fontSize = (STRIP_PX * 0.45f).toSp()),
                )
            }
        }
    }

    @Composable
    private fun BoxScope.Notches(inset: IntRect, fraction: Float, boxWidth: Int, boxHeight: Int, real: Density) {
        val line = Modifier.border(with(real) { 2.toDp() }, Color(0x88FF40C0))
        with(real) {
            if (inset.left > 0) Box(Modifier.offset(y = (boxHeight * 0.35f).toDp()).size(inset.left.toDp(), (boxHeight * 0.3f).toDp()).background(Color.Black).then(line))
            if (inset.right > 0) Box(Modifier.align(Alignment.TopEnd).offset(y = (boxHeight * 0.35f).toDp()).size(inset.right.toDp(), (boxHeight * 0.3f).toDp()).background(Color.Black).then(line))
            if (inset.top > 0) Box(Modifier.align(Alignment.TopCenter).size((boxWidth * fraction).toDp(), inset.top.toDp()).background(Color.Black).then(line))
            if (inset.bottom > 0) Box(Modifier.align(Alignment.BottomCenter).size((boxWidth * fraction).toDp(), inset.bottom.toDp()).background(Color.Black).then(line))
        }
    }

    companion object {
        /** Height of the strip under the boxes that holds the caption and the chip, in real pixels. */
        private const val STRIP_PX = 44

        /** Space between the two panels (the divider sits in the middle of it), the divider's thickness and the height of a panel's label, in real pixels. */
        private const val GAP_PX = 14
        private const val DIVIDER_PX = 2
        private const val LABEL_PX = 26
        private val DIVIDER = Color(0xFFFF40C0)

        /** The simulator of this launch: always there in a debug build (it draws nothing extra while nothing is simulated); the adb extras and the remembered choice are read here. */
        fun from(context: Context, intent: Intent): ShapeSimulator {
            ShapeSimulation.load(context)
            ShapeSimulation.applyIntent(intent)
            return ShapeSimulator(CompanionPreview.fakeState(intent))
        }

        private fun stop(context: Context) {
            ShapeSimulation.setMain(context, null, SimCutout.NONE)
            ShapeSimulation.setCompanion(context, null)
        }

        /** A box of exactly [width] x [height] pixels, whatever the density rounds to. */
        private fun Modifier.pixelSize(width: Int, height: Int) = layout { measurable, _ ->
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(width, height) { placeable.place(0, 0) }
        }
    }
}
