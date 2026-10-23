package com.limelight.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import com.limelight.shell.ui.Cue
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.InitialFocus
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.focusEffects
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx

/** A screen that is not built yet (profile, devices): title, note and a way back. */
@Composable
fun PlaceholderScreen(icon: String, title: String, note: String, onBack: () -> Unit) {
    val colors = LocalDelosColors.current
    val back = remember { FocusRequester() }
    InitialFocus(back)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.px)) {
            Symbol(icon, 120.tpx, colors.accent, filled = true, opticalSize = 48f)
            BasicText(title, style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 64.tpx))
            BasicText(
                note,
                style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 26.tpx, lineHeight = 1.4.em, textAlign = TextAlign.Center),
            )
            var focused by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .padding(top = 20.px)
                    .focusRequester(back)
                    .focusEffects(onClick = onBack, corner = 999f, focusScale = 1.06f, clickCue = Cue.BACK, onFocusChange = { focused = it })
                    .clip(CircleShape)
                    .background(if (focused) colors.accent else colors.surface2)
                    .height(76.px)
                    .padding(horizontal = 34.px),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.px),
            ) {
                Symbol(Symbols.ARROW_BACK, 32.tpx, if (focused) colors.onAccent else colors.text2, filled = focused)
                BasicText(
                    "Back to home",
                    style = TextStyle(color = if (focused) colors.onAccent else colors.text2, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 26.tpx),
                )
            }
        }
    }
}
