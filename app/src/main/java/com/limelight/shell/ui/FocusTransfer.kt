package com.limelight.shell.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * A stable, zero-size holder for the focus while a lazy list is replaced (a tab change by L1 / R1 or a tap): clearing
 * the focus instead lets Compose's beyond-bounds focus search pick a row of the new list at once. It can take focus
 * only inside [request]: otherwise the D-pad or the stick (Up from the first row, now that the tabs are touch only)
 * could land on this invisible element.
 */
@Stable
class FocusTransfer {
    internal val requester = FocusRequester()
    internal var armed by mutableStateOf(false)

    /** Moves the focus here until something else takes it. */
    fun request() {
        armed = true
        requester.requestFocus()
    }
}

@Composable
fun rememberFocusTransfer(): FocusTransfer = remember { FocusTransfer() }

/** The invisible holder of [transfer]; put it anywhere in the screen. */
@Composable
fun FocusTransferTarget(transfer: FocusTransfer) {
    Box(
        Modifier.size(0.px)
            .focusRequester(transfer.requester)
            .focusProperties { canFocus = transfer.armed }
            .onFocusChanged { if (!it.isFocused) transfer.armed = false }
            .focusable(),
    )
}
