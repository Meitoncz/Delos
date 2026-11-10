package com.limelight.diag

/** Turns a [Problem] and the [DeviceInfo] into the prefilled bug report form: facts only, never a PC name or address. */
object ProblemIssue {
    fun draft(problem: Problem, info: DeviceInfo, fileName: String): IssueDraft {
        val title = when (problem.kind) {
            ProblemKind.JAVA_CRASH -> "Crash: ${problem.summary}"
            ProblemKind.NATIVE_CRASH -> "Native crash: ${problem.summary.removePrefix("Native crash: ")}"
            ProblemKind.ANR -> "Not responding: ${problem.summary.removePrefix("Not responding (ANR): ")}"
            ProblemKind.UNCLEAN_EXIT -> "Delos stopped without closing normally"
            ProblemKind.STREAM_FAILED -> "Stream failed: ${problem.summary}"
        }
        val what = buildString {
            appendLine(problem.kind.sentence)
            appendLine()
            appendLine("(Created from the problem report in Delos. Please add what you were doing just before.)")
            appendLine()
            append("The diagnostic log was saved by Delos as `$fileName` in the Downloads folder. I attach it to this issue.")
        }
        val settings = info.settings.joinToString(", ") { (label, value) -> "$label $value" }
        return IssueDraft(
            title = title,
            whatHappened = what,
            device = "${info.manufacturer} ${info.model}, Android ${info.androidRelease} (API ${info.sdk})",
            build = "Delos ${info.delosVersion} (${info.buildType})",
            streamSettings = settings,
            logs = logsText(problem),
            area = if (problem.kind == ProblemKind.STREAM_FAILED) "Pairing/connection" else "Other",
        )
    }

    /** The summary and the head of the stack trace (or the system's description): what fits an address; the file has the rest. */
    fun logsText(problem: Problem): String {
        val detail = problem.detail.lines().let { lines ->
            val head = lines.takeWhile { !it.trim().startsWith("at ") }
            val frames = lines.filter { it.trim().startsWith("at ") }.take(12)
            (head + frames).joinToString("\n")
        }
        return listOf(problem.summary, detail).filter { it.isNotBlank() }.distinct().joinToString("\n\n")
    }

    /** The report's "Problem" section: the same facts with the full stack trace. */
    fun section(problem: Problem, detectedAt: String): String = buildString {
        appendLine("Kind: ${problem.kind}")
        appendLine("Detected: $detectedAt")
        appendLine("Summary: ${problem.summary}")
        if (problem.detail.isNotBlank()) {
            appendLine()
            append(problem.detail)
        }
    }.trimEnd()
}
