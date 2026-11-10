package com.limelight.diag

import java.io.PrintWriter
import java.io.StringWriter

/** Text forms of a [Throwable] for the diagnostic log and the crash file. */
object CrashText {
    /** A stack trace (causes included) is cut here so one exception cannot fill the log. */
    const val MAX_TRACE_CHARS = 6_000

    fun stackTrace(error: Throwable, maxChars: Int = MAX_TRACE_CHARS): String {
        val text = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trimEnd()
        return if (text.length > maxChars) text.substring(0, maxChars) + "\n… (stack trace cut)" else text
    }
}
