package com.limelight.shell.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.em
import com.limelight.shell.ContextMenu
import com.limelight.shell.data.Avatar

/** `.center-col`: a centered column; it moves up while the on-screen keyboard is shown. */
@Composable
fun CenterColumn(gap: Float, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap.px), content = content)
    }
}

/** `.h1` with an optional `.sub` under it. */
@Composable
fun Heading(title: String, sub: String? = null) {
    val colors = LocalDelosColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            title,
            style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 64.tpx, letterSpacing = (-.01).em, textAlign = TextAlign.Center),
        )
        if (sub != null) {
            BasicText(
                sub,
                style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 26.tpx, lineHeight = 1.4.em, textAlign = TextAlign.Center),
                modifier = Modifier.padding(top = 14.px).widthIn(max = 1000.px),
            )
        }
    }
}

/** `.btn` (and `.btn.primary`): a pill with an icon and a label. */
@Composable
fun PillButton(label: String, icon: String?, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, clickCue: Cue? = Cue.SELECT) {
    val colors = LocalDelosColors.current
    var focused by remember { mutableStateOf(false) }
    val content = if (primary) colors.onAccent else colors.text
    Row(
        modifier
            .focusEffects(
                onClick = onClick, corner = 999f, focusScale = 1.08f, ring = FocusRing(4f, 4f), glow = FocusGlow(18f, 50f, -12f),
                sweep = primary, clickCue = clickCue, onFocusChange = { focused = it },
            )
            .clip(CircleShape)
            .background(if (primary) colors.accent else colors.surface)
            .then(if (primary) Modifier else Modifier.border(1.px, colors.line, CircleShape))
            .height(76.px)
            .padding(horizontal = 40.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.px),
    ) {
        if (icon != null) Symbol(icon, 32.tpx, content, filled = focused)
        BasicText(label, style = TextStyle(color = content, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 26.tpx))
    }
}

/** The avatar's colors (`radial-gradient(circle at 35% 30%, …)`) behind its white icon. */
@Composable
fun AvatarBadge(avatar: Avatar, size: Float, iconSize: Float, modifier: Modifier = Modifier) {
    Box(modifier.size(size.px).clip(CircleShape).avatarBackground(avatar.hue), contentAlignment = Alignment.Center) {
        Symbol(avatar.icon, iconSize.tpx, Color.White, filled = true, opticalSize = if (iconSize >= 48f) 48f else 24f)
    }
}

private fun Modifier.avatarBackground(hue: Float) = drawBehind {
    drawRect(cssRadialCircle(Offset(size.width * .35f, size.height * .3f), size, 0f to hsl(hue, 80f, 62f), 1f to hsl(hue + 30f, 70f, 38f)))
}

/**
 * A `.circle-item`: a focusable circle (176 px, [big] 220 px) with an icon, its label and an optional
 * meta line with a status dot. [avatarHue] fills it with an avatar's colors, [selected] adds the check
 * badge, [pulse] the online pulse ring (`.online-ring`).
 */
@Composable
fun CircleItem(
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    metaDot: Color? = null,
    big: Boolean = false,
    selected: Boolean = false,
    pulse: Boolean = false,
    avatarHue: Float? = null,
    contextMenu: ContextMenu? = null,
) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    var focused by remember { mutableStateOf(false) }
    val lit by animateFloatAsState(if (focused || selected) 1f else 0f, tween(400, easing = EaseOut), label = "circleLabel")
    // The same scale as focusEffects, for the check badge that is drawn above the focus ring
    val badgeScale by animateFloatAsState(if (focused) 1.16f else 1f, tween(380, easing = EaseSpring), label = "circleBadge")
    val side = if (big) 220f else 176f
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val wave by pulseTransition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = EaseOut)), label = "pulseWave")
    Column(Modifier.width(220.px), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.px)) {
        Box {
            Box(
                modifier
                    .focusEffects(
                        onClick = onClick, corner = 999f, focusScale = 1.16f, ring = FocusRing(5f, 5f), glow = FocusGlow(26f, 70f, -16f),
                        contextMenu = contextMenu, onFocusChange = { focused = it },
                    )
                    .size(side.px)
                    .then(
                        if (!pulse) Modifier
                        else Modifier.drawBehind {
                            // inset -16 px, 2 px accent-2 border, scale .92 → 1.25 while fading out
                            val k = stage.dpPerPx * density
                            val radius = (size.minDimension / 2f + 16f * k) * (.92f + .33f * wave)
                            drawCircle(colors.accent2.copy(alpha = .7f * (1f - wave)), radius = radius, style = Stroke(2f * k))
                        }
                    ),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .then(if (avatarHue != null) Modifier.avatarBackground(avatarHue) else Modifier.background(colors.surface2).border(1.px, colors.line, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    Symbol(
                        icon, (if (big) 96f else 76f).tpx, if (avatarHue != null) Color.White else colors.text,
                        filled = avatarHue != null || focused, opticalSize = 48f,
                    )
                }
            }
            if (selected) {
                // `.circle::before` is content, so it lies above the focus ring (a box shadow)
                Box(
                    Modifier
                        .matchParentSize()
                        .zIndex(2f)
                        .graphicsLayer {
                            scaleX = badgeScale
                            scaleY = badgeScale
                        },
                ) {
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(x = (-4).px, y = 4.px).size(52.px).clip(CircleShape).background(colors.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Symbol(Symbols.CHECK, 34.tpx, colors.onAccent, filled = true, filledWeight = 600)
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.px)) {
            BasicText(
                label,
                style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 26.tpx, textAlign = TextAlign.Center),
                maxLines = 1,
                modifier = Modifier.graphicsLayer {
                    alpha = .55f + .45f * lit
                    translationY = -6f * (1f - lit) * stage.dpPerPx * density
                },
            )
            if (meta != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.px)) {
                    if (metaDot != null) Box(Modifier.size(12.px).clip(CircleShape).background(metaDot))
                    BasicText(meta, style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 20.tpx), maxLines = 1)
                }
            }
        }
    }
}

/** `.circles`: a centered row of circle items. */
@Composable
fun Circles(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(56.px), verticalAlignment = Alignment.Top) { content() }
}

/** The green of an online PC (`.status-dot`). */
val OnlineGreen = Color(0xFF3DDC84)

/**
 * `.field`: a pill-shaped text field. Moving the focus onto it does not open the on-screen keyboard
 * (that would pop up on every D-pad move); A, Enter or a tap does. Up and down leave the field.
 */
@Composable
fun TextFieldPill(
    value: String,
    onValueChange: (String) -> Unit,
    icon: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    maxLength: Int = 24,
    width: Float = 560f,
    onDone: () -> Unit = {},
) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val feedback = LocalFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(380, easing = EaseSpring), label = "fieldScale")
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(380, easing = EaseOut), label = "fieldRing")
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.take(maxLength)) },
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawWithContent {
                drawContent()
                if (ring > 0f) drawFocusRing(FocusRing(4f, 4f), size.height / 2f, ring, colors, stage)
            }
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    if (focused) feedback.focusMoved() else keyboard?.hide()
                }
            }
            .onPreviewKeyEvent {
                when (it.key) {
                    Key.ButtonA, Key.DirectionCenter -> {
                        if (it.type == KeyEventType.KeyUp) keyboard?.show()
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (it.type == KeyEventType.KeyDown) focusManager.moveFocus(if (it.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                        true
                    }
                    else -> false
                }
            },
        singleLine = true,
        textStyle = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 30.tpx),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Words else KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
            showKeyboardOnFocus = false,
        ),
        keyboardActions = KeyboardActions(onDone = {
            keyboard?.hide()
            onDone()
        }),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { inner ->
            Row(
                Modifier
                    .width(width.px)
                    .height(84.px)
                    .clip(CircleShape)
                    .background(colors.surface2)
                    .border(1.px, colors.line, CircleShape)
                    .padding(horizontal = 28.px),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.px),
            ) {
                Symbol(icon, 34.tpx, colors.text3)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        BasicText(placeholder, style = TextStyle(color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 30.tpx), maxLines = 1)
                    }
                    inner()
                }
            }
        },
    )
}
