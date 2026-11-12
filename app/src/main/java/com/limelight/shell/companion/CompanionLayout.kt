package com.limelight.shell.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.shell.settings.ImpactLevel
import com.limelight.shell.settings.SettingImpact
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.IMPACT_AXES
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.LowRed
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.impactColor
import com.limelight.shell.ui.levels
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx

/*
 * The building blocks of the companion's pages (docs/DUAL_SCREEN.md, "No scrolling, ever"): a glass card whose blocks are laid out by [FitColumn], which
 * measures them, drops the least important optional ones when they do not all fit and gives what is left to a filler or a flexible block, so a page
 * can never overflow whatever the panel, the language or the interface scale. The budgets of CompanionFit pick the text first; this is the net under it.
 */

/** Priority of a block that is never dropped. */
internal const val MANDATORY = Int.MAX_VALUE

/** What a block says to [FitColumn]: its [priority] (lower goes first when the page is too tall) and whether it takes the room that is left. */
internal class FitData(val priority: Int, val flex: Boolean, val minHeight: Dp, val weight: Float)

private class FitModifier(private val data: FitData) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = data
}

internal interface FitScope {
    /** The block is left out when the page is too tall; blocks of a lower [priority] go before it. */
    fun Modifier.optional(priority: Int): Modifier

    /** The block takes the height the fixed blocks leave (at least [minHeight], shared by [weight]); [priority] as in [optional], mandatory by default. */
    fun Modifier.fills(minHeight: Dp = 0.dp, weight: Float = 1f, priority: Int = MANDATORY): Modifier
}

private object FitScopeImpl : FitScope {
    override fun Modifier.optional(priority: Int): Modifier = this.then(FitModifier(FitData(priority, false, 0.dp, 0f)))
    override fun Modifier.fills(minHeight: Dp, weight: Float, priority: Int): Modifier = this.then(FitModifier(FitData(priority, true, minHeight, weight)))
}

/** The empty block that takes the height nothing else wants: it pushes what follows to the bottom of the card. */
@Composable
internal fun FitScope.Filler() {
    Box(Modifier.fills())
}

/**
 * A column that never overflows: the fixed blocks are measured at their natural height, the optional ones with the lowest priority are dropped
 * while the sum does not fit the height it gets, and what is left goes to the flexible blocks ([FitScope.fills], a [Filler] is one).
 */
@Composable
internal fun FitColumn(modifier: Modifier = Modifier, gap: Dp, content: @Composable FitScope.() -> Unit) {
    Layout(content = { FitScopeImpl.content() }, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        val gapPx = gap.roundToPx()
        val data = measurables.map { it.parentData as? FitData ?: FitData(MANDATORY, false, 0.dp, 0f) }
        val fixed = measurables.mapIndexed { i, m -> if (data[i].flex) null else m.measure(Constraints(maxWidth = width)) }
        val alive = BooleanArray(measurables.size) { true }
        fun need(i: Int): Int = if (data[i].flex) data[i].minHeight.roundToPx() else fixed[i]!!.height
        fun used(): Int {
            val count = alive.count { it }
            return (0 until measurables.size).filter { alive[it] }.sumOf { need(it) } + gapPx * (count - 1).coerceAtLeast(0)
        }
        while (used() > height) {
            val victim = (0 until measurables.size).filter { alive[it] && data[it].priority != MANDATORY }.minByOrNull { data[it].priority } ?: break
            alive[victim] = false
        }
        val spare = if (height == Int.MAX_VALUE) 0 else (height - used()).coerceAtLeast(0)
        val weightSum = (0 until measurables.size).filter { alive[it] && data[it].flex }.sumOf { data[it].weight.toDouble() }.toFloat()
        val placed = ArrayList<Pair<androidx.compose.ui.layout.Placeable, Int>>()
        var y = 0
        var first = true
        for (i in 0 until measurables.size) {
            if (!alive[i]) continue
            if (!first) y += gapPx
            first = false
            val placeable = if (data[i].flex) {
                val extra = if (weightSum > 0f) (spare * data[i].weight / weightSum).toInt() else 0
                val h = data[i].minHeight.roundToPx() + extra
                measurables[i].measure(Constraints(minWidth = 0, maxWidth = width, minHeight = h, maxHeight = h))
            } else fixed[i]!!
            placed += placeable to y
            y += placeable.height
        }
        layout(width, if (height == Int.MAX_VALUE) y else height) {
            placed.forEach { (p, top) -> p.placeRelative(0, top) }
        }
    }
}

/** The fit of one page: the panel it is on (in text-scaled stage px), its budgets and the measuring of text. */
internal class FitContext(val panel: Panel, val budget: FitBudget, val metrics: TextMetrics)

@Composable
internal fun rememberFit(): FitContext {
    val stage = LocalStage.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val metrics = remember(measurer, stage.dpPerPx, stage.textScale, density.density, density.fontScale) { ComposeTextMetrics(measurer, density, stage.dpPerPx, stage.textScale) }
    val panel = Panel(stage.stageWidth / stage.textScale, stage.stageHeight / stage.textScale)
    return remember(panel, metrics) { FitContext(panel, CompanionFit.budget(panel), metrics) }
}

/**
 * Measures text the way the page draws it, in "fit pixels": stage pixels divided by the interface scale (text grows with it, so a panel
 * at 130 % has fewer fit pixels). One fit pixel is [pxPerFit] raw pixels.
 */
private class ComposeTextMetrics(private val measurer: TextMeasurer, density: Density, dpPerPx: Float, textScale: Float) : TextMetrics {
    private val pxPerFit = textScale * dpPerPx * density.density
    private val body = TextStyle(fontFamily = Figtree, fontSize = (MIN_TEXT * dpPerPx * textScale / density.fontScale).sp, fontFeatureSettings = "tnum")
    private val title = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = (TITLE_SIZE * dpPerPx * textScale / density.fontScale).sp)

    private fun style(kind: FitText) = if (kind == FitText.TITLE) title else body

    override fun lines(text: String, width: Float, kind: FitText): Int =
        measurer.measure(AnnotatedString(text), style(kind), constraints = Constraints(maxWidth = (width * pxPerFit).toInt().coerceAtLeast(1))).lineCount

    override fun width(text: String, kind: FitText): Float =
        measurer.measure(AnnotatedString(text), style(kind), softWrap = false, constraints = Constraints()).size.width / pxPerFit
}

internal const val TITLE_SIZE = 36

/** The strip layout of every page: outer margin, header and the free footer line (CompanionFit). */
@Composable
internal fun PageCard(modifier: Modifier = Modifier, content: @Composable FitScope.(FitContext) -> Unit) {
    val fit = rememberFit()
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(30.px)
    val pad = CompanionFit.cardPadding(fit.panel)
    Box(Modifier.fillMaxSize().padding(start = CompanionFit.MARGIN.px, end = CompanionFit.MARGIN.px, top = CompanionFit.MARGIN.px, bottom = (CompanionFit.MARGIN + CompanionFit.FOOTER).px)) {
        Box(
            modifier.fillMaxSize().clip(shape)
                .background(Brush.linearGradient(listOf(colors.surface2, colors.surface)))
                .border(1.px, colors.line, shape)
                .padding(pad.px),
        ) {
            FitColumn(Modifier.fillMaxSize(), CompanionFit.gap(fit.panel).px) { content(fit) }
        }
    }
}

/** The disc with an icon, the title in the display face and the small line under it (the card's `.heading`). */
@Composable
internal fun PageHeading(icon: String, title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val colors = LocalDelosColors.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.px), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.px).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
            Symbol(icon, 34.tpx, colors.onAccent, filled = true)
        }
        Column(Modifier.weight(1f)) {
            BasicText(
                title,
                style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = TITLE_SIZE.tpx, lineHeight = 1.2.em),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) BodyText(subtitle, color = colors.text2, maxLines = 1)
        }
    }
}

/** A run of text at the companion's body size, 1.2 line height; [lines] is the budget it was cut to, [maxLines] only a safety net. */
@Composable
internal fun BodyText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalDelosColors.current.text,
    maxLines: Int = Int.MAX_VALUE,
    weight: FontWeight = FontWeight.Normal,
    size: Int = MIN_TEXT,
) {
    BasicText(
        text, modifier,
        style = TextStyle(color = color, fontFamily = Figtree, fontWeight = weight, fontSize = size.tpx, lineHeight = 1.2.em, fontFeatureSettings = "tnum"),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/** Fact rows in two columns: the label above its value on a tall panel, the value alone on a short one (`.facts`). */
@Composable
internal fun FactsGrid(facts: List<Pair<String, String>>, roomy: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalDelosColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.px)) {
        facts.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.px)) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        if (roomy) BodyText(label, color = colors.text2, maxLines = 1)
                        BodyText(value, color = colors.text, maxLines = 1)
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** A wrapping row of small glass chips (genres, filters). */
@Composable
internal fun ChipRow(labels: List<String>, modifier: Modifier = Modifier, color: Color = LocalDelosColors.current.text) {
    val colors = LocalDelosColors.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.px)) {
        labels.forEach {
            BasicText(
                it,
                Modifier.clip(RoundedCornerShape(14.px)).background(colors.surface3).border(1.px, colors.line, RoundedCornerShape(14.px)).padding(horizontal = 12.px, vertical = 5.px),
                style = TextStyle(color = color, fontFamily = Figtree, fontSize = MIN_TEXT.tpx, lineHeight = 1.2.em),
                maxLines = 1,
            )
        }
    }
}

/**
 * The three impact chips of a setting or a game, equal in width: the axis and its bars. [impact] null shows dashes (this setting costs nothing on
 * the stream). An estimate, never a measurement (the legend sentence belongs to the settings help).
 */
@Composable
internal fun ImpactChips(impact: SettingImpact?, modifier: Modifier = Modifier) {
    val colors = LocalDelosColors.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.px)) {
        IMPACT_AXES.forEachIndexed { i, (label, _) ->
            val level = impact?.levels()?.get(i)
            val tint = if (level == null || level == ImpactLevel.NONE) colors.text3 else impactColor(level)
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(14.px)).background(tint.copy(alpha = .06f)).border(1.px, tint.copy(alpha = .4f), RoundedCornerShape(14.px)).padding(horizontal = 6.px, vertical = 6.px),
                horizontalArrangement = Arrangement.spacedBy(10.px, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(stringResource(label), style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = MIN_TEXT.tpx, lineHeight = 1.2.em), maxLines = 1)
                if (level == null) BasicText("\u2013", style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = MIN_TEXT.tpx)) else LevelBars(level, tint)
            }
        }
    }
}

/** One to three filled bars in [fill], the rest faint. */
@Composable
internal fun LevelBars(level: ImpactLevel, fill: Color) {
    val colors = LocalDelosColors.current
    Row(Modifier.height(30.px), horizontalArrangement = Arrangement.spacedBy(4.px), verticalAlignment = Alignment.Bottom) {
        for (step in 1..3) {
            Box(
                Modifier.size(width = 9.px, height = (12 + (step - 1) * 9).px).clip(RoundedCornerShape(3.px))
                    .background(if (step <= level.ordinal) fill else colors.text3.copy(alpha = .3f)),
            )
        }
    }
}

/** A touch button of a page: what it says, what it runs (the owner's own path) and how it looks. */
@Immutable
internal data class PageAction(
    val label: String,
    val onClick: () -> Unit,
    val style: ButtonStyle = ButtonStyle.NORMAL,
    val icon: String? = null,
    val enabled: Boolean = true,
)

/** The buttons of one row, equal in width, each at least 64 stage px high. */
@Composable
internal fun ActionRow(actions: List<PageAction>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.px)) {
        actions.forEach {
            CompanionButton(it.label, it.onClick, Modifier.weight(1f).height(MIN_TARGET.px), it.style, icon = it.icon, enabled = it.enabled, textSize = MIN_TEXT)
        }
    }
}

/** Actions that are set apart from the others by a rule above them (the destructive ones: Remove, Unpair). */
@Composable
internal fun SeparatedActions(actions: List<PageAction>, modifier: Modifier = Modifier) {
    val colors = LocalDelosColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.px)) {
        Box(Modifier.fillMaxWidth().height(1.px).background(colors.line))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.px)) {
            actions.forEach {
                CompanionButton(it.label, it.onClick, Modifier.height(MIN_TARGET.px), ButtonStyle.DANGER, icon = it.icon, enabled = it.enabled, textSize = MIN_TEXT)
            }
        }
    }
}

/** The entries of the main hint bar: the button's letter (or symbol) in the accent color and its word; [rest] more hints are on the main screen. */
@Composable
internal fun HintList(hints: List<CompanionHint>, rest: Int, modifier: Modifier = Modifier) {
    val colors = LocalDelosColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.px)) {
        hints.forEach { hint ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.px), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(56.px), contentAlignment = Alignment.CenterStart) {
                    if (hint.key != null) BodyText(hint.key, color = colors.accent, weight = FontWeight.Bold, maxLines = 1)
                    else if (hint.symbol != null) Symbol(hint.symbol, 32.tpx, colors.accent, filled = true)
                }
                BodyText(hint.label, color = colors.text, maxLines = 1)
            }
        }
        if (rest > 0) BodyText(stringResource(R.string.companion_hints_more, rest), color = colors.text2, maxLines = 1)
    }
}
