package com.limelight.shell.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Handed from the new UI to the stream (same process): the stream's loader continues exactly where the
 * one that started it left off, so the switch between the two windows cannot be seen.
 */
object LaunchHandoff {
    /** The game background (HeroBitmaps) behind the ring. */
    @Volatile var background: ImageBitmap? = null

    /** How far the ring got before the stream window took over. */
    @Volatile var progress: Float = 0f

    /** When the launch scene started (System.nanoTime), so its pulses and moon go on without a jump. */
    @Volatile var startedAt: Long = 0L
}

/** Seconds since [start] (System.nanoTime, like the frame clock), updated every frame; read it while drawing. */
@Composable
fun rememberLaunchClock(start: Long): () -> Float {
    val now = remember { mutableLongStateOf(start) }
    LaunchedEffect(start) { while (true) withFrameNanos { now.longValue = it } }
    return remember(start) { { (now.longValue - start).coerceAtLeast(0L) / 1e9f } }
}

/** What the launch scene shows. */
@Immutable
data class LaunchCard(val id: Int, val title: String, val subtitle: String, val art: GameArt, val background: ImageBitmap?)

/** Ring geometry in stage pixels (the prototype's 300 px loader with a 248 px cover). */
private const val RING_RADIUS = 140f
private const val COVER_RADIUS = 124f

/**
 * The launch scene, shared by the new UI and the stream: the game background, the ring with its progress
 * around the game's cover at the center of the screen, the title and the connection stage below. With a
 * [clock] (seconds since the launch began; none with reduced motion), pulses spread out from the ring and
 * the moon of the Delos logo circles it. [opening] (0–1) opens the ring: first the cover gives way to
 * what is behind it (the stream), then the opening grows past the screen edges, the pulses and the moon
 * with it. Everything is read while drawing, so the animations recompose nothing.
 */
@Composable
fun LaunchScene(card: LaunchCard, stage: String?, progress: () -> Float, opening: () -> Float, clock: (() -> Float)?, modifier: Modifier = Modifier) {
    val colors = LocalDelosColors.current
    val metrics = LocalStage.current
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val hole = holeRadius(opening(), metrics.dpPerPx * density, size)
                if (hole > 0f) {
                    // A soft-edged opening: whatever lies behind this layer (the stream) shows through
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val edge = 10f * metrics.dpPerPx * density
                    drawCircle(
                        Brush.radialGradient(
                            0f to Color.Black, ((hole - edge) / hole).coerceIn(0f, 1f) to Color.Black, 1f to Color.Transparent,
                            center = center, radius = hole,
                        ),
                        radius = hole, center = center, blendMode = BlendMode.DstOut,
                    )
                }
                // The cover on top of the opening: it fades out into the stream instead of being cut away with it
                val k = metrics.dpPerPx * density
                drawCover(card, Offset(size.width / 2f, size.height / 2f), COVER_RADIUS * k, alpha = (1f - opening() / .35f).coerceIn(0f, 1f))
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val k = metrics.dpPerPx * density
            drawRect(colors.bg)
            card.background?.let { bg ->
                // The hero area of the home screen: 2100×1260 stage pixels around the screen
                val w = 2100f * k
                val h = 1260f * k
                drawImage(
                    bg, dstOffset = IntOffset(((size.width - w) / 2f).roundToInt(), ((size.height - h) / 2f).roundToInt()),
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                )
            }
            drawRect(colors.scrim)
            val o = opening()
            val center = Offset(size.width / 2f, size.height / 2f)
            val ring = ringRadius(o, k, size)
            val fade = (1f - ((o - .35f) / .65f).coerceIn(0f, 1f)).let { it * it }
            val time = clock?.invoke()
            // The cover is drawn above all of this (after the opening is cut)
            if (time != null) drawPulses(center, k, ring / (RING_RADIUS * k), fade, time, colors)
            drawCircle(colors.line.copy(alpha = colors.line.alpha * fade), radius = ring, center = center, style = Stroke(10f * k))
            val sweep = 360f * progress()
            val box = Size(ring * 2f, ring * 2f)
            val topLeft = Offset(center.x - ring, center.y - ring)
            drawArc(colors.glow.copy(alpha = colors.glow.alpha * fade), -90f, sweep, false, topLeft, box, style = Stroke(22f * k, cap = StrokeCap.Round))
            drawArc(colors.accent.copy(alpha = fade), -90f, sweep, false, topLeft, box, style = Stroke(10f * k, cap = StrokeCap.Round))
            if (time != null) drawMoon(center, ring + 30f * k, k, fade, time, colors)
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .offset(y = (RING_RADIUS + 34f + 60f).px)
                .graphicsLayer { alpha = (1f - opening() * 4f).coerceIn(0f, 1f) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                card.title,
                style = TextStyle(color = Color.White, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 38.tpx, textAlign = TextAlign.Center),
            )
            BasicText(
                stage ?: card.subtitle,
                style = TextStyle(color = Color.White.copy(alpha = .7f), fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 24.tpx, textAlign = TextAlign.Center),
                modifier = Modifier.padding(top = 10.px),
            )
        }
    }
}

/**
 * The ring stays put while the cover gives way (opening 0–.35), then grows past the corners: barely at
 * first, then it rushes past. Exponential growth reads as flying towards it; the growing exponent makes
 * the flight speed up.
 */
private fun ringRadius(opening: Float, k: Float, size: Size): Float {
    val b = ((opening - .35f) / .65f).coerceIn(0f, 1f)
    val near = RING_RADIUS * k
    val far = hypot(size.width, size.height) / 2f + 40f * k
    return near * (far / near).pow(b.pow(2.4f))
}

/** The opening: the cover's circle first, then just inside the growing ring. */
private fun holeRadius(opening: Float, k: Float, size: Size): Float =
    if (opening <= 0f) 0f else max(COVER_RADIUS * k, ringRadius(opening, k, size) - 16f * k)

/** Two pulses, half a period apart. */
private const val PULSE_PERIOD = 2.4f
private const val PULSES = 2

/** One moon orbit around the ring, in seconds. */
private const val ORBIT_PERIOD = 2.6f

/**
 * Soft rings pulsing out of the ring, the colors alternating (like the pairing screen's online pulse,
 * `@keyframes pulse`): each grows by about half the ring's size while it fades out. [grow] is how much
 * the ring has grown while opening.
 */
private fun DrawScope.drawPulses(center: Offset, k: Float, grow: Float, fade: Float, time: Float, colors: DelosColors) {
    for (i in 0 until PULSES) {
        val local = time - i * PULSE_PERIOD / PULSES
        if (local < 0f) continue
        val t = (local % PULSE_PERIOD) / PULSE_PERIOD
        val alpha = fade * .55f * (1f - t) * (1f - t)
        if (alpha <= 0f) continue
        val radius = RING_RADIUS * k * (1f + .55f * EaseOut.transform(t)) * grow
        // The glow on both sides of the line (the prototype's outer and inset box shadows)
        val halo = 20f * k
        val outer = radius + halo
        drawCircle(
            Brush.radialGradient(
                0f to Color.Transparent, ((radius - halo) / outer).coerceAtLeast(0f) to Color.Transparent,
                radius / outer to colors.glow, 1f to Color.Transparent,
                center = center, radius = outer,
            ),
            radius = outer, center = center, alpha = alpha,
        )
        drawCircle(if (i % 2 == 0) colors.accent else colors.accent2, radius = radius, center = center, style = Stroke(3f * k), alpha = alpha)
    }
}

/** The moon of the Delos logo (`.boot-logo .orbit`) circling the ring at [orbit], from the top right. */
private fun DrawScope.drawMoon(center: Offset, orbit: Float, k: Float, fade: Float, time: Float, colors: DelosColors) {
    if (fade <= 0f) return
    // Clockwise on the screen (y points down), starting at the top right like in the logo
    val angle = time / ORBIT_PERIOD * 2f * PI.toFloat() - PI.toFloat() / 4f
    val moon = center + Offset(cos(angle) * orbit, sin(angle) * orbit)
    val halo = 34f * k
    drawCircle(Brush.radialGradient(listOf(colors.accent2.copy(alpha = .5f), Color.Transparent), center = moon, radius = halo), radius = halo, center = moon, alpha = fade)
    drawCircle(colors.accent2, radius = 12f * k, center = moon, alpha = fade)
}

/** The game's cover in the ring (center-cropped), or its placeholder colors. */
private fun DrawScope.drawCover(card: LaunchCard, center: Offset, radius: Float, alpha: Float) {
    if (alpha <= 0f) return
    val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, radius)) }
    clipPath(circle) {
        val image = card.art.image
        if (image != null) {
            val side = minOf(image.width, image.height)
            drawImage(
                image,
                srcOffset = IntOffset((image.width - side) / 2, (image.height - side) / 2), srcSize = IntSize(side, side),
                dstOffset = IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()),
                dstSize = IntSize((radius * 2f).roundToInt(), (radius * 2f).roundToInt()), alpha = alpha,
            )
        } else {
            drawCircle(
                Brush.linearGradient(listOf(hsl(card.art.hue, 58f, 34f), hsl(card.art.hue2, 60f, 18f)), center - Offset(radius, radius), center + Offset(radius, radius)),
                radius = radius, center = center, alpha = alpha,
            )
        }
    }
}

/**
 * The new UI's half of starting a game: the scene fades in over the home screen and the ring starts
 * filling; the stream window then takes over the same scene (StreamLoader).
 */
@Composable
fun LaunchOverlay(card: LaunchCard?, reducedMotion: Boolean) {
    val last = remember { arrayOfNulls<LaunchCard>(1) }
    if (card != null) last[0] = card
    AnimatedVisibility(card != null, enter = fadeIn(tween(300)), exit = fadeOut(tween(450))) {
        val c = last[0] ?: return@AnimatedVisibility
        val progress = remember(c.id) { Animatable(0f) }
        LaunchedEffect(c.id) { progress.animateTo(HANDOFF_PROGRESS, tween(700, easing = EaseOut)) }
        val start = remember(c.id) { System.nanoTime().also { LaunchHandoff.startedAt = it } }
        val clock = rememberLaunchClock(start)
        // Holds the focus while a game starts, so nothing underneath reacts; only B (cancel) passes through
        val trap = remember { FocusRequester() }
        LaunchedEffect(c.id) { runCatching { trap.requestFocus() } }
        LaunchScene(
            c, stage = null, progress = { progress.value }, opening = { 0f }, clock = clock.takeUnless { reducedMotion },
            modifier = Modifier
                .focusRequester(trap)
                .focusable()
                .onPreviewKeyEvent { it.key != Key.Back && it.key != Key.ButtonB },
        )
    }
}

/** How far the ring fills in the new UI before the stream window takes over. */
const val HANDOFF_PROGRESS = .3f
