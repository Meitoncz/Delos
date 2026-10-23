package com.limelight.shell.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.runtime.mutableStateMapOf
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
    private val latest = LruCache<String, ImageBitmap>(24)

    /** The dominant hue of each game's background (null: grey), observed by the animated background. */
    private val hues = mutableStateMapOf<String, Float?>()

    fun cached(key: String, blurPx: Int): ImageBitmap? = cache.get("$key/$blurPx")

    /** The dominant hue of [key]'s background once it has been rendered; null while unknown or grey. */
    fun hue(key: String): Float? = hues[key]

    /** The background rendered last for [key] (whatever the blur), e.g. for the launch scene. */
    fun latest(key: String): ImageBitmap? = latest.get(key)

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
        if (key !in hues) {
            val hue = dominantHue(pixels)
            android.os.Handler(android.os.Looper.getMainLooper()).post { hues[key] = hue }
        }
        // CSS blur(σ) in stage pixels → σ at this scale; three box blurs approximate a Gaussian
        val radius = (blurPx * SCALE).roundToInt()
        if (radius > 0) repeat(3) {
            boxBlur(pixels, WIDTH, HEIGHT, radius, horizontal = true)
            boxBlur(pixels, WIDTH, HEIGHT, radius, horizontal = false)
        }
        fadeDiagonally(pixels)
        out.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        return out.asImageBitmap().also {
            cache.put("$key/$blurPx", it)
            latest.put(key, it)
        }
    }

    /**
     * The image's main color as a hue: every pixel votes with its hue, weighted by how colorful and
     * bright it is, so grey and dark areas hardly count. Null when the image is (nearly) grey.
     */
    private fun dominantHue(pixels: IntArray): Float? {
        val hsv = FloatArray(3)
        var x = 0.0
        var y = 0.0
        var weight = 0.0
        for (i in pixels.indices step 3) {
            android.graphics.Color.colorToHSV(pixels[i], hsv)
            val w = (hsv[1] * hsv[1] * hsv[2]).toDouble()
            val angle = Math.toRadians(hsv[0].toDouble())
            x += kotlin.math.cos(angle) * w
            y += kotlin.math.sin(angle) * w
            weight += w
        }
        if (weight < pixels.size / 3 * .02) return null
        return ((Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0).toFloat()
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
