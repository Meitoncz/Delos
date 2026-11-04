package com.limelight.shell.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.limelight.shell.Portal
import com.limelight.shell.StreamExit
import dev.chrisbanes.haze.HazeState

/** The pulses of the portal (`#portal .p-ring`): four rings 0.35 s apart, every 2.2 s. */
private const val RIPPLE_PERIOD = 2.2f
private const val RIPPLE_GAP = .35f

/**
 * The portal into a PC (`#portal`): the screen behind blurs ([haze]; over the stream the system blurs it
 * instead, or it is only dimmed where that is not available: [dim]), rings ripple out from an orb with the PC's icon, its name and state below. When [burst]
 * it flashes open and the orb flies at the viewer. It holds the focus while shown, so nothing underneath
 * reacts; only B (cancel) passes through.
 */
@Composable
fun PortalOverlay(portal: Portal?, burst: Boolean, haze: HazeState?, reducedMotion: Boolean, dim: Boolean = false) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val last = remember { arrayOfNulls<Portal>(1) }
    if (portal != null) last[0] = portal
    // An arriving burst is there at once: the window it comes in with fades in already
    AnimatedVisibility(portal != null, enter = if (portal?.arriving == true) EnterTransition.None else fadeIn(tween(300)), exit = fadeOut(tween(300))) {
        val p = last[0] ?: return@AnimatedVisibility
        val start = remember(p.id) { System.nanoTime() }
        val clock = rememberLaunchClock(start)
        val flash = remember(p.id) { Animatable(0f) }
        LaunchedEffect(burst) {
            if (!burst) flash.snapTo(0f)
            else if (p.arriving) {
                // Goes on from where the burst over the stream left off
                val from = StreamExit.BURST_MS / 900f
                flash.snapTo(from)
                flash.animateTo(1f, tween((900 * (1f - from)).toInt(), easing = EaseOut))
            } else flash.animateTo(1f, tween(900, easing = EaseOut))
        }
        // .core: scale(1.6) and fade out over .8 / .6 s when it bursts
        val coreScale by animateFloatAsState(if (burst) 1.6f else 1f, tween(800, easing = EaseOut), label = "portalCore")
        val coreAlpha by animateFloatAsState(if (burst) 0f else 1f, tween(600), label = "portalCoreAlpha")
        val trap = remember { FocusRequester() }
        LaunchedEffect(p.id) { runCatching { trap.requestFocus() } }
        Box(
            Modifier
                .fillMaxSize()
                .then(if (haze != null) Modifier.backdropBlur(haze, 22f, Color.Transparent) else Modifier)
                .then(if (dim) Modifier.background(colors.bg.copy(alpha = .45f)) else Modifier)
                .drawBehind {
                    // radial-gradient(bg 25 % at the center → bg 70 % at 75 %)
                    drawRect(
                        Brush.radialGradient(
                            0f to colors.bg.copy(alpha = .25f), .75f to colors.bg.copy(alpha = .7f),
                            center = center, radius = size.maxDimension / 2f,
                        )
                    )
                }
                .focusRequester(trap)
                .focusable()
                .onPreviewKeyEvent { it.key != Key.Back && it.key != Key.ButtonB },
            contentAlignment = Alignment.Center,
        ) {
            if (!reducedMotion) {
                Canvas(Modifier.fillMaxSize()) {
                    val k = stage.dpPerPx * density
                    val t = clock()
                    for (i in 0 until 4) {
                        val local = t - i * RIPPLE_GAP
                        if (local < 0f) continue
                        val u = (local % RIPPLE_PERIOD) / RIPPLE_PERIOD
                        // @keyframes ripple: scale .35 → 3.4 of a 300 px ring, opacity 0 → 1 (25 %) → 0
                        val alpha = if (u < .25f) EaseOut.transform(u / .25f) else 1f - EaseOut.transform((u - .25f) / .75f)
                        val radius = 150f * k * (.35f + 3.05f * EaseOut.transform(u))
                        val halo = 36f * k
                        val outer = radius + halo
                        drawCircle(
                            Brush.radialGradient(
                                0f to Color.Transparent, ((radius - halo) / outer).coerceAtLeast(0f) to Color.Transparent,
                                radius / outer to colors.glow, 1f to Color.Transparent,
                                center = center, radius = outer,
                            ),
                            radius = outer, center = center, alpha = alpha,
                        )
                        drawCircle(when (i) { 1 -> colors.accent2; 3 -> colors.accent3; else -> colors.accent }, radius = radius, center = center, style = Stroke(3f * k), alpha = alpha)
                    }
                    // .flash: accent → accent-2 → transparent, scale .5 → 16 while fading out
                    val f = flash.value
                    if (f > 0f && f < 1f) {
                        val r = 100f * k * (.5f + 15.5f * f)
                        drawCircle(
                            Brush.radialGradient(0f to colors.accent, .45f to colors.accent2, .7f to Color.Transparent, center = center, radius = r),
                            radius = r, center = center, alpha = 1f - f,
                        )
                    }
                }
            }
            Column(
                Modifier.graphicsLayer {
                    scaleX = coreScale
                    scaleY = coreScale
                    alpha = coreAlpha
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(30.px),
            ) {
                Box(
                    Modifier
                        .size(220.px)
                        .drawBehind {
                            // box-shadow: 0 0 90px -10px glow
                            val k = stage.dpPerPx * density
                            drawCircle(
                                Brush.radialGradient(0f to colors.glow, 1f to Color.Transparent, center = center, radius = size.minDimension / 2f + 80f * k),
                                radius = size.minDimension / 2f + 80f * k,
                            )
                        }
                        .clip(CircleShape)
                        .drawBehind {
                            drawRect(cssRadialCircle(Offset(size.width * .4f, size.height * .35f), size, 0f to colors.surface3, 1f to colors.bg2))
                        }
                        .border(2.px, colors.accent3Line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Symbol(p.icon, 100.tpx, colors.accent, filled = true)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.px)) {
                    BasicText(p.label, style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 36.tpx, textAlign = TextAlign.Center))
                    BasicText(p.sub, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 24.tpx, textAlign = TextAlign.Center))
                }
            }
        }
    }
}
