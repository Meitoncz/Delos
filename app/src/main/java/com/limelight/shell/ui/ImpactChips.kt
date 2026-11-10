package com.limelight.shell.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import com.limelight.shell.settings.ImpactLevel
import com.limelight.shell.settings.SettingImpact

/** The three impact axes in the order the chips and the legend show them: label, glyph. */
internal val IMPACT_AXES = listOf("GPU" to Symbols.GPU, "Latency" to Symbols.TIMER, "Network" to Symbols.WIFI)

internal fun SettingImpact.levels(): List<ImpactLevel> = listOf(gpu, latency, network)

/** Low is the palette's secondary color, Medium its main color and High the app's warm red (the low-battery red). */
@Composable
internal fun impactColor(level: ImpactLevel): Color {
    val colors = LocalDelosColors.current
    return when (level) {
        ImpactLevel.NONE -> colors.text3
        ImpactLevel.LOW -> colors.accent2
        ImpactLevel.MEDIUM -> colors.accent
        ImpactLevel.HIGH -> LowRed
    }
}

/**
 * Three tiny chips on their own line above a row's title (issue #40): a glyph for GPU, latency and network, each with a 3-step bar
 * indicator filled to the level in [impactColor]. A level of none dims its chip. One tight line; the row reserves it
 * (see `IMPACT_ROW_MIN_HEIGHT` in Rows.kt). Touch and focus behave as for the row.
 */
@Composable
internal fun ImpactChips(impact: SettingImpact, focused: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalDelosColors.current
    val description = IMPACT_AXES.indices.joinToString(", ") { "${IMPACT_AXES[it].first} ${impact.levels()[it].name.lowercase()}" }
    Row(
        modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(26.px),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IMPACT_AXES.forEachIndexed { i, (_, glyph) ->
            ImpactChip(glyph, impact.levels()[i], if (focused) colors.text2 else colors.text3)
        }
    }
}

/** One glyph with its bars: the level fills 1, 2 or 3 of them. */
@Composable
internal fun ImpactChip(glyph: String, level: ImpactLevel, glyphColor: Color, modifier: Modifier = Modifier) {
    val colors = LocalDelosColors.current
    val fill = impactColor(level)
    Row(modifier.alpha(if (level == ImpactLevel.NONE) .4f else 1f), horizontalArrangement = Arrangement.spacedBy(5.px), verticalAlignment = Alignment.CenterVertically) {
        Symbol(glyph, 25.tpx, glyphColor)
        Row(Modifier.size(width = 24.px, height = 21.px), horizontalArrangement = Arrangement.spacedBy(3.px), verticalAlignment = Alignment.Bottom) {
            for (step in 1..3) {
                Box(Modifier.size(width = 6.px, height = (9 + (step - 1) * 6).px).clip(RoundedCornerShape(2.px))
                    .background(if (step <= level.ordinal) fill else colors.text3.copy(alpha = .3f)))
            }
        }
    }
}

/** The legend of the chips, shown under the help text while a row with chips is focused: the three glyphs and what the bars mean. */
@Composable
internal fun ImpactLegend(modifier: Modifier = Modifier) {
    val colors = LocalDelosColors.current
    val small = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 18.tpx)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.px)) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.px), verticalAlignment = Alignment.CenterVertically) {
            IMPACT_AXES.forEach { (name, glyph) ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.px), verticalAlignment = Alignment.CenterVertically) {
                    Symbol(glyph, 22.tpx, colors.text2)
                    BasicText(name, style = small)
                }
            }
        }
        BasicText("Cost of the current value: 1 bar low, 2 medium, 3 high", style = small)
    }
}
