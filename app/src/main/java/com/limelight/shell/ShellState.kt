package com.limelight.shell

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot

/** Top-level screens of the shell. [main] screens show the top navigation. */
enum class Screen(val main: Boolean) { HOME(true), SETTINGS(true), PROFILE(true), DEVICES(true) }

@Immutable
data class MenuItem(val icon: String, val label: String, val action: () -> Unit)

/** A context menu (right menu button / Start) for the focused element. */
@Immutable
data class ContextMenu(val title: String, val items: List<MenuItem>)

/** Overlays above the current screen. */
sealed interface Layer {
    /** [anchor]: bounds of the element the menu belongs to, in root pixels. */
    data class Context(val menu: ContextMenu, val anchor: Rect?) : Layer
    data object Side : Layer
}

@Immutable
data class Toast(val text: String, val icon: String?, val id: Int)

/**
 * Remembers the focused element: its focus requester (to return the focus after a layer closes),
 * its position (to place the context menu) and what it offers (context menu, left/right adjusting).
 */
@Stable
class FocusTracker {
    var requester: FocusRequester? = null
        private set
    var contextMenu: ContextMenu? = null
        private set
    var adjustable by mutableStateOf(false)
        private set
    private var coordinates: LayoutCoordinates? = null

    fun focused(requester: FocusRequester, coordinates: LayoutCoordinates?, contextMenu: ContextMenu?, adjustable: Boolean) {
        this.requester = requester
        this.coordinates = coordinates
        this.contextMenu = contextMenu
        this.adjustable = adjustable
    }

    fun moved(requester: FocusRequester, coordinates: LayoutCoordinates) {
        if (requester == this.requester) this.coordinates = coordinates
    }

    fun bounds(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInRoot()
}

/** Navigation, overlays and toasts of the shell. */
@Stable
class ShellState {
    var screen by mutableStateOf(Screen.HOME)
        private set
    var layer by mutableStateOf<Layer?>(null)
        private set
    var toast by mutableStateOf<Toast?>(null)
        private set

    /** Id of the focused game on the home screen (also drives the game background). */
    var homeGame by mutableStateOf<String?>(null)

    /** L1 / R1 handler of the current screen (settings tabs), -1 / +1. */
    var onShoulder: ((Int) -> Unit)? = null
    private var toastCounter by mutableIntStateOf(0)

    /** Focus to return to when the current layer closes. */
    private var returnFocus: FocusRequester? = null

    fun navigate(to: Screen) {
        layer = null
        returnFocus = null
        screen = to
    }

    /** B / Back: closes the layer, then goes back to home. Returns false at the root (the system handles it). */
    fun back(): Boolean = when {
        layer != null -> { closeLayer(); true }
        screen != Screen.HOME -> { screen = Screen.HOME; true }
        else -> false
    }

    fun openLayer(next: Layer, tracker: FocusTracker) {
        // Switching from one layer to another (also right after closing one) keeps the original return focus
        if (layer == null && returnFocus == null) returnFocus = tracker.requester
        layer = next
    }

    fun closeLayer() {
        layer = null
    }

    /** The element to focus again once the closed layer has left the composition. */
    fun takeReturnFocus(): FocusRequester? = returnFocus.also { returnFocus = null }

    fun showToast(text: String, icon: String? = null) {
        toast = Toast(text, icon, ++toastCounter)
    }

    fun hideToast(id: Int) {
        if (toast?.id == id) toast = null
    }
}

val LocalShell = staticCompositionLocalOf<ShellState> { error("ShellState not provided") }
val LocalFocusTracker = staticCompositionLocalOf<FocusTracker> { error("FocusTracker not provided") }
