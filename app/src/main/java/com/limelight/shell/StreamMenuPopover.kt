package com.limelight.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.EaseOut
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.FocusGlow
import com.limelight.shell.ui.FocusRing
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.MarginBringIntoView
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.fadeEdges
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.focusTrap
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.delay

/**
 * The outline of the popover: a rounded card with a rounded caret on its bottom edge, [caretX] from its left edge,
 * [caretHeight] tall and [caretWidth] wide at its base, pointing down. The card is the shape's size minus the caret.
 * All values are pixels. One path, so the fill, the border, the shadow and the clip follow the caret as well.
 */
@Immutable
private class CaretCardShape(
    private val corner: Float,
    private val caretX: Float,
    private val caretWidth: Float,
    private val caretHeight: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = (size.height - caretHeight).coerceAtLeast(0f)
        val r = minOf(corner, w / 2f, h / 2f)
        val half = caretWidth / 2f
        val cx = caretX.coerceIn(r + half, (w - r - half).coerceAtLeast(r + half))
        // The tip and the two shoulders are rounded by pulling the corners back along their edges
        val shoulder = .2f
        val path = Path().apply {
            moveTo(r, 0f)
            lineTo(w - r, 0f)
            arcTo(Rect(w - 2 * r, 0f, w, 2 * r), -90f, 90f, false)
            lineTo(w, h - r)
            arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
            lineTo(cx + half + half * .5f, h)
            quadraticTo(cx + half, h, cx + half - half * shoulder, h + caretHeight * shoulder)
            lineTo(cx + half * shoulder, h + caretHeight * (1f - shoulder))
            quadraticTo(cx, h + caretHeight, cx - half * shoulder, h + caretHeight * (1f - shoulder))
            lineTo(cx - half + half * shoulder, h + caretHeight * shoulder)
            quadraticTo(cx - half, h, cx - half - half * .5f, h)
            lineTo(r, h)
            arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
            lineTo(0f, r)
            arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * The popover's chrome: a glass card [width] wide and at most [maxHeight] tall (stage pixels, the caret included)
 * with a caret on its bottom edge [caretX] from its left edge, which is where it points at the circle that opened it.
 * While [active] the focus cannot leave it; a tap inside is not "outside". Its [content] is the page.
 */
@Composable
internal fun StreamMenuPopover(
    width: Float,
    caretX: Float,
    maxHeight: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val k = LocalStage.current.dpPerPx * LocalDensity.current.density
    val shape = remember(width, caretX, k) {
        CaretCardShape(StreamMenuLayout.POPOVER_CORNER * k, caretX * k, StreamMenuLayout.CARET_WIDTH * k, StreamMenuLayout.CARET_HEIGHT * k)
    }
    Box(
        modifier
            .width(width.px)
            .heightIn(max = maxHeight.px)
            .menuGlass(shape)
            .focusTrap(active)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Box(Modifier.padding(bottom = StreamMenuLayout.CARET_HEIGHT.px)) { content() }
    }
}

/**
 * One page of the popover: the header (back chevron and [title]), the page's rows, which scroll with faded edges
 * only when they really do not fit, and under them a footer of [footerLines] lines with the complete help of the
 * focused row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PopoverPage(
    title: String,
    footer: String,
    footerLines: Int,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val k = stage.dpPerPx * LocalDensity.current.density
    val bringIntoView = remember(k) { MarginBringIntoView(before = 28f * k, after = 40f * k) }
    val scroll = rememberScrollState()
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.px, end = StreamMenuLayout.POPOVER_PADDING_X.px, top = StreamMenuLayout.POPOVER_PADDING_TOP.px).height(StreamMenuLayout.POPOVER_HEADER_HEIGHT.px),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.px),
        ) {
            var backFocused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .focusEffects(onBack, corner = 999f, focusScale = 1.08f, ring = FocusRing(3f, 2f), glow = null, clickCue = Cue.BACK, onFocusChange = { backFocused = it })
                    .size(56.px)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(Symbols.CHEVRON_LEFT, 36.tpx, if (backFocused) colors.accent3 else colors.accent3.copy(alpha = .75f))
            }
            BasicText(
                title,
                style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 24.tpx),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .then(if (scroll.maxValue > 0) Modifier.fadeEdges(stage, top = 12f, bottom = 28f) else Modifier)
                    .verticalScroll(scroll)
                    .padding(start = StreamMenuLayout.POPOVER_PADDING_X.px, end = StreamMenuLayout.POPOVER_PADDING_X.px, top = 6.px, bottom = 8.px),
                verticalArrangement = Arrangement.spacedBy(StreamMenuLayout.ROW_SPACING.px),
                content = content,
            )
        }
        PopoverFooter(footer, footerLines)
    }
}

/** The help of the focused row: a hairline and a box of exactly [lines] lines of the largest text scale. A new text fades in. */
@Composable
private fun PopoverFooter(text: String, lines: Int) {
    val colors = LocalDelosColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = StreamMenuLayout.POPOVER_PADDING_X.px)) {
        Box(Modifier.fillMaxWidth().height(1.px).background(colors.line))
        AnimatedContent(
            targetState = text,
            modifier = Modifier.fillMaxWidth().padding(top = 12.px, bottom = 14.px).height(popoverFooterTextHeight(lines).px),
            contentAlignment = Alignment.TopStart,
            transitionSpec = { fadeIn(tween(180, delayMillis = 50)) togetherWith fadeOut(tween(100)) },
            label = "popoverHelp",
        ) { help ->
            BasicText(
                help,
                style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = StreamMenuLayout.FOOTER_TEXT_SIZE.tpx, lineHeight = StreamMenuLayout.FOOTER_LINE_HEIGHT.em),
            )
        }
    }
}

/** Counts the rows of one page as they are composed, so each one knows its place in the staggered entrance. */
internal class RowStagger(val enabled: Boolean) {
    private var next = 0
    fun take(): Int = next++
}

internal val LocalRowStagger = staticCompositionLocalOf { RowStagger(enabled = false) }

/**
 * The rows of a page slide up 12 px and fade in one after the other, 25 ms apart (the tenth and every later one
 * together), once the popover has begun to open. With reduced motion they only fade; without [RowStagger.enabled]
 * (a menu that is already open) they are simply there.
 */
@Composable
internal fun Modifier.rowEntrance(): Modifier {
    val stagger = LocalRowStagger.current
    val reducedMotion = LocalReducedMotion.current
    val index = remember { stagger.take() }
    val appear = remember { Animatable(if (stagger.enabled) 0f else 1f) }
    val rise = 12f * LocalStage.current.dpPerPx * LocalDensity.current.density
    LaunchedEffect(Unit) {
        if (stagger.enabled) {
            delay(60L + minOf(index, 10) * 25L)
            appear.animateTo(1f, tween(280, easing = EaseOut))
        }
    }
    return graphicsLayer {
        alpha = appear.value
        translationY = if (reducedMotion) 0f else (1f - appear.value) * rise
    }
}

/** Text choices (key combinations, server commands) as pills that wrap to the next line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PopoverPills(labels: List<String>, first: FocusRequester, command: Boolean = false, onSelect: (Int) -> Unit) {
    val colors = LocalDelosColors.current
    val help = LocalStreamHelp.current
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.px),
        horizontalArrangement = Arrangement.spacedBy(14.px),
        verticalArrangement = Arrangement.spacedBy(14.px),
    ) {
        labels.forEachIndexed { i, label ->
            var focused by remember { mutableStateOf(false) }
            BasicText(
                label,
                style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx),
                modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier)
                    .rowEntrance()
                    .focusEffects({ onSelect(i) }, corner = 999f, focusScale = 1.05f, ring = FocusRing(3f, 2f), glow = FocusGlow(10f, 28f, -10f), onFocusChange = {
                        focused = it
                        if (it) help((if (command) "Command: " else "Keys: ") + label)
                    })
                    .clip(CircleShape)
                    .background(if (focused) colors.surface3 else colors.surface2)
                    .border(1.px, colors.line, CircleShape)
                    .padding(horizontal = 26.px, vertical = 13.px),
            )
        }
    }
}
