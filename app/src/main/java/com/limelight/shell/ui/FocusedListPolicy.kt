package com.limelight.shell.ui

/** A vertical move is based on the actual focus order, never the scrolling viewport. */
internal enum class FocusedListMove { PREVIOUS, NEXT, STOP, EXIT_UP }

internal fun focusedListMove(index: Int, count: Int, direction: Int, repeatCount: Int): FocusedListMove = when {
    index !in 0 until count -> FocusedListMove.STOP
    direction < 0 && index > 0 -> FocusedListMove.PREVIOUS
    direction > 0 && index < count - 1 -> FocusedListMove.NEXT
    direction < 0 && index == 0 && repeatCount == 0 -> FocusedListMove.EXIT_UP
    else -> FocusedListMove.STOP
}

/** Collapsing/removing rows returns focus to the nearest surviving predecessor, not outside the list. */
internal fun <K : Any> retainedFocusedListKey(previous: List<K>, current: List<K>, focused: K?): K? {
    if (focused == null || focused in current) return focused
    val oldIndex = previous.indexOf(focused)
    for (index in oldIndex - 1 downTo 0) {
        if (previous[index] in current) return previous[index]
    }
    return current.firstOrNull()
}
