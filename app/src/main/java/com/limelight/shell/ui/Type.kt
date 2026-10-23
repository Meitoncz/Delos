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

private val symbolFamilies = HashMap<Triple<Float, Int, Float>, FontFamily>()

/** Material Symbols Rounded with the given FILL, weight and optical size axes (cached per combination). */
fun symbolsFamily(fill: Float, weight: Int, opticalSize: Float): FontFamily =
    symbolFamilies.getOrPut(Triple(fill, weight, opticalSize)) {
        FontFamily(
            Font(
                R.font.material_symbols_rounded,
                variationSettings = FontVariation.Settings(
                    FontVariation.Setting("FILL", fill),
                    FontVariation.weight(weight),
                    FontVariation.Setting("GRAD", 0f),
                    FontVariation.Setting("opsz", opticalSize),
                ),
            )
        )
    }

private val iconLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/**
 * A Material Symbols icon ([Symbols] code point). [filled] cross-fades from the outlined to the filled
 * variant, like the prototype's focus effect (FILL 0 → 1, weight 400 → 500).
 */
@Composable
fun Symbol(
    icon: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    weight: Int = 400,
    filledWeight: Int = 500,
    opticalSize: Float = 24f,
) {
    val f by animateFloatAsState(if (filled) 1f else 0f, tween(350, easing = EaseOut), label = "fill")
    Box(modifier, contentAlignment = Alignment.Center) {
        if (f < 1f) {
            BasicText(icon, style = iconStyle(size, color.copy(alpha = color.alpha * (1f - f)), symbolsFamily(0f, weight, opticalSize)))
        }
        if (f > 0f) {
            BasicText(icon, style = iconStyle(size, color.copy(alpha = color.alpha * f), symbolsFamily(1f, filledWeight, opticalSize)))
        }
    }
}

private fun iconStyle(size: TextUnit, color: Color, family: FontFamily) =
    TextStyle(color = color, fontSize = size, lineHeight = size, lineHeightStyle = iconLineHeight, fontFamily = family)
