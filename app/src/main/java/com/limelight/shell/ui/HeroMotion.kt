package com.limelight.shell.ui

import android.content.Context
import android.provider.Settings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A centered, symmetric orbit of the image plane's normal; no pan, roll or changing zoom. */
internal object HeroMotion {
    const val PERIOD_NANOS = 12_000_000_000L
    const val DEGREES = 16f

    /**
     * Smallest uniform scale at which the 2100 × 1260 hero still covers the 1920 × 1080 viewport at every orbit
     * phase is 1.0157 (found by bisection on the test's projection; the 90 px inset per side absorbs most of it),
     * plus about 1.4 % margin.
     */
    const val OVERSCAN = 1.03f
    const val CAMERA_DISTANCE_FACTOR = 2.2f

    /** The orbit's phase (0–1) at a frame-clock time, so every copy of the hero is in phase and none restarts. */
    fun turnAt(frameNanos: Long): Float =
        ((frameNanos % PERIOD_NANOS).toDouble() / PERIOD_NANOS).toFloat()

    /** The system's "remove animations" switch (animator duration scale 0) counts like reduced motion. */
    fun animatorsOff(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    fun pitch(turn: Float, reduced: Boolean): Float =
        if (reduced) 0f else DEGREES * sin(turn * (2.0 * PI)).toFloat()

    fun yaw(turn: Float, reduced: Boolean): Float =
        if (reduced) 0f else DEGREES * cos(turn * (2.0 * PI)).toFloat()
}
