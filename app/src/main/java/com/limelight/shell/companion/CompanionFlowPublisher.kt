package com.limelight.shell.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.limelight.R
import com.limelight.shell.LocalShell
import com.limelight.shell.StreamResult
import com.limelight.shell.StreamResultKind
import com.limelight.shell.ui.offerTitle
import com.limelight.shell.ui.problemSentence
import com.limelight.shell.ui.problemSummary
import com.limelight.shell.ui.textResources

/**
 * Publishes what the shell's launch and connect flow wants the companion screen to show (docs/DUAL_SCREEN.md, "Launch and connect"), the one page that
 * is the most urgent: the stream result (or the problem of a start that failed), the launch scene of a game that is starting, a portal the shell waits in, the
 * report offer of a problem. The shell keeps these on the main display in layers of its own; this reads the very same state ([ShellState.result], `launch`,
 * `portal`, `problem`) and the same callbacks run what the buttons of those layers run, so a touch here is a press there.
 *
 * - [launchParameters]: the settings the launch card tells (blank while they are read); [onCancelLaunch]: B on the launch scene, offered only until the stream
 *   window has been opened for it.
 * - [onCancelPortal]: B in a portal, offered only while the portal is cancellable.
 * - [onRetry] / [onBack]: Try again and Back to library of the result layer.
 * - [onReport] / [onDismissProblem]: the report card's two buttons.
 *
 * Called once by the shell, next to the layers it mirrors. The callbacks are read at the moment of the touch, so a new function reference never re-publishes.
 */
@Composable
internal fun PublishCompanionOverlays(
    launchParameters: String,
    onCancelLaunch: () -> Unit,
    onCancelPortal: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onReport: () -> Unit,
    onDismissProblem: () -> Unit,
) {
    val shell = LocalShell.current
    val res = textResources()
    val cancelLaunch by rememberUpdatedState(onCancelLaunch)
    val cancelPortal by rememberUpdatedState(onCancelPortal)
    val retry by rememberUpdatedState(onRetry)
    val back by rememberUpdatedState(onBack)
    val report by rememberUpdatedState(onReport)
    val dismiss by rememberUpdatedState(onDismissProblem)

    val result = shell.result
    val launch = shell.launch
    val portal = shell.portal
    val problem = if (shell.offerShown) shell.problem else null
    val launchCancellable = FlowCancel.launch(launch != null, shell.diving)
    val problemTitle = problem?.let { offerTitle(it.kind) }

    val page = remember(result, launch?.id, launch?.app?.name, launch?.pc?.uuid, launch?.pc?.name, launchCancellable, launchParameters, portal, problem, problemTitle, res) {
        when {
            result != null -> flowResult(result, { retry() }, { back() })
            launch != null -> LoaderPage(
                FlowLoader(launch.app.name, launch.pc.name, launch.pc.uuid, launchParameters.takeIf { it.isNotBlank() }, StartStage.LAUNCHING),
                if (launchCancellable) ({ cancelLaunch() }) else null,
            )
            portal != null -> PortalPage(portal.icon, portal.label, portal.sub, if (portal.cancellable) ({ cancelPortal() }) else null)
            problem != null -> ReportPage(problemTitle.orEmpty(), problemSentence(res, problem.kind), problemSummary(res, problem), { report() }, { dismiss() })
            else -> null
        }
    }
    PublishCompanionPage(page)
}

/** A start that failed is a problem (its text is the failure's), every other result is a stream that ended. */
private fun flowResult(result: StreamResult, onRetry: () -> Unit, onBack: () -> Unit): CompanionPage {
    val data = FlowResult(result.kind, result.title, result.sentence, result.details, result.appName, result.pcName, result.pcUuid)
    return if (result.kind == StreamResultKind.FAILED_TO_START) ProblemPage(data, onRetry, onBack) else ResultPage(data, onRetry, onBack)
}
