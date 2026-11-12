package com.limelight.shell.companion

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import kotlin.math.floor

/** How a line of the artwork's metadata is drawn: the color it has in the prototype (white, sea, muted, gold). */
internal enum class ArtTone { NORMAL, SEA, MUTED, GOLD }

/**
 * One line of the metadata beside the picture. [text] is the whole line, [short] the one a short panel uses ("10 Nov 2026" for "Added 10 Nov 2026");
 * [priority] ranks the lines when the column has room for fewer (the lowest goes first, the order of display stays).
 */
@Immutable
internal data class ArtLine(val text: String, val short: String, val tone: ArtTone, val priority: Int)

/** The lines that stay when only [capacity] fit: the highest priorities, in the order they are shown. */
internal fun keepTop(lines: List<ArtLine>, capacity: Int): List<ArtLine> {
    if (capacity <= 0) return emptyList()
    if (lines.size <= capacity) return lines
    val dropped = lines.withIndex().sortedWith(compareBy({ it.value.priority }, { -it.index })).take(lines.size - capacity).map { it.index }.toSet()
    return lines.filterIndexed { i, _ -> i !in dropped }
}

/** The picture of the artwork page: the biggest box of the picture's own [ratio] (width / height) that fits what the page leaves it. */
internal object ArtworkFit {
    /** The picture takes at most this share of the row's width: the metadata has the rest (the prototype's `max-width: 45%`). */
    const val WIDTH_SHARE = .45f

    /** The box (width, height) for [ratio] in [availWidth] x [availHeight]: never larger than either, never cropped, the ratio kept. Zero for an empty area or a bad ratio. */
    fun box(availWidth: Float, availHeight: Float, ratio: Float, share: Float = WIDTH_SHARE): Pair<Float, Float> {
        if (availWidth <= 0f || availHeight <= 0f || ratio <= 0f || !ratio.isFinite()) return 0f to 0f
        val width = minOf(availWidth * share, availHeight * ratio)
        return width to width / ratio
    }

    /** How many lines of [lineHeight] with [gap] between them fit [height]. */
    fun lineCapacity(height: Float, lineHeight: Float, gap: Float): Int =
        if (height <= 0f || lineHeight <= 0f) 0 else floor((height + gap) / (lineHeight + gap)).toInt().coerceAtLeast(0)
}

/**
 * The image the artwork screen has focused (docs/DUAL_SCREEN.md): the picture the main display already decoded, at its own ratio and never
 * cropped, beside what is known about it, and "Use this image", which runs what A runs on the tile ([onUse]); [inUse] disables the button because
 * the image is the one in use already.
 */
@Immutable
internal data class ArtworkPage(
    val title: String,
    val image: ImageBitmap?,
    /** The ratio of a picture that has not arrived yet (the tab's own shape); the bitmap's own ratio wins. */
    val placeholderRatio: Float,
    val lines: List<ArtLine>,
    val inUse: Boolean,
    /** What A does on the tile; null for the built-in artwork of a utility app, which is read-only (no button). */
    val onUse: (() -> Unit)?,
) : CompanionPage {
    // One key for every image: moving the focus updates the page, it does not fade
    override val key: String get() = "artwork"

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        val stage = LocalStage.current
        val ratio = image?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: placeholderRatio
        PageCard { fit ->
            val heading = CompanionFit.title(title, stringResource(R.string.companion_screen_artwork), fit.budget.innerWidth - 80f, fit.metrics)
            PageHeading(Symbols.IMAGE, heading, stringResource(R.string.companion_art_subtitle))
            BoxWithConstraints(Modifier.fillMaxWidth().fills(minHeight = 64.px)) {
                val unit = 1.px.value
                val width = maxWidth.value / unit
                val height = maxHeight.value / unit
                val (boxWidth, boxHeight) = ArtworkFit.box(width, height, ratio)
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(GAP.px), verticalAlignment = Alignment.CenterVertically) {
                    val shape = RoundedCornerShape(16.px)
                    if (image != null) {
                        Image(image, null, Modifier.size(boxWidth.px, boxHeight.px).clip(shape), contentScale = ContentScale.Fit)
                    } else {
                        Box(Modifier.size(boxWidth.px, boxHeight.px).clip(shape).background(colors.surface3), contentAlignment = Alignment.Center) {
                            Symbol(Symbols.IMAGE, 64.tpx, colors.text3)
                        }
                    }
                    val metaWidth = (width - boxWidth - GAP) / stage.textScale
                    val lineHeight = MIN_TEXT * LINE * stage.textScale
                    val capacity = ArtworkFit.lineCapacity(height, lineHeight, LINE_GAP)
                    // A line that would wrap is left out, never cut: the column is as wide as the picture leaves it
                    val shown = keepTop(lines.map { if (fit.budget.roomy) it else it.copy(text = it.short) }.filter { fit.metrics.lines(it.text, metaWidth) <= 1 }, capacity)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LINE_GAP.px, Alignment.CenterVertically)) {
                        shown.forEach { BodyText(it.text, color = it.tone.color(colors.text, colors.accent2, colors.text2, colors.accent), maxLines = 1) }
                    }
                }
            }
            onUse?.let { use -> ActionRow(listOf(PageAction(stringResource(R.string.companion_art_use), use, ButtonStyle.PRIMARY, enabled = !inUse))) }
        }
    }

    private fun ArtTone.color(normal: Color, sea: Color, muted: Color, gold: Color): Color = when (this) {
        ArtTone.NORMAL -> normal
        ArtTone.SEA -> sea
        ArtTone.MUTED -> muted
        ArtTone.GOLD -> gold
    }

    private companion object {
        const val GAP = 24f
        const val LINE = 1.2f
        const val LINE_GAP = 8f
    }
}
