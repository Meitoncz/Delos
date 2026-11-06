package com.limelight.shell.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Focus order for a small, eagerly laid out settings list. Scrolling may lag behind held navigation;
 * neither the visible viewport nor the animated row geometry determines the next focus target.
 */
class FocusedListNavigation<K : Any> internal constructor() {
    private val requesters = mutableMapOf<K, FocusRequester>()
    internal var keys: List<K> = emptyList()
        private set
    internal var focused: K? = null
    internal var hasFocus = false
    internal var deliberateUpExit = false

    internal fun update(next: List<K>): K? {
        val restore = retainedFocusedListKey(keys, next, focused)
            .takeIf { hasFocus && focused != null && focused !in next }
        keys = next
        requesters.keys.retainAll(next)
        next.forEach { requesters.getOrPut(it) { FocusRequester() } }
        return restore
    }

    fun requester(key: K): FocusRequester = requesters.getValue(key)

    internal fun neighbour(key: K, direction: Int): FocusRequester {
        val index = keys.indexOf(key)
        return if (index >= 0) keys.getOrNull(index + direction)?.let(::requester) ?: FocusRequester.Cancel
        else FocusRequester.Cancel
    }
}

/** Keeps surviving rows' requesters stable when debug rows expand, collapse or disconnect. */
@Composable
fun <K : Any> rememberFocusedListNavigation(keys: List<K>): FocusedListNavigation<K> {
    val navigation = remember { FocusedListNavigation<K>() }
    val restore = remember(keys) { navigation.update(keys) }
    LaunchedEffect(keys) {
        if (restore != null) navigation.requester(restore).requestFocus()
    }
    return navigation
}

/** No failed vertical search may fall through to a parent, except one deliberate Up at the first row. */
fun <K : Any> Modifier.focusedListBoundary(navigation: FocusedListNavigation<K>): Modifier =
    onFocusChanged { navigation.hasFocus = it.hasFocus }.focusProperties {
        onExit = {
            if ((requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) &&
                !navigation.deliberateUpExit) cancelFocusChange()
        }
    }.focusGroup()

/**
 * Apply before the row's focusable modifier. Native D-pad repeats and Android's joystick-generated
 * navigation keys use the same policy. Semantic moveFocus calls have explicit neighbours and cannot
 * escape vertically: they do not carry the initial/repeat information required to allow an exit.
 */
fun <K : Any> Modifier.focusedListRow(navigation: FocusedListNavigation<K>, key: K): Modifier = composed {
    val focusManager = LocalFocusManager.current
    this
        .focusRequester(navigation.requester(key))
        .onFocusChanged { if (it.isFocused) navigation.focused = key }
        .focusProperties {
            up = if (navigation.deliberateUpExit) FocusRequester.Default else navigation.neighbour(key, -1)
            down = navigation.neighbour(key, 1)
        }
        .onPreviewKeyEvent { event ->
            val direction = when (event.key) {
                Key.DirectionUp -> -1
                Key.DirectionDown -> 1
                else -> return@onPreviewKeyEvent false
            }
            if (event.type == KeyEventType.KeyDown) {
                val index = navigation.keys.indexOf(key)
                when (focusedListMove(index, navigation.keys.size, direction, event.nativeKeyEvent.repeatCount)) {
                    FocusedListMove.PREVIOUS, FocusedListMove.NEXT -> navigation.neighbour(key, direction).requestFocus()
                    FocusedListMove.EXIT_UP -> {
                        navigation.deliberateUpExit = true
                        try {
                            focusManager.moveFocus(FocusDirection.Up)
                        } finally {
                            navigation.deliberateUpExit = false
                        }
                    }
                    FocusedListMove.STOP -> Unit
                }
            }
            // Consume both edges; never let a repeat fall back to spatial focus search.
            true
        }
}
