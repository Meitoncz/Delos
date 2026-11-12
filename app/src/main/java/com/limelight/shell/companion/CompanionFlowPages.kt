package com.limelight.shell.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.limelight.R
import com.limelight.shell.ui.LocalDelosColors
import com.limelight.shell.ui.LowRed
import com.limelight.shell.ui.Symbol
import com.limelight.shell.ui.Symbols
import com.limelight.shell.ui.px
import com.limelight.shell.ui.tpx
import com.limelight.shell.StreamResultKind

/*
 * The pages of the launch / connect flow (docs/DUAL_SCREEN.md, "Launch and connect"; prototype states loader, result, problem, worst-error). They are
 * immutable data plus the owner's own callbacks (the shell's cancel, retry, back, report and dismiss, the stream menu's Disconnect), and every one is
 * an OVERLAY: what the user must see first. The wording is selected by FlowText against the panel's budgets, in whole sentences only.
 */

/**
 * A game that is starting: its named steps with what the app really knows (never a percentage), the settings asked for, and Cancel while the start can
 * still be cancelled ([onCancel] null: no path, no button). It is waited at, so it never dims. The shell's launch scene and the stream activity's own
 * loader share the key: the hand-over from one to the other is an update, not a fade.
 */
internal data class LoaderPage(val data: FlowLoader, val onCancel: (() -> Unit)?) : CompanionPage {
    override val key: String get() = "flow/loader"
    override val priority: Int get() = PagePriority.OVERLAY
    override val keepsAwake: Boolean get() = true

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        val res = LocalContext.current.resources
        var pressed by remember { mutableStateOf(false) }
        PageCard { fit ->
            val b = fit.budget
            val width = b.innerWidth - FlowText.HEADING_INSET
            val generic = stringResource(R.string.companion_flow_starting_generic)
            val title = data.game?.takeIf { it.isNotBlank() }
                ?.let { FlowText.title(listOf(res.getString(R.string.companion_flow_starting, it), generic), width, fit.metrics) } ?: generic
            val alias = FlowText.pcAlias(stringResource(R.string.companion_flow_pc), data.pcId)
            val line = FlowText.requestedLine(data.pc, alias, data.requested, { pc, requested -> res.getString(R.string.companion_flow_requested, pc, requested) }, width, fit.metrics)
            PageHeading(Symbols.PLAY_ARROW, title, line)
            StepList(FlowSteps.rows(data.stage, FlowSteps.rowBudget(b)))
            BodyText(stringResource(R.string.companion_flow_no_percentage), Modifier.optional(40), color = colors.text2)
            Filler()
            onCancel?.let { cancel ->
                ActionRow(listOf(PageAction(stringResource(R.string.companion_flow_cancel_start), { pressed = true; cancel() }, enabled = !pressed)))
            }
        }
    }
}

/**
 * A portal the shell waits in: a PC that connects or wakes up, the link test, the exit of a stream ("Closing <game>"). The shell's own words are shown as they
 * are, no steps are invented; Cancel only where the portal can really be cancelled ([onCancel] null otherwise).
 */
internal data class PortalPage(val icon: String, val label: String, val sub: String, val onCancel: (() -> Unit)?) : CompanionPage {
    override val key: String get() = "flow/portal"
    override val priority: Int get() = PagePriority.OVERLAY
    override val keepsAwake: Boolean get() = true

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        var pressed by remember { mutableStateOf(false) }
        PageCard { fit ->
            val b = fit.budget
            val wait = stringResource(R.string.companion_flow_wait)
            val title = FlowText.title(listOf(label, wait), b.innerWidth - FlowText.HEADING_INSET, fit.metrics)
            PageHeading(icon, title)
            // A label too long for the title is still said, in whole sentences
            val said = if (title == label) sub else listOf(label, sub).filter { it.isNotBlank() }.joinToString("\n")
            val note = FlowText.portalNote(said, b, fit.metrics)
            if (note.isNotBlank()) BodyText(note, Modifier.optional(50), color = colors.text)
            BodyText(stringResource(R.string.companion_flow_no_percentage), Modifier.optional(40), color = colors.text2)
            Filler()
            onCancel?.let { cancel ->
                ActionRow(listOf(PageAction(stringResource(R.string.common_cancel), { pressed = true; cancel() }, enabled = !pressed)))
            }
        }
    }
}

/**
 * A stream that ended after it had run, or that the PC ended: the result's own title, the game and the PC, what happened and what to do next in the body
 * budget, Reconnect and Back to library (the shell's retry and back, the very callbacks of the result layer on the main display). The text never claims the
 * game stopped: only the host knows.
 */
internal data class ResultPage(val data: FlowResult, val onReconnect: () -> Unit, val onBack: () -> Unit) : CompanionPage {
    override val key: String get() = "flow/result"
    override val priority: Int get() = PagePriority.OVERLAY

    @Composable
    override fun Content() {
        val colors = LocalDelosColors.current
        PageCard { fit ->
            val b = fit.budget
            val width = b.innerWidth - FlowText.HEADING_INSET
            val ended = stringResource(R.string.companion_flow_result_title)
            val title = FlowText.title(listOf(data.title, ended), width, fit.metrics)
            val alias = FlowText.pcAlias(stringResource(R.string.companion_flow_pc), data.pcId)
            PageHeading(Symbols.INFO, title, FlowText.gameAndPc(data.game, data.pc, alias, width, fit.metrics))
            val closed = data.kind == StreamResultKind.HOST_CLOSED
            val body = FlowText.resultBody(
                data.sentence,
                stringResource(if (closed) R.string.companion_flow_closed_guidance else R.string.companion_flow_lost_guidance),
                stringResource(if (closed) R.string.companion_flow_closed_short else R.string.companion_flow_lost_short),
                b, fit.metrics, stringResource(R.string.companion_see_main),
            )
            BodyText(body, color = colors.text)
            Filler()
            ActionRow(
                listOf(
                    PageAction(stringResource(R.string.companion_flow_reconnect), onReconnect, ButtonStyle.PRIMARY, Symbols.REFRESH),
                    PageAction(stringResource(R.string.shell_hint_back_to_library), onBack, icon = Symbols.LIBRARY),
                ),
            )
        }
    }
}

/**
 * A start that failed: the failing stage and its error code, the error in the error budget (whole sentences, else an authored short form; the full text stays
 * on the main display), what to check in accent, Try again and Back (the same callbacks as the result layer's). Only the text the main screen shows is used.
 */
internal data class ProblemPage(val data: FlowResult, val onTryAgain: () -> Unit, val onBack: () -> Unit) : CompanionPage {
    override val key: String get() = "flow/problem"
    override val priority: Int get() = PagePriority.OVERLAY

    @Composable
    override fun Content() {
        val alias = FlowText.pcAlias(stringResource(R.string.companion_flow_pc), data.pcId)
        ProblemCard(
            title = stringResource(R.string.companion_flow_problem_title),
            subtitle = { width, metrics -> FlowText.gameAndPc(data.game, data.pc, alias, width, metrics) },
            errorLine = data.details,
            errorText = { b, metrics, short, last -> FlowText.errorText(data.sentence, data.details, short, b, metrics, last) },
            recovery = stringResource(R.string.companion_flow_recovery),
            recoveryShort = stringResource(R.string.companion_flow_recovery_short),
            actions = listOf(
                PageAction(stringResource(R.string.stream_result_try_again), onTryAgain, ButtonStyle.PRIMARY, Symbols.REFRESH),
                PageAction(stringResource(R.string.shell_hint_back), onBack, icon = Symbols.ARROW_BACK),
            ),
        )
    }
}

/**
 * The report offer of a problem of an earlier run (a crash, a stream that could not run): what the card on the main display says, its title and the one line of
 * facts, Report and Dismiss (the card's own callbacks). [sentence] and [summary] are the card's texts, resolved by the shell.
 */
internal data class ReportPage(val title: String, val sentence: String, val summary: String, val onReport: () -> Unit, val onDismiss: () -> Unit) : CompanionPage {
    override val key: String get() = "flow/report"
    override val priority: Int get() = PagePriority.OVERLAY

    @Composable
    override fun Content() {
        ProblemCard(
            title = title,
            fallbackTitle = stringResource(R.string.shell_problem_title_generic),
            subtitle = null,
            errorLine = summary,
            errorText = { b, metrics, _, last -> CompanionFit.summary(sentence, sentence, b.errorLines, b.innerWidth, metrics, last) },
            recovery = stringResource(R.string.shell_problem_note),
            recoveryShort = stringResource(R.string.companion_flow_report_short),
            actions = listOf(
                PageAction(stringResource(R.string.shell_problem_report), onReport, ButtonStyle.PRIMARY, Symbols.REPORT_ISSUE),
                PageAction(stringResource(R.string.shell_problem_dismiss), onDismiss, icon = Symbols.CLOSE),
            ),
        )
    }
}

/**
 * The card of a problem (the prototype's `problem`): heading, the error line in red, the error text, what to do in accent, the note that the full error stays on
 * the main display (tall panels), and two actions. Every text is cut to its budget before it is drawn; the lowest-priority blocks are also optional, so the
 * card can never overflow.
 */
@Composable
private fun ProblemCard(
    title: String,
    subtitle: ((Float, TextMetrics) -> String)?,
    errorLine: String?,
    errorText: (FitBudget, TextMetrics, String, String) -> String,
    recovery: String,
    recoveryShort: String,
    actions: List<PageAction>,
    fallbackTitle: String = title,
) {
    val colors = LocalDelosColors.current
    PageCard { fit ->
        val b = fit.budget
        val width = b.innerWidth - FlowText.HEADING_INSET
        PageHeading(Symbols.WARNING, FlowText.title(listOf(title, fallbackTitle), width, fit.metrics), subtitle?.invoke(width, fit.metrics))
        FlowText.errorLine(errorLine, if (b.roomy) 2 else 1, b.innerWidth, fit.metrics)?.let { BodyText(it, Modifier.optional(60), color = LowRed) }
        BodyText(errorText(b, fit.metrics, stringResource(R.string.companion_flow_error_short), stringResource(R.string.companion_see_main)), color = colors.text)
        FlowText.recovery(recovery, recoveryShort, b, fit.metrics).takeIf { it.isNotBlank() }?.let { BodyText(it, Modifier.optional(70), color = colors.accent) }
        if (b.roomy) BodyText(stringResource(R.string.companion_flow_full_on_main), Modifier.optional(30), color = colors.text3)
        Filler()
        ActionRow(actions)
    }
}

/** The lines of the loader's step list: a mark (done, current, pending) and the step's name; pending steps beyond the rows say "+N pending". */
@Composable
private fun StepList(rows: List<StepRow>) {
    val colors = LocalDelosColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.px)) {
        rows.forEach { row ->
            val name = stringResource(
                when (row.kind) {
                    StepKind.HOST_FOUND -> R.string.companion_flow_step_host
                    StepKind.LAUNCH_ACCEPTED -> R.string.companion_flow_step_launch
                    StepKind.WAITING_VIDEO -> R.string.companion_flow_step_video
                    StepKind.STREAM_READY -> R.string.companion_flow_step_ready
                },
            )
            val text = when {
                row.hiddenAfter > 0 -> stringResource(R.string.companion_flow_step_more, name, row.hiddenAfter)
                row.mark == StepMark.PENDING -> stringResource(R.string.companion_flow_step_pending, name)
                else -> name
            }
            val color = when (row.mark) {
                StepMark.DONE -> colors.accent2
                StepMark.CURRENT -> colors.accent
                StepMark.PENDING -> colors.text
            }
            val icon = when (row.mark) {
                StepMark.DONE -> Symbols.CHECK
                StepMark.CURRENT -> Symbols.REFRESH
                StepMark.PENDING -> if (row.kind == StepKind.STREAM_READY) Symbols.PLAY_ARROW else Symbols.SCHEDULE
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.px), verticalAlignment = Alignment.CenterVertically) {
                Symbol(icon, 34.tpx, color)
                BodyText(text, color = color)
            }
        }
    }
}
