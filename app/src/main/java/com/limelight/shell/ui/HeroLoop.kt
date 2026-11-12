package com.limelight.shell.ui

import android.graphics.Canvas
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.limelight.shell.data.AnimatedWebp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * How an animation loops without a jump. An `AnimatedImageDrawable` cannot seek, so its own loop shows the last frame and then the first
 * one. Instead each pass is a drawable of its own that plays once: during the last [fadeMillis] of a pass the next one starts at its first
 * frame and fades in over it, so at the loop point the new pass is already fully visible and the old one is dropped. The next pass starts
 * [periodMillis] (the loop's length less the fade) after the one before it. Times are milliseconds since the newest pass started.
 */
class HeroLoopTimeline private constructor(val loopMillis: Long, val fadeMillis: Long) {
    /** From the start of a pass to the start of the next one. */
    val periodMillis: Long get() = loopMillis - fadeMillis

    /** When, in a pass, the next one is decoded: [DECODE_LEAD_MS] before it starts, which is never during the fade of this pass. */
    val decodeAtMillis: Long get() = periodMillis - DECODE_LEAD_MS

    /** How far the fade of the pass that has run for [sinceStartMillis] is, 0 (just started, invisible) to 1 (fully visible, the pass before it is dropped). */
    fun progress(sinceStartMillis: Float): Float = (sinceStartMillis / fadeMillis).coerceIn(0f, 1f)

    companion object {
        /** A loop shorter than this keeps the platform's plain loop: the fade would be most of it. */
        const val MIN_LOOP_MS = 1500L

        /** The length of the fade (a third of the loop at most). */
        const val FADE_MS = 700L

        const val DECODE_LEAD_MS = 500L

        /** The timeline of an animation that takes [loopMillis] a pass, or null when it is too short (or unknown, 0) to fade. */
        fun of(loopMillis: Long): HeroLoopTimeline? =
            if (loopMillis < MIN_LOOP_MS) null else HeroLoopTimeline(loopMillis, minOf(FADE_MS, loopMillis / 3))
    }
}

/**
 * The playing passes of a looping animation (see [HeroLoopTimeline]). [bottom] is the one that plays alone, or that is on its way out
 * while [top] fades in over it at [mix]; all three are read while drawing, so a change redraws the layer that draws it.
 * Passes are decoded one at a time, shortly before they start, and released when they are dropped: outside of the fade the cost is one
 * playing drawable. [run] plays until cancelled, then leaves the drawables stopped on the frame they show ([release] frees them).
 */
@Stable
class LoopedAnimation internal constructor(
    first: AnimatedImageDrawable,
    private val timeline: HeroLoopTimeline?,
    // Held strongly here: a drawable only keeps a weak reference to its callback
    private val callback: Drawable.Callback,
    private val decode: suspend () -> AnimatedImageDrawable?,
) {
    var bottom by mutableStateOf(first.also { it.callback = callback })
        private set
    var top by mutableStateOf<AnimatedImageDrawable?>(null)
        private set
    var mix by mutableFloatStateOf(0f)
        private set

    /** The pass that starts next, decoded and not started yet. */
    private var next: AnimatedImageDrawable? = null

    /** Without a [timeline], or once a next pass could not be decoded, the first drawable repeats forever by itself. */
    private var plain = timeline == null

    // When the newest pass started (System.nanoTime)
    private var anchor = 0L

    /** Draws the pass(es) into the bounds, the incoming one over the other at [mix]. Allocation-free. */
    fun draw(canvas: Canvas, left: Int, top: Int, right: Int, bottom: Int) {
        val below = this.bottom
        below.setBounds(left, top, right, bottom)
        below.draw(canvas)
        val above = this.top ?: return
        val alpha = (mix * 255f).roundToInt()
        if (alpha <= 0) return
        val saved = canvas.saveLayerAlpha(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), alpha)
        above.setBounds(left, top, right, bottom)
        above.draw(canvas)
        canvas.restoreToCount(saved)
    }

    private fun elapsedNanos(now: Long) = now - anchor

    suspend fun run() {
        // start() always plays from the first frame (a stopped drawable that is started again resets), so a run that follows a stop begins
        // a pass anew: of the passes that were fading, the newer one stays
        top?.let {
            drop(bottom)
            bottom = it
            top = null
            mix = 0f
        }
        bottom.start()
        anchor = System.nanoTime()
        try {
            if (plain) awaitCancellation()
            val timeline = checkNotNull(timeline)
            while (true) {
                val incoming = top
                if (incoming != null) {
                    // Fading: every display frame
                    val now = withFrameNanos { it }
                    val progress = timeline.progress(elapsedNanos(now) / 1e6f)
                    if (progress >= 1f) {
                        drop(bottom)
                        bottom = incoming
                        top = null
                        mix = 0f
                    } else {
                        mix = Motion.Ease.transform(progress)
                    }
                    continue
                }
                val elapsed = elapsedNanos(System.nanoTime()) / 1e6f
                if (next == null) {
                    val wait = timeline.decodeAtMillis - elapsed
                    if (wait > 0f) {
                        delay(wait.toLong().coerceAtLeast(1L))
                    } else {
                        val pass = decode()
                        if (pass == null) {
                            // No second drawable: let the first one loop on its own
                            plain = true
                            bottom.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                            awaitCancellation()
                        }
                        pass.callback = callback
                        next = pass
                    }
                    continue
                }
                val wait = timeline.periodMillis - elapsed
                if (wait > 0f) {
                    delay(wait.toLong().coerceAtLeast(1L))
                    continue
                }
                // The loop point is a fade away: the next pass starts and fades in
                val pass = checkNotNull(next)
                next = null
                pass.start()
                anchor = System.nanoTime()
                mix = 0f
                top = pass
            }
        } finally {
            bottom.stop()
            top?.stop()
        }
    }

    fun release() {
        drop(bottom)
        top?.let(::drop)
        next?.let(::drop)
        top = null
        next = null
    }

    private fun drop(drawable: AnimatedImageDrawable) {
        drawable.stop()
        drawable.callback = null
    }
}

/**
 * The looping animation of a hero [source], decoded no wider than [maxWidth] while this is composed (and released when it leaves, so a
 * hero that is not shown costs no memory). Frames are reported to [ticker]. Running it is the caller's ([PlayLoopedAnimation]); one
 * that is not running keeps its last frame.
 */
@Composable
fun rememberLoopedAnimation(source: AnimatedSource.OfFile, maxWidth: Int, ticker: AnimationTicker): LoopedAnimation? {
    val callback = remember(ticker) {
        object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) = ticker.invalidate()
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
    }
    val animation by produceState<LoopedAnimation?>(null, source, maxWidth, callback) {
        value = loadLoopedAnimation(source, maxWidth, callback)
        awaitDispose { value?.release() }
    }
    return animation
}

private suspend fun loadLoopedAnimation(source: AnimatedSource.OfFile, maxWidth: Int, callback: Drawable.Callback): LoopedAnimation? {
    val loopMillis = withContext(Dispatchers.IO) { runCatching { source.file.inputStream().use(AnimatedWebp::loopMillis) }.getOrDefault(0L) }
    val timeline = HeroLoopTimeline.of(loopMillis)
    val repeat = if (timeline == null) AnimatedImageDrawable.REPEAT_INFINITE else 0
    val first = decodeAnimation(source, maxWidth, Int.MAX_VALUE, false, repeat) ?: return null
    return LoopedAnimation(first, timeline, callback) { decodeAnimation(source, maxWidth, Int.MAX_VALUE, false, repeat) }
}

/** Plays [animation] while [play] and the window is resumed (from where it stopped, or from its start when it never ran). */
@Composable
fun PlayLoopedAnimation(animation: LoopedAnimation?, play: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(animation, play, lifecycle) {
        if (animation == null || !play) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { animation.run() }
    }
}
