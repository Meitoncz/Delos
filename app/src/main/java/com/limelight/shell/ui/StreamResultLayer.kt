package com.limelight.shell.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import com.limelight.shell.StreamResult
import dev.chrisbanes.haze.HazeState

/**
 * What the player sees after a stream failed or its connection was lost (docs/GUI.md, "Stream exits"): a
 * calm full-screen glass layer over Home with a sad face, what happened in one sentence, and Try again /
 * Back to library. The technical text is behind Details (never open by default). The focus starts on
 * Try again and stays inside; B is [onBack] (wired by the shell), like the second button.
 */
@Composable
fun StreamResultLayer(result: StreamResult?, haze: HazeState, reducedMotion: Boolean, onRetry: () -> Unit, onBack: () -> Unit) {
    val colors = LocalDelosColors.current
    var shown by remember { mutableStateOf(result) }
    if (result != null) shown = result
    val open by rememberUpdatedState(result != null)
    AnimatedVisibility(result != null, enter = fadeIn(tween(350)), exit = fadeOut(tween(350))) {
        val r = shown ?: return@AnimatedVisibility
        var expanded by remember(r.id) { mutableStateOf(false) }
        val retry = remember { FocusRequester() }
        InitialFocus(retry, r.id)
        Box(
            Modifier
                .fillMaxSize()
                .backdropBlur(haze, 24f, colors.scrim)
                // Nothing underneath reacts to a tap
                .pointerInput(Unit) { detectTapGestures { } }
                .focusProperties { onExit = { if (open) cancelFocusChange() } }
                .focusGroup(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.px)) {
                FaceBadge(r.id, reducedMotion)
                BasicText(
                    r.title,
                    style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 52.tpx, textAlign = TextAlign.Center),
                    modifier = Modifier.padding(top = 8.px),
                )
                BasicText(
                    r.sentence,
                    style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 28.tpx, lineHeight = 1.4.em, textAlign = TextAlign.Center),
                    modifier = Modifier.widthIn(max = 1000.px),
                )
                Row(Modifier.padding(top = 12.px), horizontalArrangement = Arrangement.spacedBy(24.px)) {
                    PillButton("Try again", Symbols.RESTART_ALT, onRetry, Modifier.focusRequester(retry), primary = true)
                    PillButton("Back to library", Symbols.PHOTO_LIBRARY, onBack, clickCue = Cue.BACK)
                }
                r.details?.takeIf { it.isNotBlank() }?.let { details ->
                    DetailsToggle(expanded) { expanded = !expanded }
                    AnimatedVisibility(expanded, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
                        val shape = RoundedCornerShape(28.px)
                        BasicText(
                            details,
                            style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx, lineHeight = 1.4.em, textAlign = TextAlign.Start),
                            modifier = Modifier
                                .widthIn(max = 1100.px)
                                .heightIn(max = 280.px)
                                .clip(shape)
                                .background(colors.surface)
                                .border(1.px, colors.line, shape)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 32.px, vertical = 22.px),
                        )
                    }
                }
            }
        }
    }
}

/** The sad face in an accent-tinted circle: it settles in gently and then floats a little (fade only with reduced motion). */
@Composable
private fun FaceBadge(id: Long, reducedMotion: Boolean) {
    val colors = LocalDelosColors.current
    val enter = remember(id) { Animatable(0f) }
    LaunchedEffect(id) { enter.animateTo(1f, tween(if (reducedMotion) 300 else 800, easing = EaseOut)) }
    val float = if (reducedMotion) null else rememberInfiniteTransition(label = "face").animateFloat(
        -1f, 1f, infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "faceFloat",
    )
    val shape = CircleShape
    Box(
        Modifier
            .size(220.px)
            .graphicsLayer {
                val e = enter.value
                alpha = (e * 1.6f).coerceAtMost(1f)
                if (!reducedMotion) {
                    val scale = .72f + .28f * e
                    scaleX = scale
                    scaleY = scale
                    translationY = (float?.value ?: 0f) * 7f * density
                }
            }
            .clip(shape)
            .background(colors.accent.copy(alpha = .16f))
            .border(2.px, colors.accent.copy(alpha = .42f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(Symbols.SENTIMENT_DISSATISFIED, 128.tpx, colors.accent, filled = true)
    }
}

/** The small text button under the actions: opens and closes the technical details. */
@Composable
private fun DetailsToggle(expanded: Boolean, onClick: () -> Unit) {
    val colors = LocalDelosColors.current
    Row(
        Modifier
            .focusEffects(onClick = onClick, corner = 999f, focusScale = 1.06f, ring = FocusRing(3f, 3f), glow = null)
            .clip(CircleShape)
            .padding(horizontal = 26.px, vertical = 12.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.px),
    ) {
        BasicText("Details", style = TextStyle(color = colors.text2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.tpx))
        Symbol(if (expanded) Symbols.EXPAND_LESS else Symbols.EXPAND_MORE, 26.tpx, colors.text2)
    }
}
