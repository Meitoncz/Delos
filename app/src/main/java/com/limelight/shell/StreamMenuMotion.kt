package com.limelight.shell

import com.limelight.shell.ui.Motion

/**
 * What is particular to the stream menu's motion: the delays and durations of the dock's entrance and exit, which run
 * on one clock in milliseconds (docs/prototype/delos-gui.html, `#quickmenu`; every element takes its own progress from
 * the clock with [Motion.progress], like the prototype's per-element animations with a delay), and the page morph of
 * the popover. The easings, the common durations, the springs and the progress arithmetic are the shared
 * [Motion] language, which every screen uses.
 */
internal object StreamMenuMotion {
    // Durations, milliseconds
    const val STAGGER_TILE = 42
    const val EXIT_STAGGER = 8
    const val DOCK = 640

    // The entrance of the dock (delays from the moment the menu opens)
    const val TILE_DELAY = 100
    const val LABEL_EXTRA_DELAY = 140
    const val HEAD_DELAY = 60
    const val RING_EXTRA_DELAY = 260
    const val CAPTION_DELAY = 520
    const val LABEL_DURATION = (Motion.ENTER * .8f).toInt()

    // A page that is replaced by another page of the same tile: its rows leave, then the new rows enter
    const val MORPH_ROWS_OUT = 140
    const val MORPH_SWAP = 150L
    const val MORPH_TITLE = 360
    const val HELP_DURATION = 300
    const val HELP_FIRST_DELAY = 260
    const val HELP_FIRST_DURATION = 400

    fun tileDelay(index: Int): Int = Motion.staggerDelay(index, TILE_DELAY, STAGGER_TILE)

    fun labelDelay(index: Int): Int = tileDelay(index) + LABEL_EXTRA_DELAY

    fun ringDelay(focusIndex: Int): Int = tileDelay(focusIndex) + RING_EXTRA_DELAY

    /** The tile's exit starts later the further left it is: the last one leaves first. */
    fun exitDelay(index: Int): Int = Motion.reverseStaggerDelay(index, BarItem.entries.size, EXIT_STAGGER)

    /** The time the whole entrance of the dock needs, milliseconds (the ring on the last tile is the last to arrive). */
    val ENTER_TOTAL: Int = maxOf(
        ringDelay(BarItem.entries.size - 1) + Motion.ENTER,
        CAPTION_DELAY + Motion.ENTER,
        labelDelay(BarItem.entries.size - 1) + LABEL_DURATION,
    )

    /** The time the whole exit needs, milliseconds: the first tile starts last. */
    val EXIT_TOTAL: Int = exitDelay(0) + Motion.EXIT

    /** The menu's window closes this long after the exit starts: the exit, then the 80 ms the prototype waits before it hides the menu. */
    const val CLOSE = Motion.EXIT + 80
}
