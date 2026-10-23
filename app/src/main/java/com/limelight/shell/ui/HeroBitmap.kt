package com.limelight.shell.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The game background, rendered once per game: cropped to the hero area (2100×1260 stage pixels, the
 * prototype's inset -90 px) at a fifth of the size, saturated, blurred and faded out diagonally from the
 * top-right to the bottom-left corner. The screen then only scales one small bitmap up, instead of
 * blurring a full-screen layer on every frame (which made scrolling the home screen miss frames).
 */
object HeroBitmaps {
    const val WIDTH = 420
    const val HEIGHT = 252

    /** Scale from the hero area in stage pixels to the small bitmap. */
    private const val SCALE = WIDTH / 2100f

    private val cache = LruCache<String, ImageBitmap>(24)

    fun cached(key: String, blurPx: Int): ImageBitmap? = cache.get("$key/$blurPx")

    /** Builds the background for [source]; call off the main thread. */
    fun render(key: String, source: ImageBitmap, blurPx: Int): ImageBitmap {
        cache.get("$key/$blurPx")?.let { return it }
        val src = source.asAndroidBitmap()
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        // Center crop, like ContentScale.Crop, with saturate(1.15)
        val scale = max(WIDTH / src.width.toFloat(), HEIGHT / src.height.toFloat())
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((WIDTH - src.width * scale) / 2f, (HEIGHT - src.height * scale) / 2f)
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.15f) })
        }
        val readable = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        Canvas(out).drawBitmap(readable, matrix, paint)

        val pixels = IntArray(WIDTH * HEIGHT)
        out.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        // CSS blur(σ) in stage pixels → σ at this scale; three box blurs approximate a Gaussian
        val radius = (blurPx * SCALE).roundToInt()
        if (radius > 0) repeat(3) {
            boxBlur(pixels, WIDTH, HEIGHT, radius, horizontal = true)
            boxBlur(pixels, WIDTH, HEIGHT, radius, horizontal = false)
        }
        fadeDiagonally(pixels)
        out.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        return out.asImageBitmap().also { cache.put("$key/$blurPx", it) }
    }

    /** Opaque in the top-right corner, gone in the bottom-left one (gradient stops .3 → .95). */
    private fun fadeDiagonally(pixels: IntArray) {
        val dx = -WIDTH.toFloat()
        val dy = HEIGHT.toFloat()
        val len2 = dx * dx + dy * dy
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val t = ((x - WIDTH) * dx + y * dy) / len2
            val a = ((.95f - t) / .65f).coerceIn(0f, 1f)
            if (a < 1f) {
                val i = y * WIDTH + x
                val c = pixels[i]
                pixels[i] = (((c ushr 24) * a).toInt() shl 24) or (c and 0xFFFFFF)
            }
        }
    }

    /** One box blur pass along a row or a column, edges clamped. */
    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val buf = IntArray(length)
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val k = i.coerceIn(0, length - 1)
                return if (horizontal) px[line * w + k] else px[k * w + line]
            }
            var a = 0; var red = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val c = at(i)
                a += c ushr 24; red += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (i in 0 until length) {
                buf[i] = ((a / div) shl 24) or ((red / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = at(i - r)
                val inn = at(i + r + 1)
                a += (inn ushr 24) - (out ushr 24)
                red += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
            for (i in 0 until length) if (horizontal) px[line * w + i] = buf[i] else px[i * w + line] = buf[i]
        }
    }
}
