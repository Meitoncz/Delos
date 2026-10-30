package com.limelight.shell

import android.view.KeyEvent

/**
 * Detects the stream menu shortcut: all of [buttons] held at the same time, in any order. [onComplete]
 * runs when the last held button is let go.
 *
 * Only the button that completes the shortcut is kept from the stream (together with its release); the
 * ones pressed before it already reached the game, so their release must reach it too, or the host
 * would keep a stick click or button pressed for good.
 */
internal class ButtonShortcut(private val buttons: Set<Int>, private val onComplete: () -> Unit) {
    private val held = HashSet<Int>()
    private val swallowed = HashSet<Int>()
    private var pending = false

    /** Returns whether the stream (or the open menu) must not see this key event. */
    fun onKey(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        if (keyCode !in buttons) return false
        when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (repeatCount > 0) return keyCode in swallowed
                held += keyCode
                if (held.containsAll(buttons)) {
                    pending = true
                    swallowed += keyCode
                    return true
                }
            }
            KeyEvent.ACTION_UP -> {
                held -= keyCode
                val consumed = swallowed.remove(keyCode)
                if (pending && held.isEmpty()) {
                    pending = false
                    onComplete()
                }
                return consumed
            }
        }
        return false
    }
}
