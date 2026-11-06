package com.limelight.shell.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlinx.coroutines.cancelAndJoin
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

    internal fun boundsOf(coordinates: LayoutCoordinates): Rect? {
        val h = host ?: return null
        if (!h.isAttached || !coordinates.isAttached) return null
        return h.localBoundingBoxOf(coordinates, clipBounds = false)
    }

    internal fun report(key: Any, coordinates: LayoutCoordinates) {
        val bounds = boundsOf(coordinates) ?: return
        holder = key
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
 * A row of tabs with one accent pill (`colors.accent`) behind the selected tab that glides to the next selected one instead of switching
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
    val geometry = remember { PillGeometry() }
    val target = state.target
    LaunchedEffect(target) {
        if (target == null) {
            geometry.alpha.snapTo(0f)
            return@LaunchedEffect
        }
        geometry.setRow(target)
        if (geometry.alpha.value == 0f || reduced) {
            geometry.snapEdges(target)
            geometry.alpha.snapTo(1f)
            return@LaunchedEffect
        }
        geometry.glideTo(target)
    }
    Box(modifier.onGloballyPositioned { state.attach(it) }) {
        Box(Modifier.pillBody(geometry, colors.accent))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalSelectionPill provides state) { content() }
        }
    }
}

/**
 * Marks a tab of a [SelectionPillHost]: while it is [selected] the pill glides to it. Put it before any scale or other
 * transform on the tab (so its bounds are those of the layout, not of the press squeeze). Does nothing outside a host.
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

/**
 * The two edges of a pill (springs), its visibility and the row it travels in. Shared by the [SelectionPillHost] and the
 * [TransientPillHost]; the values are read in the layout and draw phases only ([pillBody]).
 */
@Stable
class PillGeometry {
    val left = Animatable(0f)
    val right = Animatable(0f)
    val alpha = Animatable(0f)
    var top by mutableFloatStateOf(0f)
        private set
    var height by mutableFloatStateOf(0f)
        private set

    internal fun setRow(bounds: Rect) {
        top = bounds.top
        height = bounds.height
    }

    internal suspend fun snapEdges(bounds: Rect) {
        left.snapTo(bounds.left)
        right.snapTo(bounds.right)
    }

    /** Both edges to [bounds] with their springs: the one in the direction of travel is the quicker. Returns when both have settled. */
    internal suspend fun glideTo(bounds: Rect) {
        val rightwards = SelectionPillMotion.travelsRight(left.value, right.value, bounds.left, bounds.right)
        coroutineScope {
            launch { left.animateTo(bounds.left, SelectionPillMotion.edgeSpec(leading = !rightwards)) }
            launch { right.animateTo(bounds.right, SelectionPillMotion.edgeSpec(leading = rightwards)) }
        }
    }
}

/** The capsule of a pill between its edges; it takes no space in its parent and is not drawn while [PillGeometry.alpha] is 0. */
internal fun Modifier.pillBody(geometry: PillGeometry, color: Color): Modifier = this
    .layout { measurable, _ ->
        val visible = geometry.alpha.value > 0f
        val w = (geometry.right.value - geometry.left.value).roundToInt().coerceAtLeast(0)
        val placeable = measurable.measure(Constraints.fixed(if (visible) w else 0, if (visible) geometry.height.roundToInt() else 0))
        layout(0, 0) { placeable.place(IntOffset(geometry.left.value.roundToInt(), geometry.top.roundToInt())) }
    }
    .graphicsLayer { alpha = geometry.alpha.value }
    .clip(CircleShape)
    .background(color)

/**
 * The pill of a [TransientPillHost]: unlike the [SelectionPill] it is not the selected state but a flourish of the focus moving.
 * A button reports its focus changes with [focusChanged]; when one button loses focus and another gains it at once, the pill
 * starts as the old button looks focused (its circle scaled by the focus scale), glides to the new button's focused look, stretching
 * between the two, so it is one shape of the same height all the way. Once it has arrived and the button's own focus scale has
 * settled ([Motion.FOCUS] after the gain) the button's accent fill fades in over it ([handover]) and the pill is removed.
 * Focus that enters the host from outside or leaves it, and reduced motion, show no pill.
 */
@Stable
class TransientPill internal constructor(
    private val scope: CoroutineScope,
    private val host: SelectionPill,
    internal val geometry: PillGeometry,
) {
    internal var reduced = false
    private var lostAt = TransientPillMotion.NONE
    private var lostBounds: Rect? = null
    private var moveJob: Job? = null
    private var hideJob: Job? = null
    private val fill = Animatable(0f)

    /** How much of the focused button's own accent fill shows: 0 while the pill is the focus, rising to 1 over the pill at the hand-over, 1 at rest. Read while drawing. */
    fun handover(): Float = if (geometry.alpha.value > 0f) fill.value else 1f

    /**
     * A button of the host gained or lost focus; [coordinates] are its layout bounds (before any scale) and [focusScale] the
     * scale it has while focused.
     */
    fun focusChanged(focused: Boolean, coordinates: LayoutCoordinates?, focusScale: Float, now: Long = System.nanoTime()) {
        if (!focused) {
            lostBounds = coordinates?.let(host::boundsOf)?.let { TransientPillMotion.scaled(it, focusScale) }
            lostAt = now
            // No button gained the focus right after: it left the host, so whatever pill is still on its way goes
            hideJob?.cancel()
            hideJob = scope.launch {
                delay(TransientPillMotion.MOVE_WINDOW_MS * 2)
                moveJob?.cancel()
                geometry.alpha.animateTo(0f, tween(Motion.EXIT, easing = Motion.Ease))
            }
            return
        }
        hideJob?.cancel()
        val to = coordinates?.let(host::boundsOf)?.let { TransientPillMotion.scaled(it, focusScale) }
        val from = lostBounds
        val moved = to != null && from != null && !reduced && TransientPillMotion.isMove(lostAt, now)
        lostAt = TransientPillMotion.NONE
        lostBounds = null
        moveJob?.cancel()
        // Started in the focus event itself (not dispatched): the new button's own fill is already held back when it first draws
        moveJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!moved || to == null || from == null) {
                geometry.alpha.snapTo(0f)
                return@launch
            }
            // A pill still on its way (a quick second move) carries on from where it is
            if (geometry.alpha.value <= 0f) geometry.snapEdges(from)
            geometry.setRow(to)
            fill.snapTo(0f)
            geometry.alpha.snapTo(1f)
            val springs = launch { geometry.glideTo(to) }
            while (abs(geometry.left.value - to.left) > TransientPillMotion.ARRIVE_PX || abs(geometry.right.value - to.right) > TransientPillMotion.ARRIVE_PX) {
                withFrameNanos { }
            }
            // The button's focus scale has come to rest by now: pill and button have the same bounds
            val rest = Motion.FOCUS - (System.nanoTime() - now) / 1_000_000L
            if (rest > 0) delay(rest)
            springs.cancelAndJoin()
            geometry.snapEdges(to)
            fill.animateTo(1f, tween(TransientPillMotion.HANDOVER_MS, easing = LinearEasing))
            geometry.alpha.snapTo(0f)
        }
    }
}

val LocalTransientPill = staticCompositionLocalOf<TransientPill?> { null }

/**
 * A box with a [TransientPill] between its [background] and its [content] (the buttons, see `NavButtonView`): the pill
 * glides behind the buttons while the focus moves between them and is never drawn at rest.
 */
@Composable
fun TransientPillHost(
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalDelosColors.current
    val scope = rememberCoroutineScope()
    val host = remember { SelectionPill() }
    val geometry = remember { PillGeometry() }
    val pill = remember { TransientPill(scope, host, geometry) }
    pill.reduced = LocalReducedMotion.current
    Box(modifier.onGloballyPositioned { host.attach(it) }) {
        background()
        Box(Modifier.pillBody(geometry, colors.accent))
        CompositionLocalProvider(LocalTransientPill provides pill) { content() }
    }
}

/** Which focus changes of a [TransientPillHost] play the pill, and the numbers of its hand-over to the focused button. */
object TransientPillMotion {
    const val NONE = Long.MIN_VALUE

    /** A focus move takes the old button's loss and the new one's gain in one go; a gain later than this is focus coming in from elsewhere. */
    const val MOVE_WINDOW_MS = 100L

    /** The pill has arrived when both its edges are this close (px) to the target; the hand-over then waits for the button's own focus scale to settle. */
    const val ARRIVE_PX = 2f

    /** How long the button's own accent fill takes to fade in over the pill (which has the same bounds and colour, so nothing changes visibly). */
    const val HANDOVER_MS = 140

    /** Whether a gain at [gainedAt] follows a loss at [lostAt] (both `System.nanoTime`, [NONE] = no loss) closely enough to be a move between two buttons. */
    fun isMove(lostAt: Long, gainedAt: Long): Boolean =
        lostAt != NONE && gainedAt - lostAt in 0..MOVE_WINDOW_MS * 1_000_000L

    /** [bounds] scaled by [scale] about their centre: a button's bounds as it looks while focused (the pill matches that, not the resting circle). */
    fun scaled(bounds: Rect, scale: Float): Rect {
        val halfW = bounds.width * scale / 2f
        val halfH = bounds.height * scale / 2f
        return Rect(bounds.center.x - halfW, bounds.center.y - halfH, bounds.center.x + halfW, bounds.center.y + halfH)
    }
}
