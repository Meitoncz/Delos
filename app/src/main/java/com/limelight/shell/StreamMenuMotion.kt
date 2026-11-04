package com.limelight.shell

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * The motion of the stream menu: the durations, delays and easings of prototype B's `--d-*` and `--ease-*`
 * variables, one for one (docs/prototype/delos-gui.html, `#quickmenu`). The entrance and the exit of the dock run
 * on one clock in milliseconds; every element takes its own progress from it with [progress], like the
 * prototype's per-element animations with a delay.
 */
internal object StreamMenuMotion {
    // Easings
    /** `--ease-glide`: the ring's glide, overshoots a little. */
    val Glide: Easing = CubicBezierEasing(.3f, 1.22f, .42f, 1f)

    /** `--ease-in`: everything that leaves. */
    val In: Easing = CubicBezierEasing(.5f, 0f, .75f, 0f)

    /** CSS `ease`: colors and opacities. */
    val Ease: Easing = CubicBezierEasing(.25f, .1f, .25f, 1f)

    /** CSS `ease-in`: the rows of a page that is replaced. */
    val EaseIn: Easing = CubicBezierEasing(.42f, 0f, 1f, 1f)

    /** CSS `ease-out`: the labels' entrance. */
    val EaseOutCss: Easing = CubicBezierEasing(0f, 0f, .58f, 1f)

    /** CSS `ease-in-out`: the ring's breathing. */
    val EaseInOut: Easing = CubicBezierEasing(.42f, 0f, .58f, 1f)

    // Durations, milliseconds
    const val ENTER = 620
    const val STAGGER_TILE = 42
    const val EXIT = 240
    const val EXIT_STAGGER = 8
    const val FOCUS = 420
    const val PRESS = 120
    const val PRESS_HOLD = 130L
    const val RING = 560
    const val DOCK = 640
    const val POP_IN = 560
    const val POP_OUT = 240
    const val STAGGER_ROW = 46
    const val ROW_IN = 420
    const val CAPTION = 420
    const val FADE = 280
    const val GRADIENT = 460
    const val SCRIM = 420
    const val BREATHE = 2600
    const val PILL_POP = 460
    const val CARD_FADE_IN = (POP_IN * .6f).toInt()

    // The entrance of the dock (delays from the moment the menu opens)
    const val TILE_DELAY = 100
    const val LABEL_EXTRA_DELAY = 140
    const val HEAD_DELAY = 60
    const val RING_EXTRA_DELAY = 260
    const val CAPTION_DELAY = 520
    const val LABEL_DURATION = (ENTER * .8f).toInt()

    // The geometry of the popover follows its target with a slightly under-damped spring
    const val GEO_STIFFNESS = 190f
    const val GEO_DAMPING = .8f

    // A page that is replaced by another page of the same tile: its rows leave, then the new rows enter
    const val MORPH_ROWS_OUT = 140
    const val MORPH_SWAP = 150L
    const val MORPH_TITLE = 360
    const val HELP_DURATION = 300
    const val HELP_FIRST_DELAY = 260
    const val HELP_FIRST_DURATION = 400
    const val CHOICE_BUMP = 320
    const val CHOICE_SLIDE = 300
    const val VALUE_SLIDE = 22f
    const val CHEVRON_BUMP = 7f

    fun tileDelay(index: Int): Int = TILE_DELAY + index * STAGGER_TILE

    fun labelDelay(index: Int): Int = tileDelay(index) + LABEL_EXTRA_DELAY

    fun ringDelay(focusIndex: Int): Int = TILE_DELAY + focusIndex * STAGGER_TILE + RING_EXTRA_DELAY

    /** The tile's exit starts later the further left it is: the last one leaves first. */
    fun exitDelay(index: Int): Int = (BarItem.entries.size - 1 - index) * EXIT_STAGGER

    /** The time the whole entrance of the dock needs, milliseconds (the ring on the last tile is the last to arrive). */
    val ENTER_TOTAL: Int = maxOf(
        ringDelay(BarItem.entries.size - 1) + ENTER,
        CAPTION_DELAY + ENTER,
        labelDelay(BarItem.entries.size - 1) + LABEL_DURATION,
    )

    /** The time the whole exit needs, milliseconds: the first tile starts last. */
    val EXIT_TOTAL: Int = exitDelay(0) + EXIT

    /** The menu's window closes this long after the exit starts: the exit, then the 80 ms the prototype waits before it hides the menu. */
    const val CLOSE = EXIT + 80

    /** [easing] of how far an animation that starts at [delay] and lasts [duration] has come at [time]: 0 before it, 1 after it (the easing may overshoot between). */
    fun progress(time: Float, delay: Int, duration: Int, easing: Easing): Float {
        val linear = ((time - delay) / duration).coerceIn(0f, 1f)
        return easing.transform(linear)
    }

    /** Linear progress of an animation (no easing). */
    fun linear(time: Float, delay: Int, duration: Int): Float = ((time - delay) / duration).coerceIn(0f, 1f)

    fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction
}
