package com.limelight.shell.ui

import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min

/**
 * The hero area: the stage plus an overscan margin on every side that the tilt (HeroMotion) swings into view. The margin
 * is [MARGIN_X] stage pixels per side at the 1920-pixel width and [MARGIN_Y] at the 1080-pixel height and grows in
 * proportion with a wider or taller stage, because the far edge of the tilted plane recedes in proportion to the plane's
 * size: 2100 × 1260 at the 1920 × 1080 stage, 2756.25 × 1260 at 21:9, 2100 × 1680 at 4:3.
 */
@Immutable
data class HeroArea(val width: Float, val height: Float) {
    companion object {
        const val MARGIN_X = 90f
        const val MARGIN_Y = 90f

        /** The area around a stage of [stageWidth] × [stageHeight] stage pixels. */
        fun of(stageWidth: Float, stageHeight: Float): HeroArea =
            HeroArea(stageWidth + 2f * MARGIN_X * stageWidth / 1920f, stageHeight + 2f * MARGIN_Y * stageHeight / 1080f)

        fun of(stage: StageMetrics): HeroArea = of(stage.stageWidth, stage.stageHeight)

        /** The area of the 1920 × 1080 stage: 2100 × 1260. */
        val Standard = of(1920f, 1080f)
    }
}

/**
 * Where a game background image sits in the hero area (a [HeroArea]: 2100 × 1260 stage pixels around the 1920 × 1080 screen), in one
 * place for every path that draws the hero: the cached lens bitmap, the legacy launch / stream-exit snapshot, the sharp
 * (blur 0) hero and the playing animation, so they all agree on the layout and the still → animated fade does not jump.
 *
 * A wide hero (SteamGridDB: about 3.1 : 1) is scaled so its WIDTH fills the area and is aligned to the TOP; its bottom edge
 * fades over [FADE] pixels into an extension, a heavily blurred ([EXTENSION_BLUR]), darkened copy of the same image
 * stretched over the whole area, so no bar stays empty. An image that would not leave anything to extend (its width-fit
 * height reaches the area's height: an aspect of the area's or less (5 : 3 for the standard one), e.g. a box art used as the background) fills the whole area
 * and is cropped as before. All numbers here are stage pixels of the hero area.
 */
internal object HeroGeometry {
    /** The soft bottom edge of a width-fitted hero. */
    const val FADE = 160f

    /** CSS blur(σ) of the extension, stage pixels. */
    const val EXTENSION_BLUR = 40f

    /** How far the blurred extension is blended toward [EXTENSION_TINT_COLOR] (darker, less saturated). */
    const val EXTENSION_TINT = .55f

    /** The background color of the dark theme (14, 18, 36) the extension is blended toward. */
    const val EXTENSION_TINT_COLOR = 0xFF0E1224.toInt()

    /** The smallest blur (σ, in pixels of the live layer) the playing hero keeps: below it the low-resolution layer would show its pixels. */
    const val LIVE_MIN_SIGMA = 2f

    /** The resolutions the playing hero's layer may have, as a fraction of the full area, smallest (cheapest) first. */
    private val LIVE_SCALES = floatArrayOf(.25f, .5f, 1f)

    /**
     * The size of the playing hero's layer as a fraction of the area: the smallest of [LIVE_SCALES] at which the blur
     * ([blurSigma], σ in full-size pixels) is still at least [LIVE_MIN_SIGMA] pixels wide. A strongly blurred hero is drawn
     * at a quarter of the area (a 16th of the pixels), a lightly blurred one larger, a sharp one in full.
     */
    fun liveScale(blurSigma: Float): Float = LIVE_SCALES.firstOrNull { blurSigma * it >= LIVE_MIN_SIGMA } ?: 1f

    /**
     * The shape of the band's bottom fade as (position along the fade, opacity) pairs: a smoothstep, so the fade has no
     * visible start or end. Shared by the bitmap mask and the live gradient.
     */
    val FADE_STOPS: List<Pair<Float, Float>> = listOf(0f to 1f, .25f to .84375f, .5f to .5f, .75f to .15625f, 1f to 0f)

    /**
     * The image's rectangle in the hero area. [extended]: it is a width-fitted band over a blurred extension (its bottom [fade]
     * pixels fade out), else it fills the area, cropped by the area's edges (no fade).
     */
    data class Layout(val extended: Boolean, val left: Float, val top: Float, val width: Float, val height: Float, val fade: Float) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
    }

    /** The layout of a [srcWidth] × [srcHeight] hero in [area]. An image of unknown size fills the area. */
    fun layout(area: HeroArea, srcWidth: Int, srcHeight: Int): Layout {
        if (srcWidth <= 0 || srcHeight <= 0) return Layout(false, 0f, 0f, area.width, area.height, 0f)
        val fitHeight = area.width * srcHeight / srcWidth
        if (fitHeight >= area.height) return cover(area, srcWidth, srcHeight)
        return Layout(true, 0f, 0f, area.width, fitHeight, min(FADE, fitHeight))
    }

    /** The image scaled to fill the whole [area] and centered (ContentScale.Crop), whatever its shape. */
    fun cover(area: HeroArea, srcWidth: Int, srcHeight: Int): Layout {
        if (srcWidth <= 0 || srcHeight <= 0) return Layout(false, 0f, 0f, area.width, area.height, 0f)
        val scale = max(area.width / srcWidth, area.height / srcHeight)
        val w = srcWidth * scale
        val h = srcHeight * scale
        return Layout(false, (area.width - w) / 2f, (area.height - h) / 2f, w, h, 0f)
    }
}
