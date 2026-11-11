package com.limelight.shell.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The compact status bar's arithmetic (issue #9): where the top navigation sits between the brand cluster on the left (logo, wordmark, the
 * connected PC's dot) and the status cluster on the right (Wi-Fi, battery, clock), and what the bar gives up when they do not all fit.
 * Pure: the composable measures the clusters and asks here; `StatusBarFitTest` runs it over the compact stage widths.
 */
object StatusBarLayout {
    /** The bar's side margin (the display cutout comes on top) and the least air between the navigation and either cluster. */
    const val SIDE = 72f
    const val MIN_GAP = 24f

    /** A navigation button on a compact stage (64 elsewhere), the pill's padding around the buttons and the gap between them (normal, tight). */
    const val NAV_BUTTON = 56f
    const val NAV_PADDING = 8f
    const val NAV_GAP = 14f
    const val NAV_GAP_TIGHT = 6f

    /** The gap between Wi-Fi, battery and clock and the one between the battery and its percentage (normal, tight). */
    const val STATUS_GAP = 28f
    const val STATUS_GAP_TIGHT = 16f
    const val BATTERY_GAP = 10f
    const val BATTERY_GAP_TIGHT = 6f

    /** The width of a navigation pill of [buttons] buttons [button] wide and [gap] apart. */
    fun navWidth(buttons: Int, button: Float, gap: Float): Float =
        if (buttons <= 0) 0f else buttons * button + (buttons - 1) * gap + 2 * NAV_PADDING
}

/** What the compact status bar gives up, in this order, when the navigation cannot keep its air: nothing, the inner gaps, then also the battery's percentage. */
enum class StatusBarStep(val tight: Boolean, val percent: Boolean) {
    FULL(tight = false, percent = true),
    TIGHT(tight = true, percent = true),
    NO_PERCENT(tight = true, percent = false),
}

/** The widths of the three parts of the bar for one [StatusBarStep]; 0 for a part that is not shown. */
class StatusBarWidths(val left: Float, val nav: Float, val right: Float)

/**
 * The result: the [step] that is drawn, the navigation's left edge [navX] (from the bar's left edge), whether it sits on the bar's
 * middle ([centred]) or in the middle of the free space between the clusters, and [fits]: false only when not even the last step
 * keeps the gap (then the navigation still sits in the middle of what is left).
 */
class StatusBarFit(val step: StatusBarStep, val navX: Float, val centred: Boolean, val fits: Boolean)

/**
 * Where something [width] wide goes between [start] and [end] (the free space, the gaps already taken off): on [centre] when it fits
 * there, else in the middle of the free space; null when the free space is narrower than it.
 */
fun placeBetween(start: Float, end: Float, width: Float, centre: Float): Float? {
    if (end - start < width) return null
    val centred = centre - width / 2f
    return if (centred >= start && centred + width <= end) centred else start + (end - start - width) / 2f
}

/**
 * Lays the compact status bar out on [width] pixels with the side margins [sideLeft] and [sideRight] (cutout included): tries the
 * [StatusBarStep]s in order ([widths] measures one) and takes the first whose navigation keeps [minGap] to both clusters, centred on
 * the middle of the bar when that leaves the gap, otherwise in the middle of the free space between the clusters. A cluster that is
 * not shown (width 0) needs no gap: the side margin is the limit there.
 */
fun fitStatusBar(width: Float, sideLeft: Float, sideRight: Float, minGap: Float, widths: (StatusBarStep) -> StatusBarWidths): StatusBarFit {
    val centre = sideLeft + (width - sideLeft - sideRight) / 2f
    var last: StatusBarWidths? = null
    for (step in StatusBarStep.entries) {
        val w = widths(step)
        last = w
        val start = sideLeft + w.left + if (w.left > 0f) minGap else 0f
        val end = width - sideRight - w.right - if (w.right > 0f) minGap else 0f
        val x = placeBetween(start, end, w.nav, centre) ?: continue
        return StatusBarFit(step, x, x == centre - w.nav / 2f, fits = true)
    }
    val w = last!!
    val start = sideLeft + w.left
    val end = width - sideRight - w.right
    return StatusBarFit(StatusBarStep.entries.last(), start + (end - start - w.nav) / 2f, centred = false, fits = false)
}

/** What the status bar tells the screen under it: where its right cluster (Wi-Fi, battery, clock) starts, raw pixels from the stage's left edge; -1 until it is laid out. */
@Stable
class StatusBarEdges {
    var rightStartPx by mutableIntStateOf(-1)
}

/** The [StatusBarEdges] of the shell's status bar; null where there is none (previews of single components). */
val LocalStatusBarEdges = staticCompositionLocalOf<StatusBarEdges?> { null }
