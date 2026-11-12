package com.limelight.shell.companion

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
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
import com.limelight.shell.ui.LowRed
import com.limelight.shell.ui.PlaceholderArt
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx

/**
 * The settings help on the second panel (docs/DUAL_SCREEN.md, "Settings help"): the description of the focused row. It follows the focus live with a
 * short crossfade between rows (the update of one row, like a value that changed, keeps its key and does not fade) and tells [CompanionSession] that a
 * real window shows it, which is what makes the main screen give its description's room to the list.
 *
 * Nothing scrolls: the page is one [PageCard] whose description is cut by [CompanionFit.summary] to the panel's line budget; the source block and
 * the three impact chips always stay.
 */
internal data class SettingsHelpPage(val help: CompanionHelp) : CompanionPage {
    override val key: String get() = "help/${help.identity}"
    override val priority: Int get() = PagePriority.SCREEN

    @Composable
    override fun Content() = HelpPage(help)
}

/**
 * One row's page, top to bottom: the heading (the row's icon, its title, where it lives), the source of a per-game value, the description, the row's
 * text value, the palette's swatches, the background preview, the debug details, the unavailable notice and the saved note, and at the bottom the
 * three impact chips (dashes for a row that costs nothing). What is left over goes to the flexible block (the background preview, else a filler).
 */
@Composable
private fun HelpPage(help: CompanionHelp) {
    val colors = LocalDelosColors.current
    PageCard { fit ->
        val b = fit.budget
        val head = HelpFit.heading(help.title, help.section, b.innerWidth, fit.metrics)
        PageHeading(help.icon, head.title, head.subtitle)

        help.source?.let { SourceBlock(HelpFit.source(it, b.sourceLines, b.innerWidth, fit.metrics), it.custom, it.icon) }

        val body = CompanionFit.summary(
            HelpFit.prepare(help.body), help.short.orEmpty(), HelpFit.bodyLines(help, b), b.innerWidth, fit.metrics, stringResource(R.string.companion_see_main),
        )
        BodyText(body, color = if (help.notice) LowRed else colors.text)

        help.value?.let { BodyText(HelpFit.value(it, VALUE_LINES, b.innerWidth, fit.metrics), Modifier.optional(90), color = colors.accent, maxLines = VALUE_LINES) }
        if (help.swatches.isNotEmpty()) Swatches(help.swatches, HelpFit.swatchesInRow(help.swatches.map { it.label }, b.innerWidth, fit.metrics))
        help.backdrop?.let { BackdropPreview(it, Modifier.fills(minHeight = PREVIEW_MIN.px)) }
        help.details?.let {
            val view = HelpFit.details(it, if (b.roomy) DETAIL_ROWS_ROOMY else DETAIL_ROWS, HelpFit.charsPerRow(b.innerWidth))
            Details(view, Modifier.optional(40))
        }
        if (help.unavailable) BodyText(stringResource(R.string.settings_screen_unavailable), Modifier.optional(70), color = colors.text2, maxLines = 2)
        if (help.savedNote) SavedNote(Modifier.optional(30))

        if (help.backdrop == null) Filler()
        ImpactChips(help.impact)
    }
}

/** The text value is at most two lines. */
private const val VALUE_LINES = 2

/** The rows of the debug details (the count line included) on a short and on a tall panel. */
private const val DETAIL_ROWS = 3
private const val DETAIL_ROWS_ROOMY = 7

/** The background preview's least height in stage px. */
private const val PREVIEW_MIN = 80

/**
 * Where the value of a per-game row comes from: a quiet frame, violet while the value is custom for this game. The text is [view]: the bold title and
 * the sentence behind it, already cut to two lines at a sentence ([HelpFit.source]).
 */
@Composable
private fun SourceBlock(view: SourceView, custom: Boolean, icon: String) {
    val colors = LocalDelosColors.current
    val tint = colors.accent3
    val shape = RoundedCornerShape(22.px)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (custom) tint.copy(alpha = .22f) else colors.text.copy(alpha = .06f))
            .border(1.px, if (custom) tint.copy(alpha = .5f) else colors.line, shape).padding(horizontal = 18.px, vertical = 10.px),
        horizontalArrangement = Arrangement.spacedBy(14.px),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(icon, 32.tpx, if (custom) tint else colors.text2, filled = true)
        BasicText(
            buildAnnotatedString {
                withStyle(SpanStyle(color = if (custom) tint else colors.text2, fontWeight = FontWeight.Bold)) { append(view.title) }
                append("  ")
                append(view.before)
                if (view.strong.isNotEmpty()) withStyle(SpanStyle(color = colors.text, fontWeight = FontWeight.Bold)) { append(view.strong) }
                append(view.after)
            },
            Modifier.weight(1f), style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = MIN_TEXT.tpx, lineHeight = 1.2.em),
            maxLines = 2,
        )
    }
}

/** The palette's colors, one chip each (a dot in the color and its name): in one row when they fit it, else one under the other. */
@Composable
private fun Swatches(swatches: List<HelpSwatch>, inRow: Boolean) {
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(14.px)
    @Composable
    fun chip(swatch: HelpSwatch, modifier: Modifier) {
        Row(
            modifier.clip(shape).background(colors.surface3).border(1.px, colors.line, shape).padding(horizontal = 14.px, vertical = 6.px),
            horizontalArrangement = Arrangement.spacedBy(12.px),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.px).clip(CircleShape).background(swatch.color).border(1.px, colors.line, CircleShape))
            BasicText(swatch.label, style = TextStyle(color = colors.text, fontFamily = Figtree, fontSize = MIN_TEXT.tpx, lineHeight = 1.2.em), maxLines = 1)
        }
    }
    if (inRow) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.px)) { swatches.forEach { chip(it, Modifier.weight(1f)) } }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.px)) { swatches.forEach { chip(it, Modifier.fillMaxWidth()) } }
    }
}

/**
 * The preview of the game background: the lens image (or the placeholder hues) at the opacity the setting has, with the setting's name over it. It takes
 * the height the other blocks leave, in the 16:9 of a screen at most: a taller panel gets a wider box, never a stretched one.
 */
@Composable
private fun BackdropPreview(backdrop: HelpBackdrop, modifier: Modifier) {
    val colors = LocalDelosColors.current
    val shape = RoundedCornerShape(22.px)
    BoxWithConstraints(modifier) {
        val box = Modifier.fillMaxWidth().height(minOf(maxHeight, maxWidth * 9f / 16f))
        Box(box.clip(shape).background(colors.bg2).border(1.px, colors.line, shape), contentAlignment = Alignment.Center) {
            if (backdrop.active) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = backdrop.opacity }) {
                    val image = backdrop.image
                    if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else PlaceholderArt(backdrop.hue, backdrop.hue2, backdrop.icon, Modifier.fillMaxSize(), glyphSize = 160f)
                }
            }
            BasicText(backdrop.title, style = TextStyle(color = colors.text, fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = MIN_TEXT.tpx), maxLines = 1)
        }
    }
}

/** The debug details in the monospace face, whole lines, and the count of those that did not fit. */
@Composable
private fun Details(view: DetailsView, modifier: Modifier) {
    val colors = LocalDelosColors.current
    Column(modifier.fillMaxWidth()) {
        if (view.text.isNotEmpty()) {
            BasicText(view.text, style = TextStyle(color = colors.text2, fontFamily = FontFamily.Monospace, fontSize = MIN_TEXT.tpx, lineHeight = 1.2.em))
        }
        if (view.hidden > 0) BodyText(pluralStringResource(R.plurals.companion_details_more, view.hidden, view.hidden), color = colors.text3, maxLines = 1)
    }
}

/** "Saved automatically": a per-game row needs no Save button. */
@Composable
private fun SavedNote(modifier: Modifier) {
    val colors = LocalDelosColors.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.px), verticalAlignment = Alignment.Top) {
        Symbol(Symbols.CHECK_CIRCLE, 32.tpx, colors.text3)
        BodyText(stringResource(R.string.settings_game_saved_note), Modifier.weight(1f), color = colors.text3, maxLines = 2)
    }
}
