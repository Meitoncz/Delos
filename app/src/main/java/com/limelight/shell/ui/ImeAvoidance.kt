package com.limelight.shell.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect as AndroidRect
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.limelight.BuildConfig
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The pure arithmetic of keeping a text field above Android's on-screen keyboard (docs/GUI.md, "Text input").
 * Everything is in real pixels of the window: the keyboard is, and the stage scale only matters for the margin.
 */
object ImeShift {
    /** The gap that stays between the keyboard (or the screen's top edge) and what is kept visible, in stage pixels. */
    const val MARGIN = 24f

    /** A visible frame that is smaller than the window by less than this fraction of it is not a keyboard (a navigation bar, a cutout). */
    const val FRAME_MIN_FRACTION = .15f

    /** The keyboard's height, as a fraction of the window, when the system says it is visible but reports no height anywhere. */
    const val ESTIMATE_FRACTION = .5f

    /**
     * While a text field is being edited (activated by A or a tap, until the focus leaves it) its bottom edge is pinned at
     * most this far down the window, as a fraction of the window height: the field sits in the top quarter of a landscape
     * window, whatever the keyboard is. Keyboards report very different heights (the Odin's SwiftKey in its split layout says
     * 111 px and covers 58% of the screen) and can be resized, floated, split or switched at any moment, so this rule does not
     * look at the keyboard at all. The measured keyboard can only push the field higher.
     */
    const val EDIT_BAND_BOTTOM_FRACTION = .25f

    /** The same band in a portrait window (taller than wide). */
    const val EDIT_BAND_BOTTOM_FRACTION_PORTRAIT = .20f

    /** Where the editing band ends, in pixels from the top of a window of [windowWidth] x [windowHeight] pixels. */
    fun editBandBottom(windowWidth: Float, windowHeight: Float): Float =
        windowHeight * if (windowWidth >= windowHeight) EDIT_BAND_BOTTOM_FRACTION else EDIT_BAND_BOTTOM_FRACTION_PORTRAIT

    /**
     * The shift that pins a field being edited into the band: its bottom edge ([fieldBottom], window coordinates, without any
     * shift already applied) goes to [bandBottom] or above, and a field that is already there does not move. The field's top
     * keeps [margin] pixels from the window's top edge, so a field taller than the band is not pushed out of the window. What
     * is above the field (a dialog's title, a page's heading) is only left on the screen if there is room in the band.
     */
    fun bandShift(fieldTop: Float, fieldBottom: Float, bandBottom: Float, margin: Float): Float =
        (fieldBottom - bandBottom).coerceIn(0f, maxOf(fieldTop - margin, 0f))

    /**
     * Whether the keyboard is up at all, whatever its height: any source that measures something, or the system's own visible flag
     * ([imeVisible], from the root window insets). The flag is reliable where the heights are not (the Odin's SwiftKey says 111 px and
     * covers 58%, but its flag goes true and false with the keyboard), and it is what ends the shift the moment the user hides the keyboard.
     */
    fun keyboardVisible(windowHeight: Float, insetBottom: Float, composeBottom: Float, visibleBottom: Float, imeVisible: Boolean): Boolean =
        imeVisible || keyboardHeight(windowHeight, insetBottom, composeBottom, visibleBottom, false) > 0f

    /**
     * The shift for the field being edited: the band rule while the field is [engaged] *and the keyboard is [visible]*, and the one for
     * the measured [keyboard] height ([shift]); the larger of the two, so a keyboard that is measured to cover more can only push
     * the field higher than the band does. A keyboard that is hidden (the user closed it, the field is still focused) or a field
     * that is not engaged: nothing moves, and what moved glides back.
     */
    fun editShift(
        engaged: Boolean, visible: Boolean, fieldTop: Float, fieldBottom: Float, regionTop: Float, regionBottom: Float,
        windowWidth: Float, windowHeight: Float, keyboard: Float, margin: Float,
    ): Float {
        val band = if (engaged && visible) bandShift(fieldTop, fieldBottom, editBandBottom(windowWidth, windowHeight), margin) else 0f
        return maxOf(band, shift(fieldTop, fieldBottom, regionTop, regionBottom, windowHeight, keyboard, margin))
    }

    /**
     * How much of the bottom of the window (which is [windowHeight] pixels tall *now*) the keyboard covers, from every
     * source the system may give: [insetBottom] (the root window insets), [composeBottom] (Compose's own `WindowInsets.ime`)
     * and the visible display frame ([visibleBottom], the bottom of the frame in window coordinates; the keyboard is whatever
     * the frame lost, unless that is less than [FRAME_MIN_FRACTION] of the window). The largest one wins. Every source
     * measures against the window as it is now, so when `adjustResize` really shrank the window, only the part of the keyboard
     * that still overlaps it counts: none, if the window ends at the keyboard's top, and nothing is shifted twice.
     * [imeVisible] with no height anywhere is an estimate, so the field is not left under a keyboard nobody measured.
     */
    fun keyboardHeight(windowHeight: Float, insetBottom: Float, composeBottom: Float, visibleBottom: Float, imeVisible: Boolean): Float {
        if (windowHeight <= 0f) return 0f
        val lostByFrame = windowHeight - visibleBottom
        val fromFrame = if (lostByFrame > windowHeight * FRAME_MIN_FRACTION) lostByFrame else 0f
        val measured = maxOf(insetBottom, composeBottom, fromFrame, 0f)
        val height = if (measured == 0f && imeVisible) windowHeight * ESTIMATE_FRACTION else measured
        return height.coerceAtMost(windowHeight)
    }

    /**
     * How many pixels what is being moved has to move up so the text field (the [fieldTop] .. [fieldBottom] band, window
     * coordinates, not counting any shift already applied) is above a keyboard that covers the bottom [ime] pixels of a
     * window that is [height] pixels tall. The [regionTop] .. [regionBottom] band is what should stay visible with the field (a dialog
     * with its buttons, the field with the button under it): it is brought in as far as its own top still fits on the
     * screen, but the field always wins. Never more than the keyboard's own height, so no empty strip opens above the
     * keyboard, and never so much that the field itself leaves the screen at the top. When the region is taller than
     * what is left above the keyboard, its top stays on the screen and the field is shown below it; when even that does not
     * work, the field wins and the rest of the region (a title) leaves at the top.
     */
    fun shift(fieldTop: Float, fieldBottom: Float, regionTop: Float, regionBottom: Float, height: Float, ime: Float, margin: Float): Float {
        if (ime <= 0f) return 0f
        val free = height - ime
        val forField = fieldBottom + margin - free
        val forRegion = minOf(regionBottom + margin - free, regionTop - margin)
        val topLimit = maxOf(fieldTop - margin, 0f)
        return maxOf(forField, forRegion).coerceIn(0f, minOf(ime, topLimit))
    }
}

/** A composable's place on the screen, remembered by whoever registered it. */
@Stable
class ImeAnchor {
    /** Inside the mover's offset: positions measured against it do not contain the mover's own shift. */
    internal var coordinates: LayoutCoordinates? = null

    /** A mover only: outside its offset, so where it really is without the shift. */
    internal var outer: LayoutCoordinates? = null

    /** A mover only: how far it is moved up right now (animated). */
    internal val shift = Animatable(0f)

    /** The field was activated (A, a tap) and the focus has not left it: it is being edited. */
    internal var engaged by mutableStateOf(false)
}

/** One reading of every source and the shift computed from it. Pixels, window coordinates; equal readings are not logged twice. */
@Immutable
internal data class ImeMeasure(
    val insetBottom: Int = 0,
    val insetVisible: Boolean = false,
    val composeBottom: Int = 0,
    /** The bottom of the visible display frame, in window coordinates. */
    val visibleBottom: Int = 0,
    val windowHeight: Int = 0,
    val windowWidth: Int = 0,
    /** The field is engaged (activated, focus not left). */
    val engaged: Boolean = false,
    /** The keyboard is up (any source): the shift is only active then. */
    val visible: Boolean = false,
    /** The shift of the band rule alone (0 when not engaged or not visible). */
    val band: Float = 0f,
    /** How much the window is lower than it was without the keyboard: `adjustResize` really resized it. */
    val resizedBy: Int = 0,
    val keyboard: Float = 0f,
    /** The focused field's bounds as laid out, without the shift. */
    val field: Rect? = null,
    /** Where the field is on the window right now (with the shift): the check that the unshifted bounds above are right. */
    val fieldTopNow: Float = Float.NaN,
    val region: Rect? = null,
    /** The mover that holds the field: the only one that moves, by [shift]. */
    val mover: ImeAnchor? = null,
    val shift: Float = 0f,
)

/**
 * Keeps the focused text field above the on-screen keyboard, in one place for the whole shell. Only what is marked moves: a
 * container marked with [moveAboveIme] (the dialog, the profile page's content) glides up by [ImeMeasure.shift] while the
 * keyboard is up and the field in it is being edited, and glides back when the keyboard is hidden or the field is left. The
 * status bar, the background, the scrim and the other bars stay where they are. The text fields ([TextFieldPill]) report their
 * place when they have focus; a container marked with [keepVisibleWithIme] (the name field with its button) is brought in with the
 * field when the measured keyboard asks for it.
 *
 * The keyboard is not taken from one place: the system does not always say its height in edge to edge windows with hidden bars
 * (and some keyboards say far less than they cover). [measure] reads Compose's `WindowInsets.ime`, the root window insets and the
 * visible display frame and uses the largest ([ImeShift.keyboardHeight]); the visible flag ([ImeShift.keyboardVisible]) is what
 * switches the editing band on and off. A layout listener and a short poll while a field has focus notice a keyboard that shows
 * without any inset event. In debug builds every change is logged under `DelosIme`.
 */
@Stable
class ImeAvoidance {
    /** The latest reading; the movers follow it. */
    internal var current by mutableStateOf(ImeMeasure())

    internal var view: View? = null

    private var focused by mutableStateOf<ImeAnchor?>(null)
    private var tick by mutableIntStateOf(0)
    private val regions = ArrayList<ImeAnchor>()
    private val movers = ArrayList<ImeAnchor>()
    private var baselineHeight = 0

    internal val hasField: Boolean get() = focused != null

    /** Something may have changed that no state tells about (the layout, the time): measure again. */
    internal fun poke() { tick++ }

    internal fun focusChanged(anchor: ImeAnchor, isFocused: Boolean) {
        if (isFocused) focused = anchor else if (focused === anchor) focused = null
    }

    internal fun addRegion(anchor: ImeAnchor) { regions.add(anchor) }

    internal fun removeRegion(anchor: ImeAnchor) { regions.remove(anchor) }

    internal fun addMover(anchor: ImeAnchor) { movers.add(anchor) }

    internal fun removeMover(anchor: ImeAnchor) { movers.remove(anchor) }

    /** Reads every source for the keyboard and, with a field focused, where its mover should be; [margin] in pixels. */
    internal fun measure(composeBottom: Int, margin: Float): ImeMeasure {
        @Suppress("UNUSED_EXPRESSION") tick // read: the layout listener and the poll make this measure again
        val view = view ?: return ImeMeasure(composeBottom = composeBottom)
        val decor = view.rootView
        val windowHeight = decor.height
        val windowWidth = decor.width
        val onScreen = IntArray(2).also { decor.getLocationOnScreen(it) }
        val frame = AndroidRect().also { view.getWindowVisibleDisplayFrame(it) }
        val visibleBottom = frame.bottom - onScreen[1]
        val insets = ViewCompat.getRootWindowInsets(view)
        val insetBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val insetVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
        val engaged = focused?.engaged == true
        val h = windowHeight.toFloat()
        val keyboard = ImeShift.keyboardHeight(h, insetBottom.toFloat(), composeBottom.toFloat(), visibleBottom.toFloat(), insetVisible)
        val visible = ImeShift.keyboardVisible(h, insetBottom.toFloat(), composeBottom.toFloat(), visibleBottom.toFloat(), insetVisible)
        if (keyboard == 0f) baselineHeight = windowHeight
        val resizedBy = (baselineHeight - windowHeight).coerceAtLeast(0)
        val base = ImeMeasure(insetBottom, insetVisible, composeBottom, visibleBottom, windowHeight, windowWidth, engaged, visible, 0f, resizedBy, keyboard)
        if (!visible) return base
        val fieldCoordinates = focused?.coordinates?.takeIf { it.isAttached } ?: return base
        // The mover that holds the field (the smallest one): positions relative to its inside do not contain its own shift, and where
        // its outside is in the window is where it would be without it
        data class Held(val mover: ImeAnchor, val inside: LayoutCoordinates, val origin: androidx.compose.ui.geometry.Offset, val field: Rect)
        val held = movers.mapNotNull { m ->
            val inside = m.coordinates?.takeIf { it.isAttached } ?: return@mapNotNull null
            val outside = m.outer?.takeIf { it.isAttached } ?: return@mapNotNull null
            val f = inside.localBoundingBoxOf(fieldCoordinates, clipBounds = false)
            if (!Rect(0f, 0f, inside.size.width.toFloat(), inside.size.height.toFloat()).contains(f.center)) null
            else Held(m, inside, outside.positionInWindow(), f)
        }.minByOrNull { it.inside.size.width.toLong() * it.inside.size.height } ?: return base
        fun Rect.toWindow() = translate(held.origin)
        val f = held.field.toWindow()
        // The region that holds the field: the mover itself, or a smaller marked one inside it
        val whole = Rect(0f, 0f, held.inside.size.width.toFloat(), held.inside.size.height.toFloat()).toWindow()
        val region = (listOf(whole) + regions.mapNotNull { anchor ->
            anchor.coordinates?.takeIf { it.isAttached }?.let { held.inside.localBoundingBoxOf(it, clipBounds = false).toWindow() }
        }).filter { it.contains(f.center) }.minByOrNull { it.height } ?: whole
        val band = if (engaged) ImeShift.bandShift(f.top, f.bottom, ImeShift.editBandBottom(windowWidth.toFloat(), h), margin) else 0f
        val shift = ImeShift.editShift(engaged, visible, f.top, f.bottom, region.top, region.bottom, windowWidth.toFloat(), h, keyboard, margin)
        return base.copy(field = f, fieldTopNow = fieldCoordinates.positionInWindow().y, region = region, band = band, mover = held.mover, shift = shift)
    }
}

val LocalImeAvoidance = staticCompositionLocalOf<ImeAvoidance?> { null }

/** The state of [ImeAvoidance] for the shell: measures the keyboard and the focused field; the movers follow it with the shared geometry spring. */
@Composable
fun rememberImeAvoidance(): ImeAvoidance {
    val avoidance = remember { ImeAvoidance() }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val view = LocalView.current
    val margin = ImeShift.MARGIN * stageUnit()
    avoidance.view = view
    DisposableEffect(avoidance, view) {
        // A keyboard that is shown without an inset event still changes the layout or the visible frame
        val listener = ViewTreeObserver.OnGlobalLayoutListener { avoidance.poke() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            avoidance.view = null
        }
    }
    // The poll: only while a text field has focus, which is the only time the keyboard can matter
    LaunchedEffect(avoidance) {
        while (true) {
            delay(POLL_MS)
            if (avoidance.hasField) avoidance.poke()
        }
    }
    LaunchedEffect(avoidance, density, margin) {
        snapshotFlow { avoidance.measure(ime.getBottom(density), margin) }.collect { measure ->
            if (BuildConfig.DEBUG) log(view, measure)
            avoidance.current = measure
        }
    }
    return avoidance
}

private const val TAG = "DelosIme"
private const val POLL_MS = 60L

/** `DelosIme` (debug builds): `ime inset=… compose=… visibleBottom=… -> keyboard=… | engaged=… visible=… band=… | window WxH resized=… | field=… topNow=… region=… | shift=… | softInput=…`. */
@Suppress("DEPRECATION") // the adjust flags are deprecated, and still what the window reports
private fun log(view: View, m: ImeMeasure) {
    fun Rect?.text() = if (this == null) "-" else "[${left.toInt()},${top.toInt()},${right.toInt()},${bottom.toInt()}]"
    val mode = activityOf(view.context)?.window?.attributes?.softInputMode
    val adjust = when (mode?.and(WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)) {
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE -> "resize"
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN -> "pan"
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING -> "nothing"
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED -> "unspecified"
        else -> "?"
    }
    Log.d(
        TAG,
        "ime inset=${m.insetBottom}(visible=${m.insetVisible}) compose=${m.composeBottom} visibleBottom=${m.visibleBottom} -> keyboard=${m.keyboard.toInt()}" +
            " | engaged=${m.engaged} visible=${m.visible} band=${m.band.toInt()}" +
            " | window ${m.windowWidth}x${m.windowHeight} resized=${m.resizedBy > 0}(by ${m.resizedBy})" +
            " | field(window, unshifted)=${m.field.text()} topNow=${m.fieldTopNow.takeIf { !it.isNaN() }?.toInt() ?: "-"} region=${m.region.text()}" +
            " | shift=${m.shift.toInt()} | softInput=0x${(mode ?: 0).toString(16)} adjust=$adjust",
    )
}

private fun activityOf(context: Context): Activity? {
    var c: Context? = context
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * This container moves up, with everything in it, while the keyboard is up and a text field inside it is being edited
 * ([ImeShift.editShift]), and glides back with the shared geometry spring (a snap with reduced motion) when the keyboard is hidden
 * or the field is left. Put it before what draws the container (its shadow, blur, border, padding) so all of it moves, and only it:
 * what is outside stays. No-op outside the shell.
 */
@Composable
fun Modifier.moveAboveIme(): Modifier {
    val avoidance = LocalImeAvoidance.current ?: return this
    val anchor = remember { ImeAnchor() }
    val reduced = LocalReducedMotion.current
    DisposableEffect(avoidance, anchor) {
        avoidance.addMover(anchor)
        onDispose { avoidance.removeMover(anchor) }
    }
    LaunchedEffect(avoidance, anchor, reduced) {
        // The spring keeps its speed when the target moves, so a keyboard that is animating (or a user who hides it half way) glides (Motion.GEO_*)
        snapshotFlow { avoidance.current.takeIf { it.mover === anchor }?.shift ?: 0f }.collectLatest { target ->
            if (reduced) anchor.shift.snapTo(target)
            else anchor.shift.animateTo(target, spring(Motion.GEO_DAMPING, Motion.GEO_STIFFNESS, visibilityThreshold = .5f))
        }
    }
    // The shift is a layout offset on purpose: the node before it stays where it would be, the one after it is moved, so the two
    // coordinates tell the field's place without the shift
    return onGloballyPositioned { anchor.outer = it }
        .offset { IntOffset(0, -anchor.shift.value.coerceAtLeast(0f).roundToInt()) }
        .onGloballyPositioned { anchor.coordinates = it }
}

/** A text field reports its place while it has focus, and whether it is [engaged] (activated by A or a tap), so the shell can keep it above the keyboard. No-op outside the shell. */
@Composable
fun Modifier.keepFieldAboveIme(engaged: Boolean): Modifier {
    val avoidance = LocalImeAvoidance.current ?: return this
    val anchor = remember { ImeAnchor() }
    SideEffect { anchor.engaged = engaged }
    DisposableEffect(avoidance, anchor) { onDispose { avoidance.focusChanged(anchor, false) } }
    return onGloballyPositioned { anchor.coordinates = it }.onFocusChanged { avoidance.focusChanged(anchor, it.isFocused) }
}

/** What should stay visible together with a text field inside it, when it fits above the keyboard: a dialog, a field with its button. */
@Composable
fun Modifier.keepVisibleWithIme(): Modifier {
    val avoidance = LocalImeAvoidance.current ?: return this
    val anchor = remember { ImeAnchor() }
    DisposableEffect(avoidance, anchor) {
        avoidance.addRegion(anchor)
        onDispose { avoidance.removeRegion(anchor) }
    }
    return onGloballyPositioned { anchor.coordinates = it }
}
