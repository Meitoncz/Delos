package com.limelight.shell

import android.view.KeyEvent

/**
 * Gamepad buttons that work everywhere in the shell, independent of the focused element: the two top
 * menu buttons, X and the shoulder buttons. The activity feeds every key event through [dispatch]
 * before the normal (focus) dispatch.
 */
class GlobalKeys {
    /** Handles a key-up of one of [KEYS]. */
    var listener: ((keyCode: Int) -> Unit)? = null

    /** Y opens settings search only while the settings screen accepts input. */
    var canSearch: () -> Boolean = { false }
    var onSearch: (() -> Unit)? = null

    /** Y toggles the library filters on the home screen (and closes them while they are open). */
    var canFilter: () -> Boolean = { false }
    var onFilter: (() -> Unit)? = null

    /** While set, every key goes here first (recording the stream menu buttons); true consumes it. */
    var capture: ((KeyEvent) -> Boolean)? = null

    /** Returns whether the event was consumed: both edges are, so no fallback key is synthesized. */
    fun dispatch(event: KeyEvent): Boolean {
        capture?.let { if (it(event)) return true }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            val search = onSearch?.takeIf { canSearch() }
            val filter = onFilter?.takeIf { search == null && canFilter() }
            val action = search ?: filter ?: return false
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0 && !event.isCanceled) action()
            return true
        }
        if (event.keyCode !in KEYS) return false
        val l = listener ?: return false
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) l(event.keyCode)
        return true
    }

    companion object {
        val KEYS = setOf(
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        )
    }
}
