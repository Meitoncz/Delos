package com.limelight.shell.ui

import java.util.ArrayDeque

/** UI-thread-confined FIFO: every real connection stage gets its full visible dwell time. */
internal class LaunchStageQueue(
    initialLine: String? = null,
    initialShownAtNanos: Long = 0L,
    private val minimumDurationMillis: Long = 1200L,
) {
    private val pending = ArrayDeque<String>()
    private var shownAtNanos = initialShownAtNanos

    var currentLine: String? = initialLine
        private set

    /** Enqueuing never changes the visible line; [advance] supplies its actual presentation time. */
    fun enqueue(line: String) {
        pending.addLast(line)
    }

    /** A late frame advances only one stage and starts that stage's own full dwell, without catching up. */
    fun advance(nowNanos: Long): Boolean {
        if (pending.isEmpty() || remainingMillis(nowNanos) > 0L) return false
        currentLine = pending.removeFirst()
        shownAtNanos = nowNanos
        return true
    }

    fun remainingMillis(nowNanos: Long): Long {
        if (currentLine == null) return 0L
        val elapsedNanos = (nowNanos - shownAtNanos).coerceAtLeast(0L)
        // Subtract whole elapsed milliseconds to ceil the remainder without overflowing a ms-to-ns conversion.
        return (minimumDurationMillis - elapsedNanos / 1_000_000L).coerceAtLeast(0L)
    }

    fun isDrained(nowNanos: Long): Boolean = pending.isEmpty() && remainingMillis(nowNanos) == 0L
}

/** Displays effective stream settings as supplied by the caller, without guessing codec negotiation. */
internal fun formatLaunchStreamParameters(width: Int, height: Int, codec: String, fps: Int): String =
    "$width × $height · $codec · $fps FPS"

internal fun formatLaunchStreamParameters(width: Int, height: Int, codec: String, fps: Float): String =
    "$width × $height · $codec · ${fps.toString().removeSuffix(".0")} FPS"
