package com.limelight.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.cssLinearGradient

/**
 * Drawing helpers of the stream menu: CSS-like box shadows and the prototype's glass fills. All lengths are
 * pixels of the canvas unless a function says stage pixels.
 */

/** CSS line boxes: the extra space of `line-height` is split evenly above and below the text (half-leading), as in the prototype. */
internal val CssLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** Raw pixels per stage pixel in a draw scope or a layout block of the stream menu. */
@Composable
@ReadOnlyComposable
internal fun stageScale(): Float = LocalStage.current.dpPerPx * LocalDensity.current.density

/** Stage pixels -> text size that ignores the interface text scale (the prototype's title and icons are not scaled). */
internal val Number.fsp: TextUnit
    @Composable @ReadOnlyComposable
    get() = (toFloat() * LocalStage.current.dpPerPx / LocalDensity.current.fontScale).sp

/**
 * The blur radius of Android's shadow layer for a CSS blur of [cssBlur] pixels: CSS blurs with a Gaussian of
 * sigma = blur / 2, Skia's radius r gives sigma = 0.57735 r + 0.5.
 */
internal fun shadowRadius(cssBlur: Float): Float = ((cssBlur / 2f - .5f) / .57735f).coerceAtLeast(.5f)

/** A text shadow of the prototype (`text-shadow: 0 [offsetY]px [blur]px rgba(0,0,0,[alpha])`) at [k] pixels per stage pixel. */
internal fun menuTextShadow(k: Float, offsetY: Float, blur: Float, alpha: Float): Shadow =
    Shadow(Color.Black.copy(alpha = alpha), Offset(0f, offsetY * k), shadowRadius(blur * k))

/**
 * CSS `box-shadow: 0 [offsetY] [blur] [spread] [color]` of a rounded box, outside the box only (as CSS clips it, so a
 * translucent surface is not tinted from behind). The shadow is drawn with the canvas's own blur. A [spread] above zero
 * would draw the shape itself outside the box, so it must be zero or negative.
 */
internal fun DrawScope.drawBoxShadow(box: Rect, corner: Float, offsetY: Float, blur: Float, spread: Float, color: Color) {
    if (color.alpha <= 0f) return
    val s = spread.coerceAtMost(0f)
    val hole = Path().apply { addRoundRect(RoundRect(box, CornerRadius(corner))) }
    val shape = Path().apply {
        addRoundRect(RoundRect(box.left - s, box.top - s, box.right + s, box.bottom + s, CornerRadius((corner + s).coerceAtLeast(0f))))
    }
    clipPath(hole, ClipOp.Difference) {
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = android.graphics.Color.BLACK
                setShadowLayer(shadowRadius(blur), 0f, offsetY, color.toArgb())
            }
            canvas.nativeCanvas.drawPath(shape.asAndroidPath(), paint)
        }
    }
}

/**
 * CSS `box-shadow: inset 0 0 [blur] [spread] [color]` of a rounded box: a soft glow just inside its edge. The
 * [spread] must be zero or negative (a negative one makes the glow thinner).
 */
internal fun DrawScope.drawInsetShadow(box: Rect, corner: Float, blur: Float, spread: Float, color: Color) {
    if (color.alpha <= 0f) return
    val s = -spread.coerceAtMost(0f)
    val m = blur * 2f + 8f
    val inner = Path().apply { addRoundRect(RoundRect(box, CornerRadius(corner))) }
    // The frame around the hole is outside the clip, only its shadow reaches into the box
    val frame = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(box.left - m, box.top - m, box.right + m, box.bottom + m))
        addRoundRect(RoundRect(box.left - s, box.top - s, box.right + s, box.bottom + s, CornerRadius(corner + s)))
    }
    clipPath(inner) {
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = android.graphics.Color.BLACK
                setShadowLayer(shadowRadius(blur), 0f, 0f, color.toArgb())
            }
            canvas.nativeCanvas.drawPath(frame.asAndroidPath(), paint)
        }
    }
}

private val white = Color.White

/** `--tile-fill`: the glass of a tile at rest. */
internal fun DrawScope.drawTileFill(corner: Float) {
    drawRoundRect(
        cssLinearGradient(155f, size, 0f to white.copy(.22f), .58f to white.copy(.08f), 1f to white.copy(.12f)),
        cornerRadius = CornerRadius(corner),
    )
}

/** `--tile-fill-focus`: the lighter glass of the focused tile, laid over the one at rest. */
internal fun DrawScope.drawTileFocusFill(corner: Float, alpha: Float) {
    if (alpha <= 0f) return
    drawRoundRect(
        cssLinearGradient(155f, size, 0f to white.copy(.40f * alpha), .60f to white.copy(.16f * alpha), 1f to white.copy(.22f * alpha)),
        cornerRadius = CornerRadius(corner),
    )
}

/** `--tile-fill-on`: the accent glass of a tile that is on, laid over the one at rest. */
internal fun DrawScope.drawTileOnFill(corner: Float, accent: Color, alpha: Float) {
    if (alpha <= 0f) return
    drawRoundRect(
        cssLinearGradient(155f, size, 0f to accent.copy(.30f * alpha), .60f to accent.copy(.10f * alpha), 1f to white.copy(.12f * alpha)),
        cornerRadius = CornerRadius(corner),
    )
}
