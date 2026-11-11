package com.limelight.shell.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A game's three main colors (main, secondary, accent): their hues and how saturated they are
 * (1 = as vivid as the palette colors, down to .28 for nearly grey ones, up to 1.18 = fully saturated).
 */
data class GameColors(val hues: List<Float>, val saturations: List<Float>)

/**
 * Legacy Gaussian background snapshots for the launch scene and pre-Android 13 devices. The live
 * Android 13+ hero uses a separately cached GPU disk blur, so changing its optics does not change
 * [latest] or the launch handoff's visuals. Both paths draw the hero composition of [HeroGeometry] into the
 * overscanned [HeroArea] (2100 × 1260 at the 1920 × 1080 stage; a wide hero fitted to the width over its blurred extension,
 * any other image cropped), saturate by 1.15 and fade diagonally at a bounded resolution: 420 pixels wide, as high as the
 * area's aspect gives (252 at 5 : 3).
 */
object HeroBitmaps {
    const val WIDTH = 420

    /** The bitmap's height for [area]: 252 for the standard area. */
    fun height(area: HeroArea): Int = (WIDTH * area.height / area.width).roundToInt().coerceAtLeast(1)

    /** Scale from the hero area in stage pixels to the small bitmap. */
    private fun scale(area: HeroArea): Float = WIDTH / area.width

    private val cache = LruCache<String, ImageBitmap>(24)
    private val latest = LruCache<String, ImageBitmap>(24)
    private val lensCache = LruCache<String, ImageBitmap>(24)

    /** Bumped when the composition changes, so bitmaps of an older one are never reused. */
    private const val COMPOSITION_VERSION = 2

    /** The blurred, darkened copy of each wide hero that fills the area under its band (a tenth of the area: it is blurred 40 px). */
    private val extensions = LruCache<String, ImageBitmap>(24)
    private const val EXTENSION_SCALE = .1f

    /**
     * The three main hues of each game's background, strongest first (null: grey), observed by the
     * animated background and the title's gradient.
     */
    private val hues = mutableStateMapOf<String, GameColors?>()

    /** The image each game's hue was taken from: another one (SteamGridDB) takes it again. */
    private val hueSources = HashMap<String, Int>()

    /** Renders are kept per image too, so a game's new image (e.g. from SteamGridDB) is rendered again. */
    private fun cacheKey(key: String, source: ImageBitmap, blurPx: Int, area: HeroArea) =
        "v$COMPOSITION_VERSION/$key/${System.identityHashCode(source)}/$blurPx/${area.width}x${area.height}"

    private fun extensionKey(key: String, source: ImageBitmap, area: HeroArea) =
        "v$COMPOSITION_VERSION/$key/${System.identityHashCode(source)}/${area.width}x${area.height}"

    /** The extension of [source]'s hero when it has been built. */
    internal fun cachedExtension(key: String, source: ImageBitmap, area: HeroArea): ImageBitmap? = extensions.get(extensionKey(key, source, area))

    /**
     * The blurred, darkened copy of [source] stretched over the whole hero area ([area] at
     * [EXTENSION_SCALE]); only a wide hero shows it ([HeroGeometry.Layout.extended]). Call off the main thread.
     */
    internal fun extension(key: String, source: ImageBitmap, area: HeroArea): ImageBitmap {
        cachedExtension(key, source, area)?.let { return it }
        val src = source.asAndroidBitmap()
        val w = (area.width * EXTENSION_SCALE).roundToInt()
        val h = (area.height * EXTENSION_SCALE).roundToInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cover = HeroGeometry.cover(area, src.width, src.height)
        val readable = if (src.config == Bitmap.Config.HARDWARE) checkNotNull(src.copy(Bitmap.Config.ARGB_8888, false)) else src
        try {
            Canvas(out).drawBitmap(
                readable, null,
                RectF(cover.left * EXTENSION_SCALE, cover.top * EXTENSION_SCALE, cover.right * EXTENSION_SCALE, cover.bottom * EXTENSION_SCALE),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        } finally {
            if (readable !== src) readable.recycle()
        }
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)
        val radius = (HeroGeometry.EXTENSION_BLUR * EXTENSION_SCALE).roundToInt()
        repeat(3) {
            boxBlur(pixels, w, h, radius, horizontal = true)
            boxBlur(pixels, w, h, radius, horizontal = false)
        }
        val tint = HeroGeometry.EXTENSION_TINT_COLOR
        val keep = 1f - HeroGeometry.EXTENSION_TINT
        for (i in pixels.indices) {
            val c = pixels[i]
            fun mix(shift: Int) = ((((c shr shift) and 0xFF) * keep + ((tint shr shift) and 0xFF) * HeroGeometry.EXTENSION_TINT).roundToInt()) shl shift
            pixels[i] = (0xFF shl 24) or mix(16) or mix(8) or mix(0)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out.asImageBitmap().also { extensions.put(extensionKey(key, source, area), it) }
    }

    fun cached(key: String, source: ImageBitmap, blurPx: Int, area: HeroArea = HeroArea.Standard): ImageBitmap? =
        cache.get(cacheKey(key, source, blurPx, area))

    internal fun cachedLens(key: String, source: ImageBitmap, blurPx: Int, area: HeroArea = HeroArea.Standard): ImageBitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) lensCache.get(cacheKey(key, source, blurPx, area)) else cached(key, source, blurPx, area)

    /** Live hero only. Keep the legacy snapshot populated for the unchanged launch handoff. */
    internal fun renderLens(key: String, source: ImageBitmap, blurPx: Int, area: HeroArea = HeroArea.Standard): ImageBitmap {
        cachedLens(key, source, blurPx, area)?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return render(key, source, blurPx, area)
        val input = composed(key, source, area)
        val result = try {
            val snapshot = cached(key, source, blurPx, area)
                ?: renderSnapshot(key, source, blurPx, area, checkNotNull(input.copy(Bitmap.Config.ARGB_8888, true)))
            try {
                HeroLensShader.render(input, blurPx, area).asImageBitmap()
            } catch (e: RuntimeException) {
                // An unsupported GPU must retain the supported bitmap path, not crash the home screen.
                com.limelight.LimeLog.warning("Hero lens $key unavailable: ${e.message}") // l10n-ignore
                snapshot
            }
        } finally {
            input.recycle()
        }
        lensCache.put(cacheKey(key, source, blurPx, area), result)
        return result
    }

    /** The dominant hue of [key]'s background once it has been rendered; null while unknown or grey. */
    fun hue(key: String): Float? = hues[key]?.hues?.firstOrNull()

    /** The three main hues of [key]'s background (main, secondary, accent) once rendered; null while unknown or grey. */
    fun hues(key: String): GameColors? = hues[key]

    /** Whether [key]'s colors have been worked out yet (they may be null: a grey image). */
    fun huesKnown(key: String): Boolean = key in hues

    /** The three main hues of each game's cover (null: grey), from every cover the dashboard has decoded. */
    private val covers = CoverColorCache()

    /** The three main hues of [key]'s cover (main, secondary, accent); null while unknown or grey. */
    fun coverHues(key: String): GameColors? = covers.get(key)

    fun coverKnown(key: String): Boolean = covers.known(key)

    /** The colors the interface takes from [key]: its cover's, the game background's while those are unknown; null while unknown or grey. */
    fun gameColors(key: String): GameColors? = if (covers.known(key)) covers.get(key) else hues[key]

    /**
     * Works out the main hues of [key]'s [cover] (once per image); call off the main thread, as soon as the cover is decoded,
     * so the colors are there before anything asks for them.
     */
    fun analyzeCover(key: String, cover: ImageBitmap) {
        val sourceId = System.identityHashCode(cover)
        if (!covers.claim(key, sourceId)) return
        // A failure here only costs the game its colors (the palette's stay), never the app
        val result = runCatching {
            val src = cover.asAndroidBitmap()
            val readable = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            val small = Bitmap.createScaledBitmap(readable, 120, (120f * src.height / src.width.coerceAtLeast(1)).toInt().coerceIn(1, 360), true)
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            dominantHues(pixels)
        }.onFailure { com.limelight.LimeLog.warning("Cover colors $key: $it") }.getOrNull() // l10n-ignore
        android.os.Handler(android.os.Looper.getMainLooper()).post { covers.store(key, sourceId, result) }
    }

    /** The background rendered last for [key] (whatever the blur and the area), e.g. for the launch scene. */
    fun latest(key: String): ImageBitmap? = latest.get(key)

    /** Builds the background for [source]; call off the main thread. */
    fun render(key: String, source: ImageBitmap, blurPx: Int, area: HeroArea = HeroArea.Standard): ImageBitmap {
        cache.get(cacheKey(key, source, blurPx, area))?.let { return it }
        return renderSnapshot(key, source, blurPx, area, composed(key, source, area))
    }

    private fun renderSnapshot(key: String, source: ImageBitmap, blurPx: Int, area: HeroArea, out: Bitmap): ImageBitmap {
        val height = height(area)
        val pixels = IntArray(WIDTH * height)
        out.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, height)
        val sourceId = System.identityHashCode(source)
        if (synchronized(hueSources) { hueSources.put(key, sourceId) != sourceId }) {
            val hue = runCatching { dominantHues(huePixels(source, pixels, area)) }.getOrNull()
            android.os.Handler(android.os.Looper.getMainLooper()).post { hues[key] = hue }
        }
        // CSS blur(σ) in stage pixels → σ at this scale; three box blurs approximate a Gaussian
        val radius = (blurPx * scale(area)).roundToInt()
        if (radius > 0) repeat(3) {
            boxBlur(pixels, WIDTH, height, radius, horizontal = true)
            boxBlur(pixels, WIDTH, height, radius, horizontal = false)
        }
        fadeDiagonally(pixels, height)
        out.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, height)
        return out.asImageBitmap().also {
            cache.put(cacheKey(key, source, blurPx, area), it)
            latest.put(key, it)
        }
    }

    /**
     * The hero composition at the bitmap's size, with saturate(1.15), shared by both blur paths: a wide hero is a band
     * fitted to the width and aligned to the top, its bottom edge fading into [extension]; any other image is cropped to
     * fill ([coverCrop]). The geometry is [HeroGeometry.layout] of [area].
     */
    private fun composed(key: String, source: ImageBitmap, area: HeroArea): Bitmap {
        val src = source.asAndroidBitmap()
        val layout = HeroGeometry.layout(area, src.width, src.height)
        val height = height(area)
        val scale = scale(area)
        if (!layout.extended) return coverCrop(src, height)
        val extension = extension(key, source, area).asAndroidBitmap()
        val out = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val band = RectF(layout.left * scale, layout.top * scale, layout.right * scale, layout.bottom * scale)
        val saturate = Paint().apply { colorFilter = saturation() }
        val readable = if (src.config == Bitmap.Config.HARDWARE) checkNotNull(src.copy(Bitmap.Config.ARGB_8888, false)) else src
        try {
            // One layer for the whole composition, so the saturation is applied after the band is blended over the extension
            val whole = canvas.saveLayer(0f, 0f, WIDTH.toFloat(), height.toFloat(), saturate)
            canvas.drawBitmap(extension, null, RectF(0f, 0f, WIDTH.toFloat(), height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            val layer = canvas.saveLayer(band, null)
            canvas.drawBitmap(readable, null, band, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            val fade = layout.fade * scale
            val colors = IntArray(HeroGeometry.FADE_STOPS.size) { (HeroGeometry.FADE_STOPS[it].second * 255f).roundToInt() shl 24 }
            val positions = FloatArray(HeroGeometry.FADE_STOPS.size) { HeroGeometry.FADE_STOPS[it].first }
            val mask = Paint().apply {
                shader = LinearGradient(0f, band.bottom - fade, 0f, band.bottom, colors, positions, Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            canvas.drawRect(band, mask)
            canvas.restoreToCount(layer)
            canvas.restoreToCount(whole)
        } finally {
            if (readable !== src) readable.recycle()
        }
        return out
    }

    private fun saturation() = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.15f) })

    /** Center crop, like ContentScale.Crop, with saturate(1.15). */
    private fun coverCrop(src: Bitmap, height: Int): Bitmap {
        val out = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val scale = max(WIDTH / src.width.toFloat(), height / src.height.toFloat())
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((WIDTH - src.width * scale) / 2f, (height - src.height * scale) / 2f)
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = saturation() }
        val readable = if (src.config == Bitmap.Config.HARDWARE) checkNotNull(src.copy(Bitmap.Config.ARGB_8888, false)) else src
        try {
            Canvas(out).drawBitmap(readable, matrix, paint)
        } finally {
            if (readable !== src) readable.recycle()
        }
        return out
    }

    /** The pixels the game's colors are taken from: the composition's, or (a wide hero) the plain crop's, whose colors the extension's tint would mute. */
    private fun huePixels(source: ImageBitmap, composition: IntArray, area: HeroArea): IntArray {
        val src = source.asAndroidBitmap()
        if (!HeroGeometry.layout(area, src.width, src.height).extended) return composition
        val height = height(area)
        val crop = coverCrop(src, height)
        return IntArray(WIDTH * height).also {
            crop.getPixels(it, 0, WIDTH, 0, 0, WIDTH, height)
            crop.recycle()
        }
    }

    /**
     * The image's main color as a hue: every pixel votes with its hue, weighted by how colorful and
     * bright it is, so grey and dark areas hardly count. Null when the image is (nearly) grey.
     */
    /**
     * The three main colors of the image. The pixels are grouped into color buckets, judged in Material
     * You's perceptual CAM16 space (chroma, tone, hue); only colorful ones count, not near-black or near-white tones (a dark background with a hint of red
     * would otherwise win). Their weight (share of the image × chroma², so a small vivid logo still counts) is summed per 15° of hue, so a color
     * split into many small clusters still counts as one big color; the three strongest hue groups at
     * least 30° apart win, each represented by its strongest cluster. Their hues are returned, strongest
     * first (as HSV hues, which the backgrounds' hsl() takes), with how saturated each one really is, so a
     * greyish blue stays muted. An image with one or two colors gets close shades of them (±14°); a grey
     * image null.
     */
    @android.annotation.SuppressLint("RestrictedApi")
    internal fun dominantHues(pixels: IntArray): GameColors? {
        val opaque = pixels.filter { (it ushr 24) >= 200 }.toIntArray()
        if (opaque.isEmpty()) return null
        // Buckets of 16 levels per channel, each one's average color: flat colors stay exactly themselves (the
        // Wu quantizer merges an image of a few flat colors into one average), photos split finely enough
        val sums = HashMap<Int, IntArray>()
        for (c in opaque) {
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val bl = c and 255
            val sum = sums.getOrPut(((r shr 4) shl 8) or ((g shr 4) shl 4) or (bl shr 4)) { IntArray(4) }
            sum[0] += r; sum[1] += g; sum[2] += bl; sum[3]++
        }
        val quantized = sums.values.associate { (0xFF shl 24) or ((it[0] / it[3]) shl 16) or ((it[1] / it[3]) shl 8) or (it[2] / it[3]) to it[3] }
        class Cluster(val argb: Int, val weight: Double, val bin: Int)
        val clusters = quantized.mapNotNull { (argb, count) ->
            val hct = com.google.android.material.color.utilities.Hct.fromInt(argb)
            if (hct.chroma < MIN_CHROMA || hct.tone < 18.0 || hct.tone > 94.0) null
            // Chroma squared: a small but vivid color (a logo, an accent) counts the way the eye sees it
            else Cluster(argb, count * hct.chroma * hct.chroma, (hct.hue / 15.0).toInt().coerceIn(0, 23))
        }
        if (clusters.isEmpty()) return null
        fun binDistance(a: Int, b: Int): Int {
            val d = ((a - b) % 24 + 24) % 24
            return minOf(d, 24 - d)
        }
        val bins = DoubleArray(24)
        clusters.forEach { bins[it.bin] += it.weight }
        val groups = DoubleArray(24) { b -> bins[(b + 23) % 24] * .5 + bins[b] + bins[(b + 1) % 24] * .5 }
        val top = groups.max()
        val chosen = ArrayList<Cluster>(3)
        val usedBins = ArrayList<Int>(3)
        for (b in (0 until 24).sortedByDescending { groups[it] }) {
            if (chosen.size == 3 || groups[b] < top * .08) break
            if (usedBins.any { binDistance(it, b) < 2 }) continue
            val representative = clusters.filter { binDistance(it.bin, b) <= 1 && it !in chosen }.maxByOrNull { it.weight } ?: continue
            chosen += representative
            usedBins += b
        }
        if (chosen.isEmpty()) return null
        val hsl = FloatArray(3)
        val hues = ArrayList<Float>(3)
        val saturations = ArrayList<Float>(3)
        chosen.forEach { cluster ->
            androidx.core.graphics.ColorUtils.colorToHSL(cluster.argb, hsl)
            hues += hsl[0]
            // The backgrounds draw at about 85 % saturation: scale them to the color's own, never to grey, and
            // up to full saturation for really vivid colors (a deep red stays red instead of turning coral)
            saturations += (hsl[1] / .85f).coerceIn(.28f, 1.18f)
        }
        while (hues.size < 3) {
            hues += (hues[0] + if (hues.size == 1) 14f else 346f) % 360f
            saturations += saturations[0]
        }
        return GameColors(hues, saturations)
    }

    /** The least chroma (CAM16) a color needs to count: below it, it reads as white, black or grey. */
    private const val MIN_CHROMA = 16.0

    /** Opaque in the top-right corner, gone in the bottom-left one (gradient stops .3 → .95). */
    private fun fadeDiagonally(pixels: IntArray, height: Int) {
        val dx = -WIDTH.toFloat()
        val dy = height.toFloat()
        val len2 = dx * dx + dy * dy
        for (y in 0 until height) for (x in 0 until WIDTH) {
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
