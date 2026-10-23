package com.limelight.shell.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** A CSS linear-gradient(angle, …) over a box of [size] at [origin]. */
fun cssLinearGradient(angleDeg: Float, size: Size, vararg stops: Pair<Float, Color>, origin: Offset = Offset.Zero): Brush {
    val a = Math.toRadians(angleDeg.toDouble())
    val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
    val half = (abs(size.width * dir.x) + abs(size.height * dir.y)) / 2f
    val center = origin + Offset(size.width / 2f, size.height / 2f)
    return Brush.linearGradient(*stops, start = center - dir * half, end = center + dir * half)
}

/** A CSS radial-gradient(circle at x y, …): the gradient ray reaches the farthest corner. */
fun cssRadialCircle(center: Offset, size: Size, vararg stops: Pair<Float, Color>): Brush {
    val r = max(max(hypot(center.x, center.y), hypot(size.width - center.x, center.y)),
        max(hypot(center.x, size.height - center.y), hypot(size.width - center.x, size.height - center.y)))
    return Brush.radialGradient(*stops, center = center, radius = r.coerceAtLeast(1f))
}

/**
 * Procedural placeholder art for a game (the prototype's `.art`): used as cover and game background
 * until the SteamGridDB images exist. [hue] and [hue2] are the two colors of the game.
 */
@Composable
fun PlaceholderArt(
    hue: Float,
    hue2: Float,
    icon: String,
    modifier: Modifier = Modifier,
    /** Glyph size, distance from the right edge (negative = overflowing) and top in stage pixels / fraction. */
    glyphSize: Float = 320f,
    glyphRight: Float = -36f,
    glyphTop: Float = .24f,
    glyphAlpha: Float = .16f,
    title: String? = null,
    titleSize: Float = 34f,
    titleInset: Float = 26f,
    titleBottom: Float = 26f,
    subtitle: String? = null,
) {
    BoxWithConstraints(
        modifier.drawBehind {
            drawRect(cssLinearGradient(165f, size, 0f to hsl(hue, 58f, 24f), 1f to hsl(hue2, 50f, 10f)))
            drawRect(cssRadialCircle(Offset(size.width * .18f, size.height * .92f), size, 0f to hsl(hue, 85f, 48f, .9f), .58f to hsl(hue, 85f, 48f, 0f)))
            drawRect(cssRadialCircle(Offset(size.width * .74f, size.height * .22f), size, 0f to hsl(hue2, 90f, 66f, .95f), .42f to hsl(hue2, 90f, 66f, 0f)))
        }
    ) {
        Symbol(
            icon,
            size = glyphSize.tpx,
            color = Color.White.copy(alpha = glyphAlpha),
            filled = true,
            weight = 300,
            filledWeight = 300,
            opticalSize = 48f,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-glyphRight).px, y = maxHeight * glyphTop),
        )
        if (title != null) {
            // CSS text-shadow 0 4px 24px: blur 24 = 2 sigma; Android's shadow radius ≈ (sigma - 0.5) / 0.577
            val k = LocalStage.current.dpPerPx * LocalDensity.current.density
            val titleShadow = Shadow(Color.Black.copy(alpha = .45f), Offset(0f, 4f * k), 20f * k)
            Column(Modifier.align(Alignment.BottomStart).padding(start = titleInset.px, end = titleInset.px, bottom = titleBottom.px)) {
                if (subtitle != null) {
                    BasicText(
                        subtitle.uppercase(),
                        style = TextStyle(color = Color.White.copy(alpha = .8f), fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx, letterSpacing = .1.em),
                        modifier = Modifier.padding(bottom = 30.px),
                    )
                }
                BasicText(
                    title,
                    style = TextStyle(
                        color = Color.White, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = titleSize.tpx,
                        lineHeight = 1.05.em, shadow = titleShadow,
                    ),
                )
            }
        }
    }
}
