package com.limelight.shell.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import com.limelight.shell.DialogSpec
import dev.chrisbanes.haze.HazeState

/**
 * A dialog (`.dialog`) in the middle of the screen: title, note, its fields and Save / Cancel. The
 * focus stays inside while it is open and starts on the first field.
 */
@Composable
fun DialogLayer(spec: DialogSpec?, haze: HazeState, onDismiss: () -> Unit) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val density = LocalDensity.current.density
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(spec) }
    if (spec != null) shown = spec
    val open by rememberUpdatedState(spec != null)
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            spec != null,
            enter = dialogEnter(reduced),
            exit = dialogExit(reduced),
        ) {
            val d = shown ?: return@AnimatedVisibility
            val values = remember(d) { d.fields.map { TextFieldState(it.initial) } }
            val first = remember { FocusRequester() }
            InitialFocus(first, d)
            val k = stage.dpPerPx * density
            val shape = RoundedCornerShape(40.px)
            val confirm = {
                if (d.onConfirm(values.map { it.text.toString() })) onDismiss()
            }
            Column(
                Modifier
                    .width(760.px)
                    .focusProperties { onExit = { if (open) cancelFocusChange() } }
                    .focusGroup()
                    // box-shadow: 0 40px 100px -30px rgba(0, 0, 0, .7)
                    .dropShadow(shape) {
                        radius = 100f * k
                        spread = -30f * k
                        offset = Offset(0f, 40f * k)
                        color = Color.Black
                        alpha = .7f
                    }
                    .clip(shape)
                    .backdropBlur(haze, 24f, colors.surface3)
                    .border(1.px, colors.line, shape)
                    .padding(52.px),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(22.px),
            ) {
                BasicText(d.title, modifier = Modifier.staggerEntrance(0, Motion.MENU_DELAY), style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 38.tpx, textAlign = TextAlign.Center))
                BasicText(d.note, modifier = Modifier.staggerEntrance(1, Motion.MENU_DELAY), style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 24.tpx, lineHeight = 1.4.em, textAlign = TextAlign.Center))
                d.fields.forEachIndexed { i, field ->
                    TextFieldPill(
                        values[i], field.icon,
                        modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).staggerEntrance(i + 2, Motion.MENU_DELAY),
                        placeholder = field.placeholder, keyboardType = field.keyboard, password = field.password, maxLength = field.maxLength,
                        // Done on the last field confirms, like the button
                        onDone = { if (i == d.fields.lastIndex) confirm() },
                    )
                }
                Row(Modifier.staggerEntrance(d.fields.size + 2, Motion.MENU_DELAY).padding(top = 8.px), horizontalArrangement = Arrangement.spacedBy(24.px)) {
                    // A dialog without fields (a question) starts on its button
                    PillButton(d.confirm, Symbols.CHECK, confirm, if (d.fields.isEmpty()) Modifier.focusRequester(first) else Modifier, primary = true)
                    PillButton("Cancel", null, onDismiss, clickCue = Cue.BACK)
                }
            }
        }
    }
}
