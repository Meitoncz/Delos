package com.limelight.shell.ui

import android.content.res.Resources
import androidx.compose.ui.res.stringResource
import com.limelight.R
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

@Composable
internal fun offerTitle(kind: ProblemKind): String = stringResource(if (kind == ProblemKind.STREAM_FAILED) R.string.shell_problem_title_stream else R.string.shell_problem_title_generic)

/** The sentence under the title: what happened the last time, by [kind]. */
internal fun problemSentence(res: Resources, kind: ProblemKind): String = res.getString(
    when (kind) {
        ProblemKind.JAVA_CRASH -> R.string.diag_problem_java_crash
        ProblemKind.NATIVE_CRASH -> R.string.diag_problem_native_crash
        ProblemKind.ANR -> R.string.diag_problem_anr
        ProblemKind.UNCLEAN_EXIT -> R.string.diag_problem_unclean_exit
        ProblemKind.STREAM_FAILED -> R.string.diag_problem_stream_failed
    },
)

/**
 * The line with the facts under the sentence: the kind and the technical subject (exception, signal, system description or
 * the title of the failed stream). The subject is data and stays as the system or the stream reported it.
 */
internal fun problemSummary(res: Resources, problem: Problem): String {
    val subject = problem.subject.take(Problem.SUMMARY_MAX)
    return when (problem.kind) {
        ProblemKind.JAVA_CRASH ->
            if (!problem.viaExitRecord) subject
            else if (subject.isBlank()) res.getString(R.string.diag_problem_summary_crash)
            else res.getString(R.string.diag_problem_summary_crash_with, subject)
        ProblemKind.NATIVE_CRASH ->
            if (subject.isBlank()) res.getString(R.string.diag_problem_summary_native_crash)
            else res.getString(R.string.diag_problem_summary_native_crash_with, subject)
        ProblemKind.ANR ->
            if (subject.isBlank()) res.getString(R.string.diag_problem_summary_anr)
            else res.getString(R.string.diag_problem_summary_anr_with, subject)
        ProblemKind.UNCLEAN_EXIT -> res.getString(R.string.diag_problem_summary_unclean_exit)
        ProblemKind.STREAM_FAILED -> subject
    }
}

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
    // A compact stage tucks the card under the status bar, 48 px from the edge, and gives it the width that is left (780 at most)
    val stage = LocalStage.current
    val compact = stage.compact
    val margin = if (compact) 48f else 64f
    Box(Modifier.fillMaxSize().padding(top = (if (compact) Compact.STATUS_BAR * stage.textScale + stage.safe.top else 230f).px, end = margin.px), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(problem != null, enter = popEnter(origin, reducedMotion), exit = popExit(origin, reducedMotion)) {
            val p = shown ?: return@AnimatedVisibility
            val report = remember { FocusRequester() }
            InitialFocus(report, p.id)
            val shape = RoundedCornerShape(34.px)
            val res = textResources()
            Column(
                Modifier.width((if (compact) minOf(780f, stage.stageWidth - 2f * 48f) else 780f).px).clip(shape).glass(colors.surface).border(1.px, colors.line, shape).padding((if (compact) 32 else 36).px).focusGroup(),
                verticalArrangement = Arrangement.spacedBy(18.px),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(22.px), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(84.px).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                        Symbol(Symbols.DEBUG, 46.tpx, colors.onAccent, filled = true)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.px)) {
                        BasicText(offerTitle(p.kind), style = TextStyle(color = colors.text, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 28.tpx))
                        BasicText(problemSentence(res, p.kind), style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 22.tpx, lineHeight = 1.35.em))
                    }
                }
                BasicText(
                    problemSummary(res, p),
                    style = TextStyle(color = colors.text3, fontFamily = Figtree, fontSize = 18.tpx, lineHeight = 1.35.em),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                BasicText(stringResource(R.string.shell_problem_note), style = TextStyle(color = colors.text2, fontFamily = Figtree, fontSize = 20.tpx, lineHeight = 1.4.em))
                Row(Modifier.padding(top = 6.px), horizontalArrangement = Arrangement.spacedBy((if (compact) Compact.BUTTON_GAP else 18f).px)) {
                    PillButton(stringResource(R.string.shell_problem_report), Symbols.REPORT_ISSUE, onReport, Modifier.focusRequester(report), primary = true, compact = true,
                        trailing = { GlyphButton(Glyph.A, 34) })
                    PillButton(stringResource(R.string.shell_problem_dismiss), Symbols.CLOSE, onDismiss, compact = true, clickCue = Cue.BACK, trailing = { GlyphButton(Glyph.B, 34) })
                }
            }
        }
    }
}
