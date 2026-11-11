package com.limelight.shell.companion

import android.view.KeyEvent
import com.limelight.shell.StreamStats
import com.limelight.shell.settings.ImpactLevel
import com.limelight.shell.settings.SettingImpact
import com.limelight.shell.settings.impact
import java.util.Locale

/*
 * The pure parts of the companion screen (no Compose, no views), unit tested in CompanionLogicTest: the burn-in policy, the
 * statistics throttle, the touch keyboard's key mapping and echo, and the impact summary of a game.
 */

/**
 * The burn-in policy of the AMOLED companion (docs/DUAL_SCREEN.md, "Burn-in"). Two measures, both cheap:
 * - **Pixel shift**: all content moves by a few device-independent pixels every [SHIFT_PERIOD_MS], through a fixed serpentine
 *   path over a 3 × 3 grid with a [SHIFT_STEP_DP] pitch, so no edge (the header rule, a button outline, a logo) stays on the
 *   same pixels for more than two minutes. The cycle is 18 minutes.
 * - **Dim**: in the shell state, where the same picture can stay all day, the content fades to [DIM_ALPHA] after [DIM_AFTER_MS]
 *   without a touch; a touch wakes it (and is not passed on, so waking can never press a button).
 */
object CompanionBurnIn {
    const val SHIFT_PERIOD_MS = 120_000L
    const val SHIFT_STEP_DP = 3
    const val DIM_AFTER_MS = 60_000L
    const val DIM_ALPHA = .22f

    /** Offset of a shift position in steps of [SHIFT_STEP_DP]: -1, 0 or 1 on each axis. */
    data class Shift(val x: Int, val y: Int)

    private val PATH = listOf(
        Shift(0, 0), Shift(1, 0), Shift(1, 1), Shift(0, 1), Shift(-1, 1), Shift(-1, 0), Shift(-1, -1), Shift(0, -1), Shift(1, -1),
    )

    /** The shift [elapsedMs] after the window started. Starts in place; the whole path is visited once per cycle, then repeats. */
    fun shiftAt(elapsedMs: Long): Shift = PATH[((elapsedMs.coerceAtLeast(0L) / SHIFT_PERIOD_MS) % PATH.size).toInt()]

    /** Whether the content is dimmed at [now] when the last touch was at [lastTouchMs] (same clock). */
    fun dimmed(lastTouchMs: Long, now: Long): Boolean = now - lastTouchMs >= DIM_AFTER_MS
}

/**
 * Lets at most one statistics snapshot through per [minIntervalMs] (2 Hz by default), so the companion's recomposition costs
 * nothing next to the stream: the decoder reports about once a second, and anything faster is dropped before it reaches the main
 * thread. Not thread safe: one caller (the thread that reports statistics).
 */
class StatsThrottle(private val minIntervalMs: Long = 500L) {
    private var last = Long.MIN_VALUE

    /** True when a snapshot arriving at [now] (milliseconds, a monotonic clock) is to be shown. */
    fun accept(now: Long): Boolean {
        if (last != Long.MIN_VALUE && now - last < minIntervalMs) return false
        last = now
        return true
    }

    fun reset() {
        last = Long.MIN_VALUE
    }
}

/** A key of the host's keyboard and whether Shift has to be held with it. */
data class KeyStroke(val code: Int, val shift: Boolean = false)

/**
 * The US layout the host expects from the key events the Full keyboard sends (the host turns Android key codes into its own
 * through `KeyboardTranslator`): the key and Shift for a typed character.
 */
object KeyMap {
    private val SHIFTED_DIGITS = ")!@#$%^&*("
    private const val PLAIN_PUNCTUATION = "`-=[]\\;',./"
    private const val SHIFTED_PUNCTUATION = "~_+{}|:\"<>?"
    private val PUNCTUATION_CODES = intArrayOf(
        KeyEvent.KEYCODE_GRAVE, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET,
        KeyEvent.KEYCODE_BACKSLASH, KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD,
        KeyEvent.KEYCODE_SLASH,
    )

    /** The stroke that types [c], null for a character the US layout has no key for. */
    fun stroke(c: Char): KeyStroke? = when (c) {
        in 'a'..'z' -> KeyStroke(KeyEvent.KEYCODE_A + (c - 'a'))
        in 'A'..'Z' -> KeyStroke(KeyEvent.KEYCODE_A + (c - 'A'), shift = true)
        in '0'..'9' -> KeyStroke(KeyEvent.KEYCODE_0 + (c - '0'))
        ' ' -> KeyStroke(KeyEvent.KEYCODE_SPACE)
        else -> SHIFTED_DIGITS.indexOf(c).takeIf { it >= 0 }?.let { KeyStroke(KeyEvent.KEYCODE_0 + it, shift = true) }
            ?: PLAIN_PUNCTUATION.indexOf(c).takeIf { it >= 0 }?.let { KeyStroke(PUNCTUATION_CODES[it]) }
            ?: SHIFTED_PUNCTUATION.indexOf(c).takeIf { it >= 0 }?.let { KeyStroke(PUNCTUATION_CODES[it], shift = true) }
    }

    /** The key events of a finger going down on [stroke], in order: the modifier first. */
    fun down(stroke: KeyStroke): List<Pair<Int, Boolean>> =
        if (stroke.shift) listOf(KeyEvent.KEYCODE_SHIFT_LEFT to true, stroke.code to true) else listOf(stroke.code to true)

    /** The key events of the finger coming up: the key first, then the modifier. */
    fun up(stroke: KeyStroke): List<Pair<Int, Boolean>> =
        if (stroke.shift) listOf(stroke.code to false, KeyEvent.KEYCODE_SHIFT_LEFT to false) else listOf(stroke.code to false)
}

/**
 * The little echo of what was typed (the companion keyboard has no text field: the keys go to the stream). It keeps the last
 * [MAX] characters; Backspace takes one off, Enter shows as a return mark.
 */
class TypedEcho(private val text: String = "") {
    val value: String get() = text

    fun typed(c: Char): TypedEcho = TypedEcho((text + c).takeLast(MAX))

    fun enter(): TypedEcho = typed(RETURN)

    fun backspace(): TypedEcho = TypedEcho(text.dropLast(1))

    fun cleared(): TypedEcho = TypedEcho()

    companion object {
        const val MAX = 40
        const val RETURN = '\u21B5'
    }
}

/** The settings whose cost the chips of a game add up (the keys of `impact`, the same chips the settings rows have). */
private val IMPACT_KEYS = listOf(
    "list_resolution", "list_fps", "seekbar_bitrate_kbps", "video_format", "checkbox_enable_hdr", "frame_pacing", "checkbox_sgsr",
    "list_sgsr_quality", "seekbar_sgsr_sharpness", "list_sgsr_supersampling_level",
)

/**
 * What a game costs in one summary: on each axis the highest level any of its effective stream settings has (the settings rows' own
 * chips, [impact]). Never a measurement, like the chips. Null when no setting has a chip (the settings are not loaded yet).
 */
fun gameImpact(values: Map<String, *>): SettingImpact? {
    val all = IMPACT_KEYS.mapNotNull { impact(it, values) }
    if (all.isEmpty()) return null
    fun top(level: (SettingImpact) -> ImpactLevel) = all.maxOf { level(it) }
    return SettingImpact(top { it.gpu }, top { it.latency }, top { it.network })
}

/** The numbers the stream state shows, formatted like the statistics panel does ([Locale.US]: readouts are not translated). */
data class StatsReadout(val fps: String, val network: String, val decode: String, val picture: String?) {
    companion object {
        /** No statistics yet: the dash of every value. */
        val EMPTY = StatsReadout("\u2013", "\u2013", "\u2013", null)

        fun of(stats: StreamStats?): StatsReadout {
            if (stats == null) return EMPTY
            val picture = buildString {
                append("${stats.width} \u00d7 ${stats.height} \u00b7 ${String.format(Locale.US, "%.0f", stats.fps)} FPS \u00b7 ${stats.codec}") // l10n-ignore
                if (stats.hdr) append(" HDR")
            }
            return StatsReadout(
                fps = String.format(Locale.US, "%.1f", stats.fps),
                network = "${stats.networkMs} ms", // l10n-ignore
                decode = String.format(Locale.US, "%.1f ms", stats.decodeMs), // l10n-ignore
                picture = picture,
            )
        }
    }
}
