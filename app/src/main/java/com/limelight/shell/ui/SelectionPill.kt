package com.limelight.shell.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Where the pill of a [SelectionPillHost] goes: the selected tab reports its bounds here. */
@Stable
class SelectionPill {
    private var host: LayoutCoordinates? = null
    private var holder: Any? = null

    /** The selected tab's bounds in the host's coordinates, null while no tab is selected. */
    var target by mutableStateOf<Rect?>(null)
        private set

    internal fun attach(coordinates: LayoutCoordinates) {
        host = coordinates
    }

    internal fun report(key: Any, coordinates: LayoutCoordinates) {
        val h = host ?: return
        if (!h.isAttached || !coordinates.isAttached) return
        holder = key
        val bounds = h.localBoundingBoxOf(coordinates, clipBounds = false)
        if (bounds != target) target = bounds
    }

    internal fun release(key: Any) {
        if (holder != key) return
        holder = null
        target = null
    }
}

/** The pill of the surrounding [SelectionPillHost]; null outside one (a tab then draws its own background). */
val LocalSelectionPill = staticCompositionLocalOf<SelectionPill?> { null }

/**
 * A row of tabs with one frosted pill behind the selected tab that glides to the next selected one instead of switching
 * statically. The pill's two edges are two springs ([Motion.GEO_STIFFNESS], [Motion.GEO_DAMPING], the geometry spring of
 * the rest of the app); the edge in the direction of travel is quicker, so the pill stretches over the gap between the old and
 * the new tab and squashes into the new one: it follows the new tab's width as well as its position. The first tab is
 * placed without animation and reduced motion jumps. The edges are read in the layout phase: no recomposition per frame.
 * Tabs mark themselves with [selectionPillTarget] (see `TabPill`).
 */
@Composable
fun SelectionPillHost(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalDelosColors.current
    val reduced = LocalReducedMotion.current
    val state = remember { SelectionPill() }
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val target = state.target
    LaunchedEffect(target) {
        if (target == null) {
            alpha.snapTo(0f)
            return@LaunchedEffect
        }
        if (alpha.value == 0f || reduced) {
            left.snapTo(target.left)
            right.snapTo(target.right)
            alpha.snapTo(1f)
            return@LaunchedEffect
        }
        val rightwards = SelectionPillMotion.travelsRight(left.value, right.value, target.left, target.right)
        coroutineScope {
            launch { left.animateTo(target.left, SelectionPillMotion.edgeSpec(leading = !rightwards)) }
            launch { right.animateTo(target.right, SelectionPillMotion.edgeSpec(leading = rightwards)) }
        }
    }
    Box(modifier.onGloballyPositioned { state.attach(it) }) {
        Box(
            Modifier
                .layout { measurable, _ ->
                    val a = alpha.value
                    val w = (right.value - left.value).roundToInt().coerceAtLeast(0)
                    val h = (state.target?.height ?: 0f).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(if (a > 0f) w else 0, if (a > 0f) h else 0))
                    layout(0, 0) { placeable.place(IntOffset(left.value.roundToInt(), state.target?.top?.roundToInt() ?: 0)) }
                }
                .clip(CircleShape)
                .glass(colors.surface2),
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalSelectionPill provides state) { content() }
        }
    }
}

/**
 * Marks a tab of a [SelectionPillHost]: while it is [selected] the pill glides to it. Put it before the tab's `focusEffects`
 * (so its bounds are those of the layout, not of the focus scale). Does nothing outside a host.
 */
@Composable
fun Modifier.selectionPillTarget(selected: Boolean): Modifier {
    val pill = LocalSelectionPill.current ?: return this
    val key = remember { Any() }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    LaunchedEffect(selected) {
        if (selected) coordinates[0]?.let { pill.report(key, it) } else pill.release(key)
    }
    DisposableEffect(pill) { onDispose { pill.release(key) } }
    return onGloballyPositioned {
        coordinates[0] = it
        if (selected) pill.report(key, it)
    }
}

/** The numbers of the pill's travel, apart from the drawing so they can be tested. */
object SelectionPillMotion {
    /** The edge in the direction of travel is this much stiffer than the geometry spring, the one behind it as much softer. */
    const val LEADING_STIFFNESS = 1.5f
    const val TRAILING_STIFFNESS = .75f

    /** Whether the pill moves to the right (its centre does); a pill that stays put counts as rightwards. */
    fun travelsRight(fromLeft: Float, fromRight: Float, toLeft: Float, toRight: Float): Boolean =
        toLeft + toRight >= fromLeft + fromRight

    fun stiffness(leading: Boolean): Float = Motion.GEO_STIFFNESS * if (leading) LEADING_STIFFNESS else TRAILING_STIFFNESS

    fun edgeSpec(leading: Boolean) =
        spring<Float>(dampingRatio = Motion.GEO_DAMPING, stiffness = stiffness(leading), visibilityThreshold = .25f)
}
