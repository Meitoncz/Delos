package com.limelight.shell.ui

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import com.limelight.shell.GlobalKeys
import com.limelight.shell.settings.StreamMenuSettings
import kotlinx.coroutines.delay

/** How long the capture waits for a first button before it gives up. */
private const val IDLE_MS = 10_000L

/**
 * Records the stream menu buttons: every key goes here while it is shown. The buttons held together
 * (one or more) are saved once all are let go; without any press for 10 s it closes unchanged. A tap
 * anywhere cancels too, since every controller button may be wanted for the shortcut.
 */
@Composable
fun ButtonCaptureLayer(visible: Boolean, keys: GlobalKeys, onDone: (Set<Int>?) -> Unit) {
    val colors = LocalDelosColors.current
    AnimatedVisibility(visible, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
        val combo = remember { mutableStateListOf<Int>() }
        DisposableEffect(keys) {
            val held = HashSet<Int>()
            var finished = false
            keys.capture = { event ->
                if (!StreamMenuSettings.usable(event.keyCode)) false
                else {
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                            held += event.keyCode
                            if (event.keyCode !in combo) combo += event.keyCode
                        }
                        KeyEvent.ACTION_UP -> {
                            held -= event.keyCode
                            if (held.isEmpty() && combo.isNotEmpty() && !finished) {
                                finished = true
                                onDone(combo.toSet())
                            }
                        }
                    }
                    true
                }
            }
            onDispose { keys.capture = null }
        }
        LaunchedEffect(combo.isEmpty()) {
            if (combo.isEmpty()) {
                delay(IDLE_MS)
                onDone(null)
            }
        }
        Box(
            Modifier.fillMaxSize().background(colors.scrim).pointerTap { onDone(null) },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.width(820.px).clip(RoundedCornerShape(40.px)).background(colors.surface3).border(1.px, colors.line, RoundedCornerShape(40.px)).padding(52.px),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(26.px),
            ) {
                Symbol(Symbols.SPORTS_ESPORTS, 72.tpx, colors.accent, filled = true)
                BasicText("Press the stream menu buttons", style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 36.tpx, textAlign = TextAlign.Center))
                BasicText(
                    "Hold them together, then let go. One button works too, e.g. a rear button. Wait or tap to cancel.",
                    style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 24.tpx, lineHeight = 1.4.em, textAlign = TextAlign.Center),
                )
                Row(Modifier.height(64.px), horizontalArrangement = Arrangement.spacedBy(14.px), verticalAlignment = Alignment.CenterVertically) {
                    combo.forEach { code ->
                        BasicText(
                            StreamMenuSettings.buttonName(code),
                            style = TextStyle(color = colors.onAccent, fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 26.tpx),
                            modifier = Modifier.clip(CircleShape).background(colors.accent).padding(horizontal = 26.px, vertical = 12.px),
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.pointerTap(onTap: () -> Unit) = this.then(
    Modifier.pointerInput(Unit) { detectTapGestures { onTap() } }
)
