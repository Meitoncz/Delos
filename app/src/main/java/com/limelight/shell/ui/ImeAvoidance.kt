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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.limelight.BuildConfig
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
     * The shift of the whole screen for the field being edited: the band rule when the field is [engaged], and the one
     * for the measured [keyboard] height ([shift]); the larger of the two, so a keyboard that is measured to cover more can only
     * push the field higher than the band does. Not engaged and no keyboard: nothing moves.
     */
    fun editShift(
        engaged: Boolean, fieldTop: Float, fieldBottom: Float, regionTop: Float, regionBottom: Float,
        windowWidth: Float, windowHeight: Float, keyboard: Float, margin: Float,
    ): Float {
        val band = if (engaged) bandShift(fieldTop, fieldBottom, editBandBottom(windowWidth, windowHeight), margin) else 0f
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
     * How many pixels the whole screen has to move up so the text field (the [fieldTop] .. [fieldBottom] band, window
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
    internal var coordinates: LayoutCoordinates? = null

    /** The field was activated (A, a tap) and has not lost the focus since: the keyboard is meant to be up. */
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
    /** The field is engaged (activated, focus not left): it is pinned into the top band whatever the keyboard says. */
    val engaged: Boolean = false,
    /** The shift of the band rule alone (0 when not engaged). */
    val band: Float = 0f,
    /** How much the window is lower than it was without the keyboard: `adjustResize` really resized it. */
    val resizedBy: Int = 0,
    val keyboard: Float = 0f,
    /** The focused field's bounds as laid out, without the shift. */
    val field: Rect? = null,
    /** Where the field is on the window right now (with the shift): the check that the unshifted bounds above are right. */
    val fieldTopNow: Float = Float.NaN,
    val region: Rect? = null,
    val shift: Float = 0f,
)

/**
 * Keeps the focused text field above the on-screen keyboard for the whole shell, in one place: the shell's root moves
 * up by [shift] pixels while the keyboard is open and glides back when it closes. The text fields ([TextFieldPill])
 * report their place when they have focus; a container marked with [keepVisibleWithIme] (a dialog, the name field
 * with its button) is brought in with the field when it fits above the keyboard.
 *
 * The keyboard's height is not taken from one place: the system does not always say it in edge to edge windows with
 * hidden bars. [measure] reads Compose's `WindowInsets.ime`, the root window insets and the visible display frame and uses
 * the largest ([ImeShift.keyboardHeight]); a layout listener and a short poll while a field has focus notice a keyboard
 * that shows without any inset event. In debug builds every change is logged under `DelosIme`.
 */
@Stable
class ImeAvoidance {
    /** The pixels the root is moved up right now (animated). */
    internal val shift = Animatable(0f)

    /** The root's own coordinates, the space the field is measured in (its own movement is not part of them). */
    internal var root: LayoutCoordinates? = null

    internal var view: View? = null

    private var focused by mutableStateOf<ImeAnchor?>(null)
    private var tick by mutableIntStateOf(0)
    private val regions = ArrayList<ImeAnchor>()
    private var baselineHeight = 0

    internal val hasField: Boolean get() = focused != null

    /** Something may have changed that no state tells about (the layout, the time): measure again. */
    internal fun poke() { tick++ }

    internal fun focusChanged(anchor: ImeAnchor, isFocused: Boolean) {
        if (isFocused) focused = anchor else if (focused === anchor) focused = null
    }

    internal fun addRegion(anchor: ImeAnchor) { regions.add(anchor) }

    internal fun removeRegion(anchor: ImeAnchor) { regions.remove(anchor) }

    /** Reads every source for the keyboard and, with a field focused, where the shift should be; [margin] in pixels. */
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
        val keyboard = ImeShift.keyboardHeight(windowHeight.toFloat(), insetBottom.toFloat(), composeBottom.toFloat(), visibleBottom.toFloat(), insetVisible)
        if (keyboard == 0f) baselineHeight = windowHeight
        val resizedBy = (baselineHeight - windowHeight).coerceAtLeast(0)
        val base = ImeMeasure(insetBottom, insetVisible, composeBottom, visibleBottom, windowHeight, windowWidth, engaged, 0f, resizedBy, keyboard)
        if (keyboard <= 0f && !engaged) return base
        val root = root?.takeIf { it.isAttached } ?: return base
        val fieldCoordinates = focused?.coordinates?.takeIf { it.isAttached } ?: return base
        // Bounds in the root, which does not contain its own shift, moved to window coordinates by where the host view sits in the window
        val inWindow = IntArray(2).also { view.getLocationInWindow(it) }
        fun Rect.toWindow() = translate(inWindow[0].toFloat(), inWindow[1].toFloat())
        val f = root.localBoundingBoxOf(fieldCoordinates, clipBounds = false).toWindow()
        // The region that holds the field (the smallest one, if there are several)
        val region = regions.mapNotNull { anchor -> anchor.coordinates?.takeIf { it.isAttached }?.let { root.localBoundingBoxOf(it, clipBounds = false).toWindow() } }
            .filter { it.contains(f.center) }
            .minByOrNull { it.height }
        val r = region ?: f
        val band = if (engaged) ImeShift.bandShift(f.top, f.bottom, ImeShift.editBandBottom(windowWidth.toFloat(), windowHeight.toFloat()), margin) else 0f
        val shift = ImeShift.editShift(engaged, f.top, f.bottom, r.top, r.bottom, windowWidth.toFloat(), windowHeight.toFloat(), keyboard, margin)
        return base.copy(field = f, fieldTopNow = fieldCoordinates.positionInWindow().y, region = region, band = band, shift = shift)
    }
}

val LocalImeAvoidance = staticCompositionLocalOf<ImeAvoidance?> { null }

/** The state of [ImeAvoidance] for a shell root; follows the keyboard with the shared geometry spring. */
@Composable
fun rememberImeAvoidance(): ImeAvoidance {
    val avoidance = remember { ImeAvoidance() }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val view = LocalView.current
    val margin = ImeShift.MARGIN * stageUnit()
    val reduced = LocalReducedMotion.current
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
    LaunchedEffect(avoidance, density, margin, reduced) {
        // The spring follows the keyboard and keeps its speed when the target moves, so the screen glides like every
        // other geometry in the UI (Motion.GEO_*)
        snapshotFlow { avoidance.measure(ime.getBottom(density), margin) }.collectLatest { measure ->
            if (BuildConfig.DEBUG) log(view, measure)
            if (reduced) avoidance.shift.snapTo(measure.shift)
            else avoidance.shift.animateTo(measure.shift, spring(Motion.GEO_DAMPING, Motion.GEO_STIFFNESS, visibilityThreshold = .5f))
        }
    }
    return avoidance
}

private const val TAG = "DelosIme"
private const val POLL_MS = 60L

/** `DelosIme` (debug builds): `ime inset=… compose=… visibleBottom=… -> keyboard=… | engaged=… band=… | window WxH resized=… | field=… topNow=… region=… | shift=… | softInput=…`. */
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
            " | engaged=${m.engaged} band=${m.band.toInt()}" +
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

/** Moves the shell root up while the keyboard needs the room. Put it on the root of the shell, with [rememberImeAvoidance]'s state. */
fun Modifier.imeAvoidance(avoidance: ImeAvoidance): Modifier =
    // The coordinates sit inside the layer: positions measured against them do not contain the shift
    graphicsLayer { translationY = -avoidance.shift.value.coerceAtLeast(0f) }
        .onGloballyPositioned { avoidance.root = it }

/** A text field reports its place while it has focus, and whether it is [engaged] (activated: the keyboard is meant to be up), so the shell can keep it above the keyboard. No-op outside the shell. */
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
