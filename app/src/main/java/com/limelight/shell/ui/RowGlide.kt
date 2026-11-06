package com.limelight.shell.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * The one focus ring of a scrolling list of rows (settings, per-game settings, debug info): it glides from the row that
 * lost the focus to the row that has it instead of every row fading its own ring in and out. Like [GlideRing] it is told
 * about the focused row by [rowGlideTarget] and drawn behind the host's content by [RowGlideHost] (which also gives the
 * ring its look); unlike it the ring is *not* animated in window space. A list that scrolls while the ring glides would
 * leave a window-space animation behind (and restart it with every scrolled frame), so the ring is drawn at the focused row's
 * bounds as they are in this very frame plus a carry that shrinks to nothing ([RowGlideMotion]): it travels with the row it
 * heads for and at rest sits exactly on it. Everything the draw phase needs is a snapshot state read there (or plain
 * arrays changed together with one), so a frame of the glide or of a scroll redraws the ring without recomposing anything
 * and without allocating.
 */
@Stable
class RowGlide internal constructor(private val scope: CoroutineScope) {
    private var host: LayoutCoordinates? = null
    private var holder: Any? = null
    private var holderCoordinates: LayoutCoordinates? = null

    /** Reduced motion: the ring jumps from row to row. Kept up to date by the host. */
    internal var reduced = false

    /** The focused row's bounds in the host, pixels: left, top, right, bottom. */
    private val bounds = FloatArray(4)

    /** Whether [bounds] hold a row the ring has been on (it keeps them while it fades out, so a quick next focus glides from there). */
    private var placed = false

    /** What the ring still trails those bounds by (same order) until [progress] runs out. */
    private val carry = FloatArray(4)

    /** Bumped whenever [bounds] or [carry] change, so the draw phase redraws. */
    private var tick by mutableIntStateOf(0)

    /** The glide's linear progress, 0 when it starts and 1 once the ring has arrived. */
    private var progress by mutableFloatStateOf(1f)
    private val alpha = Animatable(0f)
    private var glideJob: Job? = null
    private var fadeJob: Job? = null

    internal fun attach(coordinates: LayoutCoordinates) {
        host = coordinates
        update()
    }

    /** The row [key], now at [coordinates], has the focus: the ring glides to it from where it is, or appears on it. */
    internal fun focus(key: Any, coordinates: LayoutCoordinates) {
        // Where the ring is drawn right now, before anything changes
        val glide = placed && RowGlideMotion.glides(alpha.value, reduced)
        val eased = Motion.Glide.transform(progress)
        val l = RowGlideMotion.edge(bounds[0], carry[0], eased)
        val t = RowGlideMotion.edge(bounds[1], carry[1], eased)
        val r = RowGlideMotion.edge(bounds[2], carry[2], eased)
        val b = RowGlideMotion.edge(bounds[3], carry[3], eased)
        holder = key
        holderCoordinates = coordinates
        fadeJob?.cancel()
        glideJob?.cancel()
        val found = update()
        if (glide && found) {
            carry[0] = RowGlideMotion.carry(l, bounds[0])
            carry[1] = RowGlideMotion.carry(t, bounds[1])
            carry[2] = RowGlideMotion.carry(r, bounds[2])
            carry[3] = RowGlideMotion.carry(b, bounds[3])
            progress = 0f
            glideJob = scope.launch {
                val start = withFrameNanos { it }
                while (progress < 1f) {
                    val now = withFrameNanos { it }
                    progress = ((now - start) / 1_000_000f / Motion.ROW_FOCUS).coerceIn(0f, 1f)
                }
            }
        } else {
            carry.fill(0f)
            progress = 1f
        }
        tick++
        fadeJob = scope.launch { alpha.animateTo(1f, tween(120, easing = Motion.Ease)) }
    }

    /** The focused row moved (scrolled, resized) or was laid out again: the ring follows. */
    internal fun moved(key: Any, coordinates: LayoutCoordinates) {
        if (holder != key) return
        holderCoordinates = coordinates
        update()
    }

    /** The row [key] lost the focus; the ring waits a moment for the next row (the focus passes through "nothing") before it fades. */
    internal fun blur(key: Any) {
        if (holder != key) return
        holder = null
        holderCoordinates = null
        fadeJob?.cancel()
        fadeJob = scope.launch {
            delay(70)
            alpha.animateTo(0f, tween(Motion.FADE, easing = Motion.Ease))
        }
    }

    /** The row [key] left the composition (another tab, a list that was replaced): the ring is gone with it, like a ring a row draws itself. */
    internal fun release(key: Any) {
        if (holder != key) return
        holder = null
        holderCoordinates = null
        glideJob?.cancel()
        fadeJob?.cancel()
        fadeJob = scope.launch { alpha.snapTo(0f) }
    }

    /** Re-reads the focused row's bounds in the host. Returns whether there were bounds to read. */
    private fun update(): Boolean {
        val h = host ?: return false
        val c = holderCoordinates ?: return false
        if (!h.isAttached || !c.isAttached) return false
        val origin = h.localPositionOf(c, Offset.Zero)
        val left = origin.x
        val top = origin.y
        val right = left + c.size.width
        val bottom = top + c.size.height
        placed = true
        if (left != bounds[0] || top != bounds[1] || right != bounds[2] || bottom != bounds[3]) {
            bounds[0] = left
            bounds[1] = top
            bounds[2] = right
            bounds[3] = bottom
            tick++
        }
        return true
    }

    /** Draws the ring around the focused row, [focusScale] times its size (the scale the row grows to), in the host's coordinates. */
    internal fun draw(drawScope: DrawScope, ring: FocusRing, corner: Float, focusScale: Float, colors: DelosColors, stage: StageMetrics) {
        // Reading the tick is what redraws the ring when the row's bounds or the carry change
        if (tick == Int.MIN_VALUE) return
        val a = alpha.value
        if (a <= 0f) return
        val eased = Motion.Glide.transform(progress)
        val l = RowGlideMotion.edge(bounds[0], carry[0], eased)
        val t = RowGlideMotion.edge(bounds[1], carry[1], eased)
        val r = RowGlideMotion.edge(bounds[2], carry[2], eased)
        val b = RowGlideMotion.edge(bounds[3], carry[3], eased)
        if (r - l <= 0f || b - t <= 0f) return
        with(drawScope) {
            inset(
                RowGlideMotion.grownNear(l, r, focusScale), RowGlideMotion.grownNear(t, b, focusScale),
                size.width - RowGlideMotion.grownFar(l, r, focusScale), size.height - RowGlideMotion.grownFar(t, b, focusScale),
            ) {
                drawFocusRing(ring, min(stagePx(corner, stage), this.size.minDimension / 2f), a, colors, stage)
            }
        }
    }
}

/** The ring of the surrounding [RowGlideHost]; null outside one (a row then draws its own ring). */
val LocalRowGlide = staticCompositionLocalOf<RowGlide?> { null }

/**
 * One focus ring for the rows of a (scrolling) list that glides to whichever row has the focus, [Motion.ROW_FOCUS] ms with
 * the slight overshoot of [Motion.Glide], instead of every row fading its own in and out ([RowGlide]). The rows mark
 * themselves with [rowGlideTarget] and draw no ring of their own while [LocalRowGlide] is set; the host has to enclose
 * the list (give it the list's size, fade and clip modifiers), because the ring is drawn behind the host's content,
 * [focusScale] times as big as the row. The first focus into the list, and reduced motion, put the ring on the row
 * without gliding.
 */
@Composable
fun RowGlideHost(
    ring: FocusRing,
    corner: Float,
    focusScale: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val state = remember { RowGlide(scope) }
    SideEffect { state.reduced = reduced }
    CompositionLocalProvider(LocalRowGlide provides state) {
        Box(
            modifier
                .onGloballyPositioned { state.attach(it) }
                .drawBehind { state.draw(this, ring, corner, focusScale, colors, stage) },
            content = { content() },
        )
    }
}

/**
 * Marks a row of a [RowGlideHost]: while it is [focused] the ring glides to it and follows it as it scrolls. Put it before the
 * row's `focusEffects` (so its bounds are those of the layout, not of the focus scale). Does nothing outside a host.
 */
@Composable
fun Modifier.rowGlideTarget(focused: Boolean): Modifier {
    val ring = LocalRowGlide.current ?: return this
    val key = remember { Any() }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    LaunchedEffect(focused) {
        if (focused) coordinates[0]?.let { ring.focus(key, it) } else ring.blur(key)
    }
    DisposableEffect(ring) { onDispose { ring.release(key) } }
    return onGloballyPositioned {
        coordinates[0] = it
        if (focused) ring.moved(key, it)
    }
}
