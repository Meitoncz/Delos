package com.limelight.shell.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.flow.first

/**
 * Focuses [requester] silently when a screen or layer appears (like the prototype's showScreen and
 * openLayer). Waits until the window has focus: requests before that are lost.
 */
@Composable
fun InitialFocus(requester: FocusRequester, key: Any? = Unit) {
    val feedback = LocalFeedback.current
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(key) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        feedback.silenceNextFocus()
        runCatching { requester.requestFocus() }
    }
}
