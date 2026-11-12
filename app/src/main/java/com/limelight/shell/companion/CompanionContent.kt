package com.limelight.shell.companion

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.LogoMark
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx

/**
 * The single place the content of the companion screen is decided (variant A of the approved prototype, "Details and actions"):
 * the focused game with Start, Resume and Game settings in the shell; the statistics and the stream menu as touch tiles in a
 * stream; the keyboard when typing. It runs inside the companion's own `Stage`, so `LocalStage` is planned from that display's
 * size, and inside the Delos colors. [startTyping] opens a stream on the keyboard (debug previews).
 *
 * Burn-in (docs/DUAL_SCREEN.md): everything drifts by a few pixels every two minutes ([CompanionBurnIn.shiftAt]); in the shell
 * state the screen dims after a minute without a touch and the touch that wakes it is not passed on.
 */
@Composable
internal fun CompanionContent(state: CompanionState, startTyping: Boolean = false, startSheet: String? = null, inWindow: Boolean = false) {
    val colors = LocalDelosColors.current
    val reducedMotion = LocalReducedMotion.current
    val dimming = state is CompanionState.Shell
    var lastTouch by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var dimmed by remember { mutableStateOf(false) }
    // Armed from the last touch; a touch restarts it, so no polling. Outside the shell state the screen is never dimmed
    // The settings help is the description the main screen gave up: it dims later, and moving the focus (a new row's help) counts like a touch
    val help = (state as? CompanionState.Shell)?.help
    LaunchedEffect(lastTouch, dimming, help?.identity) {
        dimmed = false
        if (!dimming) return@LaunchedEffect
        delay(CompanionBurnIn.dimAfter(help != null))
        dimmed = true
    }
    var shift by remember { mutableStateOf(CompanionBurnIn.Shift(0, 0)) }
    LaunchedEffect(Unit) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            shift = CompanionBurnIn.shiftAt(SystemClock.elapsedRealtime() - start)
            delay(CompanionBurnIn.SHIFT_PERIOD_MS)
        }
    }
    val alpha by animateFloatAsState(if (dimmed) CompanionBurnIn.DIM_ALPHA else 1f, if (reducedMotion) snap() else tween(1200), label = "companionDim")
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            // Every touch counts, whoever consumes it
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    lastTouch = SystemClock.elapsedRealtime()
                }
            },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = shift.x * CompanionBurnIn.SHIFT_STEP_DP * density
                translationY = shift.y * CompanionBurnIn.SHIFT_STEP_DP * density
                this.alpha = alpha
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                Header(
                    when (state) {
                        is CompanionState.Shell -> state.pcName
                        is CompanionState.Stream -> state.pcName
                    },
                )
                Box(Modifier.fillMaxSize()) {
                    when (state) {
                        is CompanionState.Shell -> ShellContent(state, inWindow)
                        is CompanionState.Stream -> StreamContent(state, startTyping, startSheet)
                    }
                }
            }
        }
        if (dimmed) {
            // Dimmed: the first touch only wakes the screen (it must never press a button it cannot be seen to press)
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        dimmed = false
                        lastTouch = SystemClock.elapsedRealtime()
                        waitForUpOrCancellation()
                    }
                },
            )
        }
    }
}

/** The strip at the top (`.stage-header`): the brand on the left, the PC on the right. */
@Composable
private fun Header(pcName: String?) {
    val colors = LocalDelosColors.current
    Row(
        Modifier.fillMaxWidth().height(HEADER_HEIGHT.px).padding(horizontal = 44.px),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.px), verticalAlignment = Alignment.CenterVertically) {
            LogoMark(size = 44f)
            BasicText(
                "DELOS",
                style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = MIN_TEXT.tpx, letterSpacing = .12.em),
            )
        }
        if (!pcName.isNullOrBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.px), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.px).clip(CircleShape).background(colors.accent2))
                BasicText(
                    pcName,
                    style = TextStyle(color = colors.text2, fontFamily = com.limelight.shell.ui.Figtree, fontSize = MIN_TEXT.tpx),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.px).background(Brush.horizontalGradient(listOf(colors.line, colors.line))))
}

private const val HEADER_HEIGHT = 92
