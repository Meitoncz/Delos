package com.limelight.shell.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.limelight.diag.Problem
import com.limelight.diag.ProblemKind

/** What the card says about a [ProblemKind]: the title and the sentence under it. */
internal fun offerTitle(kind: ProblemKind): String = when (kind) {
    ProblemKind.STREAM_FAILED -> "A stream could not run"
    else -> "Delos had a problem"
}

internal const val OFFER_NOTE = "Nothing is sent automatically. Report opens GitHub in your browser with a draft for you to check, " +
    "and saves a cleaned log file to Downloads for you to attach."

/**
 * The offer after a problem (docs/DIAGNOSTICS.md, docs/GUI.md "Problem report card"): a glass card in the top right corner of the
 * start screen and the home screen, in the toast's place but with two actions. It is not a dialog: nothing is dimmed, the
 * rest of the screen stays usable, and it asks nothing before leaving. Focus starts on Report this problem; A activates the
 * focused button, left/right moves between the two, B is Dismiss (the shell's back handler). It is offered once per problem.
 */
@Composable
fun ProblemOfferCard(problem: Problem?, reducedMotion: Boolean, onReport: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalDelosColors.current
    var shown by remember { mutableStateOf(problem) }
    if (problem != null) shown = problem
    val origin = TransformOrigin(1f, 0f)
    Box(Modifier.fillMaxSize().padding(top = 230.px, end = 64.px), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(problem != null, enter = popEnter(origin, reducedMotion), exit = popExit(origin, reducedMotion)) {
            val p = shown ?: return@AnimatedVisibility
            val report = remember { FocusRequester() }
            InitialFocus(report, p.id)
            val shape = RoundedCornerShape(34.px)
            Column(
                Modifier.width(780.px).clip(shape).glass(colors.surface).border(1.px, colors.line, shape).padding(36.px).focusGroup(),
                verticalArrangement = Arrangement.spacedBy(18.px),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(22.px), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(84.px).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                        Symbol(Symbols.DEBUG, 46.tpx, colors.onAccent, filled = true)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.px)) {
                        BasicText(offerTitle(p.kind), style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 28.tpx))
                        BasicText(p.kind.sentence, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx, lineHeight = 1.35.em))
                    }
                }
                BasicText(
                    p.summary,
                    style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 18.tpx, lineHeight = 1.35.em),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                BasicText(OFFER_NOTE, style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 20.tpx, lineHeight = 1.4.em))
                Row(Modifier.padding(top = 6.px), horizontalArrangement = Arrangement.spacedBy(18.px)) {
                    PillButton("Report this problem", Symbols.REPORT_ISSUE, onReport, Modifier.focusRequester(report), primary = true, compact = true,
                        trailing = { GlyphButton(Glyph.A, 34) })
                    PillButton("Dismiss", Symbols.CLOSE, onDismiss, compact = true, clickCue = Cue.BACK, trailing = { GlyphButton(Glyph.B, 34) })
                }
            }
        }
    }
}
