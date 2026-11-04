package com.limelight.shell.ui

import kotlin.math.hypot

/** Approved balanced-tunnel tuning; independent of Android and Compose for timeline tests. */
internal object HyperspaceConfig {
    const val RENDER_SCALE = .6f
    const val OPENING_MS = 2200
    const val REDUCED_OPENING_MS = 500
    const val LINE_MS = 1200L
    const val INFO_FADE_END = .34f

    const val IRIS_START = .68f
    const val IRIS_END = .88f
    const val IRIS_SOFTNESS = .055f
    const val ZOOM_START = .10f
    const val ZOOM_END = 1f
    const val ZOOM_SCALE = 11f

    const val CENTER_X = .5f
    const val CENTER_Y = .5f
    const val DEPTH = .28f
    const val THROAT = .045f
    const val WALL_GAIN = .62f
    const val SPOKES = 44f
    const val BAND_FREQUENCY = 5.2f
    const val SWIRL = .22f
    const val LENS_PINCH = 1.15f

    const val STREAK_COUNT = 24
    const val VELOCITY = .9f
    const val ACCELERATION = 2.6f
    const val STREAK_LENGTH = .78f
    const val ABERRATION = .0016f
    const val OPENING_ACCELERATION = 5.5f
    const val SPEED_DECAY = 3.2f

    const val CLOUD_COUNT = 24
    const val CLOUD_SIZE = .19f
    const val CLOUD_DENSITY = .48f
    const val CLOUD_FLASH_GAIN = .11f
    const val CLOUD_DRIFT = .04f

    const val CONTRAST = 1.12f
    const val EXPOSURE = 1.55f
    const val EDGE_BLOOM = .16f
    const val CLOUD_RIM = .028f
}

/** CPU counterparts of the shader's exit equations; x/y and dimensions are physical pixels. */
internal object HyperspaceTimeline {
    fun smootherstep(value: Float): Float {
        val x = unit(value)
        return x * x * x * (x * (x * 6f - 15f) + 10f)
    }

    fun tunnelScale(opening: Float): Float {
        val phase = unit((unit(opening) - HyperspaceConfig.ZOOM_START) /
            (HyperspaceConfig.ZOOM_END - HyperspaceConfig.ZOOM_START))
        return 1f + (HyperspaceConfig.ZOOM_SCALE - 1f) * phase * phase
    }

    fun infoAlpha(opening: Float): Float =
        1f - unit(unit(opening) / HyperspaceConfig.INFO_FADE_END)

    /** Invalid geometry cannot reveal an uninitialized surface; completion always removes the overlay. */
    fun apertureOpacity(opening: Float, x: Float, y: Float, width: Float, height: Float): Float {
        val o = unit(opening)
        if (o >= 1f) return 0f
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f ||
            !x.isFinite() || !y.isFinite()) return 1f

        val iris = smootherstep((o - HyperspaceConfig.IRIS_START) /
            (HyperspaceConfig.IRIS_END - HyperspaceConfig.IRIS_START))
        // Double intermediates keep even finite extreme aspect ratios from overflowing.
        val aspect = width.toDouble() / height
        val softness = HyperspaceConfig.IRIS_SOFTNESS.toDouble()
        val start = -(softness + .001)
        val end = hypot(aspect, 1.0) * .5 + softness + .01
        val radius = start + (end - start) * iris
        val screenX = (x.toDouble() / width - HyperspaceConfig.CENTER_X) * aspect
        val screenY = y.toDouble() / height - HyperspaceConfig.CENTER_Y
        val edge = ((hypot(screenX, screenY) - (radius - softness)) / (2.0 * softness))
            .coerceIn(0.0, 1.0)
        return (edge * edge * (3.0 - 2.0 * edge)).toFloat()
    }

    private fun unit(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
}
