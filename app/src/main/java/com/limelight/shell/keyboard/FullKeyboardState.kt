package com.limelight.shell.keyboard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutCoordinates
import com.limelight.shell.DirectionalRepeater
import com.limelight.shell.MenuDirection
import com.limelight.shell.ui.Motion

/** What the keyboard asks of whoever hosts it ([com.limelight.binding.input.virtual_controller.keyboard.KeyBoardLayoutController]). */
interface FullKeyboardHost {
    /** `checkbox_enable_sticky_modifier_key_virtual_keyboard`. */
    val stickyModifiers: Boolean

    /** One key event to the host PC: the same path the touch keys always took. */
    fun sendKey(code: Int, down: Boolean)

    fun haptic(haptic: KeyHaptic)

    /** The Hide key or B: hides the keyboard. */
    fun close()
}

/** The vibration cues of a key (`checkbox_vibrate_keyboard`). */
enum class KeyHaptic { PRESS, RELEASE, LATCH }

/** What drives the keyboard now: the touch screen hides the focus ring and the hints, the controller brings them back. */
enum class InputMode { TOUCH, CONTROLLER }

/** The popup above the pressed key [code]. */
class KeyPopup(val code: Int, val text: String)

/**
 * Everything the Full keyboard shows and does: open or closed, the focused key, which keys are pressed and which
 * modifiers latched, the popup, and the touch and controller input that changes them. The composables only read it;
 * keys reach the host through [FullKeyboardHost.sendKey]. Used on the main thread only.
 */
@Stable
class FullKeyboardState(private val host: FullKeyboardHost) {
    private val grid = KeyGrid.standard
    private val latch = ModifierLatch()
    private val handler = Handler(Looper.getMainLooper())
    private val analog = DirectionalRepeater()

    /** The keyboard is up (it takes the controller). */
    var open by mutableStateOf(false)
        private set

    /** The keyboard is on screen: open, or still leaving. */
    var present by mutableStateOf(false)
        private set

    var input by mutableStateOf(InputMode.TOUCH)
        private set

    var prefs by mutableStateOf(KeyboardPrefs())

    /** The key the controller focus is on, null before the first controller input. */
    var focus by mutableStateOf<KeyPos?>(null)
        private set

    /** The keyboard has settled after opening, so the ring may appear (it follows the keys in 560 ms). */
    var ringReady by mutableStateOf(false)
        private set

    var latched by mutableStateOf<Set<Int>>(emptySet())
        private set

    var pressed by mutableStateOf<Set<Int>>(emptySet())
        private set

    var popup by mutableStateOf<KeyPopup?>(null)
        private set

    var popupOn by mutableStateOf(false)
        private set

    /** Counts the moves that hit the edge of the keyboard; the ring nudges toward [nudgeDirection] for each. */
    var nudgeCount by mutableIntStateOf(0)
        private set
    internal var nudgeDirection by mutableStateOf(MenuDirection.RIGHT)
        private set

    /** Where each key is (for the popup): the keys report their layout here as they are placed. */
    internal val keyCoordinates = HashMap<Int, LayoutCoordinates>()

    /** Both the ring and the focused key's look are for the controller. */
    val focusVisible: Boolean get() = open && ringReady && input == InputMode.CONTROLLER

    private var anchorX = 0f
    private val held = HashSet<Int>()
    private var padHeld: Int? = null
    private var padHeldSendsUp = false
    private val latchTimers = HashMap<Int, Runnable>()
    private val pressStart = HashMap<Int, Long>()
    private val releaseTimers = HashMap<Int, Runnable>()
    private val hidePopup = Runnable { popupOn = false }
    private val leave = Runnable { present = false }

    fun show() {
        handler.removeCallbacks(leave)
        analog.reset()
        input = if (hasController()) InputMode.CONTROLLER else InputMode.TOUCH
        present = true
        open = true
    }

    fun hide() {
        if (!open) return
        releaseHeld()
        analog.reset()
        open = false
        ringReady = false
        handler.removeCallbacks(hidePopup)
        popupOn = false
        handler.postDelayed(leave, Motion.EXIT + LEAVE_MARGIN_MS)
    }

    /** The first controller focus (Q) and the ring, once the keys are in. */
    fun settled() {
        if (!open) return
        if (focus == null) focusAt(grid.find(KeyGrid.FIRST_KEY)!!)
        ringReady = true
    }

    // ---- Touch ----

    /** A finger went down on [code]; false when the keyboard is not taking input (it is leaving), then nothing happens. */
    fun touchDown(code: Int): Boolean {
        if (!open) return false
        input = InputMode.TOUCH
        addPressed(code)
        if (code == HIDE_CODE) return true
        val sticky = host.stickyModifiers
        val send = latch.touchDown(code, sticky)
        syncLatched()
        if (send == KeySend.DOWN) {
            showPopup(code)
            keyDown(code)
            if (sticky && isModifierCode(code)) scheduleLatch(code)
        }
        return true
    }

    fun touchUp(code: Int) {
        cancelLatch(code)
        removePressed(code)
        if (code == HIDE_CODE) {
            if (open) host.close()
            return
        }
        hidePopupSoon()
        if (latch.touchUp(code, host.stickyModifiers) == KeySend.UP) keyUp(code)
    }

    // ---- Controller ----

    /** A controller key event while the keyboard is up. Always consumed: nothing of it reaches the game. */
    fun onControllerKey(event: KeyEvent): Boolean {
        padKeyAction(event.keyCode, event.action, event.repeatCount, event.isCanceled)?.let(::act)
        return true
    }

    /** A stick event while the keyboard is up: the left stick moves the focus like the D-pad. Always consumed. */
    fun onControllerMotion(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return true
        val direction = analogDirection(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y))
        if (analog.shouldMove(direction, event.eventTime)) act(PadAction.Move(direction!!))
        return true
    }

    private fun act(action: PadAction) {
        if (!open) return
        input = InputMode.CONTROLLER
        when (action) {
            is PadAction.Move -> move(action.direction)
            PadAction.PressDown -> padDown()
            PadAction.PressUp -> padUp()
            PadAction.Close -> host.close()
            PadAction.ToggleShift -> toggleShift()
        }
    }

    private fun move(direction: MenuDirection) {
        val from = focus
        if (from == null) {
            settled()
            return
        }
        val next = grid.move(from, anchorX, direction)
        if (next == null) {
            nudgeDirection = direction
            nudgeCount++
        } else {
            focus = next.pos
            anchorX = next.anchorX
        }
    }

    private fun focusAt(pos: KeyPos) {
        val landed = grid.land(pos)
        focus = landed.pos
        anchorX = landed.anchorX
    }

    private fun padDown() {
        val pos = focus
        if (pos == null) {
            settled()
            return
        }
        if (padHeld != null) return
        val code = grid.key(pos).code
        padHeld = code
        addPressed(code)
        when {
            code == HIDE_CODE -> padHeldSendsUp = false
            isModifierCode(code) -> {
                padHeldSendsUp = false
                toggleLatch(code)
            }
            else -> {
                padHeldSendsUp = true
                showPopup(code)
                keyDown(code)
            }
        }
    }

    private fun padUp() {
        val code = padHeld ?: return
        padHeld = null
        removePressed(code)
        hidePopupSoon()
        if (code == HIDE_CODE) host.close() else if (padHeldSendsUp) keyUp(code)
    }

    private fun toggleShift() {
        val shift = KeyEvent.KEYCODE_SHIFT_LEFT
        toggleLatch(shift)
        // A tap of the key, so it shows the press like a touch would
        addPressed(shift)
        handler.post { removePressed(shift) }
    }

    /** A or X on a modifier: latch it (down on the host) or let it go. */
    private fun toggleLatch(code: Int) {
        val send = latch.padPress(code)
        syncLatched()
        host.sendKey(code, send == KeySend.DOWN)
        host.haptic(if (send == KeySend.DOWN) KeyHaptic.LATCH else KeyHaptic.RELEASE)
    }

    // ---- Keys ----

    private fun keyDown(code: Int) {
        held.add(code)
        host.sendKey(code, true)
        host.haptic(KeyHaptic.PRESS)
    }

    private fun keyUp(code: Int) {
        held.remove(code)
        host.sendKey(code, false)
        host.haptic(KeyHaptic.RELEASE)
    }

    /** Lets go of every key that is held down (not the latched modifiers): a hidden keyboard must not leave a key pressed. */
    private fun releaseHeld() {
        for (timer in latchTimers.values) handler.removeCallbacks(timer)
        latchTimers.clear()
        for (code in held.toList()) host.sendKey(code, false)
        held.clear()
        padHeld = null
        for (timer in releaseTimers.values) handler.removeCallbacks(timer)
        releaseTimers.clear()
        pressed = emptySet()
    }

    private fun scheduleLatch(code: Int) {
        val timer = Runnable {
            latchTimers.remove(code)
            latch.latchHeld(code)
            held.remove(code)
            syncLatched()
            host.haptic(KeyHaptic.LATCH)
        }
        cancelLatch(code)
        latchTimers[code] = timer
        handler.postDelayed(timer, LATCH_HOLD_MS)
    }

    private fun cancelLatch(code: Int) {
        latchTimers.remove(code)?.let(handler::removeCallbacks)
    }

    private fun syncLatched() {
        latched = latch.codes()
    }

    /** The key shows as pressed from now on. */
    private fun addPressed(code: Int) {
        releaseTimers.remove(code)?.let(handler::removeCallbacks)
        pressStart[code] = SystemClock.uptimeMillis()
        pressed = pressed + code
    }

    /** The key stops showing as pressed, but not before it was shown for [Motion.PRESS_HOLD]: a tap is still seen. */
    private fun removePressed(code: Int) {
        val wait = Motion.PRESS_HOLD - (SystemClock.uptimeMillis() - (pressStart[code] ?: 0L))
        if (wait <= 0L) {
            pressed = pressed - code
            return
        }
        releaseTimers.remove(code)?.let(handler::removeCallbacks)
        val timer = Runnable {
            releaseTimers.remove(code)
            pressed = pressed - code
        }
        releaseTimers[code] = timer
        handler.postDelayed(timer, wait)
    }

    private fun showPopup(code: Int) {
        val pos = grid.find(code) ?: return
        val text = popupText(grid.key(pos)) ?: return
        handler.removeCallbacks(hidePopup)
        popup = KeyPopup(code, text)
        popupOn = true
    }

    private fun hidePopupSoon() {
        handler.removeCallbacks(hidePopup)
        handler.postDelayed(hidePopup, POPUP_MS)
    }

    private fun hasController(): Boolean = InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id)
        device != null && !device.isVirtual && (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
    }

    /** Applies the size settings (Java side of [KeyboardPrefs]). */
    fun setPrefs(autoFitDisabled: Boolean, heightDp: Int, widthDp: Int, alignMode: String?, opacityPercent: Int) {
        prefs = KeyboardPrefs(autoFitDisabled, heightDp, widthDp, KeyboardAlign.parse(alignMode), opacityPercent.coerceIn(0, 100))
    }

    private companion object {
        /** A modifier held this long stays latched (`timerLongClickTimeout` of the old keyboard). */
        const val LATCH_HOLD_MS = 300L

        /** The popup stays this long after the release. */
        const val POPUP_MS = 75L

        /** The keyboard stays composed this much longer than its exit animation. */
        const val LEAVE_MARGIN_MS = 40L
    }
}
