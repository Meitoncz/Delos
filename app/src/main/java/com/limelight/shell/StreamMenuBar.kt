package com.limelight.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.FocusGlow
import com.limelight.shell.ui.FocusRing
import com.limelight.shell.ui.Glyph
import com.limelight.shell.ui.GlyphButton
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.MarginBringIntoView
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.fadeEdges
import com.limelight.shell.ui.fadeMask
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.focusTrap
import com.limelight.shell.ui.glass
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx

/** Called with the help key of whatever gets the focus; the bar's help line shows its text. */
internal val LocalStreamHelp = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * One circle of the bar. [label] is also its help key. [on] is a toggle's state (filled with the accent and
 * "On" / "Off" under the label), null for actions; [state] replaces "On" / "Off" for toggles with more steps.
 */
@Immutable
internal class BarCircleSpec(
    val item: BarItem,
    val icon: String,
    val label: String,
    val on: Boolean? = null,
    val state: String? = null,
    val onClick: () -> Unit,
)

/** The soft legibility gradient under the bar: transparent at the top, a little over half black at the bottom edge. */
@Composable
internal fun StreamMenuGradient(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(StreamMenuLayout.GRADIENT_HEIGHT.px).background(
            Brush.verticalGradient(
                0f to Color.Transparent,
                .5f to Color.Black.copy(alpha = StreamMenuLayout.GRADIENT_MID_ALPHA),
                1f to Color.Black.copy(alpha = StreamMenuLayout.GRADIENT_BOTTOM_ALPHA),
            )
        )
    )
}

/**
 * A glass card: a soft shadow, the palette's background tint (the animated-background blur of the shell cannot
 * reach the stream, a separate window, so only the tint is drawn here) and a hairline border with a light top edge.
 */
@Composable
internal fun Modifier.menuCard(corner: Float): Modifier {
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(corner.px)
    val k = LocalStage.current.dpPerPx * LocalDensity.current.density
    return this
        .dropShadow(shape) {
            radius = 70f * k
            spread = -10f * k
            offset = Offset(0f, 18f * k)
            color = Color.Black
            alpha = .47f
        }
        .clip(shape)
        .glass(colors.bg.copy(alpha = .86f))
        .border(1.px, Brush.verticalGradient(listOf(Color.White.copy(alpha = .28f), colors.line)), shape)
}

/**
 * The bottom bar (docs/GUI.md, "Stream menu"): the game's name and the button hints on the left, one row of
 * circles, and under them the single help line. Always [StreamMenuLayout.BAR_HEIGHT] high.
 */
@Composable
internal fun StreamMenuBar(
    title: String,
    backLabel: String,
    circles: List<BarCircleSpec>,
    requesters: Map<BarItem, FocusRequester>,
    selected: BarItem?,
    helpTitle: String,
    helpText: String,
    width: Float,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDelosColors.current
    Column(
        modifier
            .width(width.px)
            .height(StreamMenuLayout.BAR_HEIGHT.px)
            .menuCard(34f)
            // A tap between the circles is not "outside"
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(Modifier.padding(start = 28.px, end = 28.px, top = 24.px).height(138.px)) {
            Column(Modifier.width(190.px).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                BasicText(
                    title,
                    style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.px)) {
                    BarHint(Glyph.A, "Select")
                    BarHint(Glyph.B, backLabel)
                }
            }
            Box(Modifier.padding(start = 8.px, end = 7.px).width(1.px).fillMaxHeight().background(colors.line))
            Row(Modifier.weight(1f)) {
                circles.forEach { spec ->
                    BarCircle(spec, requesters.getValue(spec.item), spec.item == selected, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(14.px))
        Box(Modifier.padding(horizontal = 28.px).fillMaxWidth().height(1.px).background(colors.line))
        HelpLine(helpTitle, helpText, Modifier.fillMaxWidth().height(47.px).padding(horizontal = 30.px))
    }
}

@Composable
private fun BarHint(glyph: Glyph, label: String) {
    val colors = LocalDelosColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
        GlyphButton(glyph, compact = true)
        BasicText(label, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 19.tpx), maxLines = 1)
    }
}

/** `#quickmenu .circle`: an 80 px glass circle, its label and, for toggles, the state under it. */
@Composable
private fun BarCircle(spec: BarCircleSpec, requester: FocusRequester, selected: Boolean, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val help = LocalStreamHelp.current
    var focused by remember { mutableStateOf(false) }
    val on = spec.on == true
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .focusRequester(requester)
                .focusEffects(spec.onClick, corner = 999f, focusScale = 1.12f, ring = FocusRing(4f, 4f), glow = FocusGlow(14f, 40f, -10f), onFocusChange = {
                    focused = it
                    if (it) help(spec.label)
                })
                .size(80.px)
                .clip(CircleShape)
                .background(if (on) colors.accent else colors.surface3)
                // The circle whose popover is open keeps a thick outline while the focus is inside the popover (dark on an accent fill)
                .border(if (selected) 3.px else 1.px, if (!selected) colors.line else if (on) colors.onAccent else colors.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(spec.icon, 36.tpx, if (on) colors.onAccent else colors.text, filled = focused || on)
        }
        BasicText(
            spec.label,
            style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 17.tpx, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.px, start = 2.px, end = 2.px),
        )
        if (spec.on != null) {
            BasicText(
                spec.state ?: if (on) "On" else "Off",
                style = TextStyle(color = if (on) colors.accent else colors.text3, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 14.5f.tpx),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.px),
            )
        }
    }
}

/**
 * The one help line of the bar: the focused item's name, then its help on a single line. A line that does not
 * fit runs through as a marquee; with reduced motion it stays still and ends in an ellipsis.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HelpLine(title: String, text: String, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val reducedMotion = LocalReducedMotion.current
    val body = remember(text) { helpLineText(text) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.px)) {
        BasicText(
            title,
            style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 360.px),
        )
        Box(
            Modifier.weight(1f)
                .then(if (reducedMotion) Modifier else Modifier.fadeMask(stage, vertical = false, startClear = 0f, startSolid = 0f, endSolid = 36f, endClear = 0f))
        ) {
            // A new text starts its marquee from the beginning
            key(title, body) {
                BasicText(
                    body,
                    style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 20.tpx),
                    maxLines = 1,
                    softWrap = false,
                    overflow = if (reducedMotion) TextOverflow.Ellipsis else TextOverflow.Clip,
                    modifier = if (reducedMotion) Modifier else Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
            }
        }
    }
}

/**
 * The popover above the bar: a glass card with a header (back chevron and [title]) and the page's [content],
 * which scrolls inside when taller than [maxHeight] (stage pixels). While [active] the focus cannot leave it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StreamMenuPopover(
    title: String,
    width: Float,
    maxHeight: Float,
    active: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val k = stage.dpPerPx * LocalDensity.current.density
    val bringIntoView = remember(k) { MarginBringIntoView(before = 28f * k, after = 40f * k) }
    val scroll = rememberScrollState()
    Column(
        modifier
            .width(width.px)
            .heightIn(max = maxHeight.px)
            .menuCard(34f)
            .focusTrap(active)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.px, end = 26.px, top = 16.px).height(54.px),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.px),
        ) {
            var backFocused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .focusEffects(onBack, corner = 999f, focusScale = 1.08f, ring = FocusRing(3f, 2f), glow = null, clickCue = Cue.BACK, onFocusChange = { backFocused = it })
                    .size(44.px)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(Symbols.CHEVRON_LEFT, 30.tpx, if (backFocused) colors.accent3 else colors.accent3.copy(alpha = .75f))
            }
            BasicText(
                title,
                style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 21.tpx),
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
                    .padding(start = 14.px, end = 14.px, top = 12.px, bottom = 14.px),
                verticalArrangement = Arrangement.spacedBy(6.px),
                content = content,
            )
        }
    }
}

/** Text choices (key combinations, server commands) as pills that wrap to the next line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PopoverPills(labels: List<String>, first: FocusRequester, command: Boolean = false, onSelect: (Int) -> Unit) {
    val colors = LocalDelosColors.current
    val help = LocalStreamHelp.current
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 6.px),
        horizontalArrangement = Arrangement.spacedBy(10.px),
        verticalArrangement = Arrangement.spacedBy(10.px),
    ) {
        labels.forEachIndexed { i, label ->
            var focused by remember { mutableStateOf(false) }
            BasicText(
                label,
                style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 20.tpx),
                modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier)
                    .focusEffects({ onSelect(i) }, corner = 999f, focusScale = 1.05f, ring = FocusRing(3f, 2f), glow = FocusGlow(10f, 28f, -10f), onFocusChange = {
                        focused = it
                        if (it) help((if (command) "Command: " else "Keys: ") + label)
                    })
                    .clip(CircleShape)
                    .background(if (focused) colors.surface3 else colors.surface2)
                    .border(1.px, colors.line, CircleShape)
                    .padding(horizontal = 22.px, vertical = 10.px),
            )
        }
    }
}
