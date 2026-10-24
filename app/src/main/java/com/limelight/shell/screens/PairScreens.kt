package com.limelight.shell.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.limelight.shell.ContextMenu
import com.limelight.shell.LocalDeviceActions
import com.limelight.shell.MenuItem
import com.limelight.shell.Screen
import com.limelight.shell.data.Pc
import com.limelight.shell.data.PcRepository
import com.limelight.shell.data.PcSetup
import com.limelight.shell.ui.CenterColumn
import com.limelight.shell.ui.CircleItem
import com.limelight.shell.ui.Circles
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.EaseSpring
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.Heading
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalFeedback
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.OnlineGreen
import com.limelight.shell.ui.PillButton
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.px
import com.limelight.shell.ui.glass
import com.limelight.shell.ui.tpx
import kotlinx.coroutines.delay

/**
 * `#scr-pair`: the PCs Delos knows in circles (discovered ones to pair, paired ones connect right away),
 * then entering an address and searching again.
 */
@Composable
fun PairPcScreen(screen: Screen.PairPc, pcs: List<Pc>) {
    val actions = LocalDeviceActions.current
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(searching) {
        if (searching) {
            delay(3000)
            searching = false
        }
    }
    val first = remember { FocusRequester() }
    InitialFocus(first)
    // Discovered PCs first (online ones before offline), paired ones after them
    val shown = pcs.sortedWith(compareBy({ it.paired }, { !it.online }, { it.name.lowercase() })).take(5)
    CenterColumn(gap = 48f) {
        Heading(
            "Pair a PC",
            when {
                searching -> "Searching the local network…"
                pcs.isEmpty() -> "No PC found yet. Start Vibepollo on your PC, or enter its address."
                else -> "Delos found ${pcs.size} ${if (pcs.size == 1) "PC" else "PCs"} on your network."
            },
        )
        Circles {
            shown.forEachIndexed { i, pc ->
                CircleItem(
                    Symbols.DESKTOP_WINDOWS, pc.name,
                    onClick = { if (pc.paired) actions.connect(pc) else actions.pair(pc, screen) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    meta = when {
                        pc.paired -> if (pc.online) "Paired · Online" else "Paired · Offline"
                        pc.online -> "Online · ready to pair"
                        else -> "Offline"
                    },
                    metaDot = if (pc.online) OnlineGreen else null,
                    big = true,
                    selected = pc.paired,
                    pulse = pc.online && !pc.paired,
                    contextMenu = if (pc.paired) null else ContextMenu(
                        pc.name,
                        listOf(
                            MenuItem(Symbols.DIALPAD, "Pair with a PIN") { actions.pair(pc, screen) },
                            MenuItem(Symbols.PASSWORD, "Pair with a one-time code") { actions.pairWithCode(pc, screen) },
                        ),
                    ),
                )
            }
            CircleItem(
                Symbols.ADD, "Enter address", onClick = { actions.addByAddress(screen) },
                modifier = if (shown.isEmpty()) Modifier.focusRequester(first) else Modifier,
                meta = "IP or host name", big = true,
            )
            CircleItem(
                Symbols.REFRESH, "Search again",
                onClick = {
                    searching = true
                    actions.rescan()
                },
                meta = "Local network", big = true,
            )
        }
    }
}

/**
 * `#scr-pin`: pairing [screen]'s PC. Shows the PIN to type in the Vibepollo web UI (or pairs with the
 * one-time code), waits for the PC and moves on when it accepts. Leaving the screen cancels the attempt.
 */
@Composable
fun PinScreen(screen: Screen.Pin, repo: PcRepository, setup: PcSetup, onFailed: (String) -> Unit, onCancel: () -> Unit) {
    val colors = LocalDelosColors.current
    val feedback = LocalFeedback.current
    val actions = LocalDeviceActions.current
    val pin = remember(screen) { screen.otp?.pin ?: setup.newPin() }
    var accepted by remember { mutableStateOf(false) }
    LaunchedEffect(screen) {
        // A PC that was just added may take a moment to show up
        val pc = repo.awaitPc(screen.pcUuid, 5_000) { true }
        if (pc == null) {
            onFailed("${screen.pcName} cannot be found")
            return@LaunchedEffect
        }
        when (val result = setup.pair(pc, pin, screen.otp?.passphrase)) {
            PcSetup.PairResult.Paired -> {
                accepted = true
                feedback.play(Cue.SELECT)
                delay(900)
                actions.paired(pc.uuid)
            }
            is PcSetup.PairResult.Failed -> onFailed(result.message)
        }
    }
    val cancel = remember { FocusRequester() }
    InitialFocus(cancel)
    CenterColumn(gap = 44f) {
        if (screen.otp == null) {
            Heading("Enter this PIN on ${screen.pcName}", "Open the Vibepollo web UI on your PC, go to PIN and type the code.")
            PinDigits(pin)
        } else {
            Heading("Pairing with ${screen.pcName}", "Using the one-time code from Vibepollo.")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.px)) {
            if (!accepted) Spinner()
            BasicText(
                if (accepted) (if (screen.otp == null) "PIN accepted" else "Code accepted") else "Waiting for Vibepollo…",
                style = TextStyle(color = if (accepted) OnlineGreen else colors.text2, fontFamily = Figtree, fontSize = 26.tpx),
            )
        }
        PillButton("Cancel", Symbols.CLOSE, onCancel, Modifier.focusRequester(cancel), clickCue = Cue.BACK)
    }
}

/** `.pin`: the four digits, each springing in a little after the one before (`@keyframes pinIn`). */
@Composable
private fun PinDigits(pin: String) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    Row(horizontalArrangement = Arrangement.spacedBy(26.px)) {
        pin.forEachIndexed { i, digit ->
            val enter = remember(pin) { Animatable(0f) }
            LaunchedEffect(pin) {
                delay(80L * i)
                enter.animateTo(1f, tween(700, easing = EaseSpring))
            }
            val shape = RoundedCornerShape(32.px)
            Box(
                Modifier
                    .graphicsLayer {
                        val e = enter.value
                        alpha = e.coerceIn(0f, 1f)
                        val s = .8f + .2f * e
                        scaleX = s
                        scaleY = s
                        translationY = 30f * (1f - e) * stage.dpPerPx * density
                    }
                    .size(148.px, 176.px)
                    .clip(shape)
                    .glass(colors.surface2)
                    .border(1.px, colors.line, shape),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(digit.toString(), style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 92.tpx))
            }
        }
    }
}

/** `.spinner`: a 30 px ring with an accent quarter, a turn every 0.9 s. */
@Composable
private fun Spinner() {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spinnerTurn")
    Canvas(Modifier.size(30.px)) {
        val w = 4f * stage.dpPerPx * density
        val inset = w / 2f
        val box = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(colors.line, 0f, 360f, false, topLeft, box, style = Stroke(w))
        drawArc(colors.accent, turn - 135f, 90f, false, topLeft, box, style = Stroke(w, cap = StrokeCap.Butt))
    }
}
