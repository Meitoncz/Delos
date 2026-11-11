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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.shell.ui.textResources
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
import com.limelight.shell.ui.compact
import com.limelight.shell.ui.compactScale
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
    // Discovered PCs first (online ones before offline), paired ones after them; all of them, the row scrolls
    val shown = pcs.sortedWith(compareBy({ it.paired }, { !it.online }, { it.name.lowercase() }))
    CenterColumn(gap = 48f) {
        Heading(
            stringResource(R.string.pairing_pc_title),
            when {
                searching -> stringResource(R.string.pairing_pc_searching)
                pcs.isEmpty() -> stringResource(R.string.pairing_pc_none)
                else -> pluralStringResource(R.plurals.pairing_pc_found, pcs.size, pcs.size)
            },
        )
        Circles {
            shown.forEachIndexed { i, pc ->
                CircleItem(
                    Symbols.DESKTOP_WINDOWS, pc.name,
                    onClick = { if (pc.paired) actions.connect(pc) else actions.pair(pc, screen) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    meta = when {
                        pc.paired -> stringResource(if (pc.online) R.string.pairing_pc_paired_online else R.string.pairing_pc_paired_offline)
                        pc.online -> stringResource(R.string.pairing_pc_ready)
                        else -> stringResource(R.string.pairing_pc_offline)
                    },
                    metaDot = if (pc.online) OnlineGreen else null,
                    big = true,
                    selected = pc.paired,
                    pulse = pc.online && !pc.paired,
                    contextMenu = if (pc.paired) null else ContextMenu(
                        pc.name,
                        below = true,
                        items = listOf(
                            MenuItem(Symbols.DIALPAD, stringResource(R.string.pairing_pc_with_pin)) { actions.pair(pc, screen) },
                            MenuItem(Symbols.PASSWORD, stringResource(R.string.pairing_pc_with_code)) { actions.pairWithCode(pc, screen) },
                        ),
                    ),
                )
            }
            CircleItem(
                Symbols.ADD_ADDRESS, stringResource(R.string.pairing_pc_address), onClick = { actions.addByAddress(screen) },
                modifier = if (shown.isEmpty()) Modifier.focusRequester(first) else Modifier,
                meta = stringResource(R.string.pairing_pc_address_hint), big = true,
            )
            CircleItem(
                Symbols.REFRESH, stringResource(R.string.pairing_pc_search_again),
                onClick = {
                    searching = true
                    actions.rescan()
                },
                meta = stringResource(R.string.pairing_pc_network), big = true,
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
    val res = textResources()
    val colors = LocalDelosColors.current
    val feedback = LocalFeedback.current
    val actions = LocalDeviceActions.current
    val pin = remember(screen) { screen.otp?.pin ?: setup.newPin() }
    var accepted by remember { mutableStateOf(false) }
    LaunchedEffect(screen) {
        // A PC that was just added may take a moment to show up
        val pc = repo.awaitPc(screen.pcUuid, 5_000) { true }
        if (pc == null) {
            onFailed(res.getString(R.string.pairing_failed_not_found, screen.pcName))
            return@LaunchedEffect
        }
        when (val result = setup.pair(pc, pin, screen.otp?.passphrase)) {
            PcSetup.PairResult.Paired -> {
                accepted = true
                feedback.play(Cue.SELECT)
                delay(900)
                actions.paired(pc.uuid)
            }
            is PcSetup.PairResult.Failed -> onFailed(result.text(res, pc.name))
        }
    }
    val cancel = remember { FocusRequester() }
    InitialFocus(cancel)
    CenterColumn(gap = 44f) {
        if (screen.otp == null) {
            Heading(stringResource(R.string.pairing_pin_title, screen.pcName), stringResource(R.string.pairing_pin_note))
            PinDigits(pin)
        } else {
            Heading(stringResource(R.string.pairing_code_title, screen.pcName), stringResource(R.string.pairing_code_note))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.px)) {
            if (!accepted) Spinner()
            BasicText(
                stringResource(if (accepted) (if (screen.otp == null) R.string.pairing_pin_accepted else R.string.pairing_code_accepted) else R.string.pairing_pin_waiting),
                style = TextStyle(color = if (accepted) OnlineGreen else colors.text2, fontFamily = Figtree, fontSize = 26.tpx),
            )
        }
        PillButton(stringResource(R.string.pairing_cancel), Symbols.CLOSE, onCancel, Modifier.focusRequester(cancel), clickCue = Cue.BACK)
    }
}

/** `.pin`: the four digits, each springing in a little after the one before (`@keyframes pinIn`). */
@Composable
private fun PinDigits(pin: String) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    // A compact stage makes the digit boxes up to 20 % smaller (the digits keep their size)
    val ds = if (stage.compact) stage.compactScale.coerceAtLeast(.8f) else 1f
    Row(horizontalArrangement = Arrangement.spacedBy((26f * ds).px)) {
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
                    .size((148f * ds).px, (176f * ds).px)
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
