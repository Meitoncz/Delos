package com.limelight.shell.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/** The prototype's --ease-out: fast start, long soft landing. */
val EaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** The prototype's --ease-spring: overshoots a little, used for focus scaling. */
val EaseSpring = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/**
 * The motion language of Delos, in one place: the durations, delays, easings and the pure arithmetic behind every
 * animation (docs/GUI.md, "Motion"). It started as the stream menu's `--d-*` / `--ease-*` variables of the prototype
 * (`docs/prototype/delos-gui.html`, `#quickmenu`) and is now what every screen animates with, so one press feels the
 * same everywhere: focus and press are springs, things enter by rising a little and fading in one after the other,
 * leave faster than they came, a menu grows out of its anchor, a changed value slides in from the side the key came
 * from. With reduced motion only fades remain (see [rise], [scaleIn], [slide]).
 *
 * The building blocks that use these numbers (modifiers, switch, glide ring, transitions) are in `MotionKit.kt`; this
 * file has no Compose runtime in it, so the arithmetic is unit tested (`MotionTest`).
 */
object Motion {
    // Easings
    /** `--ease-glide`: the ring's glide, overshoots a little. */
    val Glide: Easing = CubicBezierEasing(.3f, 1.22f, .42f, 1f)

    /** `--ease-in`: everything that leaves. */
    val In: Easing = CubicBezierEasing(.5f, 0f, .75f, 0f)

    /** CSS `ease`: colors and opacities. */
    val Ease: Easing = CubicBezierEasing(.25f, .1f, .25f, 1f)

    /** CSS `ease-in`: the rows of a page that is replaced. */
    val EaseIn: Easing = CubicBezierEasing(.42f, 0f, 1f, 1f)

    /** CSS `ease-out`: the labels' entrance. */
    val EaseOutCss: Easing = CubicBezierEasing(0f, 0f, .58f, 1f)

    /** CSS `ease-in-out`: the ring's breathing. */
    val EaseInOut: Easing = CubicBezierEasing(.42f, 0f, .58f, 1f)

    // Durations, milliseconds
    /** One element's entrance (the stream menu's tiles; a screen's headline). */
    const val ENTER = 620

    /** What leaves does so quickly: every exit (layer, menu, popover, screen) takes this long. */
    const val EXIT = 240

    /** Focus: the spring of a scale, a ring, a knob, a lifted element. */
    const val FOCUS = 420

    /** The squeeze of a pressed element, and how long it is held before it springs back. */
    const val PRESS = 120
    const val PRESS_HOLD = 130L

    /** Colors and opacities (`ease`). */
    const val FADE = 280

    /** The focus ring gliding to the next element. */
    const val RING = 560
    /** For this long after a gliding ring first appears it follows its element exactly (the screen is still entering). */
    const val RING_FOLLOW_MS = 900

    /** A popover or menu growing out of its anchor, and shrinking back into it. */
    const val POP_IN = 560
    const val POP_OUT = EXIT

    /** The glass of a card that grows fades in faster than the card grows. */
    const val CARD_FADE_IN = (POP_IN * .6f).toInt()

    /** The rows of a page or a menu rise one after the other: the first after [ROW_DELAY], then [STAGGER_ROW] apart. */
    const val ROW_DELAY = 120
    const val STAGGER_ROW = 46
    const val ROW_IN = 420

    /** The items of a context menu, a dialog or the side menu start sooner: they are what the user just asked for. */
    const val MENU_DELAY = 60

    /** A caption or a help text changing. */
    const val CAPTION = 420

    /** A gradient (the stream's backdrop) and a scrim. */
    const val GRADIENT = 460
    const val SCRIM = 420

    /** The ring's breathing. */
    const val BREATHE = 2600

    /** A pill whose text changes, and a badge that appears, pops in from [POP_FROM_PILL]. */
    const val PILL_POP = 460

    /** A choice's new value slides in, the chevron it went toward nudges. */
    const val CHOICE_BUMP = 320
    const val CHOICE_SLIDE = 300

    /** A slider's fill following a new value. */
    const val SLIDER_FILL = 160

    /** A screen replaced by another one: the new one settles in, the old one leaves ([EXIT]). */
    const val SCREEN_IN = 560

    // Distances and scales
    /** Stage pixels a row rises while it enters. */
    const val RISE = 18f

    /** Stage pixels a new value slides in, and a chevron nudges. */
    const val VALUE_SLIDE = 22f
    const val CHEVRON_BUMP = 7f

    /** A pressed element squeezes to this scale (absolute, below every focus scale). */
    const val PRESS_SCALE = .97f

    /** A card grows out of its anchor from this scale. */
    const val POP_FROM_SCALE = .86f

    /** A badge or pill that pops in. */
    const val POP_FROM_PILL = .6f

    /** A dialog settles in from this scale; a screen from [SCREEN_FROM_SCALE]. */
    const val DIALOG_FROM_SCALE = .96f
    const val SCREEN_FROM_SCALE = 1.035f

    /** The knob of a focused slider grows to this. */
    const val KNOB_FOCUS_SCALE = 1.18f

    /** At most this many rows of a screen or a menu wait for the ones before them: the rest rise with the last one. */
    const val STAGGER_CAP = 8

    /** A screen's elements enter in this window after the screen is composed; later arrivals just appear. */
    const val STAGGER_WINDOW = 700L

    // A geometry that follows its target uses a slightly under-damped spring
    const val GEO_STIFFNESS = 190f
    const val GEO_DAMPING = .8f

    /** [easing] of how far an animation that starts at [delay] and lasts [duration] has come at [time]: 0 before it, 1 after it (the easing may overshoot between). */
    fun progress(time: Float, delay: Int, duration: Int, easing: Easing): Float {
        val linear = ((time - delay) / duration).coerceIn(0f, 1f)
        return easing.transform(linear)
    }

    /** Linear progress of an animation (no easing). */
    fun linear(time: Float, delay: Int, duration: Int): Float = ((time - delay) / duration).coerceIn(0f, 1f)

    fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

    /** When element [index] of a staggered entrance starts: [base] for the first one, then [step] apart. */
    fun staggerDelay(index: Int, base: Int, step: Int): Int = base + index.coerceAtLeast(0) * step

    /** When element [index] of [count] leaves in a staggered exit that starts with the last one. */
    fun reverseStaggerDelay(index: Int, count: Int, step: Int): Int = (count - 1 - index).coerceAtLeast(0) * step

    /** How long a staggered entrance of [count] elements takes in all, milliseconds (0 for none). */
    fun staggerTotal(count: Int, base: Int, step: Int, duration: Int): Int =
        if (count <= 0) 0 else staggerDelay(count - 1, base, step) + duration

    /** How far a rising element still has to go at [progress]; reduced motion: it does not move, it only fades. */
    fun rise(distance: Float, progress: Float, reduced: Boolean): Float = if (reduced) 0f else distance * (1f - progress)

    /** The scale of an element growing from [from] to 1 at [progress]; reduced motion: no scaling. */
    fun scaleIn(from: Float, progress: Float, reduced: Boolean): Float = if (reduced) 1f else lerp(from, 1f, progress)

    /** The offset of a value sliding in from the side [direction] (-1 left, 1 right) came from; reduced motion: none. */
    fun slide(direction: Int, distance: Float, progress: Float, reduced: Boolean): Float =
        if (reduced) 0f else direction.coerceIn(-1, 1) * distance * (1f - progress)

    /** The nudge of the chevron on [side] (-1 left, 1 right) while a choice stepped in [direction]: only the chevron that was pressed. */
    fun chevronBump(side: Int, direction: Int, bump: Float, reduced: Boolean): Float =
        if (reduced || Integer.signum(direction) != side) 0f else side * CHEVRON_BUMP * bump

    /** The scale an element aims for: pressed squeezes it ([pressScale] 1 turns that off), else focus lifts it. */
    fun scaleTarget(focused: Boolean, pressed: Boolean, focusScale: Float, pressScale: Float): Float = when {
        pressed && pressScale < 1f -> pressScale
        focused -> focusScale
        else -> 1f
    }
}

/**
 * Counts the elements of one screen, menu or page as they are composed, so each knows its place in the staggered
 * entrance ([take]). Without [enabled] (a menu that is already open) nothing animates. [cap] is the last place
 * anyone waits for; an element composed more than [lateAfterMs] after the counter was made (a row that shows up later)
 * is not part of the entrance at all, and neither is one that lazy lists compose while scrolling. [now] is the clock.
 */
class EntranceStagger(
    val enabled: Boolean,
    private val cap: Int = Int.MAX_VALUE,
    private val lateAfterMs: Long = Long.MAX_VALUE,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val created = now()
    private var next = 0

    /** The next place in the entrance, or -1 when this element does not take part in it. */
    fun take(): Int {
        if (!enabled || now() - created > lateAfterMs) return -1
        return minOf(next++, cap)
    }
}
