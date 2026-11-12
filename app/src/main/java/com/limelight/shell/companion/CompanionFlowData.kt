package com.limelight.shell.companion

import androidx.compose.runtime.Immutable
import com.limelight.shell.StreamResultKind

/*
 * The data and the pure choices of the launch / connect flow pages (docs/DUAL_SCREEN.md, "Launch and connect"): the loader of a game that is
 * starting, the result of a stream that ended and the problem of a connection that failed. Nothing here draws, nothing here reaches the shell: the
 * pages (CompanionFlowPages.kt) take what these functions choose, and the tests (CompanionFlowPagesTest) run them on every panel shape.
 */

/**
 * How far a stream start has got, as far as the app really knows (never a percentage). The stream activity learns it from the connection's own
 * callbacks: the HTTP launch request (the first stage `NvConnection` reports, under the name of the game) is accepted, then the native stages run
 * until the connection starts, then the loader waits for the first frame.
 */
internal enum class StartStage {
    /** The launch request is being sent (the shell's launch scene and the first seconds of the stream activity). */
    LAUNCHING,

    /** The host accepted the launch: the streams are being set up and the first video is awaited. */
    CONNECTING,

    /** The connection runs: only the first presented frame is awaited. */
    CONNECTED;

    /** The first stage of the connection finished: that is the launch request (every later one is a native stage). */
    fun launchAccepted(): StartStage = if (this == LAUNCHING) CONNECTING else this

    /** The native connection reported that the stream runs. */
    fun connectionStarted(): StartStage = CONNECTED
}

/** What the stream's companion knows of a start that is still going: the stage and the stream settings that were asked for (null when unknown). */
@Immutable
internal data class StartProgress(val stage: StartStage, val requested: String?)

/** The named steps of the loader (the prototype's four): each one is an event the app really observes. */
internal enum class StepKind { HOST_FOUND, LAUNCH_ACCEPTED, WAITING_VIDEO, STREAM_READY }

internal enum class StepMark { DONE, CURRENT, PENDING }

/** One shown line of the step list; [hiddenAfter] is the number of pending steps folded into it ("+1 pending"). */
internal data class StepRow(val kind: StepKind, val mark: StepMark, val hiddenAfter: Int = 0)

internal object FlowSteps {
    /** The rows a panel gives the list: all four on a tall panel, three on a short one (the fourth folds into "+1 pending"). */
    fun rowBudget(budget: FitBudget): Int = if (budget.roomy) 4 else 3

    /**
     * The four steps with their marks: the host was found whenever a start runs at all; exactly one step is current, the ones before it are done, the ones
     * after it are pending.
     */
    fun steps(stage: StartStage): List<Pair<StepKind, StepMark>> {
        val current = when (stage) {
            StartStage.LAUNCHING -> StepKind.LAUNCH_ACCEPTED
            StartStage.CONNECTING -> StepKind.WAITING_VIDEO
            StartStage.CONNECTED -> StepKind.STREAM_READY
        }
        return StepKind.entries.map { kind ->
            kind to when {
                kind == current -> StepMark.CURRENT
                kind.ordinal < current.ordinal -> StepMark.DONE
                else -> StepMark.PENDING
            }
        }
    }

    /**
     * The steps cut to [maxRows]: trailing pending steps fold into the count on the last shown row; when the current step would still not show,
     * the oldest completed steps leave first. The current step is always shown.
     */
    fun rows(stage: StartStage, maxRows: Int): List<StepRow> {
        val all = steps(stage)
        if (all.size <= maxRows) return all.map { (kind, mark) -> StepRow(kind, mark) }
        val current = all.indexOfFirst { it.second == StepMark.CURRENT }
        val first = (current - (maxRows - 1)).coerceAtLeast(0)
        val end = first + maxRows
        return all.subList(first, end).mapIndexed { i, (kind, mark) -> StepRow(kind, mark, if (first + i == end - 1) all.size - end else 0) }
    }
}

/** Whether the loader offers Cancel: only while there is a real path that stops the start. */
internal object FlowCancel {
    /**
     * The shell's launch scene cancels with B until the stream window has been opened for it ([diving]); from then on the stream activity owns the
     * start.
     */
    fun launch(launchShown: Boolean, diving: Boolean): Boolean = launchShown && !diving

    /** The stream's loader: Cancel is the stream menu's own Disconnect, so it exists once the menu is bound to the companion. */
    fun streamStart(menuBound: Boolean): Boolean = menuBound
}

/** The things the loader needs to say about the game it starts. */
@Immutable
internal data class FlowLoader(
    /** The game, null when the name is not known. */
    val game: String?,
    val pc: String,
    val pcId: String,
    /** The requested stream settings as the loader of the main display tells them ("1920 × 1080 · HEVC · 120 FPS"), null while unknown. */
    val requested: String?,
    val stage: StartStage,
)

/** What a result of a stream that ended is made of. */
@Immutable
internal data class FlowResult(
    val kind: StreamResultKind,
    val title: String,
    val sentence: String,
    /** The technical text behind Details on the main display, null when there is none. */
    val details: String?,
    val game: String,
    val pc: String,
    val pcId: String,
)

internal object FlowText {
    /** The disc of a heading and the gap after it: the title and the subtitle have the card's width less this. */
    const val HEADING_INSET = 80f

    /** The first of [candidates] that fits one line of [width] (null when none does). */
    fun oneLine(candidates: List<String>, width: Float, metrics: TextMetrics, kind: FitText = FitText.BODY): String? =
        candidates.firstOrNull { it.isNotBlank() && metrics.lines(it, width, kind) <= 1 }

    /** A short name for a PC whose own name does not fit: "PC · 7C2A" (the prefix alone when the PC has no id). */
    fun pcAlias(prefix: String, uuid: String): String = if (uuid.any { it.isLetterOrDigit() }) CompanionFit.alias(prefix, uuid) else prefix

    /** The title of a heading: the first of [candidates] that fits one line, else the last one (an authored short title). */
    fun title(candidates: List<String>, width: Float, metrics: TextMetrics): String =
        oneLine(candidates.dropLast(1), width, metrics, FitText.TITLE) ?: candidates.last()

    /**
     * "game · PC" under a heading: the whole line, then with the PC's alias, then the game alone, then the PC alone; the alias when nothing fits
     * (a blank game or PC is left out).
     */
    fun gameAndPc(game: String, pc: String, alias: String, width: Float, metrics: TextMetrics): String {
        val both = { name: String -> listOf(game, name).filter { it.isNotBlank() }.joinToString(" \u00b7 ") }
        return oneLine(listOf(both(pc), both(alias), game, pc), width, metrics) ?: alias
    }

    /** The parts of the requested settings from the long to the short form: everything, resolution and the last part (codec left out), the resolution. */
    fun requestedForms(requested: String?): List<String> {
        val parts = requested.orEmpty().split(" \u00b7 ").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        return listOfNotNull(parts.joinToString(" \u00b7 "), if (parts.size >= 3) "${parts.first()} \u00b7 ${parts.last()}" else null, parts.first()).distinct()
    }

    /**
     * The loader's line under its title: "PC · requested 1920 × 1080 · HEVC · 120 FPS" in the longest form that fits one line (the PC's alias when
     * its name is too long), else the PC alone; [format] joins the PC and the settings ("%1$s · requested %2$s").
     */
    fun requestedLine(pc: String, alias: String, requested: String?, format: (String, String) -> String, width: Float, metrics: TextMetrics): String {
        val candidates = ArrayList<String>()
        for (name in listOf(pc, alias)) {
            if (name.isBlank()) continue
            requestedForms(requested).forEach { candidates += format(name, it) }
        }
        candidates += listOf(pc, alias)
        return oneLine(candidates, width, metrics) ?: alias
    }

    /**
     * The text of a result under its heading, in the body budget and made of whole sentences only: what the app says happened and what to do next
     * ([sentence] and [guidance]); when both do not fit, the sentence with the short guidance, then the guidance, then the short guidance, then [last].
     */
    fun resultBody(sentence: String, guidance: String, guidanceShort: String, budget: FitBudget, metrics: TextMetrics, last: String): String {
        fun fits(text: String) = text.isNotBlank() && metrics.lines(text, budget.innerWidth) <= budget.bodyLines
        val candidates = listOf(
            listOf(sentence, guidance), listOf(sentence, guidanceShort), listOf(guidance), listOf(guidanceShort),
        ).map { parts -> parts.filter { it.isNotBlank() }.joinToString(" ") }
        return candidates.firstOrNull(::fits) ?: last
    }

    /** The first line of a failure's technical text (the failing stage and its error code), when it fits [maxLines]; else null (the sentence says it). */
    fun errorLine(details: String?, maxLines: Int, width: Float, metrics: TextMetrics): String? =
        details?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.takeIf { metrics.lines(it, width) <= maxLines }

    /** The error text of a failed start: what the app says plus the technical lines after the first one; [short] and [last] are the authored fallbacks. */
    fun errorText(sentence: String, details: String?, short: String, budget: FitBudget, metrics: TextMetrics, last: String): String {
        val rest = details.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.drop(1)
        val full = (listOf(sentence.trim()) + rest).filter { it.isNotEmpty() }.joinToString("\n")
        return CompanionFit.summary(full, short, budget.errorLines, budget.innerWidth, metrics, last)
    }

    /** The recovery sentence in its two lines; blank when even the short form does not fit. */
    fun recovery(full: String, short: String, budget: FitBudget, metrics: TextMetrics): String =
        CompanionFit.summary(full, short, budget.recoveryLines, budget.innerWidth, metrics, "")

    /** The text under the portal's heading: [sub] in whole sentences within the body budget, blank when nothing fits. */
    fun portalNote(sub: String, budget: FitBudget, metrics: TextMetrics): String =
        CompanionFit.summary(sub, "", budget.bodyLines, budget.innerWidth, metrics, "")
}
