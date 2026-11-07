package com.limelight.shell.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * When animated artwork plays and how much of it plays at once. The animation of a hero or a picker tile follows the
 * same rules as the rest of the app's motion: nothing moves with reduced motion or the system's animations off, and
 * nothing runs where it cannot be seen.
 */
object AnimatedPlayback {
    /** One screenful of picker tiles (7 covers per row, two rows) plays at most. */
    const val MAX_TILES = 14

    /** The most pixels a decoded frame of a picker tile has across. */
    const val TILE_WIDTH = 520

    /** A hero frame is decoded at half the hero area (2100 px), which the blur hides anyway. */
    const val HERO_WIDTH = 1050

    /**
     * Whether the animation of a view plays now. [visible]: the view is on screen and not covered; [foreground]: the app's
     * window is resumed; [streaming]: a stream is being started or runs.
     */
    fun shouldPlay(visible: Boolean, foreground: Boolean, streaming: Boolean, reducedMotion: Boolean, animatorsOff: Boolean): Boolean =
        visible && foreground && !streaming && !reducedMotion && !animatorsOff

    /** The keys of the animated tiles that play: those in [visibleAnimated] (in screen order), at most [limit]. */
    fun <K> tilesToPlay(visibleAnimated: List<K>, limit: Int = MAX_TILES): Set<K> = visibleAnimated.take(limit).toSet()

    /** The size a [width] × [height] image is decoded at: [maxWidth] across at most, never enlarged, the shape kept. */
    fun decodeSize(width: Int, height: Int, maxWidth: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        if (width <= maxWidth) return width to height
        return maxWidth to (height.toLong() * maxWidth / width).toInt().coerceAtLeast(1)
    }
}

/**
 * The one frame ticker of the animated images of a screen: every animation reports when it has a new frame, and the
 * ticker turns all reports of a frame into a single redraw of every view that reads [frame]. It only runs while
 * [rememberAnimationTicker] is active, so a paused screen costs nothing.
 */
@Stable
class AnimationTicker {
    /** Changes once per rendered frame while an animation reported a new image; read it in the draw phase. */
    val frame = mutableIntStateOf(0)
    private val reports = Channel<Unit>(Channel.CONFLATED)

    fun invalidate() {
        reports.trySend(Unit)
    }

    internal suspend fun run() {
        for (report in reports) {
            withFrameNanos { }
            frame.intValue++
        }
    }
}

@Composable
fun rememberAnimationTicker(active: Boolean): AnimationTicker {
    val ticker = remember { AnimationTicker() }
    LaunchedEffect(active, ticker) { if (active) ticker.run() }
    return ticker
}

/** What an animation is decoded from: a downloaded file or the bytes of a thumbnail. */
sealed interface AnimatedSource {
    data class OfFile(val file: File) : AnimatedSource
    class OfBytes(val bytes: ByteArray) : AnimatedSource {
        // Identity: the same bytes are the same animation
        override fun equals(other: Any?) = other is OfBytes && other.bytes === bytes
        override fun hashCode() = System.identityHashCode(bytes)
    }
}

/** The animation of [source] decoded at [maxWidth] across, or null when it is not an animation or cannot be decoded. */
private suspend fun decodeAnimation(source: AnimatedSource, maxWidth: Int): AnimatedImageDrawable? = withContext(Dispatchers.Default) {
    runCatching {
        val decoderSource = when (source) {
            is AnimatedSource.OfFile -> ImageDecoder.createSource(source.file)
            is AnimatedSource.OfBytes -> ImageDecoder.createSource(ByteBuffer.wrap(source.bytes))
        }
        val drawable = ImageDecoder.decodeDrawable(decoderSource) { decoder, info, _ ->
            if (info.size.width.toLong() * info.size.height > com.limelight.shell.data.AnimatedWebp.MAX_PIXELS) throw IOException("canvas too big")
            val (w, h) = AnimatedPlayback.decodeSize(info.size.width, info.size.height, maxWidth)
            decoder.setTargetSize(w, h)
        }
        (drawable as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE }
    }.getOrNull()
}

/**
 * The decoded animation of [source], running while [playing] and the window is resumed, null while it is not loaded or
 * [playing] is false (the drawable is released then: a paused tile costs no memory). Frames are decoded on demand by the
 * platform, one at a time, at [maxWidth] across; every new frame is reported to [ticker].
 */
@Composable
fun rememberAnimatedDrawable(source: AnimatedSource?, playing: Boolean, maxWidth: Int, ticker: AnimationTicker): AnimatedImageDrawable? {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Held strongly here: a drawable only keeps a weak reference to its callback
    val callback = remember(ticker) {
        object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) = ticker.invalidate()
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
    }
    val drawable by produceState<AnimatedImageDrawable?>(null, source, playing, maxWidth) {
        value = if (source != null && playing) decodeAnimation(source, maxWidth) else null
        awaitDispose { value?.let { it.stop(); it.callback = null } }
    }
    val current = drawable
    LaunchedEffect(current, lifecycle) {
        if (current == null) return@LaunchedEffect
        current.callback = callback
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            current.start()
            try {
                awaitCancellation()
            } finally {
                current.stop()
            }
        }
    }
    return current
}

/**
 * Draws [drawable] over the whole [modifier] area, cropped to fill it or ([fit]) whole, fading in over the still image
 * underneath. The current frame is drawn whenever [ticker] has a new one, with no recomposition.
 */
@Composable
fun AnimatedDrawableView(drawable: AnimatedImageDrawable, ticker: AnimationTicker, modifier: Modifier = Modifier, fit: Boolean = false) {
    val shown = remember(drawable) { Animatable(0f) }
    LaunchedEffect(shown) { shown.animateTo(1f, tween(Motion.FADE, easing = Motion.Ease)) }
    Canvas(modifier.graphicsLayer { alpha = shown.value }) {
        // Reading the frame is what redraws this view for the next image
        if (ticker.frame.intValue == Int.MIN_VALUE) return@Canvas
        val iw = drawable.intrinsicWidth.coerceAtLeast(1)
        val ih = drawable.intrinsicHeight.coerceAtLeast(1)
        val scale = if (fit) minOf(size.width / iw, size.height / ih) else maxOf(size.width / iw, size.height / ih)
        val w = iw * scale
        val h = ih * scale
        val left = ((size.width - w) / 2f).toInt()
        val top = ((size.height - h) / 2f).toInt()
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            c.save()
            c.clipRect(0f, 0f, size.width, size.height)
            drawable.setBounds(left, top, left + w.toInt(), top + h.toInt())
            drawable.draw(c)
            c.restore()
        }
    }
}
