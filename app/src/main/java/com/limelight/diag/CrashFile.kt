package com.limelight.diag

/** What the uncaught-exception handler leaves for the next start: who crashed, the stack trace and the newest log lines. */
data class CrashRecord(
    val whenMs: Long,
    val pid: Int,
    val thread: String,
    /** `java.lang.IllegalStateException: message`, one line. */
    val exception: String,
    val stackTrace: String,
    /** The newest lines of the diagnostic log, raw: they are scrubbed when the report is built. */
    val logTail: List<String>,
)

/** The text form of a [CrashRecord] (the crash file) and the short sentences made from it. */
object CrashFile {
    /** How many log lines the crash file keeps. */
    const val LOG_TAIL_LINES = 200

    private const val TITLE = "Delos crash report"
    private const val STACK_HEADING = "== Stack trace =="
    private const val LOG_HEADING = "== Last log lines =="

    fun record(whenMs: Long, pid: Int, thread: String, error: Throwable, rawLog: String): CrashRecord = CrashRecord(
        whenMs, pid, thread,
        error.toString().lineSequence().first().take(300),
        CrashText.stackTrace(error),
        DiagnosticReports.lines(rawLog).takeLast(LOG_TAIL_LINES),
    )

    fun format(record: CrashRecord): String = buildString {
        appendLine(TITLE)
        appendLine("Time: ${record.whenMs}")
        appendLine("Process: ${record.pid}")
        appendLine("Thread: ${record.thread}")
        appendLine("Exception: ${record.exception}")
        appendLine(STACK_HEADING)
        appendLine(record.stackTrace)
        appendLine(LOG_HEADING)
        record.logTail.forEach { appendLine(it) }
    }

    /** The record in [text], or null when it is not (completely) a crash file. */
    fun parse(text: String): CrashRecord? {
        val lines = text.lines()
        if (lines.firstOrNull() != TITLE) return null
        val stack = lines.indexOf(STACK_HEADING)
        val log = lines.indexOf(LOG_HEADING)
        if (stack < 0 || log < stack) return null
        fun header(name: String) = lines.take(stack).firstOrNull { it.startsWith("$name: ") }?.removePrefix("$name: ")
        return CrashRecord(
            whenMs = header("Time")?.toLongOrNull() ?: return null,
            pid = header("Process")?.toIntOrNull() ?: return null,
            thread = header("Thread") ?: return null,
            exception = header("Exception") ?: return null,
            stackTrace = lines.subList(stack + 1, log).joinToString("\n"),
            logTail = lines.drop(log + 1).dropLastWhile { it.isEmpty() },
        )
    }

    /** `IllegalStateException: boom (at com.limelight.Foo.bar)`: what the card and the issue title say. */
    fun summary(record: CrashRecord): String {
        val type = record.exception.substringBefore(':').substringAfterLast('.')
        val message = record.exception.substringAfter(':', "").trim()
        val frame = topFrame(record.stackTrace)
        val head = if (message.isEmpty()) type else "$type: $message"
        return (if (frame != null) "$head (at $frame)" else head).take(180)
    }

    /** The first stack frame of Delos' own code (else the first frame): `com.limelight.Foo.bar`, without file and line. */
    fun topFrame(stackTrace: String): String? {
        val frames = stackTrace.lines().map { it.trim() }.filter { it.startsWith("at ") }.map { it.removePrefix("at ").substringBefore('(') }
        return frames.firstOrNull { it.startsWith("com.limelight") } ?: frames.firstOrNull()
    }

    /** The first [frames] frames of the stack trace with the exception line: short enough for the prefilled issue. */
    fun shortTrace(record: CrashRecord, frames: Int = 12): String {
        val lines = record.stackTrace.lines()
        val head = lines.takeWhile { !it.trim().startsWith("at ") }
        val traceFrames = lines.filter { it.trim().startsWith("at ") }.take(frames)
        return (head + traceFrames).joinToString("\n")
    }
}
