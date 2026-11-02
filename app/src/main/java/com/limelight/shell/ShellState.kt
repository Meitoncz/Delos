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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import com.limelight.shell.data.App
import com.limelight.shell.data.Pc
import com.limelight.shell.settings.SettingsTab
import com.limelight.shell.ui.GameArt

/** Screens of the shell. [main] screens show the top navigation and the PC; [back] is where B goes (null: the system). */
sealed interface Screen {
    val main: Boolean get() = false
    val back: Screen? get() = null

    data object Home : Screen {
        override val main get() = true
    }

    data object Settings : Screen {
        override val main get() = true
        override val back: Screen get() = Home
    }

    data object SettingsSearch : Screen {
        override val back: Screen get() = Settings
    }

    data object Profile : Screen {
        override val main get() = true
        override val back: Screen get() = Home
    }

    data object Devices : Screen {
        override val main get() = true
        override val back: Screen get() = Home
    }

    /** The start screen on every launch: the PCs to connect to, the profile in the corner. */
    data object Start : Screen

    /** First launch: the logo and "Get started" (also replayed from the settings, then B returns there). */
    data class Boot(override val back: Screen? = null) : Screen

    /** Creating a profile, or choosing another avatar for the current one ([editing]). */
    data class CreateProfile(val editing: Boolean, override val back: Screen?) : Screen

    /** The PCs to pair: discovered ones, an address to enter, searching again. */
    data class PairPc(override val back: Screen?) : Screen

    /** Debug info: the device, a vibration test and the connected controllers (Settings → About). */
    data object DebugInfo : Screen {
        override val back: Screen get() = Settings
    }

    /** The on-screen controls without a stream, to look at and edit their layout (Debug info). */
    data object OscPreview : Screen {
        override val back: Screen get() = DebugInfo
    }

    /** Selected stream/display overrides for one game, within the current user profile. */
    data class GameSettings(val pc: Pc, val app: App) : Screen {
        override val back: Screen get() = Home
    }

    /** The SteamGridDB artwork of one game, chosen by hand (the game's options → Artwork). */
    data class Artwork(val pc: com.limelight.shell.data.Pc, val app: com.limelight.shell.data.App) : Screen {
        override val back: Screen get() = Home
    }

    /** Pairing [pcUuid]: the PIN to enter on the PC, or a one-time code from Vibepollo ([otp]). */
    data class Pin(val pcUuid: String, val pcName: String, val otp: Otp?, override val back: Screen) : Screen
}

/** A one-time PIN and passphrase created in the Vibepollo web UI. */
data class Otp(val pin: String, val passphrase: String)

@Immutable
data class MenuItem(val icon: String, val label: String, val action: () -> Unit)

/** A context menu (right menu button / Start) for the focused element. */
@Immutable
data class ContextMenu(val title: String, val items: List<MenuItem>)

/** Overlays above the current screen. */
sealed interface Layer {
    /** [anchor]: bounds of the element the menu belongs to, in root pixels. */
    data class Context(val menu: ContextMenu, val anchor: Rect?, val below: Boolean = false) : Layer
    data object Side : Layer
    /** The library filter modal (Y on the home screen). */
    data object Filters : Layer
    data class Dialog(val dialog: DialogSpec) : Layer
}

/** A field of a dialog. */
@Immutable
data class DialogField(
    val icon: String,
    val initial: String = "",
    val placeholder: String = "",
    val keyboard: KeyboardType = KeyboardType.Text,
    val password: Boolean = false,
    val maxLength: Int = 24,
)

/** A small dialog (`.dialog`): title, note, fields and a confirm button. [onConfirm] gets the values and returns whether it may close. */
@Immutable
data class DialogSpec(
    val title: String,
    val note: String,
    val fields: List<DialogField>,
    val confirm: String,
    val onConfirm: (List<String>) -> Boolean,
)

/**
 * The portal into a PC (`#portal`): pulses around an orb with the PC, then a burst into home. [arriving]:
 * the end of a burst that began over the stream (StreamExit), shown at once and without the orb.
 */
@Immutable
data class Portal(val id: Int, val icon: String, val label: String, val sub: String, val cancellable: Boolean, val arriving: Boolean = false)

/** The one thing a toast offers (Undo): [run] on Y of the controller or a tap, while the toast is visible. */
@Immutable
data class ToastAction(val label: String, val run: () -> Unit)

@Immutable
data class Toast(val text: String, val icon: String?, val id: Int, val action: ToastAction? = null)

/** Starting [app] on [pc]; [intent] opens the stream. */
data class LaunchRequest(val id: Int, val pc: Pc, val app: App, val intent: android.content.Intent, val image: ImageBitmap?, val background: ImageBitmap?)

/** Game actions the screens can trigger (implemented by the shell). */
interface GameActions {
    /** Starts or resumes [app] (with the launch animation). */
    fun start(pc: Pc, app: App, image: ImageBitmap?)

    /** Quits the game running on [pc]. */
    fun quit(pc: Pc, app: App)

    /** Quits the running game, then starts [app]. */
    fun quitAndStart(pc: Pc, running: App, app: App, image: ImageBitmap?)
}

val LocalGameActions = staticCompositionLocalOf<GameActions> { error("GameActions not provided") }

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
    private var activate: (() -> Unit)? = null
    private var adjust: ((direction: Int, repeatCount: Int) -> Unit)? = null

    fun focused(
        requester: FocusRequester,
        coordinates: LayoutCoordinates?,
        contextMenu: ContextMenu?,
        adjustable: Boolean,
        activate: (() -> Unit)? = null,
        adjust: ((direction: Int, repeatCount: Int) -> Unit)? = null,
    ) {
        this.requester = requester
        this.coordinates = coordinates
        this.contextMenu = contextMenu
        this.adjustable = adjustable
        this.activate = activate
        this.adjust = adjust
    }

    fun moved(requester: FocusRequester, coordinates: LayoutCoordinates) {
        if (requester == this.requester) this.coordinates = coordinates
    }

    fun bounds(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInRoot()

    /** Performs the focused control's semantic click without manufacturing a key event. */
    fun activate(): Boolean = activate?.let { it(); true } ?: false

    /** Adjusts the focused slider/choice. False means left/right should move focus instead. */
    fun adjust(direction: Int, repeatCount: Int): Boolean = adjust?.let { it(direction, repeatCount); true } ?: false
}

/** Navigation, overlays and toasts of the shell. */
@Stable
class ShellState(initial: Screen) {
    var screen by mutableStateOf(initial)
        private set
    var layer by mutableStateOf<Layer?>(null)
        private set
    var toast by mutableStateOf<Toast?>(null)
        private set

    /** Key ("pc uuid/app id") of the focused game on the home screen. */
    var homeGame by mutableStateOf<String?>(null)

    /** The game background behind the home screen (the focused game's art). */
    var hero by mutableStateOf<GameArt?>(null)

    /** The portal while connecting to a PC; [portalBurst] once it bursts into home. */
    var portal by mutableStateOf<Portal?>(null)
    var portalBurst by mutableStateOf(false)

    /** A game being started: the launch overlay is shown until the stream has opened (step 6.5). */
    var launch by mutableStateOf<LaunchRequest?>(null)

    /** The stream window has been opened for [launch] (it took over the launch scene). */
    var diving by mutableStateOf(false)

    /** The result overlay of a stream that failed or lost its connection (docs/GUI.md, "Stream exits"), until it is answered. */
    var result by mutableStateOf<StreamResult?>(null)

    /** L1 / R1 handler of the current screen (settings tabs), -1 / +1. */
    var onShoulder: ((Int) -> Unit)? = null
    var settingsTab by mutableStateOf(SettingsTab.STREAM)
    var settingsFocus by mutableStateOf<String?>(null)
    var onSettingsSearch: (() -> Unit)? = null

    /** While set, the next buttons pressed together are recorded (the stream menu shortcut) and passed here. */
    var buttonCapture by mutableStateOf<((Set<Int>) -> Unit)?>(null)

    /**
     * Puts the focus back into the current screen when the element that had it is gone (e.g. the home
     * layout changed while a menu was open). Set by the screen; returns whether it worked.
     */
    var restoreScreenFocus: (() -> Boolean)? = null
    private var toastCounter by mutableIntStateOf(0)

    /** Focus to return to when the current layer closes. */
    private var returnFocus: FocusRequester? = null

    fun navigate(to: Screen) {
        layer = null
        returnFocus = null
        screen = to
    }

    /** B / Back: closes the layer, then goes to the screen's [Screen.back]. Returns false at the root (the system handles it). */
    fun back(): Boolean {
        if (layer != null) {
            closeLayer()
            return true
        }
        val to = screen.back ?: return false
        navigate(to)
        return true
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

    /** A toast with an [action] stays longer (ToastHost) and can be run once with [runToastAction]. */
    fun showToast(text: String, icon: String? = null, action: ToastAction? = null) {
        toast = Toast(text, icon, ++toastCounter, action)
    }

    fun hideToast(id: Int) {
        if (toast?.id == id) toast = null
    }

    /** Runs the action of the visible toast and hides it; false when there is none. */
    fun runToastAction(): Boolean {
        val shown = toast ?: return false
        val action = shown.action ?: return false
        toast = null
        action.run()
        return true
    }
}

val LocalShell = staticCompositionLocalOf<ShellState> { error("ShellState not provided") }
val LocalFocusTracker = staticCompositionLocalOf<FocusTracker> { error("FocusTracker not provided") }

/** The current PC's games, e.g. for the settings that download artwork for all of them. */
val LocalLibrary = androidx.compose.runtime.compositionLocalOf<com.limelight.shell.data.Library> { com.limelight.shell.data.Library.Loading }
