package com.limelight.shell.ui

import kotlin.math.exp
import kotlin.math.max

/** UI-draw-thread motion state, shared by both launch windows; no per-frame Compose state/allocation. */
internal class LaunchTravel(initialTravel: Float = 0f, initialProgress: Float = 0f) {
    var travel: Float = initialTravel
        private set
    var smoothedProgress: Float = initialProgress.coerceIn(0f, 1f)
        private set
    private var lastSeconds = Float.NaN

    /** Integrate velocity, never elapsed-time times a newly changed velocity. Exit owns its own integral. */
    fun advance(seconds: Float, targetProgress: Float, opening: Float) {
        if (!seconds.isFinite()) return
        if (lastSeconds.isNaN()) {
            lastSeconds = seconds
            return
        }
        val dt = (seconds - lastSeconds).coerceIn(0f, HyperspaceConfig.MAX_FRAME_SECONDS)
        lastSeconds = max(lastSeconds, seconds)
        if (opening > 0f || dt <= 0f) return
        val target = if (targetProgress.isFinite()) targetProgress.coerceIn(0f, 1f) else smoothedProgress
        val follow = 1f - exp(-dt / HyperspaceConfig.PROGRESS_RESPONSE_SECONDS)
        smoothedProgress += (target - smoothedProgress) * follow
        val speed = HyperspaceConfig.VELOCITY + smoothedProgress * smoothedProgress * HyperspaceConfig.ACCELERATION
        travel += speed * dt
    }

    /** CPU counterpart of the unchanged shader exit integral, anchored to the frozen connection travel. */
    fun travelAtOpening(opening: Float): Float {
        val o = if (opening.isFinite()) opening.coerceIn(0f, 1f) else 0f
        val k = HyperspaceConfig.SPEED_DECAY
        val base = HyperspaceConfig.VELOCITY + smoothedProgress * smoothedProgress * HyperspaceConfig.ACCELERATION
        val exit = (HyperspaceConfig.OPENING_MS / 1000f) * (base * o +
            3f * HyperspaceConfig.OPENING_ACCELERATION * (1f - (1f + k * o) * exp(-k * o)) / (k * k))
        return travel + exit
    }
}
