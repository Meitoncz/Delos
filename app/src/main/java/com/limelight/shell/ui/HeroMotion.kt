package com.limelight.shell.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A centered, symmetric orbit of the image plane's normal; no pan, roll or changing zoom. */
internal object HeroMotion {
    const val PERIOD_NANOS = 30_000_000_000L
    const val DEGREES = 9f
    const val OVERSCAN = 1.025f
    const val CAMERA_DISTANCE_FACTOR = 3f

    fun pitch(turn: Float, reduced: Boolean): Float =
        if (reduced) 0f else DEGREES * sin(turn * (2.0 * PI)).toFloat()

    fun yaw(turn: Float, reduced: Boolean): Float =
        if (reduced) 0f else DEGREES * cos(turn * (2.0 * PI)).toFloat()
}
