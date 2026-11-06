package com.limelight.shell.ui

import com.limelight.utils.ToastKind
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A toast shows at most this many lines of text, then an ellipsis (no marquee: a toast is never focused). */
const val TOAST_MAX_LINES = 2

/** The word above the text of the toast. */
val ToastKind.word: String
    get() = when (this) {
        ToastKind.INFO -> "INFO"
        ToastKind.SUCCESS -> "DONE"
        ToastKind.WARNING -> "WARNING"
        ToastKind.ERROR -> "ERROR"
        ToastKind.ACTION -> "UNDO"
    }

/** The icon of a toast that brings none of its own. */
val ToastKind.defaultIcon: String
    get() = when (this) {
        ToastKind.INFO -> Symbols.INFO
        ToastKind.SUCCESS -> Symbols.CHECK_CIRCLE
        ToastKind.WARNING -> Symbols.WARNING
        ToastKind.ERROR -> Symbols.ERROR
        ToastKind.ACTION -> Symbols.DELETE
    }

/**
 * The kind of a toast: a toast with an action is always [ToastKind.ACTION]; otherwise the caller's [explicit] kind;
 * otherwise it is read from the [icon] the caller chose (a failure icon is an error, a check a success, the warning
 * triangle a warning), and everything else is information.
 */
fun toastKindFor(explicit: ToastKind?, icon: String?, hasAction: Boolean): ToastKind {
    if (hasAction) return ToastKind.ACTION
    if (explicit != null) return explicit
    return when (icon) {
        Symbols.ERROR, Symbols.WIFI_OFF, Symbols.LINK_OFF -> ToastKind.ERROR
        Symbols.CHECK_CIRCLE, Symbols.CHECK -> ToastKind.SUCCESS
        Symbols.WARNING -> ToastKind.WARNING
        else -> ToastKind.INFO
    }
}

/**
 * The widest the text of a toast may be (stage pixels): 560 at the interface [scale], and never wider than the stage
 * minus 360 so a 4:3 screen at 130 % still has room for the icon block and the margins.
 */
fun toastTextMaxWidth(stageWidth: Float, scale: Float): Float = min(560f * scale, stageWidth - 360f * scale)

/** Timings of the console toast that are not in [Motion] (milliseconds, stage pixels, fractions). */
object ToastTiming {
    // How long a toast stays, from the moment it starts to enter to the moment it starts to leave (milliseconds). One place for all of them.
    /** The stream's and the controller windows' short and long hints (`DelosToast.SHORT` / `LONG`). */
    const val STREAM_SHORT_MS = 4000L
    const val STREAM_LONG_MS = 6500L

    /** A toast of the shell. */
    const val SHELL_MS = 4500L

    /** A toast with an action: the profile deletion lets itself be undone for as long. */
    const val ACTION_MS = 10_000L

    /** Distance the card travels beyond its own width when it springs in from outside the right edge. */
    const val SLIDE_EXTRA = 140f

    /** Distance the card leaves to the right. */
    const val EXIT_SLIDE = 72f

    // The enter is a spring (a single, clearly visible overshoot): the card is 94 % wide at the start and grows to 100 % around its right edge.
    /** Damping ratio and stiffness (mass 1) of the enter spring: it overshoots 8.4 % of the travel (peak at 327 ms), first reaches its place at 233 ms and is within 1 % after 627 ms. */
    const val SPRING_DAMPING = .62f
    const val SPRING_STIFFNESS = 150f
    const val FROM_SCALE = .94f

    /** When the card first reaches its place (ms after it started to enter; `springStep` crosses 1 at 233 ms): everything else is timed against it. */
    const val LANDING_MS = 230

    /** The icon block pops and the word, the text and the chip rise (one [Motion.STAGGER_ROW] apart) just before the card lands, so they settle with its overshoot. */
    const val ICON_DELAY = LANDING_MS - 100
    const val ROW_AT = LANDING_MS - 90

    /** The kind-coloured halo flashes as the card lands, then settles at [GLOW_REST] of its peak. */
    /** The glow comes up quickly (its own short fade, ahead of the card's), so it is already there when the card lands. */
    const val GLOW_IN = 150
    const val GLOW_AT = 200
    const val GLOW_MS = 900
    const val GLOW_REST = .45f

    /** The light sweep crosses the card once, right after it lands. */
    const val SWEEP_AT = LANDING_MS + 20
    const val SWEEP_MS = 860

    /** The gold timer line draws in, then drains. */
    const val LINE_AT = LANDING_MS + 50
    const val LINE_DRAW = 420

    // The soft halo and drop shadow around the card: a many-stop eased gradient per edge and corner (no blur, no banding at these alphas)
    /** Opacity of the glow at the card's edge at its peak, and how far it reaches (stage pixels). */
    const val GLOW_PEAK = .42f
    const val GLOW_REACH = 150f
    /** The glow's strength as the card lands, as a multiple of its settled strength: it rises with the card's fade-in and then falls quickly to [GLOW_REST]. */
    const val GLOW_FLASH = 1.2f

    /** The drop shadow: its opacity at the edge, reach, drop and inset from the card (stage pixels). */
    const val SHADOW_PEAK = .22f
    const val SHADOW_REACH = 44f
    const val SHADOW_DROP = 14f
    const val SHADOW_INSET = 10f

    /** The enter spring's position [ms] after the card started to enter: 0 at the start, 1 at rest, above 1 while it overshoots. */
    fun springStep(ms: Float): Float {
        if (ms <= 0f) return 0f
        val w0 = sqrt(SPRING_STIFFNESS)
        val wd = w0 * sqrt(1f - SPRING_DAMPING * SPRING_DAMPING)
        val t = ms / 1000f
        return 1f - exp(-SPRING_DAMPING * w0 * t) * (cos(wd * t) + SPRING_DAMPING * w0 / wd * sin(wd * t))
    }

    /** How strong the halo is at [t] (0 at the card's edge, 1 at its reach) as a fraction of its peak: a smoothstep, so it starts and ends without a visible edge. */
    fun haloFalloff(t: Float): Float {
        val s = 1f - t.coerceIn(0f, 1f)
        return s * s * (3f - 2f * s)
    }

    /** How much of the timer line is there [age] ms after the toast started to enter: it draws in, then drains over [holdMs]. */
    fun timerFraction(age: Float, holdMs: Long): Float {
        val left = (1f - age / holdMs).coerceIn(0f, 1f)
        return min(Motion.progress(age, LINE_AT, LINE_DRAW, EaseOut), left)
    }

    /** The squeeze of the Undo chip [sinceMs] after it was pressed (negative: not pressed). */
    fun pressScale(sinceMs: Float): Float = when {
        sinceMs < 0f -> 1f
        sinceMs < Motion.PRESS_HOLD -> Motion.lerp(1f, Motion.PRESS_SCALE, Motion.progress(sinceMs, 0, Motion.PRESS, EaseSpring))
        else -> Motion.lerp(Motion.PRESS_SCALE, 1f, Motion.progress(sinceMs, Motion.PRESS_HOLD.toInt(), Motion.PRESS, EaseSpring))
    }
}

/** One toast on the stage: it starts to enter at [startMs], leaves at [exitMs] and is gone [Motion.EXIT] ms after that. */
class ToastSlot<T>(val payload: T, val startMs: Long, val holdMs: Long) {
    /** Set when a newer toast (or a dismissal) sends this one away before its time is up. */
    var forcedExitMs = Long.MAX_VALUE
        internal set
    var pressMs = NO_PRESS
        internal set
    internal var notified = false

    val exitMs: Long get() = min(forcedExitMs, startMs + holdMs)
    val goneMs: Long get() = exitMs + Motion.EXIT

    companion object {
        const val NO_PRESS = Long.MIN_VALUE / 4
    }
}

/**
 * The replace rule of the console toast, as plain arithmetic on a clock in milliseconds: there is never a queue. A new
 * toast sends every live one away at once (they leave in [Motion.EXIT]) and, when there was one, starts to enter
 * [Motion.ROW_DELAY] later, so the two cross; a toast that arrives in that gap replaces the waiting one (which never
 * shows). Without a live toast it enters at once.
 */
class ToastStageLogic<T> {
    private val items = mutableListOf<ToastSlot<T>>()

    val isEmpty: Boolean get() = items.isEmpty()

    fun slots(): List<ToastSlot<T>> = items.toList()

    fun show(payload: T, holdMs: Long, now: Long): ToastSlot<T> {
        removeGone(now)
        var replaced = false
        for (slot in items) {
            if (now < slot.exitMs) {
                replaced = true
                slot.forcedExitMs = min(slot.forcedExitMs, now)
            }
        }
        val slot = ToastSlot(payload, if (replaced) now + Motion.ROW_DELAY else now, holdMs)
        items.add(slot)
        return slot
    }

    /** Sends every live toast away (the toast was dismissed or its action ran). */
    fun dismiss(now: Long) {
        for (slot in items) if (now < slot.exitMs) slot.forcedExitMs = min(slot.forcedExitMs, now)
    }

    /** The newest toast that has not started to leave. */
    fun latestLive(now: Long): ToastSlot<T>? = items.lastOrNull { now < it.exitMs }

    /** The Undo chip of the newest live toast was pressed. */
    fun press(now: Long) {
        latestLive(now)?.pressMs = now
    }

    /** Calls [onExit] once for each toast whose time is up, drops the ones that have left; true when the list changed. */
    fun advance(now: Long, onExit: (ToastSlot<T>) -> Unit): Boolean {
        for (slot in items) {
            if (now >= slot.exitMs && !slot.notified) {
                slot.notified = true
                onExit(slot)
            }
        }
        return removeGone(now)
    }

    private fun removeGone(now: Long): Boolean = items.removeAll { now >= it.goneMs }
}
