package com.limelight.shell.keyboard

import android.view.KeyEvent
import com.limelight.shell.MenuDirection
import kotlin.math.abs
import kotlin.math.min

/*
 * The pure logic of the Full keyboard: where the D-pad moves the focus, what a modifier does when it is touched or
 * pressed with a controller, which controller buttons mean what, and how big the keys are. No Compose and no views in
 * here, so it is unit tested (FullKeyboardInputTest); FullKeyboardState puts it to work.
 */

/** A key in the grid: its row and its place in that row. */
data class KeyPos(val row: Int, val col: Int)

/** The part of a row a key covers, in the units of [KeyGrid.NAV_WIDTH]. */
data class KeySpan(val start: Float, val end: Float) {
    val center: Float get() = (start + end) / 2f
}

/** Where the focus went and the horizontal position [anchorX] that later up and down moves aim for. */
data class NavMove(val pos: KeyPos, val anchorX: Float)

/**
 * The key grid the controller moves through. A move left or right stays in its row and stops at the ends; up and down go
 * to the key of the next row that lies under the *anchor* (the center of the last key reached by a horizontal move), or
 * else to the nearest one, so a column of the keyboard is followed even though the rows are not aligned. Spans come from
 * the weights, gaps and margins of the rows, laid out at [NAV_WIDTH]: only their proportions matter.
 */
internal class KeyGrid(val rows: List<List<KeyDef>>) {
    private val rowSpans: List<List<KeySpan>> = rows.map { layoutSpans(it) }

    fun key(pos: KeyPos): KeyDef = rows[pos.row][pos.col]

    fun span(pos: KeyPos): KeySpan = rowSpans[pos.row][pos.col]

    fun find(code: Int): KeyPos? {
        for (r in rows.indices) {
            val c = rows[r].indexOfFirst { it.code == code }
            if (c >= 0) return KeyPos(r, c)
        }
        return null
    }

    /** The focus after a step in [direction] from [from], null at the edge of the keyboard (the ring nudges there). */
    fun move(from: KeyPos, anchorX: Float, direction: MenuDirection): NavMove? {
        val row = from.row
        return when (direction) {
            MenuDirection.LEFT -> if (from.col > 0) horizontal(KeyPos(row, from.col - 1)) else null
            MenuDirection.RIGHT -> if (from.col < rows[row].size - 1) horizontal(KeyPos(row, from.col + 1)) else null
            MenuDirection.UP -> if (row > 0) NavMove(KeyPos(row - 1, nearest(row - 1, anchorX)), anchorX) else null
            MenuDirection.DOWN -> if (row < rows.size - 1) NavMove(KeyPos(row + 1, nearest(row + 1, anchorX)), anchorX) else null
        }
    }

    /** A focus that lands on [pos] directly (the first key, a remembered key): its center is the anchor. */
    fun land(pos: KeyPos) = NavMove(pos, span(pos).center)

    private fun horizontal(pos: KeyPos) = NavMove(pos, span(pos).center)

    /** The column of [row] that lies under [x], the nearest one when none does (the first of two equally near ones). */
    private fun nearest(row: Int, x: Float): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        rowSpans[row].forEachIndexed { col, span ->
            val distance = if (x >= span.start && x <= span.end) 0f else min(abs(x - span.start), abs(x - span.end))
            if (distance < bestDistance) {
                bestDistance = distance
                best = col
            }
        }
        return best
    }

    companion object {
        /** The width the grid is laid out at for navigation, in stage pixels (the widest keyboard). */
        const val NAV_WIDTH = KeyboardMetrics.MAX_BODY_WIDTH

        /** The key at which the controller focus starts (and the one it falls back to): Q. */
        const val FIRST_KEY = KeyEvent.KEYCODE_Q

        val standard = KeyGrid(FullKeyboardKeys.rows)

        /**
         * Where the keys of [row] lie in a row [width] wide with [gap] between keys and [sep] for every margin: the weights
         * share what the gaps leave, a margin takes from its own key only (like CSS grid tracks with margins on the items).
         */
        fun layoutSpans(row: List<KeyDef>, width: Float = NAV_WIDTH, gap: Float = KeyboardMetrics.GAP, sep: Float = KeyboardMetrics.SEP): List<KeySpan> {
            val free = width - gap * (row.size - 1)
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            var x = 0f
            return row.map { key ->
                val track = free * key.weight / total
                val span = KeySpan(x + if (key.marginStart) sep else 0f, x + track - if (key.marginEnd) sep else 0f)
                x += track + gap
                span
            }
        }
    }
}

/** What the keyboard sends to the host for a touch or a press. */
enum class KeySend { NONE, DOWN, UP }

/**
 * Which modifiers are latched, and what touching or pressing one sends. A latched modifier is *down* on the host until
 * it is released, so the keys typed meanwhile carry it.
 *
 * Touch (sticky modifier setting on): a press sends Down; holding it for 300 ms latches it and the release is not sent;
 * touching a latched modifier unlatches it (that touch sends nothing, its release sends Up). With the setting off a
 * modifier is an ordinary key. A controller cannot hold one key while pressing another, so A on a modifier toggles the
 * latch whatever the setting says: Down (latched) or Up (unlatched). Hiding the keyboard releases every latched modifier.
 */
class ModifierLatch {
    private val latched = LinkedHashSet<Int>()

    fun isLatched(code: Int): Boolean = code in latched

    /** The latched modifiers, as a copy. */
    fun codes(): Set<Int> = latched.toSet()

    fun touchDown(code: Int, sticky: Boolean): KeySend {
        if (sticky && isModifierCode(code) && code in latched) {
            latched.remove(code)
            return KeySend.NONE
        }
        return KeySend.DOWN
    }

    fun touchUp(code: Int, sticky: Boolean): KeySend =
        if (sticky && isModifierCode(code) && code in latched) KeySend.NONE else KeySend.UP

    /** The touch has been held long enough: the modifier stays down. */
    fun latchHeld(code: Int) {
        latched.add(code)
    }

    /** The keyboard is hidden: every latched modifier is let go. Returns the ones that were down, in the order they were latched, so the host gets their Up. */
    fun releaseAll(): List<Int> {
        val released = latched.toList()
        latched.clear()
        return released
    }

    fun padPress(code: Int): KeySend =
        if (latched.remove(code)) KeySend.UP else { latched.add(code); KeySend.DOWN }
}

/** What a controller button does while the keyboard is up. */
internal sealed interface PadAction {
    data class Move(val direction: MenuDirection) : PadAction
    data object PressDown : PadAction
    data object PressUp : PadAction
    data object Close : PadAction
    data object ToggleShift : PadAction
}

/**
 * The keyboard action of a controller key event, or null for a key the keyboard does not use (it still swallows it, so
 * nothing reaches the game): A holds the focused key down and lets it go (also when the release is cancelled), B and Back
 * close on release, X toggles Shift. The D-pad keys are not here: they go through [DpadTracker] ([dpadDirection]), which
 * also takes the hat axes of the same D-pad.
 */
internal fun padKeyAction(keyCode: Int, keyAction: Int, repeatCount: Int, canceled: Boolean): PadAction? = when (keyCode) {
    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> when (keyAction) {
        KeyEvent.ACTION_DOWN -> if (repeatCount == 0) PadAction.PressDown else null
        KeyEvent.ACTION_UP -> PadAction.PressUp
        else -> null
    }
    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK ->
        if (keyAction == KeyEvent.ACTION_UP && !canceled) PadAction.Close else null
    KeyEvent.KEYCODE_BUTTON_X -> if (keyAction == KeyEvent.ACTION_DOWN && repeatCount == 0) PadAction.ToggleShift else null
    else -> null
}

/** The direction a D-pad key code means, null for any other key. */
internal fun dpadDirection(keyCode: Int): MenuDirection? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> MenuDirection.UP
    KeyEvent.KEYCODE_DPAD_DOWN -> MenuDirection.DOWN
    KeyEvent.KEYCODE_DPAD_LEFT -> MenuDirection.LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> MenuDirection.RIGHT
    else -> null
}

/**
 * The D-pad of a controller, which reaches the app in one of two shapes: as DPAD_* key events (they repeat by themselves)
 * or, on most handhelds (the Odin among them), as the hat axes of a joystick motion event (`AXIS_HAT_X` / `AXIS_HAT_Y`, -1,
 * 0 or 1, sent only when they change, so nothing repeats unless a timer does). One physical press must move the focus
 * once, whichever shape (or both) the device sends.
 *
 * - [onHat] is edge-triggered: a direction that is newly pressed moves at once, and [repeatAt] / [repeat] then repeat the
 *   most recent one after [initialDelayMs] every [repeatIntervalMs] until the hat returns to the center (or that
 *   direction is let go). A diagonal is the direction pressed last; rolling the thumb from Right to Up-right moves Up, and
 *   releasing Up afterwards stops the repeat (the thumb makes no new press) until the next one.
 * - [onKey] says whether a D-pad key event moves: not when the same direction is already held on the hat, and
 *   a hat press that arrives while the same direction's key is held does not move either (the key's own repeat does).
 */
internal class DpadTracker(
    private val initialDelayMs: Long = 350,
    private val repeatIntervalMs: Long = 100,
) {
    private var hatX = 0
    private var hatY = 0
    private var hatDirection: MenuDirection? = null
    private var keyDirection: MenuDirection? = null
    private var nextRepeat = NO_REPEAT

    /** When [repeat] is next due ([android.os.SystemClock.uptimeMillis] time base), null while nothing repeats. */
    val repeatAt: Long? get() = if (nextRepeat == NO_REPEAT) null else nextRepeat

    /** The hat axes ([x] and [y]) changed or were re-sent at [now]; the direction to move to right now, if any. */
    fun onHat(x: Float, y: Float, now: Long): MenuDirection? {
        val sx = hatSign(x)
        val sy = hatSign(y)
        val pressedX = sx != 0 && sx != hatX
        val pressedY = sy != 0 && sy != hatY
        hatX = sx
        hatY = sy
        if (sx == 0 && sy == 0) {
            hatDirection = null
            nextRepeat = NO_REPEAT
            return null
        }
        val horizontal = if (sx < 0) MenuDirection.LEFT else MenuDirection.RIGHT
        val vertical = if (sy < 0) MenuDirection.UP else MenuDirection.DOWN
        if (!pressedX && !pressedY) {
            // Nothing new was pressed: a diagonal let go of one side keeps repeating the side that is still the one pressed last
            val held = hatDirection
            val stillHeld = held != null && ((sx != 0 && held == horizontal) || (sy != 0 && held == vertical))
            if (!stillHeld) {
                hatDirection = if (sx != 0) horizontal else vertical
                nextRepeat = NO_REPEAT
            }
            return null
        }
        val direction = if (pressedX) horizontal else vertical
        hatDirection = direction
        if (keyDirection == direction) {
            nextRepeat = NO_REPEAT
            return null
        }
        nextRepeat = now + initialDelayMs
        return direction
    }

    /** A D-pad key event; true when it moves the focus. */
    fun onKey(direction: MenuDirection, down: Boolean): Boolean {
        if (!down) {
            if (keyDirection == direction) keyDirection = null
            return false
        }
        keyDirection = direction
        val onHat = (hatX != 0 && direction == (if (hatX < 0) MenuDirection.LEFT else MenuDirection.RIGHT)) ||
            (hatY != 0 && direction == (if (hatY < 0) MenuDirection.UP else MenuDirection.DOWN))
        return !onHat
    }

    /** The hat's repeat timer fired at [now]: the direction to move to again, or null when none is due. */
    fun repeat(now: Long): MenuDirection? {
        val direction = hatDirection
        if (direction == null || nextRepeat == NO_REPEAT || now < nextRepeat) return null
        nextRepeat = now + repeatIntervalMs
        return direction
    }

    fun reset() {
        hatX = 0
        hatY = 0
        hatDirection = null
        keyDirection = null
        nextRepeat = NO_REPEAT
    }

    private fun hatSign(value: Float) = when {
        value <= -HAT_THRESHOLD -> -1
        value >= HAT_THRESHOLD -> 1
        else -> 0
    }

    private companion object {
        const val NO_REPEAT = Long.MIN_VALUE
        const val HAT_THRESHOLD = .5f
    }
}

private const val ANALOG_THRESHOLD = .55f

/** The direction a stick pushed to ([x], [y]) means: the stronger axis, once it is past the threshold; null near the center. */
internal fun analogDirection(x: Float, y: Float): MenuDirection? = when {
    abs(x) > abs(y) && x <= -ANALOG_THRESHOLD -> MenuDirection.LEFT
    abs(x) > abs(y) && x >= ANALOG_THRESHOLD -> MenuDirection.RIGHT
    y <= -ANALOG_THRESHOLD -> MenuDirection.UP
    y >= ANALOG_THRESHOLD -> MenuDirection.DOWN
    else -> null
}

/** Whether an input source is a controller (gamepad buttons, D-pad or sticks), as opposed to a keyboard, a mouse or the touch screen. */
fun isControllerSource(source: Int): Boolean =
    (source and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
        (source and android.view.InputDevice.SOURCE_DPAD) == android.view.InputDevice.SOURCE_DPAD ||
        (source and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK

/**
 * Whether the open keyboard owns [event]: it comes from a controller source, or it is a gamepad button (A, B, X, ...) that a
 * handheld's built-in pad reports with a plain keyboard source. A physical keyboard's keys (arrows, Enter, Esc) stay its own.
 */
fun isControllerEvent(event: android.view.InputEvent): Boolean =
    isControllerSource(event.source) || (event is KeyEvent && KeyEvent.isGamepadButton(event.keyCode))

/** How the user's size settings lay the keyboard out: see [keyboardMetrics]. */
data class KeyboardPrefs(
    /** `onscreen_keyboard_autofit` is the setting that turns the automatic fit *off*. */
    val autoFitDisabled: Boolean = false,
    /** `seekbar_onscreen_keyboard_height`, dp. */
    val heightDp: Int = 200,
    /** `seekbar_onscreen_keyboard_width`, dp; 1000 means the full width. */
    val widthDp: Int = 1000,
    val align: KeyboardAlign = KeyboardAlign.CENTER,
    /** `seekbar_keyboard_axi_opacity`, percent. */
    val opacityPercent: Int = 90,
)

enum class KeyboardAlign {
    LEFT, CENTER, RIGHT;

    companion object {
        /** The stored `list_onscreen_keyboard_align_mode` value; anything unknown is the center. */
        fun parse(value: String?): KeyboardAlign = when (value) {
            "left" -> LEFT
            "right" -> RIGHT
            else -> CENTER
        }
    }
}

/** The measures of the keyboard in stage pixels (1080 high), see [keyboardMetrics]. */
data class KeyboardMetrics(val bodyWidth: Float, val keyHeight: Float) {
    val fontSize: Float get() = keyHeight * FONT_PER_KEY
    val barHeight: Float get() = TOP_PAD + ROWS * keyHeight + (ROWS - 1) * GAP + BOTTOM_PAD

    /** Room above the bar for the popup of the top row. */
    val popupRoom: Float get() = keyHeight * 1.2f

    companion object {
        const val ROWS = 6
        const val GAP = 6f
        const val SEP = 12f
        const val SIDE = 24f
        const val TOP_PAD = 22f
        /** Under the rows: the hint strip (48 + 14) and the space above it. */
        const val BOTTOM_PAD = 78f
        const val MAX_BODY_WIDTH = 1840f
        const val FONT_PER_KEY = .38f
        const val MIN_KEY = 44f
        const val MAX_KEY = 68f
        const val WIDTH_PER_KEY = .0365f
        const val MIN_CUSTOM_KEY = 24f
        const val MAX_CUSTOM_KEY = 130f
        const val FULL_WIDTH_DP = 1000
    }
}

/**
 * The size of the keys and of the keyboard: by default the keys span the width of the screen less 24 on each side, at most
 * 1840, and are 3.65 % of that tall (44 to 68), so a 21:9 screen centers a keyboard of the same width and a 4:3 one gets
 * smaller keys instead of a taller keyboard. With the automatic fit off the settings apply: the width (1000 dp = the full
 * width) and the height of the whole bar in dp, from which the key height follows. [screenWidth] is in stage pixels,
 * [dpPerPx] the dp one stage pixel is.
 */
fun keyboardMetrics(screenWidth: Float, prefs: KeyboardPrefs, dpPerPx: Float): KeyboardMetrics {
    val widest = min(screenWidth - 2f * KeyboardMetrics.SIDE, KeyboardMetrics.MAX_BODY_WIDTH).coerceAtLeast(1f)
    if (!prefs.autoFitDisabled) {
        val key = (widest * KeyboardMetrics.WIDTH_PER_KEY).coerceIn(KeyboardMetrics.MIN_KEY, KeyboardMetrics.MAX_KEY)
        return KeyboardMetrics(widest, key)
    }
    val width = if (prefs.widthDp >= KeyboardMetrics.FULL_WIDTH_DP) widest else min(prefs.widthDp / dpPerPx, widest)
    val chrome = KeyboardMetrics.TOP_PAD + (KeyboardMetrics.ROWS - 1) * KeyboardMetrics.GAP + KeyboardMetrics.BOTTOM_PAD
    val key = ((prefs.heightDp / dpPerPx - chrome) / KeyboardMetrics.ROWS).coerceIn(KeyboardMetrics.MIN_CUSTOM_KEY, KeyboardMetrics.MAX_CUSTOM_KEY)
    return KeyboardMetrics(width, key)
}
