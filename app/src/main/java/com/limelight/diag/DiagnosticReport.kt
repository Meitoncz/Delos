package com.limelight.diag

import android.content.res.Resources
import com.limelight.R
import java.util.Locale

/** A finished report: exactly the text that is shared, copied or saved. */
class DiagnosticReport(val text: String, val logLines: Int) {
    val bytes: Int = text.toByteArray(Charsets.UTF_8).size

    /** "123 KB, 1,204 log lines" for the detail line of a row, in the language of [res]. */
    fun sizeLabel(res: Resources): String {
        val locale = res.configuration.locales[0]
        return res.getQuantityString(R.plurals.debug_log_size, logLines, sizeText(bytes, locale), String.format(locale, "%,d", logLines))
    }

    /**
     * The first part (the header and the device block) and the last [tailLines] lines of the log, for the preview panel; the
     * whole file is what is shared.
     */
    fun preview(tailLines: Int = PREVIEW_TAIL_LINES): String {
        val lines = text.trimEnd().lines()
        val logStart = lines.indexOfFirst { it.startsWith(LOG_HEADING) }
        if (logStart < 0 || lines.size - logStart <= tailLines + 1) return text
        val head = lines.subList(0, logStart + 1)
        val tail = lines.takeLast(tailLines)
        val hidden = lines.size - head.size - tail.size
        return (head + "… $hidden earlier lines are in the file …" + tail).joinToString("\n")
    }

    companion object {
        const val LOG_HEADING = "== Log"
        const val PREVIEW_TAIL_LINES = 40

        fun sizeText(bytes: Int, locale: Locale = Locale.US): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 10 * 1024 -> String.format(locale, "%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 -> String.format(locale, "%d KB", bytes / 1024)
            else -> String.format(locale, "%.1f MB", bytes / 1024.0 / 1024.0)
        }
    }
}

/** Builds the text of the report and of the short summary from the raw log; everything passes the scrubber last. */
object DiagnosticReports {
    private const val INTRO = "Addresses, PC names, ids and keys were removed from this text on the device before it was written. " +
        "Nothing is sent anywhere by Delos; you decide who gets it."

    /**
     * The whole report: header, [sections] (such as the crash of an earlier run) and the log. [tailLines] keeps only that
     * many of the newest log lines.
     */
    fun build(
        info: DeviceInfo,
        rawLog: String,
        scrubber: LogScrubber,
        generated: String,
        sections: List<Pair<String, String>> = emptyList(),
        tailLines: Int? = null,
    ): DiagnosticReport {
        val log = lines(rawLog).let { all -> if (tailLines != null) all.takeLast(tailLines) else all }
        val text = buildString {
            appendLine("Delos diagnostic report")
            appendLine("Generated: $generated")
            appendLine(INTRO)
            appendLine()
            appendLine("== Device ==")
            appendLine(info.format())
            sections.forEach { (title, body) ->
                appendLine()
                appendLine("== $title ==")
                appendLine(body.trimEnd())
            }
            appendLine()
            appendLine("${DiagnosticReport.LOG_HEADING} (${log.size} lines, oldest first) ==")
            log.forEach { appendLine(it) }
        }
        return DiagnosticReport(scrubber.scrub(text), log.size)
    }

    /** The device block and the newest [tailLines] log lines: small enough to paste into an issue by hand. */
    fun summary(info: DeviceInfo, rawLog: String, scrubber: LogScrubber, generated: String, tailLines: Int = 100): DiagnosticReport =
        build(info, rawLog, scrubber, generated, tailLines = tailLines)

    fun lines(rawLog: String): List<String> = rawLog.lines().dropLastWhile { it.isEmpty() }
}
