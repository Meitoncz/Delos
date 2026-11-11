package com.limelight.shell.ui

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How the window's shape relates to the 16:9 the approved prototype is drawn for. */
enum class StageShape {
    /** Wider than 16:9 (20:9 phones in a grip, 21:9). */
    WIDE,

    /** 16:9 within [StagePlanner.STANDARD_TOLERANCE]: the prototype. */
    STANDARD,

    /** Narrower than 16:9 down to 4:3 (16:10, 3:2, 5:3, 4:3). */
    TALL,

    /** Too few stage pixels across for the prototype's layout (the legibility floor shrank the stage), or squarish. */
    COMPACT,
}

/** What [StagePlanner.plan] decides for one window. */
@Immutable
data class StagePlan(
    /** Dp per stage pixel. */
    val dpPerPx: Float,
    /** Size of the stage in stage pixels: the window divided by [dpPerPx]. 1920 × 1080 at 16:9 when nothing is floored. */
    val stageWidth: Float,
    val stageHeight: Float,
    val shape: StageShape,
)

/**
 * Turns the size of the window in dp into the stage every screen is laid out on: how many stage pixels fit across and down,
 * what one stage pixel is in dp, and which [StageShape] layout family applies.
 *
 * The stage grows with the window, as before: `dpPerPx = min(w / 1920, h / 1080)` fits the 1920 × 1080 prototype inside the
 * window, and the other axis simply holds more stage pixels (4:3 = 1920 × 1440, 21:9 = 2520 × 1080).
 *
 * **Legibility floor** ([MIN_DP_PER_PX]): on a window that is small in dp that rule would shrink text below what is readable,
 * because the scale follows the pixel count, not the physical size. The reference is the AYN Odin 2 Portal, the device the UI was
 * designed on: 1920 × 1080 px at the density Android reports for it, 369 dpi (2.306 px per dp), is 1920 / 2.306 = 832.5 × 468.3 dp,
 * so one stage pixel is 832.5 / 1920 = 0.4336 dp there (body text of 21 stage px = 9.1 dp). Android's reported density is the
 * only size information an app gets, and it is what every other dp in the system is relative to, so a window whose stage pixel
 * would be smaller than 0.4336 dp has text that is smaller than on the Odin. The floor is 0.43, a hair (0.8 %) below the Odin's
 * 0.4336, so the Odin and every window at least as large in dp (16:9 and 825.6 dp wide or more) keep `min(w / 1920, h / 1080)`
 * exactly; below it the scale stays at 0.43 and the stage shrinks (fewer stage pixels across), which is real reflow, not scaling.
 */
object StagePlanner {
    const val STAGE_WIDTH = 1920f
    const val STAGE_HEIGHT = 1080f

    /** Minimum dp per stage pixel: 0.43, 0.8 % below the Odin 2 Portal's 832.5 dp / 1920 = 0.4336. */
    const val MIN_DP_PER_PX = 0.43f

    /** A stage narrower than this (stage pixels) is [StageShape.COMPACT]. */
    const val COMPACT_STAGE_WIDTH = 1500f

    /** Relative tolerance around 16:9 that still counts as [StageShape.STANDARD] (1920 × 1056 with a bar; up to 1.83:1). */
    const val STANDARD_TOLERANCE = 0.03f

    /** The lower end of [StageShape.TALL]: 4:3, with the same tolerance. Narrower (5:4, 1:1) is [StageShape.COMPACT]. */
    const val TALL_LIMIT = 4f / 3f

    private const val STANDARD_ASPECT = 16f / 9f

    fun plan(widthDp: Float, heightDp: Float): StagePlan {
        if (widthDp <= 0f || heightDp <= 0f) return StagePlan(1f, STAGE_WIDTH, STAGE_HEIGHT, StageShape.STANDARD)
        val byWidth = widthDp / STAGE_WIDTH
        val byHeight = heightDp / STAGE_HEIGHT
        val dpPerPx = max(min(byWidth, byHeight), MIN_DP_PER_PX)
        // The limiting axis is 1920 or 1080 stage pixels exactly, not w / (w / 1920) with its rounding error
        val stageWidth = snap(widthDp / dpPerPx, STAGE_WIDTH)
        val stageHeight = snap(heightDp / dpPerPx, STAGE_HEIGHT)
        return StagePlan(dpPerPx, stageWidth, stageHeight, classify(stageWidth, stageHeight))
    }

    private fun snap(value: Float, target: Float) = if (abs(value - target) < 0.01f) target else value

    fun classify(stageWidth: Float, stageHeight: Float): StageShape {
        val aspect = stageWidth / stageHeight
        return when {
            stageWidth < COMPACT_STAGE_WIDTH -> StageShape.COMPACT
            abs(aspect - STANDARD_ASPECT) <= STANDARD_ASPECT * STANDARD_TOLERANCE -> StageShape.STANDARD
            aspect > STANDARD_ASPECT -> StageShape.WIDE
            aspect >= TALL_LIMIT * (1f - STANDARD_TOLERANCE) -> StageShape.TALL
            else -> StageShape.COMPACT
        }
    }

    /**
     * A cutout inset (pixels, as WindowInsets reports it) in stage pixels: pixels → dp by [density] (pixels per dp) → stage pixels
     * by [dpPerPx]. Zero stays exactly zero, so a device without a cutout is untouched.
     */
    fun insetToStage(pixels: Int, density: Float, dpPerPx: Float): Float =
        if (pixels <= 0 || density <= 0f || dpPerPx <= 0f) 0f else pixels / density / dpPerPx
}

/** The display cutout in stage pixels on each edge: status bar, hint bar and edge-anchored content keep clear of it. */
@Immutable
data class StageInsets(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
    /** The same insets in a stage whose pixel is [factor] times as big (the zoomed bars of the interface scale). */
    fun dividedBy(factor: Float): StageInsets =
        if (factor == 1f || factor <= 0f) this else StageInsets(left / factor, top / factor, right / factor, bottom / factor)

    companion object {
        val None = StageInsets()
    }
}
