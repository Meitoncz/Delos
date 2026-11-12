package com.limelight.shell.companion

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import com.limelight.R
import com.limelight.shell.ui.Figtree
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LocalReducedMotion
import com.limelight.shell.ui.LocalStage
import com.limelight.shell.ui.Motion
import com.limelight.shell.ui.PlaceholderArt
import com.limelight.shell.ui.StageMetrics
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.Unbounded
import com.limelight.shell.ui.fadeEdges
import com.limelight.shell.ui.px
import com.limelight.shell.ui.stagePx
import com.limelight.shell.ui.tpx

/**
 * The settings help on the second panel (docs/DUAL_SCREEN.md, "Settings help"): the description of the focused row, large. It follows the
 * focus live with a short crossfade between rows (the update of one row, like a value that changed, does not fade) and, when [inWindow], tells
 * [CompanionSession] that a real window shows it, which is what makes the main screen give its description's room to the list.
 */
internal data class SettingsHelpPage(val help: CompanionHelp) : CompanionPage {
    override val key: String get() = "help/${help.identity}"
    override val priority: Int get() = PagePriority.SCREEN

    @Composable
    override fun Content() = HelpPage(help)
}

/** One row's page: everything in ONE column under the header, scrolling by touch when it does not fit, with a fade at an edge only while content continues behind it. */
@Composable
private fun HelpPage(help: CompanionHelp) {
    val colors = LocalDelosColors.current
    val stage = LocalStage.current
    val scroll = rememberScrollState()
    // The box of the main screen's description, as big as the panel allows: a glass card with the outer margin around it
    Box(Modifier.fillMaxSize().padding(start = HelpLayout.MARGIN.px, end = HelpLayout.MARGIN.px, top = HelpLayout.TOP.px, bottom = HelpLayout.BOTTOM.px).card(HelpLayout.CORNER).scrollIndicator(stage, scroll, colors.text3)) {
        Column(
            Modifier.fillMaxSize().fadeEdges(stage, scroll, top = HelpLayout.FADE, bottom = HelpLayout.FADE).verticalScroll(scroll)
                .padding(horizontal = HelpLayout.PAD_X.px, vertical = HelpLayout.PAD_Y.px),
            verticalArrangement = Arrangement.spacedBy(HelpLayout.GAP.px),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HelpLayout.DISC_GAP.px), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(HelpLayout.DISC.px).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                    Symbol(help.icon, HelpLayout.GLYPH.tpx, colors.onAccent, filled = true)
                }
                BasicText(help.title, Modifier.weight(1f), style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = HelpLayout.TITLE.tpx, lineHeight = 1.25.em))
            }
            help.source?.let { SourceBlock(it) }
            BasicText(help.body, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = HelpLayout.BODY.tpx, lineHeight = 1.45.em))
            if (help.unavailable) {
                BasicText(stringResource(R.string.settings_screen_unavailable), style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = MIN_TEXT.tpx, lineHeight = 1.4.em))
            }
            help.value?.let {
                BasicText(it, style = TextStyle(color = colors.accent, fontFamily = Figtree, fontSize = HelpLayout.BODY.tpx, lineHeight = 1.3.em))
            }
            if (help.swatches.isNotEmpty()) Swatches(help.swatches)
            help.backdrop?.let { BackdropPreview(it) }
            help.impact?.let {
                ImpactRow(it)
                BasicText(stringResource(R.string.impact_legend), style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = MIN_TEXT.tpx, lineHeight = 1.4.em))
            }
            help.details?.let {
                BasicText(it, style = TextStyle(color = colors.text3, fontFamily = FontFamily.Monospace, fontSize = MIN_TEXT.tpx, lineHeight = 1.4.em))
            }
            if (help.savedNote) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.px), verticalAlignment = Alignment.Top) {
                    Symbol(Symbols.CHECK_CIRCLE, HelpLayout.NOTE_GLYPH.tpx, colors.text3)
                    BasicText(stringResource(R.string.settings_game_saved_note), Modifier.weight(1f), style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = MIN_TEXT.tpx, lineHeight = 1.4.em))
                }
            }
        }
    }
}

/**
 * A slim bar at the right edge of the card while its content scrolls: how much there is and where the view stands, so a page whose next line
 * is still out of sight (the fade has nothing to fade there) is known to continue. Nothing for a page that fits.
 */
private fun Modifier.scrollIndicator(stage: StageMetrics, scroll: ScrollState, color: Color): Modifier = drawWithContent {
    drawContent()
    val max = scroll.maxValue
    if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
    val inset = stagePx(HelpLayout.INDICATOR_INSET, stage)
    val width = stagePx(HelpLayout.INDICATOR_WIDTH, stage)
    val track = size.height - 2f * stagePx(HelpLayout.INDICATOR_END, stage)
    val bar = (track * size.height / (size.height + max)).coerceAtLeast(stagePx(HelpLayout.INDICATOR_MIN, stage))
    val top = stagePx(HelpLayout.INDICATOR_END, stage) + (track - bar) * scroll.value / max
    drawRoundRect(
        color.copy(alpha = .55f), Offset(size.width - inset - width, top), Size(width, bar),
        androidx.compose.ui.geometry.CornerRadius(width / 2f),
    )
}

/** Where the value of a per-game row comes from: a quiet frame, violet while the value is custom for this game; the sentence wraps inside it. */
@Composable
private fun SourceBlock(source: HelpSource) {
    val colors = LocalDelosColors.current
    val tint = colors.accent3
    val shape = RoundedCornerShape(24.px)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (source.custom) tint.copy(alpha = .22f) else colors.text.copy(alpha = .06f))
            .border(1.px, if (source.custom) tint.copy(alpha = .5f) else colors.line, shape).padding(horizontal = 24.px, vertical = 18.px),
        horizontalArrangement = Arrangement.spacedBy(16.px),
    ) {
        Symbol(source.icon, 36.tpx, if (source.custom) tint else colors.text2, filled = true)
        BasicText(
            buildAnnotatedString {
                withStyle(SpanStyle(color = if (source.custom) tint else colors.text2, fontWeight = FontWeight.Bold)) { append(source.title) }
                append("   ")
                append(source.before)
                if (source.strong.isNotEmpty()) withStyle(SpanStyle(color = colors.text, fontWeight = FontWeight.Bold)) { append(source.strong) }
                append(source.after)
            },
            Modifier.weight(1f), style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = MIN_TEXT.tpx, lineHeight = 1.35.em),
        )
    }
}

/** The palette's colors, one big chip each with its name under it. */
@Composable
private fun Swatches(swatches: List<HelpSwatch>) {
    val colors = LocalDelosColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.px)) {
        swatches.forEach { swatch ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.px)) {
                Box(Modifier.fillMaxWidth().height(96.px).clip(RoundedCornerShape(24.px)).background(swatch.color).border(1.px, colors.line, RoundedCornerShape(24.px)))
                BasicText(swatch.label, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = MIN_TEXT.tpx), maxLines = 1)
            }
        }
    }
}

/** The preview of the game background: the lens image (or the placeholder hues) at the opacity the setting has, with the setting's name over it. */
@Composable
private fun BackdropPreview(backdrop: HelpBackdrop) {
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(24.px)
    Box(Modifier.fillMaxWidth().height(HelpLayout.PREVIEW.px).clip(shape).background(colors.bg2), contentAlignment = Alignment.Center) {
        if (backdrop.active) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = backdrop.opacity }) {
                val image = backdrop.image
                if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else PlaceholderArt(backdrop.hue, backdrop.hue2, backdrop.icon, Modifier.fillMaxSize(), glyphSize = 160f)
            }
        }
        BasicText(backdrop.title, style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = MIN_TEXT.tpx))
    }
}

/** The geometry of the help page, in stage px (the companion's own stage). */
internal object HelpLayout {
    /** The outer margin at the sides and at the bottom of the screen; the card starts [TOP] under the header's rule. */
    const val MARGIN = 48f
    const val TOP = 28f
    const val BOTTOM = 48f

    /** The card's corners and the room between its edge and the content. */
    const val CORNER = 34
    const val PAD_X = 36f
    const val PAD_Y = 32f

    /** The room between neighbouring blocks. */
    const val GAP = 28f

    /** The icon disc, the glyph in it and the room between it and the name. */
    const val DISC = 112f
    const val GLYPH = 60f
    const val DISC_GAP = 28f

    const val TITLE = 40f
    const val BODY = 30f
    const val NOTE_GLYPH = 32f
    const val PREVIEW = 220f

    /** How far the page fades at an edge it continues behind. */
    const val FADE = 40f

    /** The scroll bar: its width, its distance from the card's right edge and from its top and bottom, and its least length. */
    const val INDICATOR_WIDTH = 6f
    const val INDICATOR_INSET = 12f
    const val INDICATOR_END = 40f
    const val INDICATOR_MIN = 48f
}
