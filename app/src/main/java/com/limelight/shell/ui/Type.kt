package com.limelight.shell.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import com.limelight.R

private fun variableFamily(resId: Int, weights: List<Int>) = FontFamily(
    weights.map { w -> Font(resId, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) }
)

/** Display font (titles, logo, wordmark). */
val Unbounded = variableFamily(R.font.unbounded, listOf(400, 500, 600, 700))

/** Body font (everything else); up to ExtraBold for the hint bar's button letters. */
val Figtree = variableFamily(R.font.figtree, listOf(400, 500, 600, 700, 800))

private val symbolsRegular = FontFamily(Font(R.font.phosphor_regular))
private val symbolsFill = FontFamily(Font(R.font.phosphor_fill))

private val iconLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/**
 * A Phosphor icon ([Symbols] code point). [filled] cross-fades from Regular to Fill over [fadeMillis],
 * preserving the prototype's focus effect. Both bundled subsets use the same code points and metrics.
 */
@Composable
fun Symbol(
    icon: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    fadeMillis: Int = 350,
) {
    val f by animateFloatAsState(if (filled) 1f else 0f, tween(fadeMillis, easing = EaseOut), label = "fill")
    Box(modifier, contentAlignment = Alignment.Center) {
        if (f < 1f) {
            BasicText(icon, style = iconStyle(size, color.copy(alpha = color.alpha * (1f - f)), symbolsRegular))
        }
        if (f > 0f) {
            BasicText(icon, style = iconStyle(size, color.copy(alpha = color.alpha * f), symbolsFill))
        }
    }
}

private fun iconStyle(size: TextUnit, color: Color, family: FontFamily) =
    TextStyle(color = color, fontSize = size, lineHeight = size, lineHeightStyle = iconLineHeight, fontFamily = family)
