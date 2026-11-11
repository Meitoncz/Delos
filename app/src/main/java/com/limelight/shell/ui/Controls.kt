package com.limelight.shell.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import com.limelight.shell.ContextMenu
import com.limelight.shell.data.Avatar
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import com.limelight.shell.data.rememberAvatarGradient
import com.limelight.shell.data.rememberAvatarImage

/** `.center-col`: a centered column. */
@Composable
fun CenterColumn(gap: Float, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val stage = LocalStage.current
    if (stage.compact) {
        // A compact stage is short: the column keeps clear of the status and hint bars (which the interface scale zooms), its gaps shrink
        // with the stage, and what is still too tall (the avatar picker's rows) scrolls, with the focus scrolled into view
        Box(
            modifier.fillMaxSize().padding(
                top = (Compact.STATUS_BAR * stage.textScale + stage.safe.top).px, bottom = (Compact.HINT_BAR * stage.textScale + stage.safe.bottom).px,
            ),
            contentAlignment = Alignment.Center,
        ) {
            EntranceScope {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Compact.MARGIN.px, vertical = 24.px),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy((gap * stage.compactScale).px),
                    content = content,
                )
            }
        }
        return
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Its headline and circles enter one after the other (docs/GUI.md, "Motion")
        EntranceScope {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap.px), content = content)
        }
    }
}

/** `.h1` with an optional `.sub` under it. A compact stage sets the headline smaller (48 against 64), both at most the stage's width less 72 px on each side. */
@Composable
fun Heading(title: String, sub: String? = null) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    Column(Modifier.staggerEntrance(), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            title,
            style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = (if (stage.compact) 48 else 64).tpx, letterSpacing = (-.01).em, textAlign = TextAlign.Center),
            // A compact stage keeps a long headline 72 px from both edges: it wraps onto a second line instead
            modifier = if (stage.compact) Modifier.widthIn(max = (stage.stageWidth - 2f * Compact.SIDE).px) else Modifier,
        )
        if (sub != null) {
            BasicText(
                sub,
                style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 26.tpx, lineHeight = 1.4.em, textAlign = TextAlign.Center),
                modifier = Modifier.padding(top = (if (stage.compact) 10 else 14).px).widthIn(max = (if (stage.compact) minOf(1000f, stage.stageWidth - 2f * Compact.SIDE) else 1000f).px),
            )
        }
    }
}

/** A small spinning ring: something is being downloaded or prepared. */
@Composable
fun Spinner(size: Float, modifier: Modifier = Modifier, color: Color = LocalDelosColors.current.accent) {
    val turn = rememberInfiniteTransition(label = "spinner")
    val angle by turn.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spinnerAngle")
    androidx.compose.foundation.Canvas(modifier.size(size.px)) {
        val stroke = this.size.minDimension * .12f
        drawArc(color.copy(alpha = .25f), 0f, 360f, false, style = Stroke(stroke), topLeft = Offset(stroke / 2, stroke / 2), size = Size(this.size.width - stroke, this.size.height - stroke))
        drawArc(color, angle, 100f, false, style = Stroke(stroke, cap = StrokeCap.Round), topLeft = Offset(stroke / 2, stroke / 2), size = Size(this.size.width - stroke, this.size.height - stroke))
    }
}

/**
 * `.btn` (and `.btn.primary`): a pill with an icon and a label. [compact] is the toolbar size (64 px, smaller text); [trailing]
 * adds what follows the label (a button glyph, a count badge).
 */
@Composable
fun PillButton(
    label: String, icon: String?, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, clickCue: Cue? = Cue.SELECT,
    compact: Boolean = false, trailing: (@Composable RowScope.() -> Unit)? = null,
) {
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
            .then(if (primary) Modifier.background(colors.accent) else Modifier.glass(colors.surface).border(1.px, colors.line, CircleShape))
            .height((if (compact) 64 else 76).px)
            .padding(horizontal = (if (compact) 28 else 40).px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((if (compact) 12 else 14).px),
    ) {
        if (icon != null) Symbol(icon, (if (compact) 30 else 32).tpx, content, filled = focused)
        BasicText(label, style = TextStyle(color = content, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = (if (compact) 24 else 26).tpx))
        trailing?.invoke(this)
    }
}

/**
 * The avatar's colors (`radial-gradient(circle at 35% 30%, …)`) behind its illustration: the colors made
 * for a warm imported character (avatars.json), else the ones of its fixed hue.
 */
@Composable
fun AvatarBadge(avatar: Avatar, size: Float, iconSize: Float, modifier: Modifier = Modifier) {
    Box(modifier.size(size.px).clip(CircleShape).avatarBackground(avatar), contentAlignment = Alignment.Center) {
        AvatarArt(avatar, iconSize)
    }
}

@Composable
private fun Modifier.avatarBackground(avatar: Avatar): Modifier {
    val gradient = rememberAvatarGradient(avatar.asset)
    val hue = avatar.hue
    return drawBehind {
        val inner = gradient?.c0 ?: hsl(hue, 80f, 62f)
        val outer = gradient?.c1 ?: hsl(hue + 30f, 70f, 38f)
        drawRect(cssRadialCircle(Offset(size.width * .35f, size.height * .3f), size, 0f to inner, 1f to outer))
    }
}

/**
 * The avatar's bundled illustration filling its badge; the white font icon (at [iconSize]) stands in
 * until the image is decoded and fades out as it fades in, and stays when the asset is missing. An
 * image that is cached already is there in the first frame. The badge keeps its size throughout.
 */
@Composable
private fun AvatarArt(avatar: Avatar, iconSize: Float) {
    val image by rememberAvatarImage(avatar.asset)
    val bitmap = image
    val shown by animateFloatAsState(if (bitmap != null) 1f else 0f, tween(180), label = "avatarArt")
    if (shown < 1f) {
        Box(Modifier.graphicsLayer { alpha = 1f - shown }) { Symbol(avatar.icon, iconSize.tpx, Color.White, filled = true) }
    }
    if (bitmap != null) {
        // Medium keeps the 384 px artwork smooth (mipmaps) at the small badge sizes
        Image(
            bitmap, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shown },
        )
    }
}

/**
 * A `.circle-item`: a focusable circle (176 px, [big] 220 px, or [diameter]) with an icon, its label and an
 * optional meta line with a status dot. [avatar] fills it with the avatar's colors and illustration,
 * [selected] adds the check badge, [pulse] the online pulse ring (`.online-ring`). [width] is a fixed width
 * of the item (a grid's cell), which the label may use; null is the prototype's 220, and on a compact stage as wide as
 * the widest of the circle, the label and the status line ([CircleRowLayout]). The circle's box is always square.
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
    avatar: Avatar? = null,
    diameter: Float? = null,
    width: Float? = null,
    contextMenu: ContextMenu? = null,
    /** The factor of the circle, its icon, badge and spacing (the item's width and its labels keep their size); null: the compact stage's, 1 elsewhere. */
    scale: Float? = null,
) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val glide = LocalGlideRing.current
    val rowPlate = LocalRowGlide.current
    val reduced = LocalReducedMotion.current
    var focused by remember { mutableStateOf(false) }
    val lit by animateFloatAsState(if (focused || selected) 1f else 0f, tween(Motion.FOCUS, easing = EaseOut), label = "circleLabel")
    // The same scale as focusEffects, for the check badge that is drawn above the focus ring
    val badgeScale by animateFocusScale(focused, false, 1.16f, label = "circleBadge", focusMillis = if (rowPlate != null) Motion.ROW_FOCUS else Motion.FOCUS)
    // The badge pops in when the circle becomes the selected one
    val badgePop by animateFloatAsState(if (selected) 1f else 0f, if (reduced) tween(Motion.FADE, easing = Motion.Ease) else tween(Motion.PILL_POP, easing = EaseSpring), label = "circleBadgePop")
    val cs = scale ?: stage.compactScale
    val compact = stage.compact
    val side = (diameter ?: if (big) 220f else 176f) * cs
    // A grid's cell keeps its width (a compact stage's is at most 176, its label may run 15 px over each edge); never narrower than the circle
    val fixed = when {
        width != null -> maxOf(if (compact) minOf(width, 176f) else width, side)
        compact -> null
        else -> 220f
    }
    val iconSize = (if (big) 96f else 76f) * cs
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val wave by pulseTransition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = EaseOut)), label = "pulseWave")
    Column(
        Modifier.staggerEntrance().then(if (fixed != null) Modifier.width(fixed.px) else Modifier.widthIn(min = side.px)),
        horizontalAlignment = Alignment.CenterHorizontally,
        // A compact stage keeps the focused circle's ring off its own label (the growth, the ring and the label's own air above its letters)
        verticalArrangement = Arrangement.spacedBy((if (compact) CircleRowLayout.labelGap(side) else (if (side < 176f) 14f else 22f) * cs).px),
    ) {
        Box {
            Box(
                modifier
                    .glideTarget(focused)
                    .focusEffects(
                        onClick = onClick, corner = 999f, focusScale = 1.16f, ring = if (glide != null) null else FocusRing(5f, 5f), glow = FocusGlow(26f, 70f, -16f),
                        contextMenu = contextMenu, onLookChange = { focused = it },
                        glide = rowPlate != null,
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
                        .then(if (avatar != null) Modifier.avatarBackground(avatar) else Modifier.glass(colors.surface2).border(1.px, colors.line, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatar != null) AvatarArt(avatar, iconSize)
                    else Symbol(icon, iconSize.tpx, colors.text, filled = focused)
                }
            }
            if (badgePop > 0f) {
                // `.circle::before` is content, so it lies above the focus ring (a box shadow)
                Box(
                    Modifier
                        .matchParentSize()
                        .zIndex(2f)
                        .graphicsLayer {
                            val pop = Motion.scaleIn(Motion.POP_FROM_PILL, badgePop, reduced)
                            scaleX = badgeScale * pop
                            scaleY = badgeScale * pop
                            alpha = badgePop.coerceIn(0f, 1f)
                        },
                ) {
                    // Centered on the circle's edge at the top right (45°)
                    val edge = side / 2f * (1f - 0.70710677f)
                    val badge = 52f * cs
                    Box(
                        Modifier.offset(x = (side - edge - badge / 2f).px, y = (edge - badge / 2f).px).size(badge.px).clip(CircleShape).background(colors.accent2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Symbol(Symbols.CHECK, (26f * cs).tpx, colors.onAccent)
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.px)) {
            BasicText(
                label,
                style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 26.tpx, textAlign = TextAlign.Center),
                maxLines = 1,
                softWrap = !compact,
                // A long PC name keeps its start and its end
                overflow = if (!compact) TextOverflow.Clip else if (fixed == null) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
                modifier = Modifier
                    .then(
                        when {
                            !compact -> Modifier
                            fixed == null -> Modifier.widthIn(max = CircleRowLayout.LABEL_MAX.px)
                            else -> Modifier.wrapContentWidth(unbounded = true).widthIn(max = (fixed + 30f).px)
                        }
                    )
                    .graphicsLayer {
                        alpha = .55f + .45f * lit
                        translationY = -6f * (1f - lit) * stage.dpPerPx * density
                    },
            )
            if (meta != null) {
                Row(
                    if (compact && fixed == null) Modifier.widthIn(max = CircleRowLayout.META_MAX.px) else Modifier,
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.px),
                ) {
                    if (metaDot != null) Box(Modifier.size(12.px).clip(CircleShape).background(metaDot))
                    BasicText(
                        meta, style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 20.tpx, textAlign = TextAlign.Center),
                        maxLines = if (compact && fixed != null) 3 else 2, overflow = if (compact) TextOverflow.Ellipsis else TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

/**
 * `.circles`: a centered row of circle items. When they do not fit the stage's width they stay in one row that scrolls sideways instead of
 * wrapping into more rows or shrinking (the focus scrolls into view with the next item peeking in, the cut edges fade out); while they fit
 * it is the plain centered row (as many PCs as fit in 1920 do). A compact stage keeps the prototype's 56 px between the items while
 * they fit with it and uses 44 when they do not ([CircleRowLayout]).
 */
@Composable
fun Circles(content: @Composable RowScope.() -> Unit) {
    val stage = LocalStage.current
    val compact = stage.compact
    val c = CircleRowLayout
    val wide = c.GAP.px
    val tight = c.GAP_TIGHT.px
    // One ring glides between the circles (docs/GUI.md, "Motion") instead of every circle fading its own
    GlideRingHost(FocusRing(5f, 5f), corner = 999f, focusScale = c.FOCUS_SCALE) {
        // The scroll area gets the room of a focused circle's glow on every side (taken back from the layout), so nothing of the focus is cut;
        // a compact stage also keeps a little air above and below for the ring next to the headline
        ScrollRow(
            Modifier.fillMaxWidth(), gap = c.GAP, centered = true, edge = if (compact) c.EDGE else 40f,
            padVertical = if (compact) c.RING_AIR else 0f, focusMargin = c.FOCUS_MARGIN, bleed = c.GLOW_ROOM,
            arrangement = if (compact) remember(wide, tight) { FitGapArrangement(wide, tight) } else null,
            content = content,
        )
    }
}

/** A row's arrangement with two gaps: centred with [wide] between the items while they fit the row with it, else [tight] ([CircleRowLayout.arrange]). */
private class FitGapArrangement(private val wide: Dp, private val tight: Dp) : Arrangement.Horizontal {
    override val spacing: Dp get() = tight

    override fun Density.arrange(totalSize: Int, sizes: IntArray, layoutDirection: LayoutDirection, outPositions: IntArray) =
        CircleRowLayout.arrange(sizes, totalSize, wide.roundToPx(), tight.roundToPx(), outPositions)
}

/**
 * A row that never wraps: its items stay in one line that scrolls sideways when they do not fit the width, the D-pad focus scrolling the
 * focused one into view and the ends fading out (the user's rule for every shape: scroll with an edge fade instead of wrapping). While the
 * items fit it is a plain row, laid out exactly as the wrapping row it replaces ([centered] or at the start), with no fade and no padding.
 *
 * [edge] stage pixels on each side keep the focus scale and ring of the first and last item from being clipped, and are the width of the
 * fade; they apply only while the row scrolls, and the fade only at an end where there is more to scroll to (never over the first item of a
 * row at its start or the last one at its end). The scroll area's offscreen layer would cut the ring above and below, so [ringRoom] adds that
 * room only while the row scrolls and takes it back from the layout, the row's neighbours do not move. [padVertical] is room that is always there.
 * [focusMargin] is how far the focused item stays from the cut edges (at least [edge]; more lets the next item peek in). An [arrangement]
 * of its own replaces the plain [gap]: the row is then as wide as its place and the arrangement lays the items out in it.
 *
 * [bleed] is for items whose focus glow reaches far outside them: the scroll area is that much larger than the row on all four sides and
 * its content padded by as much, always, so the row and its neighbours are laid out as without it but a scroll container never cuts the
 * glow, the ring or the scale-up of a focused item (a scroll area clips at its own bounds). The fade starts where the row's own bounds do.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScrollRow(
    modifier: Modifier = Modifier,
    gap: Float,
    centered: Boolean = false,
    edge: Float = 24f,
    ringRoom: Float = 0f,
    padVertical: Float = 0f,
    focusMargin: Float = edge,
    bleed: Float = 0f,
    arrangement: Arrangement.Horizontal? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val stage = LocalStage.current
    val scroll = rememberScrollState()
    val k = LocalStage.current.dpPerPx * LocalDensity.current.density
    // The edge padding widens the content, so it must not decide by itself that the row still scrolls: the content overflows when it is
    // wider than the row without that padding. Before the first layout the state's maximum is Int.MAX_VALUE: nothing overflows yet.
    var padded by remember { mutableStateOf(false) }
    val measured = scroll.maxValue != Int.MAX_VALUE
    val overflow = measured && scroll.maxValue - (if (padded) 2f * edge * k else 0f) > 2f
    SideEffect { padded = overflow }
    val room = (if (overflow) ringRoom else 0f).px + bleed.px
    val bleedX = bleed.px
    // The focus scrolls this row, not the list around it (their margin specs scroll their own list): the focused item stays out of the faded ends
    val margin = (if (overflow) focusMargin else 0f) * k + bleed * k
    val bringIntoView = rememberMarginBringIntoView(scroll, before = margin, after = margin, stagePx = k)
    // An end fades only while the row goes on beyond it
    val more by remember(scroll) { derivedStateOf { (scroll.value > 0) to (scroll.value < scroll.maxValue) } }
    val startFade by animateFloatAsState(if (more.first) 1f else 0f, tween(Motion.FADE, easing = Motion.Ease), label = "rowStartFade")
    val endFade by animateFloatAsState(if (more.second) 1f else 0f, tween(Motion.FADE, easing = Motion.Ease), label = "rowEndFade")
    Box(modifier, contentAlignment = if (centered) Alignment.Center else Alignment.TopStart) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) { Row(
            Modifier
                .layout { measurable, constraints ->
                    // The room around the row is drawn over the neighbours, not laid out
                    val roomPx = room.roundToPx()
                    val sidePx = bleedX.roundToPx()
                    val placeable = measurable.measure(
                        if (sidePx > 0 && constraints.hasBoundedWidth) constraints.copy(maxWidth = constraints.maxWidth + 2 * sidePx) else constraints,
                    )
                    layout((placeable.width - 2 * sidePx).coerceAtLeast(0), (placeable.height - 2 * roomPx).coerceAtLeast(0)) { placeable.place(-sidePx, -roomPx) }
                }
                .then(
                    if (overflow) Modifier.fadeMask(
                        stage, vertical = false, startClear = bleed * startFade, startSolid = (bleed + edge) * startFade,
                        endSolid = (bleed + edge) * endFade, endClear = bleed * endFade,
                    ) else Modifier
                )
                .then(if (arrangement != null) Modifier.fillMaxWidth() else Modifier)
                .horizontalScroll(scroll)
                .padding(horizontal = (if (overflow) edge else 0f).px + bleedX, vertical = padVertical.px + room),
            horizontalArrangement = arrangement ?: Arrangement.spacedBy(gap.px),
            verticalAlignment = Alignment.Top,
            content = content,
        ) }
    }
}

/** The green of an online PC (`.status-dot`). */
val OnlineGreen = Color(0xFF3DDC84)

/**
 * `.field`: a pill-shaped text field, the one text input of the whole app (dialogs, the profile name, the
 * settings search). The on-screen keyboard appears only when the field is *activated*: a tap, or A / the
 * D-pad centre key. Moving the focus onto it, by D-pad or in code, never opens it, and it closes again when
 * the focus leaves. Up and down leave the field. See docs/GUI.md, "Text input".
 *
 * It uses the [TextFieldState] overload of `BasicTextField` on purpose. Only that one honours
 * [KeyboardOptions.showKeyboardOnFocus]; the `value: String` overload starts the input method on every focus,
 * and starting it shows the keyboard.
 */
@Composable
fun TextFieldPill(
    state: TextFieldState,
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
    // Set by A / the D-pad centre key while the field has focus: the text field only starts the input method
    // (and with it the keyboard) on focus when showKeyboardOnFocus is true, so switching it on while the field
    // is focused is how a key press opens the keyboard. A tap does not need it. Reset when the focus leaves.
    var activated by remember { mutableStateOf(false) }
    // The field is being edited: activated by A / the centre key or by a tap, until the focus leaves. ImeAvoidance pins an engaged field into the top
    // band of the window whatever the keyboard is. It never starts the keyboard itself.
    var engaged by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(Motion.FOCUS, easing = EaseSpring), label = "fieldScale")
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(Motion.FOCUS, easing = EaseOut), label = "fieldRing")
    BasicTextField(
        state = state,
        modifier = modifier
            // The shell moves up while the keyboard is open, so the field stays above it (ImeAvoidance.kt)
            .keepFieldAboveIme(engaged)
            .pointerInput(Unit) {
                // A tap starts the keyboard by itself; here it only marks the field as engaged (nothing is consumed)
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    engaged = true
                }
            }
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
                    if (focused) feedback.focusMoved() else {
                        activated = false
                        engaged = false
                        keyboard?.hide()
                    }
                }
            }
            .onPreviewKeyEvent {
                when (it.key) {
                    Key.ButtonA, Key.DirectionCenter -> {
                        if (it.type == KeyEventType.KeyUp) {
                            // Already activated (or tapped): the input method is running, so just show the keyboard again
                            engaged = true
                            if (activated) keyboard?.show() else activated = true
                        }
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (it.type == KeyEventType.KeyDown) focusManager.moveFocus(if (it.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                        true
                    }
                    else -> false
                }
            },
        inputTransformation = InputTransformation.maxLength(maxLength),
        textStyle = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 30.tpx),
        keyboardOptions = KeyboardOptions(
            capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Words else KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
            showKeyboardOnFocus = activated,
        ),
        onKeyboardAction = {
            keyboard?.hide()
            onDone()
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        cursorBrush = SolidColor(colors.accent),
        outputTransformation = if (password) PasswordMask else null,
        decorator = { inner ->
            Row(
                Modifier
                    .width(width.px)
                    .height(84.px)
                    .clip(CircleShape)
                    .glass(colors.surface2)
                    .border(1.px, colors.line, CircleShape)
                    .padding(horizontal = 28.px),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.px),
            ) {
                Symbol(icon, 34.tpx, colors.text3)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (state.text.isEmpty() && placeholder.isNotEmpty()) {
                        BasicText(placeholder, style = TextStyle(color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 30.tpx), maxLines = 1)
                    }
                    inner()
                }
            }
        },
    )
}

/**
 * A labelled value that looks like a [TextFieldPill] but is not an input: where the text is typed in the dialog (`DialogLayer`), the page
 * shows what is there ([value], or [placeholder] in the muted color when it is empty) and offers a button next to it.
 */
@Composable
fun ValuePill(label: String, value: String, icon: String, placeholder: String, modifier: Modifier = Modifier, width: Float = 520f) {
    val colors = LocalDelosColors.current
    Row(
        modifier
            .width(width.px)
            .height(84.px)
            .clip(CircleShape)
            .glass(colors.surface2)
            .border(1.px, colors.line, CircleShape)
            .padding(horizontal = 28.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.px),
    ) {
        Symbol(icon, 34.tpx, colors.text3)
        Column(verticalArrangement = Arrangement.Center) {
            BasicText(label.uppercase(), style = TextStyle(color = colors.text3, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 17.tpx, letterSpacing = .08.em), maxLines = 1)
            BasicText(
                value.ifEmpty { placeholder },
                style = TextStyle(color = if (value.isEmpty()) colors.text3 else colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 30.tpx),
                maxLines = 1,
            )
        }
    }
}

/** Shows every character of a password field as a dot. */
private val PasswordMask = OutputTransformation { replace(0, length, "\u2022".repeat(length)) }

/**
 * The online pulse (`.online-ring`) for a rounded element: an accent-2 outline starts just outside the
 * focus ring, grows outwards by [reach] stage pixels and fades, every 2.4 s. Put it after focusEffects
 * (so it scales with the element) and before any clip.
 */
fun Modifier.focusPulse(corner: Float, reach: Float = 26f): Modifier = composed {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val wave by rememberInfiniteTransition(label = "focusPulse").animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = EaseOut)), label = "focusPulseWave")
    drawBehind {
        val k = stage.dpPerPx * density
        val stroke = 2f * k
        // From just outside the focus ring (5 px gap + 5 px ring) outwards
        val offset = (10f + reach * wave) * k + stroke / 2f
        drawRoundRect(
            colors.accent2.copy(alpha = .7f * (1f - wave)),
            topLeft = Offset(-offset, -offset),
            size = androidx.compose.ui.geometry.Size(size.width + offset * 2f, size.height + offset * 2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner * k + offset),
            style = Stroke(stroke),
        )
    }
}
