package com.limelight.shell.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.collectLatest

/**
 * The pure arithmetic of keeping a text field above Android's on-screen keyboard (docs/GUI.md, "Text input").
 * Everything is in real pixels: the keyboard's inset is, and the stage scale only matters for the margin.
 */
object ImeShift {
    /** The gap that stays between the keyboard (or the screen's top edge) and what is kept visible, in stage pixels. */
    const val MARGIN = 24f

    /**
     * How many pixels the whole screen has to move up so the text field (the [fieldTop] .. [fieldBottom] band) is
     * above a keyboard that covers the bottom [ime] pixels of a screen that is [height] pixels tall. The [regionTop] ..
     * [regionBottom] band is what should stay visible with the field (a dialog with its buttons, the field with the
     * button under it): it is brought in as far as its own top still fits on the screen, but the field always wins.
     * Never more than the keyboard's own height, so no empty strip opens above the keyboard, and never so much
     * that the field itself leaves the screen at the top.
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
}

/**
 * Keeps the focused text field above the on-screen keyboard for the whole shell, in one place: the shell's root moves
 * up by [shift] pixels while the keyboard is open and glides back when it closes. The text fields ([TextFieldPill])
 * report their place when they have focus; a container marked with [keepVisibleWithIme] (a dialog, the name field
 * with its button) is brought in with the field when it fits above the keyboard.
 *
 * The inset is the real keyboard height in pixels, so nothing here depends on the stage scale. The Activity sets
 * `adjustResize` so the inset is delivered on every API level; the window itself is never resized, because the
 * shell is edge to edge.
 */
@Stable
class ImeAvoidance {
    /** The pixels the root is moved up right now (animated). */
    internal val shift = Animatable(0f)

    /** The root's own coordinates, the space the shift is computed in (its own movement is not part of them). */
    internal var root: LayoutCoordinates? = null

    private var field by mutableStateOf<ImeAnchor?>(null)
    private val regions = ArrayList<ImeAnchor>()

    internal fun focusChanged(anchor: ImeAnchor, focused: Boolean) {
        if (focused) field = anchor else if (field === anchor) field = null
    }

    internal fun addRegion(anchor: ImeAnchor) { regions.add(anchor) }

    internal fun removeRegion(anchor: ImeAnchor) { regions.remove(anchor) }

    /** Where the shift should be for a keyboard of [ime] pixels; [margin] in pixels. Reads the field's place as it is now. */
    internal fun target(ime: Float, margin: Float): Float {
        if (ime <= 0f) return 0f
        val root = root?.takeIf { it.isAttached } ?: return 0f
        val fieldCoordinates = field?.coordinates?.takeIf { it.isAttached } ?: return 0f
        val f = root.localBoundingBoxOf(fieldCoordinates, clipBounds = false)
        // The region that holds the field (the smallest one, if there are several)
        val region = regions.mapNotNull { anchor -> anchor.coordinates?.takeIf { it.isAttached }?.let { root.localBoundingBoxOf(it, clipBounds = false) } }
            .filter { it.contains(f.center) }
            .minByOrNull { it.height } ?: f
        return ImeShift.shift(f.top, f.bottom, region.top, region.bottom, root.size.height.toFloat(), ime, margin)
    }
}

val LocalImeAvoidance = staticCompositionLocalOf<ImeAvoidance?> { null }

/** The state of [ImeAvoidance] for a shell root; follows the keyboard's inset with the shared geometry spring. */
@Composable
fun rememberImeAvoidance(): ImeAvoidance {
    val avoidance = remember { ImeAvoidance() }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val margin = ImeShift.MARGIN * stageUnit()
    val reduced = LocalReducedMotion.current
    LaunchedEffect(avoidance, density, margin, reduced) {
        // The system animates the inset while the keyboard opens and closes; the spring follows it and keeps its speed
        // when the target moves, so the screen glides like every other geometry in the UI (Motion.GEO_*)
        snapshotFlow { avoidance.target(ime.getBottom(density).toFloat(), margin) }.collectLatest { target ->
            if (reduced) avoidance.shift.snapTo(target)
            else avoidance.shift.animateTo(target, spring(Motion.GEO_DAMPING, Motion.GEO_STIFFNESS, visibilityThreshold = .5f))
        }
    }
    return avoidance
}

/** Moves the shell root up while the keyboard needs the room. Put it on the root of the shell, with [rememberImeAvoidance]'s state. */
fun Modifier.imeAvoidance(avoidance: ImeAvoidance): Modifier =
    // The coordinates sit inside the layer: positions measured against them do not contain the shift
    graphicsLayer { translationY = -avoidance.shift.value.coerceAtLeast(0f) }
        .onGloballyPositioned { avoidance.root = it }

/** A text field reports its place while it has focus, so the shell can keep it above the keyboard. No-op outside the shell. */
@Composable
fun Modifier.keepFieldAboveIme(): Modifier {
    val avoidance = LocalImeAvoidance.current ?: return this
    val anchor = remember { ImeAnchor() }
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
